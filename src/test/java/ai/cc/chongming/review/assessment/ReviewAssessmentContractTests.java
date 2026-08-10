package ai.cc.chongming.review.assessment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.cc.chongming.review.domain.model.ReviewAssessment;
import ai.cc.chongming.review.domain.model.ReviewTypes.AssessmentStatus;
import ai.cc.chongming.review.domain.model.ReviewTypes.ReviewId;
import ai.cc.chongming.review.domain.model.ReviewTypes.RoleType;
import ai.cc.chongming.review.domain.repository.ReviewAssessmentStore;
import ai.cc.chongming.review.infrastructure.assessment.InMemoryReviewAssessmentStore;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * [AIREVIEW-PLAN-024#方案0] Freezes the five-state Assessment semantics, mandatory field validation and
 * the batch-scoped, idempotent storage contract of {@link ReviewAssessmentStore}.
 *
 * @author wangli
 */
class ReviewAssessmentContractTests {

    private final ReviewAssessmentStore store = new InMemoryReviewAssessmentStore();

    @Test
    void exposesExactlyFiveFrozenAssessmentStatuses() {
        assertThat(AssessmentStatus.values()).containsExactly(
                AssessmentStatus.CONFIRMED,
                AssessmentStatus.PARTIAL,
                AssessmentStatus.GAP,
                AssessmentStatus.UNKNOWN,
                AssessmentStatus.NOT_APPLICABLE);
    }

    @Test
    void requiresReasonSummaryOnlyForPartialGapAndUnknown() {
        assertThat(AssessmentStatus.CONFIRMED.requiresReasonSummary()).isFalse();
        assertThat(AssessmentStatus.NOT_APPLICABLE.requiresReasonSummary()).isFalse();
        assertThat(AssessmentStatus.PARTIAL.requiresReasonSummary()).isTrue();
        assertThat(AssessmentStatus.GAP.requiresReasonSummary()).isTrue();
        assertThat(AssessmentStatus.UNKNOWN.requiresReasonSummary()).isTrue();
    }

    @Test
    void rejectsMissingCheckpointKeyStatusAndSummary() {
        ReviewId reviewId = new ReviewId(UUID.randomUUID());
        assertThatThrownBy(() -> assessment(reviewId, 1, RoleType.PRODUCT, null,
                AssessmentStatus.CONFIRMED, "summary", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("checkpointKey");
        assertThatThrownBy(() -> assessment(reviewId, 1, RoleType.PRODUCT, "product.requirement_completeness",
                null, "summary", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("status");
        assertThatThrownBy(() -> assessment(reviewId, 1, RoleType.PRODUCT, "product.requirement_completeness",
                AssessmentStatus.CONFIRMED, " ", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("summary");
    }

    @Test
    void rejectsUnstableCheckpointKeyFormat() {
        ReviewId reviewId = new ReviewId(UUID.randomUUID());
        assertThatThrownBy(() -> assessment(reviewId, 1, RoleType.PRODUCT, "Product.Requirement",
                AssessmentStatus.CONFIRMED, "summary", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("checkpointKey");
    }

    @Test
    void requiresReasonSummaryForGapAndUnknownStatuses() {
        ReviewId reviewId = new ReviewId(UUID.randomUUID());
        assertThatThrownBy(() -> assessment(reviewId, 1, RoleType.BACKEND, "backend.data_consistency",
                AssessmentStatus.GAP, "summary", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("reasonSummary");
        assertThatThrownBy(() -> assessment(reviewId, 1, RoleType.BACKEND, "backend.data_consistency",
                AssessmentStatus.UNKNOWN, "summary", " "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("reasonSummary");
        assertThatThrownBy(() -> assessment(reviewId, 1, RoleType.FRONTEND, "frontend.ui_state_coverage",
                AssessmentStatus.PARTIAL, "summary", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("reasonSummary");
    }

    @Test
    void acceptsConfirmedAndNotApplicableWithoutReasonSummary() {
        ReviewId reviewId = new ReviewId(UUID.randomUUID());
        assertThat(assessment(reviewId, 1, RoleType.PRODUCT, "product.user_value",
                AssessmentStatus.CONFIRMED, "summary", null)).isNotNull();
        assertThat(assessment(reviewId, 1, RoleType.PROJECT, "project.schedule_risk",
                AssessmentStatus.NOT_APPLICABLE, "summary", null)).isNotNull();
    }

    @Test
    void keepsOnlyTheLatestAssessmentForTheSameCheckpointKey() {
        ReviewId reviewId = new ReviewId(UUID.randomUUID());
        ReviewAssessment first = assessment(reviewId, 1, RoleType.PRODUCT, "product.testability",
                AssessmentStatus.GAP, "first", "first reason");
        ReviewAssessment latest = assessment(reviewId, 1, RoleType.PRODUCT, "product.testability",
                AssessmentStatus.CONFIRMED, "latest", null);

        store.saveBatch(reviewId, 1, List.of(first));
        store.saveBatch(reviewId, 1, List.of(latest));

        List<ReviewAssessment> stored = store.findByReview(reviewId, 1, RoleType.PRODUCT);
        assertThat(stored).hasSize(1);
        assertThat(stored.get(0).status()).isEqualTo(AssessmentStatus.CONFIRMED);
        assertThat(stored.get(0).summary()).isEqualTo("latest");
    }

    @Test
    void isolatesAssessmentsByReviewAndAttempt() {
        ReviewId firstReview = new ReviewId(UUID.randomUUID());
        ReviewId secondReview = new ReviewId(UUID.randomUUID());
        store.saveBatch(firstReview, 1, List.of(assessment(firstReview, 1, RoleType.PRODUCT,
                "product.user_value", AssessmentStatus.CONFIRMED, "review-1 attempt-1", null)));
        store.saveBatch(firstReview, 2, List.of(assessment(firstReview, 2, RoleType.PRODUCT,
                "product.user_value", AssessmentStatus.GAP, "review-1 attempt-2", "gap reason")));
        store.saveBatch(secondReview, 1, List.of(assessment(secondReview, 1, RoleType.PRODUCT,
                "product.user_value", AssessmentStatus.UNKNOWN, "review-2 attempt-1", "missing evidence")));

        assertThat(store.findByReview(firstReview, 1)).hasSize(1);
        assertThat(store.findByReview(firstReview, 2)).hasSize(1);
        assertThat(store.findByReview(secondReview, 1)).hasSize(1);
        assertThat(store.findByReview(firstReview, 1).get(0).summary()).isEqualTo("review-1 attempt-1");
        assertThat(store.findByReview(firstReview, 3)).isEmpty();
    }

    @Test
    void isolatesAssessmentsBetweenRolesWithinTheSameCheckpointNamespace() {
        ReviewId reviewId = new ReviewId(UUID.randomUUID());
        store.saveBatch(reviewId, 1, List.of(
                assessment(reviewId, 1, RoleType.PRODUCT, "product.user_value",
                        AssessmentStatus.CONFIRMED, "product view", null),
                assessment(reviewId, 1, RoleType.BACKEND, "backend.data_consistency",
                        AssessmentStatus.GAP, "backend view", "missing constraint")));

        assertThat(store.findByReview(reviewId, 1)).hasSize(2);
        assertThat(store.findByReview(reviewId, 1, RoleType.PRODUCT))
                .extracting(ReviewAssessment::checkpointKey).containsExactly("product.user_value");
        assertThat(store.findByReview(reviewId, 1, RoleType.BACKEND))
                .extracting(ReviewAssessment::checkpointKey).containsExactly("backend.data_consistency");
        assertThat(store.findByReview(reviewId, 1, RoleType.FRONTEND)).isEmpty();
    }

    @Test
    void rejectsBatchMixingReviewsAttemptsOrDuplicateCheckpoints() {
        ReviewId reviewId = new ReviewId(UUID.randomUUID());
        ReviewId otherReview = new ReviewId(UUID.randomUUID());
        assertThatThrownBy(() -> store.saveBatch(reviewId, 1,
                List.of(assessment(otherReview, 1, RoleType.PRODUCT, "product.user_value",
                        AssessmentStatus.CONFIRMED, "summary", null))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> store.saveBatch(reviewId, 1,
                List.of(assessment(reviewId, 2, RoleType.PRODUCT, "product.user_value",
                        AssessmentStatus.CONFIRMED, "summary", null))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> store.saveBatch(reviewId, 1, List.of(
                assessment(reviewId, 1, RoleType.PRODUCT, "product.user_value",
                        AssessmentStatus.CONFIRMED, "first", null),
                assessment(reviewId, 1, RoleType.PRODUCT, "product.user_value",
                        AssessmentStatus.GAP, "second", "reason"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("duplicate");
        assertThatThrownBy(() -> store.saveBatch(reviewId, 1, List.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(store.findByReview(reviewId, 1)).isEmpty();
    }

    private ReviewAssessment assessment(ReviewId reviewId, int attemptNo, RoleType roleType, String checkpointKey,
            AssessmentStatus status, String summary, String reasonSummary) {
        return new ReviewAssessment(
                reviewId,
                attemptNo,
                roleType,
                checkpointKey,
                status,
                summary,
                reasonSummary,
                List.of(),
                ReviewAssessment.idempotencyKeyFor(reviewId, attemptNo, roleType,
                        checkpointKey == null ? "placeholder" : checkpointKey),
                Instant.now());
    }
}
