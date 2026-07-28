package ai.cc.chongming.review.document;

import static org.assertj.core.api.Assertions.assertThat;

import ai.cc.chongming.review.application.IntakeCancellation;
import ai.cc.chongming.review.config.ReviewProperties;
import ai.cc.chongming.review.domain.model.ReviewTypes.ReviewId;
import ai.cc.chongming.review.infrastructure.document.RequirementSnapshotStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * [AIREVIEW-PLAN-017] Verifies that a Director gets a mutable workspace copy, not the source snapshot.
 *
 * @author wangli
 */
class RequirementSnapshotStoreTests {

    @TempDir
    Path workspaceRoot;

    @Test
    void materializesImmutableRequirementIntoAttemptWorkspace() throws Exception {
        ReviewId reviewId = new ReviewId(UUID.randomUUID());
        Path source = workspaceRoot.resolve("reviews").resolve(reviewId.value().toString())
                .resolve("attempt-1").resolve("input");
        Files.createDirectories(source);
        Files.writeString(source.resolve("requirement.normalized.md"), "# 需求\n\n正文");
        Files.writeString(source.resolve("snapshot-manifest.json"), "{\"attemptNo\":1}");
        Path agentWorkspace = workspaceRoot.resolve("reviews").resolve(reviewId.value().toString())
                .resolve("attempts").resolve("1");

        RequirementSnapshotStore store = new RequirementSnapshotStore(
                new ReviewProperties(workspaceRoot.toString(), 8, 2));
        store.materializeForAgentWorkspace(reviewId, 1, agentWorkspace, IntakeCancellation.neverCancelled());

        Path workingCopy = agentWorkspace.resolve("input").resolve("requirement.md");
        assertThat(Files.readString(workingCopy)).isEqualTo("# 需求\n\n正文");
        Files.writeString(workingCopy, "已修改的工作副本");
        assertThat(Files.readString(source.resolve("requirement.normalized.md"))).isEqualTo("# 需求\n\n正文");
    }
}
