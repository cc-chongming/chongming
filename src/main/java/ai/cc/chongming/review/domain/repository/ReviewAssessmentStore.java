package ai.cc.chongming.review.domain.repository;

import ai.cc.chongming.review.domain.model.ReviewAssessment;
import ai.cc.chongming.review.domain.model.ReviewTypes.ReviewId;
import ai.cc.chongming.review.domain.model.ReviewTypes.RoleType;

import java.util.Collection;
import java.util.List;

/**
 * [AIREVIEW-PLAN-024#方案0] Persistence boundary for structured checkpoint assessments.
 * Only batch operations are exposed: one role submits all of its checkpoint conclusions together,
 * and reads always return complete batches. Single-row save/find is intentionally not supported.
 *
 * <p>Idempotency: repeated submissions for the same review, attempt, role and checkpointKey are
 * idempotent and the latest submission replaces the previous one.
 *
 * @author wangli
 */
public interface ReviewAssessmentStore {

    /**
     * Persists one batch of assessments for a review attempt. Every assessment must belong to the
     * given review and attempt, and a batch must not contain duplicate (role, checkpointKey) pairs.
     *
     * @param reviewId review the assessments belong to
     * @param attemptNo attempt number, positive
     * @param assessments non-empty batch of assessments
     */
    void saveBatch(ReviewId reviewId, int attemptNo, Collection<ReviewAssessment> assessments);

    /**
     * Returns all current assessments of one review attempt, ordered deterministically.
     */
    List<ReviewAssessment> findByReview(ReviewId reviewId, int attemptNo);

    /**
     * Returns the current assessments of one role inside one review attempt.
     */
    List<ReviewAssessment> findByReview(ReviewId reviewId, int attemptNo, RoleType roleType);
}
