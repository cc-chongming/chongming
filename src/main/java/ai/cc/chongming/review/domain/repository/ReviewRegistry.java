package ai.cc.chongming.review.domain.repository;

import ai.cc.chongming.review.domain.model.Review;
import ai.cc.chongming.review.domain.model.ReviewTypes.ReviewId;

import java.util.Optional;

/**
 * [AIREVIEW-PLAN-011#1.2] Minimal aggregate lookup boundary for review-scoped HTTP commands.
 *
 * @author wangli
 */
public interface ReviewRegistry {

    void register(Review review);

    Optional<Review> find(ReviewId reviewId);

    static ReviewRegistry noop() {
        return new ReviewRegistry() {
            @Override
            public void register(Review review) {
            }

            @Override
            public Optional<Review> find(ReviewId reviewId) {
                return Optional.empty();
            }
        };
    }
}
