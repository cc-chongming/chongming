package ai.cc.chongming.review.domain.repository;

import ai.cc.chongming.review.domain.model.RequirementTypes.RequirementId;
import ai.cc.chongming.review.domain.model.ReviewTypes.ReviewId;

/**
 * [AIREVIEW-PLAN-021#2][REQLIFE-H1] Atomically reserves a pending review for one requirement without making
 * Requirement depend on persistence details.
 *
 * @author zyj
 */
public interface ReviewRequirementLinkStore {

    /**
     * Binds the review only when it is still pending and is unbound or already bound to the same requirement.
     *
     * @return {@code true} when the caller owns the binding; {@code false} for a missing, non-pending, or
     *         differently-bound review
     */
    boolean tryBindPendingReview(ReviewId reviewId, RequirementId requirementId);

    /**
     * Removes every reverse review link for a requirement that has been deleted.  The review itself
     * and its immutable history remain available without a dangling requirement reference.
     *
     * @param requirementId deleted requirement identifier
     */
    default void unlinkRequirement(RequirementId requirementId) {
        // Existing adapters remain source-compatible until they need to persist reverse links.
    }
}
