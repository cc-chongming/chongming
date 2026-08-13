package ai.cc.chongming.review.infrastructure.persistence.repository;

import ai.cc.chongming.review.domain.model.ReviewConflictAudit;
import ai.cc.chongming.review.domain.model.ReviewConflictAudit.Disposition;
import ai.cc.chongming.review.domain.model.ReviewTypes.ClaimId;
import ai.cc.chongming.review.domain.model.ReviewTypes.ReviewId;
import ai.cc.chongming.review.infrastructure.persistence.mapper.ReviewConflictAuditPersistenceMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [AIREVIEW-PLAN-024#方案5] Verifies durable conflict audit replacement, finalization and reload.
 *
 * @author zyj
 */
class MyBatisReviewConflictAuditStoreTests {

    private static final Instant DETECTED_AT = Instant.parse("2026-08-11T02:00:00Z");
    private static final Instant FINALIZED_AT = Instant.parse("2026-08-11T02:01:00Z");

    @Test
    void reloadsLatestAttemptAndFinalizedDispositionsAfterStoreReconstruction() {
        FakeConflictAuditPersistenceMapper mapper = new FakeConflictAuditPersistenceMapper();
        ReviewId reviewId = new ReviewId(UUID.randomUUID());
        ClaimId claimId = new ClaimId(UUID.randomUUID());
        MyBatisReviewConflictAuditStore first =
                new MyBatisReviewConflictAuditStore(mapper, new ObjectMapper());
        first.replaceBatch(reviewId, 1, List.of(
                audit(reviewId, 1, "auth.token_policy", List.of(claimId), Disposition.DETECTED),
                audit(reviewId, 1, "auth.retry_policy", List.of(), Disposition.NO_CONFLICT)));
        first.finalizeAttempt(reviewId, 1, List.of("AUTH.TOKEN_POLICY"), FINALIZED_AT);

        MyBatisReviewConflictAuditStore reconstructed =
                new MyBatisReviewConflictAuditStore(mapper, new ObjectMapper());
        assertThat(reconstructed.findByReviewAttempt(reviewId, 1))
                .extracting(ReviewConflictAudit::subjectKey, ReviewConflictAudit::disposition)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("auth.retry_policy", Disposition.NO_CONFLICT),
                        org.assertj.core.groups.Tuple.tuple("auth.token_policy", Disposition.REGISTERED));
        assertThat(reconstructed.findByReviewAttempt(reviewId, 1))
                .filteredOn(record -> record.subjectKey().equals("auth.token_policy"))
                .singleElement()
                .satisfies(record -> {
                    assertThat(record.claimIds()).containsExactly(claimId);
                    assertThat(record.updatedAt()).isEqualTo(FINALIZED_AT);
                });

        reconstructed.replaceBatch(reviewId, 2, List.of(
                audit(reviewId, 2, "auth.new_attempt", List.of(), Disposition.NO_CONFLICT)));
        assertThat(first.findByReviewAttempt(reviewId, 2))
                .extracting(ReviewConflictAudit::subjectKey)
                .containsExactly("auth.new_attempt");

        reconstructed.replaceBatch(reviewId, 3, List.of());
        assertThat(first.findByReviewAttempt(reviewId, 3)).isEmpty();
    }

    private ReviewConflictAudit audit(
            ReviewId reviewId,
            int attemptNo,
            String subjectKey,
            List<ClaimId> claimIds,
            Disposition disposition) {
        return new ReviewConflictAudit(
                reviewId, attemptNo, subjectKey, claimIds, "deterministic rule", disposition, DETECTED_AT);
    }

    /**
     * @author zyj
     */
    private static final class FakeConflictAuditPersistenceMapper
            implements ReviewConflictAuditPersistenceMapper {

        private final Map<String, ConflictAuditRow> rows = new LinkedHashMap<>();

        @Override
        public int deleteByAttempt(String reviewId, int attemptNo) {
            int before = rows.size();
            rows.entrySet().removeIf(entry -> entry.getValue().reviewId().equals(reviewId)
                    && entry.getValue().attemptNo() == attemptNo);
            return before - rows.size();
        }

        @Override
        public int insertBatch(List<ConflictAuditRow> batch) {
            batch.forEach(row -> rows.put(key(row), row));
            return batch.size();
        }

        @Override
        public int finalizeDetected(
                String reviewId,
                int attemptNo,
                List<String> registeredSubjectHashes,
                LocalDateTime updatedAt) {
            List<ConflictAuditRow> updates = new ArrayList<>();
            rows.values().stream()
                    .filter(row -> row.reviewId().equals(reviewId)
                            && row.attemptNo() == attemptNo
                            && row.disposition().equals(Disposition.DETECTED.name()))
                    .forEach(row -> updates.add(new ConflictAuditRow(
                            row.reviewId(),
                            row.attemptNo(),
                            row.subjectHash(),
                            row.subjectKey(),
                            row.claimIdsJson(),
                            row.rules(),
                            registeredSubjectHashes.contains(row.subjectHash())
                                    ? Disposition.REGISTERED.name() : Disposition.SKIPPED.name(),
                            updatedAt)));
            updates.forEach(row -> rows.put(key(row), row));
            return updates.size();
        }

        @Override
        public List<ConflictAuditRow> findByAttempt(String reviewId, int attemptNo) {
            return rows.values().stream()
                    .filter(row -> row.reviewId().equals(reviewId) && row.attemptNo() == attemptNo)
                    .sorted(Comparator.comparing(ConflictAuditRow::subjectKey))
                    .toList();
        }

        private String key(ConflictAuditRow row) {
            return row.reviewId() + "|" + row.attemptNo() + "|" + row.subjectHash();
        }
    }
}
