package ai.cc.chongming.review.lifecycle;

import ai.cc.chongming.review.application.ReviewEventService;
import ai.cc.chongming.review.application.ReviewLifecycleService;
import ai.cc.chongming.review.domain.event.ReviewEventType;
import ai.cc.chongming.review.domain.exception.ReviewDomainException;
import ai.cc.chongming.review.domain.model.GateDecision;
import ai.cc.chongming.review.domain.model.Review;
import ai.cc.chongming.review.domain.model.ReviewTypes.DecisionActor;
import ai.cc.chongming.review.domain.model.ReviewTypes.DecisionStatus;
import ai.cc.chongming.review.domain.model.ReviewTypes.GateResult;
import ai.cc.chongming.review.domain.model.ReviewTypes.ReviewId;
import ai.cc.chongming.review.domain.model.ReviewTypes.ReviewStage;
import ai.cc.chongming.review.domain.model.ReviewTypes.RoleActivation;
import ai.cc.chongming.review.domain.model.ReviewTypes.RoleType;
import ai.cc.chongming.review.domain.protocol.ReviewStateMachine;
import ai.cc.chongming.review.infrastructure.debate.InMemoryReviewDebateStore;
import ai.cc.chongming.review.infrastructure.event.InMemoryReviewEventStore;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * [AIREVIEW-PLAN-010#1.6][AIREVIEW-PLAN-010#1.7] Lifecycle idempotency and attempt-isolation tests.
 *
 * @author wangli
 */
class ReviewLifecycleIntegrationTests {

    @Test
    void cancellationIsIdempotentAndPreservesOneAppendOnlyEvent() {
        Fixture fixture = new Fixture();
        Review review = Review.pending(fixture.reviewId);

        ReviewLifecycleService.CancelResult first = fixture.lifecycle.cancel(review, 0L);
        ReviewLifecycleService.CancelResult replay = fixture.lifecycle.cancel(review, 0L);

        assertFalse(first.replayed());
        assertTrue(replay.replayed());
        assertEquals(ReviewStage.CANCELLED, review.stage());
        assertEquals(List.of(ReviewEventType.REVIEW_CANCELLED), fixture.events.replay(fixture.reviewId, 0L, 10).stream()
                .map(event -> event.type())
                .toList());
    }

    @Test
    void retryCreatesFreshAttemptAndKeepsReviewGlobalEventSequence() {
        Fixture fixture = new Fixture();
        Review review = Review.pending(fixture.reviewId);
        review.activateRole(new RoleActivation(RoleType.PRODUCT, "product-reviewer", false));
        long cancelVersion = review.version();
        fixture.lifecycle.cancel(review, cancelVersion);
        long retryVersion = review.version();

        ReviewLifecycleService.RetryResult first = fixture.lifecycle.retry(review, retryVersion);
        ReviewLifecycleService.RetryResult replay = fixture.lifecycle.retry(review, retryVersion);

        assertFalse(first.replayed());
        assertTrue(replay.replayed());
        assertEquals(2, review.attemptNo());
        assertEquals(ReviewStage.PENDING, review.stage());
        assertTrue(review.roleActivations().isEmpty());
        assertTrue(review.commandResults().isEmpty());
        assertEquals(List.of(1L, 2L), fixture.events.replay(fixture.reviewId, 0L, 10).stream()
                .map(event -> event.sequence())
                .toList());
        assertEquals(2, fixture.events.replay(fixture.reviewId, 0L, 10).getLast().attemptNo());
    }

    @Test
    void finalHumanDecisionPreventsCancellation() {
        Fixture fixture = new Fixture();
        Review review = Review.pending(fixture.reviewId);
        fixture.debateStore.saveGateDraft(new GateDecision(
                fixture.reviewId,
                GateResult.PASS,
                DecisionStatus.FINAL,
                DecisionActor.HUMAN,
                "Human decision completed",
                Instant.now()));

        assertThrows(ReviewDomainException.class, () -> fixture.lifecycle.cancel(review, 0L));
        assertEquals(ReviewStage.PENDING, review.stage());
        assertTrue(fixture.events.replay(fixture.reviewId, 0L, 10).isEmpty());
    }

    private static final class Fixture {
        private final ReviewId reviewId = new ReviewId(UUID.randomUUID());
        private final InMemoryReviewDebateStore debateStore = new InMemoryReviewDebateStore();
        private final ReviewEventService events = new ReviewEventService(new InMemoryReviewEventStore());
        private final ReviewLifecycleService lifecycle = new ReviewLifecycleService(
                debateStore,
                new ReviewStateMachine(),
                events);
    }
}
