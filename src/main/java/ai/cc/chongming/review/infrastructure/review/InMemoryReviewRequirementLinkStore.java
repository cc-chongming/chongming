package ai.cc.chongming.review.infrastructure.review;

import ai.cc.chongming.review.domain.model.RequirementTypes.RequirementId;
import ai.cc.chongming.review.domain.model.Review;
import ai.cc.chongming.review.domain.model.ReviewTypes.ReviewId;
import ai.cc.chongming.review.domain.model.ReviewTypes.ReviewStage;
import ai.cc.chongming.review.domain.repository.ReviewRegistry;
import ai.cc.chongming.review.domain.repository.ReviewRequirementLinkStore;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;

/**
 * [AIREVIEW-PLAN-021#2] In-memory mirror of the optional review-to-requirement link.
 *
 * @author zyj
 */
@Repository
@ConditionalOnProperty(prefix = "review.persistence", name = "enabled", havingValue = "false", matchIfMissing = true)
public class InMemoryReviewRequirementLinkStore implements ReviewRequirementLinkStore {

    private final Map<ReviewId, RequirementId> requirementByReview = new ConcurrentHashMap<>();
    private final ReviewRegistry reviewRegistry;

    public InMemoryReviewRequirementLinkStore(ReviewRegistry reviewRegistry) {
        this.reviewRegistry = reviewRegistry;
    }

    @Override
    public boolean tryBindPendingReview(ReviewId reviewId, RequirementId requirementId) {
        Review review = reviewRegistry.find(reviewId).orElse(null);
        if (review == null) {
            return false;
        }
        synchronized (review) {
            if (review.stage() != ReviewStage.PENDING) {
                return false;
            }
            synchronized (requirementByReview) {
                RequirementId existing = requirementByReview.get(reviewId);
                if (existing != null && !existing.equals(requirementId)) {
                    return false;
                }
                requirementByReview.putIfAbsent(reviewId, requirementId);
                return true;
            }
        }
    }
}
