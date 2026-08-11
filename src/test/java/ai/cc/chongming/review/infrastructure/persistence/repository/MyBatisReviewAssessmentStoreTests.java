package ai.cc.chongming.review.infrastructure.persistence.repository;

import ai.cc.chongming.review.domain.model.ReviewAssessment;
import ai.cc.chongming.review.domain.model.ReviewTypes.AssessmentStatus;
import ai.cc.chongming.review.domain.model.ReviewTypes.EvidenceId;
import ai.cc.chongming.review.domain.model.ReviewTypes.ReviewId;
import ai.cc.chongming.review.domain.model.ReviewTypes.RoleType;
import ai.cc.chongming.review.infrastructure.persistence.mapper.ReviewAssessmentPersistenceMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * [AIREVIEW-PLAN-024#方案5] Verifies the durable assessment store round-trips every field and keeps
 * the batch/idempotency semantics of {@code InMemoryReviewAssessmentStore}: one batch per role
 * submission, latest submission wins per (review, attempt, role, checkpointKey), and all reads are
 * complete batch queries.
 *
 * @author wangli
 */
class MyBatisReviewAssessmentStoreTests {

    private static final Instant NOW = Instant.parse("2026-08-10T09:00:00Z");

    @Test
    void roundTripsAssessmentBatchWithEvidenceIds() {
        MyBatisReviewAssessmentStore store = newStore();
        ReviewId reviewId = new ReviewId(UUID.randomUUID());
        EvidenceId evidenceId = new EvidenceId(UUID.randomUUID());
        ReviewAssessment confirmed = assessment(reviewId, 1, RoleType.BACKEND, "token_expiry_policy",
                AssessmentStatus.CONFIRMED, "令牌过期策略已实现。", null, List.of(evidenceId));
        ReviewAssessment unknown = assessment(reviewId, 1, RoleType.FRONTEND, "snapshot_grant_scope",
                AssessmentStatus.UNKNOWN, "无法确认前端快照授权范围。", "当前评审快照未授予前端文件。", List.of());

        store.saveBatch(reviewId, 1, List.of(confirmed, unknown));

        List<ReviewAssessment> reloaded = store.findByReview(reviewId, 1);
        assertThat(reloaded).hasSize(2);
        ReviewAssessment reloadedConfirmed = reloaded.stream()
                .filter(value -> value.roleType() == RoleType.BACKEND).findFirst().orElseThrow();
        assertThat(reloadedConfirmed.checkpointKey()).isEqualTo("token_expiry_policy");
        assertThat(reloadedConfirmed.status()).isEqualTo(AssessmentStatus.CONFIRMED);
        assertThat(reloadedConfirmed.summary()).isEqualTo("令牌过期策略已实现。");
        assertThat(reloadedConfirmed.reasonSummary()).isNull();
        assertThat(reloadedConfirmed.evidenceIds()).containsExactly(evidenceId);
        assertThat(reloadedConfirmed.idempotencyKey()).isEqualTo(confirmed.idempotencyKey());
        assertThat(reloadedConfirmed.createdAt()).isEqualTo(NOW);
        ReviewAssessment reloadedUnknown = reloaded.stream()
                .filter(value -> value.roleType() == RoleType.FRONTEND).findFirst().orElseThrow();
        assertThat(reloadedUnknown.status()).isEqualTo(AssessmentStatus.UNKNOWN);
        assertThat(reloadedUnknown.reasonSummary()).isEqualTo("当前评审快照未授予前端文件。");
        assertThat(reloadedUnknown.evidenceIds()).isEmpty();
    }

    @Test
    void repeatedSubmissionReplacesPreviousAssessmentForTheSameIdentity() {
        MyBatisReviewAssessmentStore store = newStore();
        ReviewId reviewId = new ReviewId(UUID.randomUUID());
        ReviewAssessment first = assessment(reviewId, 1, RoleType.PRODUCT, "requirement_traceability",
                AssessmentStatus.PARTIAL, "部分需求缺少追踪。", "两条需求无追踪号。", List.of());
        store.saveBatch(reviewId, 1, List.of(first));

        ReviewAssessment replacement = assessment(reviewId, 1, RoleType.PRODUCT, "requirement_traceability",
                AssessmentStatus.CONFIRMED, "追踪补齐。", null, List.of());
        store.saveBatch(reviewId, 1, List.of(replacement));

        List<ReviewAssessment> reloaded = store.findByReview(reviewId, 1);
        assertThat(reloaded).singleElement()
                .satisfies(value -> {
                    assertThat(value.status()).isEqualTo(AssessmentStatus.CONFIRMED);
                    assertThat(value.summary()).isEqualTo("追踪补齐。");
                });
    }

    @Test
    void rejectsBatchContainingDuplicateCheckpointIdentity() {
        MyBatisReviewAssessmentStore store = newStore();
        ReviewId reviewId = new ReviewId(UUID.randomUUID());
        ReviewAssessment first = assessment(reviewId, 1, RoleType.PROJECT, "milestone_plan",
                AssessmentStatus.CONFIRMED, "里程碑完整。", null, List.of());
        ReviewAssessment duplicate = assessment(reviewId, 1, RoleType.PROJECT, "milestone_plan",
                AssessmentStatus.PARTIAL, "里程碑部分缺失。", "缺少灰度计划。", List.of());

        assertThatThrownBy(() -> store.saveBatch(reviewId, 1, List.of(first, duplicate)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("duplicate assessment in batch");
        assertThat(store.findByReview(reviewId, 1)).isEmpty();
    }

    @Test
    void rejectsBatchThatCrossesReviewOrAttemptBoundaries() {
        MyBatisReviewAssessmentStore store = newStore();
        ReviewId reviewId = new ReviewId(UUID.randomUUID());
        ReviewAssessment foreign = assessment(new ReviewId(UUID.randomUUID()), 1, RoleType.PRODUCT,
                "requirement_traceability", AssessmentStatus.CONFIRMED, "其它评审的结论。", null, List.of());

        assertThatThrownBy(() -> store.saveBatch(reviewId, 1, List.of(foreign)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("does not belong to review");
        assertThatThrownBy(() -> store.saveBatch(reviewId, 0, List.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> store.saveBatch(reviewId, 1, List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must not be empty");
    }

    @Test
    void filtersByRoleAndOrdersDeterministically() {
        MyBatisReviewAssessmentStore store = newStore();
        ReviewId reviewId = new ReviewId(UUID.randomUUID());
        store.saveBatch(reviewId, 1, List.of(
                assessment(reviewId, 1, RoleType.BACKEND, "token_expiry_policy",
                        AssessmentStatus.CONFIRMED, "后端结论B。", null, List.of()),
                assessment(reviewId, 1, RoleType.BACKEND, "audit_log_coverage",
                        AssessmentStatus.GAP, "审计缺口。", "审计日志缺少敏感操作。", List.of()),
                assessment(reviewId, 1, RoleType.FRONTEND, "incremental_render",
                        AssessmentStatus.CONFIRMED, "前端结论。", null, List.of())));

        assertThat(store.findByReview(reviewId, 1))
                .extracting(value -> value.roleType() + ":" + value.checkpointKey())
                .containsExactly(
                        "BACKEND:audit_log_coverage",
                        "BACKEND:token_expiry_policy",
                        "FRONTEND:incremental_render");
        assertThat(store.findByReview(reviewId, 1, RoleType.BACKEND))
                .extracting(ReviewAssessment::checkpointKey)
                .containsExactly("audit_log_coverage", "token_expiry_policy");
        assertThat(store.findByReview(reviewId, 1, RoleType.PRODUCT)).isEmpty();
        assertThat(store.findByReview(new ReviewId(UUID.randomUUID()), 1)).isEmpty();
    }

    private MyBatisReviewAssessmentStore newStore() {
        return new MyBatisReviewAssessmentStore(new FakeAssessmentPersistenceMapper(), new ObjectMapper());
    }

    private ReviewAssessment assessment(
            ReviewId reviewId,
            int attemptNo,
            RoleType roleType,
            String checkpointKey,
            AssessmentStatus status,
            String summary,
            String reasonSummary,
            List<EvidenceId> evidenceIds) {
        return new ReviewAssessment(reviewId, attemptNo, roleType, checkpointKey, status, summary,
                reasonSummary, evidenceIds,
                ReviewAssessment.idempotencyKeyFor(reviewId, attemptNo, roleType, checkpointKey), NOW);
    }

    /** @author wangli */
    private static final class FakeAssessmentPersistenceMapper implements ReviewAssessmentPersistenceMapper {

        private final Map<String, AssessmentRow> rows = new LinkedHashMap<>();

        @Override
        public int upsertBatch(List<AssessmentRow> batch) {
            for (AssessmentRow row : batch) {
                rows.put(row.reviewId() + "|" + row.attemptNo() + "|" + row.roleType() + "|" + row.checkpointKey(), row);
            }
            return batch.size();
        }

        @Override
        public List<AssessmentRow> findByAttempt(String reviewId, int attemptNo) {
            return rows.values().stream()
                    .filter(row -> row.reviewId().equals(reviewId) && row.attemptNo() == attemptNo)
                    .sorted(Comparator.comparing(AssessmentRow::roleType).thenComparing(AssessmentRow::checkpointKey))
                    .toList();
        }

        @Override
        public List<AssessmentRow> findByAttemptAndRole(String reviewId, int attemptNo, String roleType) {
            return findByAttempt(reviewId, attemptNo).stream()
                    .filter(row -> row.roleType().equals(roleType))
                    .sorted(Comparator.comparing(AssessmentRow::checkpointKey))
                    .toList();
        }
    }
}
