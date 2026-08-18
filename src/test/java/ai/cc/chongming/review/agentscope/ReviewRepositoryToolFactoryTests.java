package ai.cc.chongming.review.agentscope;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ai.cc.chongming.review.application.IntakeCancellation;
import ai.cc.chongming.review.application.RepositorySnapshotService;
import ai.cc.chongming.review.application.ReviewContextAssembler;
import ai.cc.chongming.review.application.ReviewIntakeService;
import ai.cc.chongming.review.application.ReviewRuntimeContext;
import ai.cc.chongming.review.domain.gateway.StructuredOutputs.Kind;
import ai.cc.chongming.review.domain.model.RepositorySnapshot;
import ai.cc.chongming.review.domain.model.RequirementSnapshot;
import ai.cc.chongming.review.domain.model.ReviewTypes.ReviewId;
import ai.cc.chongming.review.domain.model.ReviewTypes.RoleType;
import ai.cc.chongming.review.domain.role.RolePack;
import ai.cc.chongming.review.domain.role.RolePack.Checkpoint;
import ai.cc.chongming.review.infrastructure.agentscope.ReviewRepositoryToolFactory;
import ai.cc.chongming.review.infrastructure.agentscope.tool.ReadOnlyRepositoryTools;
import ai.cc.chongming.review.infrastructure.agentscope.tool.RepositoryFileGrantSet;
import ai.cc.chongming.review.infrastructure.repository.RepositorySearchIndex.FileMetadata;
import ai.cc.chongming.review.infrastructure.repository.RepositorySearchIndex.SourceLine;
import ai.cc.chongming.review.infrastructure.repository.RepositorySearchIndex.TextMatch;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.tool.AgentTool;
import io.agentscope.core.tool.ToolCallParam;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * [AIREVIEW-PLAN-024] Verifies role-level fileRef authorization: granted reads, ungranted
 * rejection without budget consumption, dynamic tool registration and run-level short-circuit.
 *
 * @author wangli
 */
class ReviewRepositoryToolFactoryTests {

    @Test
    void exposesOnlyRolePackApprovedReadToolsWhenTheRoleHasGrantedFiles() {
        Fixture fixture = fixture();
        when(fixture.repositoryTools.snapshotFiles(any(), any()))
                .thenReturn(List.of("src/main/java/App.java", "frontend/app.js", "docs/spec.md"));

        assertThat(fixture.factory.readTools(fixture.runtimeContext, RoleType.BACKEND,
                Set.of("listFiles", "searchText", "readLines", "getFileMetadata", "submit_claim")))
                .extracting(AgentTool::getName)
                .containsExactlyInAnyOrder("listFiles", "searchText", "readLines", "getFileMetadata");
        assertThat(fixture.factory.requireSnapshot(fixture.runtimeContext)).isSameAs(fixture.repositorySnapshot);

        RepositoryFileGrantSet grants = fixture.factory.roleFileGrants(fixture.runtimeContext, RoleType.BACKEND);
        assertThat(grants.paths()).containsExactly("src/main/java/App.java");
        assertThat(grants.containsPath("frontend/app.js")).isFalse();
    }

    @Test
    void buildsOneSharedProjectContextAndKeepsEveryRoleContextFreeOfRepositoryFilePaths() {
        Fixture fixture = fixture();
        when(fixture.repositoryTools.listFiles(any(), eq(40), any()))
                .thenReturn(List.of(new FileMetadata(
                        "service/src/main/App.java", 10, Instant.now(), "e".repeat(64), "java", true)));

        ReviewRepositoryToolFactory.SharedProjectContext first = fixture.factory.sharedProjectContext(fixture.runtimeContext);
        ReviewRepositoryToolFactory.SharedProjectContext second = fixture.factory.sharedProjectContext(fixture.runtimeContext);

        assertThat(first).isSameAs(second);
        assertThat(first.publicText(RoleType.PRODUCT)).doesNotContain("service/src/main/App.java");
        assertThat(first.publicText(RoleType.BACKEND)).doesNotContain("service/src/main/App.java");
        verify(fixture.repositoryTools, times(1)).listFiles(any(), eq(40), any());
    }

    @Test
    void roleContextAndSearchResultsExposeOnlyGrantedFileRefsAndNeverUngrantedPaths() {
        Fixture fixture = fixture();
        when(fixture.repositoryTools.snapshotFiles(any(), any()))
                .thenReturn(List.of("src/main/java/App.java", "frontend/app.js"));
        ArgumentCaptor<Predicate<String>> scopeCaptor = scopeCaptor();
        when(fixture.repositoryTools.searchText(any(), anyString(), anyBoolean(), anyInt(), any(), scopeCaptor.capture()))
                .thenReturn(List.of(new TextMatch("src/main/java/App.java", 2, "// TODO: validate")));

        String publicContext = fixture.factory.rolePublicContext(
                fixture.runtimeContext, rolePack(RoleType.BACKEND), new ReviewContextAssembler());
        assertThat(publicContext)
                .doesNotContain("frontend/app.js")
                .doesNotContain("src/main/java/App.java")
                .contains("fileRef")
                .contains("App.java");

        RepositoryFileGrantSet grants = fixture.factory.roleFileGrants(fixture.runtimeContext, RoleType.BACKEND);
        String grantedFileRef = grants.fileRefFor("src/main/java/App.java").orElseThrow();

        AgentTool searchText = toolNamed(fixture.factory.readTools(fixture.runtimeContext, RoleType.BACKEND,
                Set.of("searchText")), "searchText");
        String result = resultText(searchText.callAsync(params(Map.of("query", "TODO"))).block());

        assertThat(result).contains(grantedFileRef).doesNotContain("src/main/java/App.java");
        assertThat(scopeCaptor.getValue().test("frontend/app.js")).isFalse();
        assertThat(scopeCaptor.getValue().test("src/main/java/App.java")).isTrue();
    }

    @Test
    void rejectsUngrantedFileRefsWithoutConsumingTheReadBudget() {
        Fixture fixture = fixture();
        when(fixture.repositoryTools.snapshotFiles(any(), any()))
                .thenReturn(List.of("src/main/java/App.java"));
        when(fixture.repositoryTools.readLines(any(), eq("src/main/java/App.java"), anyInt(), anyInt(), any()))
                .thenReturn(List.of(new SourceLine(1, "class App {")));
        RepositoryFileGrantSet grants = fixture.factory.roleFileGrants(fixture.runtimeContext, RoleType.BACKEND);
        String grantedFileRef = grants.fileRefFor("src/main/java/App.java").orElseThrow();
        AgentTool readLines = toolNamed(fixture.factory.readTools(fixture.runtimeContext, RoleType.BACKEND,
                Set.of("readLines", "complete_initial_review")), "readLines");

        String denied = resultText(readLines.callAsync(params(Map.of(
                "fileRef", "definitely-not-a-granted-ref", "startLine", 1))).block());
        assertThat(denied).contains("FILE_REF_NOT_GRANTED").contains("do-not-retry");

        String granted = resultText(readLines.callAsync(params(Map.of(
                "fileRef", grantedFileRef, "startLine", 1))).block());
        assertThat(granted).contains("class App {");
        verify(fixture.repositoryTools, never()).readLines(any(), eq("definitely-not-a-granted-ref"), anyInt(), anyInt(), any());
        verify(fixture.repositoryTools, times(1)).readLines(any(), eq("src/main/java/App.java"), anyInt(), anyInt(), any());
    }

    @Test
    void omitsReadToolsForRolesWithoutGrantedFilesAndAdvisesUnknownCheckpoints() {
        Fixture fixture = fixture();
        when(fixture.repositoryTools.snapshotFiles(any(), any()))
                .thenReturn(List.of("src/main/java/App.java", "docs/spec.md"));

        List<AgentTool> frontendTools = fixture.factory.readTools(fixture.runtimeContext, RoleType.FRONTEND,
                Set.of("listFiles", "searchText", "readLines", "getFileMetadata"));
        assertThat(frontendTools).extracting(AgentTool::getName)
                .containsExactlyInAnyOrder("listFiles", "searchText");

        String publicContext = fixture.factory.rolePublicContext(
                fixture.runtimeContext, rolePack(RoleType.FRONTEND), new ReviewContextAssembler());
        assertThat(publicContext)
                .contains("UNKNOWN")
                .doesNotContain("src/main/java/App.java")
                .doesNotContain("docs/spec.md");
        assertThat(fixture.factory.roleFileGrants(fixture.runtimeContext, RoleType.FRONTEND).isEmpty()).isTrue();
    }

    @Test
    void shortCircuitsIdenticalNonRetryableErrorsWithoutFurtherRepositoryAccess() {
        Fixture fixture = fixture();
        when(fixture.repositoryTools.snapshotFiles(any(), any()))
                .thenReturn(List.of("src/main/java/App.java"));
        AgentTool readLines = toolNamed(fixture.factory.readTools(fixture.runtimeContext, RoleType.BACKEND,
                Set.of("readLines")), "readLines");
        Map<String, Object> input = params(Map.of("fileRef", "replayed-ungranted-ref", "startLine", 1)).getInput();

        String first = resultText(readLines.callAsync(params(input)).block());
        String second = resultText(readLines.callAsync(params(input)).block());
        String third = resultText(readLines.callAsync(params(input)).block());

        assertThat(first).contains("FILE_REF_NOT_GRANTED");
        assertThat(second).isEqualTo(first);
        assertThat(third).isEqualTo(first);
        verify(fixture.repositoryTools, never()).readLines(any(), anyString(), anyInt(), anyInt(), any());
    }

    @Test
    void rejectsInvalidLineRangesAsNonRetryableWithoutReadingTheRepository() {
        Fixture fixture = fixture();
        when(fixture.repositoryTools.snapshotFiles(any(), any()))
                .thenReturn(List.of("src/main/java/App.java"));
        RepositoryFileGrantSet grants = fixture.factory.roleFileGrants(fixture.runtimeContext, RoleType.BACKEND);
        String grantedFileRef = grants.fileRefFor("src/main/java/App.java").orElseThrow();
        AgentTool readLines = toolNamed(fixture.factory.readTools(fixture.runtimeContext, RoleType.BACKEND,
                Set.of("readLines")), "readLines");

        String result = resultText(readLines.callAsync(params(Map.of(
                "fileRef", grantedFileRef, "startLine", 1, "lineCount", 10_000))).block());

        assertThat(result).contains("INVALID_LINE_RANGE").contains("do-not-retry");
        verify(fixture.repositoryTools, never()).readLines(any(), anyString(), anyInt(), anyInt(), any());
    }

    @Test
    void rejectsFileRefsIssuedForAnotherSnapshotCommitAsNotInSnapshot() {
        Fixture fixture = fixture();
        when(fixture.repositoryTools.snapshotFiles(any(), any()))
                .thenReturn(List.of("src/main/java/App.java"));
        var staleGrant = ai.cc.chongming.review.infrastructure.agentscope.tool.RepositoryFileGrant.issue(
                fixture.reviewId, 1, RoleType.BACKEND, "f".repeat(40), "src/main/java/App.java");
        AgentTool readLines = toolNamed(fixture.factory.readTools(fixture.runtimeContext, RoleType.BACKEND,
                Set.of("readLines"), RepositoryFileGrantSet.of(List.of(staleGrant))), "readLines");

        String result = resultText(readLines.callAsync(params(Map.of(
                "fileRef", staleGrant.fileRef(), "startLine", 1))).block());

        assertThat(result).contains("FILE_NOT_IN_SNAPSHOT").contains("do-not-retry");
        verify(fixture.repositoryTools, never()).readLines(any(), anyString(), anyInt(), anyInt(), any());
    }

    private Fixture fixture() {
        ReviewId reviewId = new ReviewId(UUID.randomUUID());
        ReviewRuntimeContext runtimeContext = new ReviewRuntimeContext(
                reviewId, 1, "reviewer", "trace", IntakeCancellation.neverCancelled());
        RequirementSnapshot requirementSnapshot = requirementSnapshot(reviewId);
        RepositorySnapshot repositorySnapshot = repositorySnapshot(reviewId);
        ReviewIntakeService intakeService = mock(ReviewIntakeService.class);
        RepositorySnapshotService snapshotService = mock(RepositorySnapshotService.class);
        ReadOnlyRepositoryTools repositoryTools = mock(ReadOnlyRepositoryTools.class);
        when(intakeService.requireSnapshot(reviewId, 1)).thenReturn(requirementSnapshot);
        when(snapshotService.findExistingSnapshot(
                reviewId, 1, ai.cc.chongming.review.application.RepositorySource.configured("approved-repository")))
                .thenReturn(Optional.of(repositorySnapshot));
        ReviewRepositoryToolFactory factory = new ReviewRepositoryToolFactory(
                intakeService, snapshotService, repositoryTools, new ReviewContextAssembler());
        return new Fixture(reviewId, runtimeContext, repositorySnapshot, repositoryTools, factory);
    }

    private record Fixture(
            ReviewId reviewId,
            ReviewRuntimeContext runtimeContext,
            RepositorySnapshot repositorySnapshot,
            ReadOnlyRepositoryTools repositoryTools,
            ReviewRepositoryToolFactory factory) {
    }

    private static AgentTool toolNamed(List<AgentTool> tools, String name) {
        return tools.stream().filter(tool -> tool.getName().equals(name)).findFirst()
                .orElseThrow(() -> new AssertionError("expected tool to be registered: " + name));
    }

    private static ToolCallParam params(Map<String, Object> input) {
        return ToolCallParam.builder().input(input).build();
    }

    private static String resultText(ToolResultBlock result) {
        return ((TextBlock) result.getOutput().get(0)).getText();
    }

    @SuppressWarnings("unchecked")
    private static ArgumentCaptor<Predicate<String>> scopeCaptor() {
        return ArgumentCaptor.forClass(Predicate.class);
    }

    private RolePack rolePack(RoleType roleType) {
        return new RolePack(
                roleType,
                roleType.name() + " reviewer",
                List.of("Always"),
                roleType.name().toLowerCase(java.util.Locale.ROOT) + "-v1",
                Set.of("requirement-snapshot", "repository-snapshot", "role-scope", "scout-overview"),
                List.of(new Checkpoint(roleType.name().toLowerCase(java.util.Locale.ROOT) + ".checkpoint", "Check it", true)),
                Set.of("listFiles", "searchText", "readLines", "getFileMetadata"),
                Kind.ROLE_ASSESSMENT,
                "role-reviewer",
                Duration.ofSeconds(30),
                4);
    }

    private RequirementSnapshot requirementSnapshot(ReviewId reviewId) {
        return new RequirementSnapshot(
                UUID.randomUUID(), reviewId, 1, "reviewer", "approved-repository", "main", null,
                "requirement.md", "a".repeat(64), "b".repeat(64), "test",
                new RequirementSnapshot.RequirementDocument(List.of(), List.of(), 0, 0, false), Instant.now());
    }

    /**
     * [AIREVIEW-PLAN-025] Remote-bound requirements carry a null repositoryPath; the shared
     * overview must take its identity from the snapshot instead of NPE-ing the Context Scout.
     */
    @Test
    void buildsTheSharedOverviewForRemoteBoundRequirementsWithoutARepositoryPath() {
        ReviewId reviewId = new ReviewId(UUID.randomUUID());
        ReviewRuntimeContext runtimeContext = new ReviewRuntimeContext(
                reviewId, 1, "reviewer", "trace", IntakeCancellation.neverCancelled());
        RequirementSnapshot remoteRequirement = new RequirementSnapshot(
                UUID.randomUUID(), reviewId, 1, "reviewer", null, null, null,
                "requirement.md", "a".repeat(64), "b".repeat(64), "test",
                new RequirementSnapshot.RequirementDocument(
                        List.of(new RequirementSnapshot.RequirementSection("Requirement", 1, 1, "# Requirement")),
                        List.of(), 1, 0, false),
                Instant.now(),
                new ai.cc.chongming.review.domain.model.RemoteRepositorySource(
                        "https://gitee.com/dromara/easy-query", "main", null));
        RepositorySnapshot remoteSnapshot = repositorySnapshot(reviewId);
        ReviewIntakeService intakeService = mock(ReviewIntakeService.class);
        RepositorySnapshotService snapshotService = mock(RepositorySnapshotService.class);
        ReadOnlyRepositoryTools repositoryTools = mock(ReadOnlyRepositoryTools.class);
        when(intakeService.requireSnapshot(reviewId, 1)).thenReturn(remoteRequirement);
        when(snapshotService.findExistingSnapshot(
                reviewId, 1, ai.cc.chongming.review.application.RepositorySource.from(remoteRequirement)))
                .thenReturn(Optional.of(remoteSnapshot));
        when(repositoryTools.listFiles(any(), anyInt(), any()))
                .thenReturn(List.<ai.cc.chongming.review.infrastructure.repository.RepositorySearchIndex.FileMetadata>of());
        ReviewRepositoryToolFactory factory = new ReviewRepositoryToolFactory(
                intakeService, snapshotService, repositoryTools, new ReviewContextAssembler());

        ReviewRepositoryToolFactory.SharedProjectContext overview = factory.sharedProjectContext(runtimeContext);

        assertThat(overview.repositoryId()).isEqualTo(remoteSnapshot.repositoryId());
        assertThat(overview.publicText(RoleType.PROJECT)).contains(remoteSnapshot.repositoryId());
    }

    private RepositorySnapshot repositorySnapshot(ReviewId reviewId) {
        Path root = Path.of("build/test-snapshot").toAbsolutePath();
        return new RepositorySnapshot(
                UUID.randomUUID(), reviewId, "approved-repository", root, root, "c".repeat(40), "main", false,
                "d".repeat(64), 1, Instant.now());
    }
}
