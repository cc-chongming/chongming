package ai.cc.chongming.review.document;

import static org.assertj.core.api.Assertions.assertThat;

import ai.cc.chongming.review.application.IntakeCancellation;
import ai.cc.chongming.review.config.ReviewProperties;
import ai.cc.chongming.review.domain.model.RemoteRepositorySource;
import ai.cc.chongming.review.domain.model.RequirementSnapshot;
import ai.cc.chongming.review.domain.model.ReviewTypes.ReviewId;
import ai.cc.chongming.review.infrastructure.document.RequirementSnapshotStore;
import ai.cc.chongming.review.infrastructure.document.ValidatedMarkdown;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
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

    /**
     * [AIREVIEW-PLAN-029] The attempt manifest carries the requirement-supplied online repository
     * source (cipher text only) across store/load so restarts can re-materialize the mirror.
     */
    @Test
    void persistsAndLoadsTheRemoteRepositorySourceWithCipherTextOnly() throws Exception {
        ReviewId reviewId = new ReviewId(UUID.randomUUID());
        RequirementSnapshotStore store = new RequirementSnapshotStore(
                new ReviewProperties(workspaceRoot.toString(), 8, 2));
        Path rawFile = Files.createTempFile(workspaceRoot, "requirement-raw-", ".md");
        Path normalizedFile = Files.createTempFile(workspaceRoot, "requirement-normalized-", ".md");
        Files.writeString(rawFile, "# 需求\n");
        Files.writeString(normalizedFile, "# 需求\n");
        ValidatedMarkdown markdown = new ValidatedMarkdown(
                "requirement.md", rawFile, normalizedFile, "a".repeat(64), "b".repeat(64), 8);
        RemoteRepositorySource remoteSource = new RemoteRepositorySource(
                "https://example.com/group/demo.git", "main", "v1:cipher-text");
        RequirementSnapshot snapshot = new RequirementSnapshot(
                UUID.randomUUID(), reviewId, 1, "reviewer", null, null, null,
                "requirement.md", "a".repeat(64), "b".repeat(64), "test",
                new RequirementSnapshot.RequirementDocument(List.of(), List.of(), 0, 0, false),
                Instant.now(), remoteSource);

        store.store(snapshot, markdown, IntakeCancellation.neverCancelled());
        RequirementSnapshot loaded = store.load(reviewId, 1);

        assertThat(loaded.remoteSource()).isEqualTo(remoteSource);
        assertThat(loaded.repositoryPath()).isNull();
        assertThat(loaded.repositoryIdentity()).startsWith("remote:");
        // The manifest must never contain a plain-text token field.
        Path manifest = workspaceRoot.resolve("reviews").resolve(reviewId.value().toString())
                .resolve("attempt-1").resolve("input").resolve("snapshot-manifest.json");
        assertThat(Files.readString(manifest)).contains("v1:cipher-text").doesNotContain("plain-token");
    }
}
