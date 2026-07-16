package ai.cc.chongming.review.sse;

import ai.cc.chongming.review.application.ReviewEventService;
import ai.cc.chongming.review.application.ReviewSseProperties;
import ai.cc.chongming.review.application.ReviewSseRegistry;
import ai.cc.chongming.review.domain.event.ReviewEventDraft;
import ai.cc.chongming.review.domain.event.ReviewEventType;
import ai.cc.chongming.review.domain.model.ReviewTypes.ReviewId;
import ai.cc.chongming.review.domain.model.ReviewTypes.ReviewStage;
import ai.cc.chongming.review.infrastructure.event.InMemoryReviewEventStore;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * [AIREVIEW-PLAN-010#1.4][AIREVIEW-PLAN-010#1.5] Covers the history-to-live SSE hand-off.
 *
 * @author wangli
 */
class ReviewSseReplayIntegrationTests {

    @Test
    void replaysHistoryAndBufferedEventExactlyOnceBeforeLiveDelivery() {
        InMemoryReviewEventStore store = new InMemoryReviewEventStore();
        ReviewSseRegistry registry = new ReviewSseRegistry(new ReviewSseProperties(
                Duration.ofMinutes(1), Duration.ofSeconds(5), 100));
        ReviewEventService events = new ReviewEventService(store, List.of(registry));
        ReviewId reviewId = new ReviewId(UUID.randomUUID());

        events.publish(draft(reviewId, "history"));
        ReviewSseRegistry.Subscription subscription = registry.subscribe(reviewId);
        events.publish(draft(reviewId, "buffered"));

        registry.replay(subscription, events.replay(reviewId, 0L, 100));
        registry.activate(subscription);
        events.publish(draft(reviewId, "live"));

        ReviewSseRegistry.SseMetrics metrics = registry.metrics();
        assertEquals(1L, metrics.activeEmitters());
        assertEquals(3L, metrics.deliveredEvents());
        assertEquals(0L, metrics.failedDeliveries());
    }

    @Test
    void heartbeatDoesNotCreateOrConsumeABusinessEventSequence() {
        InMemoryReviewEventStore store = new InMemoryReviewEventStore();
        ReviewSseRegistry registry = new ReviewSseRegistry(new ReviewSseProperties(
                Duration.ofMinutes(1), Duration.ofSeconds(5), 100));
        ReviewEventService events = new ReviewEventService(store, List.of(registry));
        ReviewId reviewId = new ReviewId(UUID.randomUUID());
        ReviewSseRegistry.Subscription subscription = registry.subscribe(reviewId);
        registry.activate(subscription);

        registry.heartbeat();
        events.publish(draft(reviewId, "first"));

        assertEquals(1L, events.replay(reviewId, 0L, 10).getFirst().sequence());
        assertEquals(1L, registry.metrics().deliveredEvents());
    }

    private ReviewEventDraft draft(ReviewId reviewId, String value) {
        return new ReviewEventDraft(
                reviewId,
                1,
                ReviewEventType.PLAN_CREATED,
                ReviewStage.PLANNING,
                null,
                null,
                null,
                null,
                null,
                null,
                20,
                null,
                1,
                Map.of("value", value));
    }
}
