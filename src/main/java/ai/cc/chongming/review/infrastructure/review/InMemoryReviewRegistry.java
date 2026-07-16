package ai.cc.chongming.review.infrastructure.review;

import ai.cc.chongming.review.domain.model.Review;
import ai.cc.chongming.review.domain.model.ReviewTypes.ReviewId;
import ai.cc.chongming.review.domain.repository.ReviewRegistry;
import org.springframework.stereotype.Repository;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * [AIREVIEW-PLAN-011#1.2] Process-local aggregate registry for local/demo command APIs.
 *
 * @author wangli
 */
@Repository
public class InMemoryReviewRegistry implements ReviewRegistry {

    private final Map<ReviewId, Review> reviews = new ConcurrentHashMap<>();

    @Override
    public void register(Review review) {
        reviews.putIfAbsent(review.id(), review);
    }

    @Override
    public Optional<Review> find(ReviewId reviewId) {
        return Optional.ofNullable(reviews.get(reviewId));
    }
}
