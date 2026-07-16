package ai.cc.chongming.review.agentscope.tool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.cc.chongming.review.application.EvidenceLedgerService;
import ai.cc.chongming.review.application.IntakeCancellation;
import ai.cc.chongming.review.application.ReviewIntakeException;
import ai.cc.chongming.review.domain.model.RepositorySnapshot;
import ai.cc.chongming.review.domain.model.ReviewTypes.ReviewId;
import ai.cc.chongming.review.domain.model.ReviewTypes.RoleType;
import ai.cc.chongming.review.infrastructure.agentscope.tool.EvidenceTools;
import ai.cc.chongming.review.infrastructure.agentscope.tool.ReadOnlyRepositoryTools;
import ai.cc.chongming.review.infrastructure.agentscope.tool.RepositoryToolContext;
import ai.cc.chongming.review.infrastructure.repository.RepositorySearchIndex;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests that AgentScope-facing repository facades stay bound to a server-issued snapshot context.
 *
 * @author wangli
 */
class RepositoryToolFacadeTests {

    @TempDir
    Path temporaryDirectory;

    @Test
    void exposesOnlySnapshotBoundReadsAndEvidenceWithCooperativeCancellation() throws Exception {
        RepositorySnapshot snapshot = snapshot();
        RepositoryToolContext context = new RepositoryToolContext(
                "runtime-1", snapshot.reviewId(), RoleType.BACKEND, snapshot);
        ReadOnlyRepositoryTools repositoryTools = new ReadOnlyRepositoryTools(new RepositorySearchIndex());
        EvidenceTools evidenceTools = new EvidenceTools(new EvidenceLedgerService());

        assertThat(repositoryTools.findSymbol(context, "App", 10, IntakeCancellation.neverCancelled()))
                .singleElement()
                .extracting(match -> match.relativePath(), match -> match.lineNumber())
                .containsExactly("src/App.java", 1);
        assertThat(repositoryTools.getFileMetadata(context, "src/App.java", IntakeCancellation.neverCancelled()))
                .satisfies(metadata -> {
                    assertThat(metadata.relativePath()).isEqualTo("src/App.java");
                    assertThat(metadata.fileHash()).hasSize(64);
                });
        assertThat(evidenceTools.submitEvidence(context, "src/App.java", 2, IntakeCancellation.neverCancelled()).excerpt())
                .isEqualTo("// TODO: validate evidence");
        assertThatThrownBy(() -> repositoryTools.listFiles(context, 10, () -> true))
                .isInstanceOf(ReviewIntakeException.class);
    }

    @Test
    void rejectsContextsWhoseReviewDoesNotOwnTheSnapshot() throws Exception {
        RepositorySnapshot snapshot = snapshot();

        assertThatThrownBy(() -> new RepositoryToolContext(
                        "runtime-1", new ReviewId(UUID.randomUUID()), RoleType.BACKEND, snapshot))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must match");
    }

    private RepositorySnapshot snapshot() throws Exception {
        Path root = temporaryDirectory.resolve("snapshot/repository");
        Files.createDirectories(root.resolve("src"));
        Files.writeString(root.resolve("src/App.java"), "class App {\n// TODO: validate evidence\n}\n", StandardCharsets.UTF_8);
        return new RepositorySnapshot(
                UUID.randomUUID(),
                new ReviewId(UUID.randomUUID()),
                "sample-repository",
                root,
                root,
                "a".repeat(40),
                "main",
                false,
                "b".repeat(64),
                1,
                Instant.now());
    }
}
