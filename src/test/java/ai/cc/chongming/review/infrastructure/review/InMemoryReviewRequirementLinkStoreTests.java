package ai.cc.chongming.review.infrastructure.review;

import ai.cc.chongming.review.domain.model.RequirementTypes.RequirementId;
import ai.cc.chongming.review.domain.model.Review;
import ai.cc.chongming.review.domain.model.ReviewTypes.ReviewId;
import ai.cc.chongming.review.domain.protocol.ReviewStateMachine;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [AIREVIEW-PLAN-021#2][REQLIFE-H1] Verifies that the in-memory reverse link cannot be overwritten.
 *
 * @author zyj
 */
class InMemoryReviewRequirementLinkStoreTests {

    @Test
    void reservesOnlyOnePendingReviewRequirementPair() {
        InMemoryReviewRegistry registry = new InMemoryReviewRegistry();
        InMemoryReviewRequirementLinkStore store = new InMemoryReviewRequirementLinkStore(registry);
        ReviewId reviewId = new ReviewId(UUID.randomUUID());
        RequirementId firstRequirement = new RequirementId(UUID.randomUUID());
        RequirementId secondRequirement = new RequirementId(UUID.randomUUID());
        registry.register(Review.pending(reviewId));

        assertThat(store.tryBindPendingReview(reviewId, firstRequirement)).isTrue();
        assertThat(store.tryBindPendingReview(reviewId, firstRequirement)).isTrue();
        assertThat(store.tryBindPendingReview(reviewId, secondRequirement)).isFalse();
        assertThat(store.tryBindPendingReview(new ReviewId(UUID.randomUUID()), secondRequirement)).isFalse();
    }

    @Test
    void rejectsBindingAfterTheStartMonitorHasMovedTheReviewOutOfPending() {
        InMemoryReviewRegistry registry = new InMemoryReviewRegistry();
        InMemoryReviewRequirementLinkStore store = new InMemoryReviewRequirementLinkStore(registry);
        ReviewId reviewId = new ReviewId(UUID.randomUUID());
        Review review = Review.pending(reviewId);
        registry.register(review);
        synchronized (review) {
            ReviewStateMachine stateMachine = new ReviewStateMachine();
            review.transitionTo(stateMachine, ai.cc.chongming.review.domain.model.ReviewTypes.ReviewStage.SNAPSHOTTING);
            review.transitionTo(stateMachine, ai.cc.chongming.review.domain.model.ReviewTypes.ReviewStage.PLANNING);
        }

        assertThat(store.tryBindPendingReview(reviewId, new RequirementId(UUID.randomUUID()))).isFalse();
    }

    @Test
    void releasesTheReviewBindingWhenTheRequirementIsDeleted() {
        InMemoryReviewRegistry registry = new InMemoryReviewRegistry();
        InMemoryReviewRequirementLinkStore store = new InMemoryReviewRequirementLinkStore(registry);
        ReviewId reviewId = new ReviewId(UUID.randomUUID());
        RequirementId firstRequirement = new RequirementId(UUID.randomUUID());
        RequirementId replacementRequirement = new RequirementId(UUID.randomUUID());
        registry.register(Review.pending(reviewId));

        assertThat(store.tryBindPendingReview(reviewId, firstRequirement)).isTrue();

        store.unlinkRequirement(firstRequirement);

        assertThat(store.tryBindPendingReview(reviewId, replacementRequirement)).isTrue();
    }
}
