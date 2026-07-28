package ai.cc.chongming.review.agentscope;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ai.cc.chongming.review.application.IntakeCancellation;
import ai.cc.chongming.review.application.RepositorySnapshotService;
import ai.cc.chongming.review.application.ReviewIntakeService;
import ai.cc.chongming.review.application.ReviewRuntimeContext;
import ai.cc.chongming.review.domain.model.RepositorySnapshot;
import ai.cc.chongming.review.domain.model.RequirementSnapshot;
import ai.cc.chongming.review.domain.model.ReviewTypes.ReviewId;
import ai.cc.chongming.review.domain.model.ReviewTypes.RoleType;
import ai.cc.chongming.review.infrastructure.agentscope.ReviewRepositoryToolFactory;
import ai.cc.chongming.review.infrastructure.agentscope.tool.ReadOnlyRepositoryTools;
import ai.cc.chongming.review.infrastructure.repository.RepositorySearchIndex.FileMetadata;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * Verifies that Harness repository tools are bound to a server-issued frozen snapshot.
 *
 * @author wangli
 */
class ReviewRepositoryToolFactoryTests {

    @Test
    void exposesOnlyRolePackApprovedReadToolsForTheCurrentReviewSnapshot() {
        ReviewId reviewId = new ReviewId(UUID.randomUUID());
        ReviewRuntimeContext runtimeContext = new ReviewRuntimeContext(
                reviewId, 1, "reviewer", "trace", IntakeCancellation.neverCancelled());
        RequirementSnapshot requirementSnapshot = requirementSnapshot(reviewId);
        RepositorySnapshot repositorySnapshot = repositorySnapshot(reviewId);
        ReviewIntakeService intakeService = mock(ReviewIntakeService.class);
        RepositorySnapshotService snapshotService = mock(RepositorySnapshotService.class);
        when(intakeService.requireSnapshot(reviewId, 1)).thenReturn(requirementSnapshot);
        when(snapshotService.findExistingSnapshot(reviewId, 1, "approved-repository")).thenReturn(Optional.empty());
        when(snapshotService.bindSnapshot(
                reviewId, 1, "approved-repository", requirementSnapshot.contentHash(), runtimeContext.cancellation()))
                .thenReturn(repositorySnapshot);

        ReviewRepositoryToolFactory factory = new ReviewRepositoryToolFactory(
                intakeService, snapshotService, mock(ReadOnlyRepositoryTools.class));

        assertThat(factory.readTools(runtimeContext, RoleType.BACKEND,
                Set.of("listFiles", "searchText", "readLines", "submit_claim")))
                .extracting(tool -> tool.getName())
                .containsExactlyInAnyOrder("listFiles", "searchText", "readLines");
        assertThat(factory.scoutReadTools(runtimeContext)).allSatisfy(tool -> assertThat(tool.isReadOnly()).isTrue());
    }

    @Test
    void buildsOneSharedProjectContextAndKeepsProductContextFreeOfRepositoryFileNames() {
        ReviewId reviewId = new ReviewId(UUID.randomUUID());
        ReviewRuntimeContext runtimeContext = new ReviewRuntimeContext(
                reviewId, 1, "reviewer", "trace", IntakeCancellation.neverCancelled());
        RequirementSnapshot requirementSnapshot = requirementSnapshot(reviewId);
        RepositorySnapshot repositorySnapshot = repositorySnapshot(reviewId);
        ReviewIntakeService intakeService = mock(ReviewIntakeService.class);
        RepositorySnapshotService snapshotService = mock(RepositorySnapshotService.class);
        ReadOnlyRepositoryTools repositoryTools = mock(ReadOnlyRepositoryTools.class);
        when(intakeService.requireSnapshot(reviewId, 1)).thenReturn(requirementSnapshot);
        when(snapshotService.findExistingSnapshot(reviewId, 1, "approved-repository"))
                .thenReturn(Optional.of(repositorySnapshot));
        when(repositoryTools.listFiles(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.eq(40),
                org.mockito.ArgumentMatchers.any()))
                .thenReturn(List.of(new FileMetadata(
                        "service/src/main/App.java", 10, Instant.now(), "e".repeat(64), "java", true)));

        ReviewRepositoryToolFactory factory = new ReviewRepositoryToolFactory(
                intakeService, snapshotService, repositoryTools);

        ReviewRepositoryToolFactory.SharedProjectContext first = factory.sharedProjectContext(runtimeContext);
        ReviewRepositoryToolFactory.SharedProjectContext second = factory.sharedProjectContext(runtimeContext);

        assertThat(first).isSameAs(second);
        assertThat(first.publicText(RoleType.PRODUCT)).doesNotContain("service/src/main/App.java");
        assertThat(first.publicText(RoleType.BACKEND)).contains("service/src/main/App.java");
        verify(repositoryTools, times(1)).listFiles(
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.eq(40), org.mockito.ArgumentMatchers.any());
    }

    private RequirementSnapshot requirementSnapshot(ReviewId reviewId) {
        return new RequirementSnapshot(
                UUID.randomUUID(), reviewId, 1, "reviewer", "approved-repository", "main", null,
                "requirement.md", "a".repeat(64), "b".repeat(64), "test",
                new RequirementSnapshot.RequirementDocument(List.of(), List.of(), 0, 0, false), Instant.now());
    }

    private RepositorySnapshot repositorySnapshot(ReviewId reviewId) {
        Path root = Path.of("build/test-snapshot").toAbsolutePath();
        return new RepositorySnapshot(
                UUID.randomUUID(), reviewId, "approved-repository", root, root, "c".repeat(40), "main", false,
                "d".repeat(64), 1, Instant.now());
    }
}
