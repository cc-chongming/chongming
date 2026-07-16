package ai.cc.chongming.review.evidence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.cc.chongming.review.application.EvidenceLedgerService;
import ai.cc.chongming.review.application.EvidenceLedgerService.EvidenceSubmission;
import ai.cc.chongming.review.application.RepositoryAccessException;
import ai.cc.chongming.review.domain.model.RepositorySnapshot;
import ai.cc.chongming.review.domain.model.ReviewTypes.EvidenceId;
import ai.cc.chongming.review.domain.model.ReviewTypes.ReviewId;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests append-only, deduplicated evidence generation and drift validation.
 *
 * @author wangli
 */
class EvidenceLedgerServiceTests {

    @TempDir
    Path temporaryDirectory;

    @Test
    void deduplicatesEvidenceAndValidatesRequestedIdsInOneBatch() throws Exception {
        RepositorySnapshot snapshot = snapshot("class App {}\n// TODO: verify input\n");
        EvidenceLedgerService ledger = new EvidenceLedgerService();

        var first = ledger.submit(snapshot, new EvidenceSubmission("src/App.java", 1));
        var replay = ledger.submit(snapshot, new EvidenceSubmission("src/App.java", 1));
        var second = ledger.submit(snapshot, new EvidenceSubmission("src/App.java", 2));
        EvidenceId forgedId = new EvidenceId(UUID.randomUUID());
        var validation = ledger.validateAll(snapshot, Set.of(first.evidenceId(), second.evidenceId(), forgedId));

        assertThat(replay.evidenceId()).isEqualTo(first.evidenceId());
        assertThat(ledger.findByIds(snapshot.reviewId(), Set.of(first.evidenceId(), second.evidenceId())))
                .containsOnlyKeys(first.evidenceId(), second.evidenceId());
        assertThat(validation.get(first.evidenceId()).valid()).isTrue();
        assertThat(validation.get(second.evidenceId()).valid()).isTrue();
        assertThat(validation.get(forgedId))
                .extracting(verification -> verification.valid(), verification -> verification.reason())
                .containsExactly(false, "EVIDENCE_NOT_FOUND");
    }

    @Test
    void rejectsPathTraversalAndDetectsSnapshotTampering() throws Exception {
        RepositorySnapshot snapshot = snapshot("class App {}\n");
        EvidenceLedgerService ledger = new EvidenceLedgerService();

        assertThatThrownBy(() -> ledger.submit(snapshot, new EvidenceSubmission("../outside.java", 1)))
                .isInstanceOf(RepositoryAccessException.class)
                .extracting(exception -> ((RepositoryAccessException) exception).code())
                .isEqualTo(RepositoryAccessException.Code.REPOSITORY_PATH_UNSAFE);

        var evidence = ledger.submit(snapshot, new EvidenceSubmission("src/App.java", 1));
        Files.writeString(snapshot.snapshotRepositoryRoot().resolve("src/App.java"), "class Changed {}\n", StandardCharsets.UTF_8);

        assertThat(ledger.validateAll(snapshot, Set.of(evidence.evidenceId())).get(evidence.evidenceId()))
                .extracting(verification -> verification.valid(), verification -> verification.reason())
                .containsExactly(false, "FILE_HASH_MISMATCH");
    }

    private RepositorySnapshot snapshot(String source) throws Exception {
        Path root = temporaryDirectory.resolve("snapshot/repository");
        Files.createDirectories(root.resolve("src"));
        Files.writeString(root.resolve("src/App.java"), source, StandardCharsets.UTF_8);
        ReviewId reviewId = new ReviewId(UUID.randomUUID());
        return new RepositorySnapshot(
                UUID.randomUUID(),
                reviewId,
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
