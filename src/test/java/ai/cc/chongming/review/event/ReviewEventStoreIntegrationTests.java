package ai.cc.chongming.review.event;

import ai.cc.chongming.review.application.ReviewEventService;
import ai.cc.chongming.review.domain.event.ReviewEvent;
import ai.cc.chongming.review.domain.event.ReviewEventDraft;
import ai.cc.chongming.review.domain.event.ReviewEventType;
import ai.cc.chongming.review.domain.model.ReviewTypes.ReviewId;
import ai.cc.chongming.review.domain.model.ReviewTypes.ReviewStage;
import ai.cc.chongming.review.infrastructure.event.InMemoryReviewEventStore;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.time.Instant;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * [AIREVIEW-PLAN-010#1.2][AIREVIEW-PLAN-010#1.8] 领域事件顺序与回放测试。
 *
 * @author wangli
 */
class ReviewEventStoreIntegrationTests {

    @Test
    void assignsReviewGlobalMonotonicSequencesAcrossAttempts() {
        InMemoryReviewEventStore store = new InMemoryReviewEventStore();
        ReviewId reviewId = new ReviewId(UUID.randomUUID());

        ReviewEvent first = store.append(draft(reviewId, 1, "first"));
        ReviewEvent second = store.append(draft(reviewId, 2, "second"));

        assertEquals(1L, first.sequence());
        assertEquals(2L, second.sequence());
        assertEquals(List.of(second), store.findAfter(reviewId, 1L, 10));
    }

    @Test
    void serializesConcurrentAppendsForTheSameReview() throws Exception {
        InMemoryReviewEventStore store = new InMemoryReviewEventStore();
        ReviewId reviewId = new ReviewId(UUID.randomUUID());
        ExecutorService executor = Executors.newFixedThreadPool(8);
        try {
            List<Callable<ReviewEvent>> tasks = new ArrayList<>();
            for (int index = 0; index < 100; index++) {
                int current = index;
                tasks.add(() -> store.append(draft(reviewId, 1, "event-" + current)));
            }

            List<Future<ReviewEvent>> futures = executor.invokeAll(tasks);
            List<Long> sequences = futures.stream()
                    .map(this::get)
                    .map(ReviewEvent::sequence)
                    .sorted()
                    .toList();

            assertEquals(100, sequences.size());
            for (int index = 0; index < sequences.size(); index++) {
                assertEquals(index + 1L, sequences.get(index));
            }
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void notifiesListenersOnlyAfterTheEventHasBeenCommitted() {
        InMemoryReviewEventStore store = new InMemoryReviewEventStore();
        List<ReviewEvent> observed = new ArrayList<>();
        ReviewEventService service = new ReviewEventService(store, List.of(observed::add));
        ReviewId reviewId = new ReviewId(UUID.randomUUID());

        service.publish(draft(reviewId, 1, "accepted"));

        assertEquals(1, observed.size());
        assertEquals(1L, observed.getFirst().sequence());
        assertTrue(service.replay(reviewId, 0L, 10).contains(observed.getFirst()));
    }

    @Test
    void findsLatestFactOfATypeWithoutReplayingTheTimeline() {
        InMemoryReviewEventStore store = new InMemoryReviewEventStore();
        ReviewId reviewId = new ReviewId(UUID.randomUUID());
        store.append(draft(reviewId, 1, ReviewEventType.PLAN_CREATED, "plan"));
        ReviewEvent degraded = store.append(draft(
                reviewId, 1, ReviewEventType.CONTEXT_SCOUT_DEGRADED, "first-degradation"));
        ReviewEvent latestDegraded = store.append(draft(
                reviewId, 1, ReviewEventType.CONTEXT_SCOUT_DEGRADED, "latest-degradation"));
        store.append(draft(reviewId, 1, ReviewEventType.PLAN_CREATED, "later-plan"));

        assertEquals(latestDegraded, store.findLatestByType(
                reviewId, ReviewEventType.CONTEXT_SCOUT_DEGRADED).orElseThrow());
        assertEquals(latestDegraded, store.findLatestByTypeAndAttempt(
                reviewId, ReviewEventType.CONTEXT_SCOUT_DEGRADED, 1).orElseThrow());
        assertTrue(store.findLatestByType(reviewId, ReviewEventType.ROLE_COMPLETED).isEmpty());
        assertTrue(degraded.sequence() < latestDegraded.sequence());
    }

    @Test
    void isolatesLatestFactOfATypeByAttempt() {
        InMemoryReviewEventStore store = new InMemoryReviewEventStore();
        ReviewId reviewId = new ReviewId(UUID.randomUUID());
        ReviewEvent firstAttempt = store.append(draft(
                reviewId, 1, ReviewEventType.CONTEXT_SCOUT_DEGRADED, "first-attempt"));
        ReviewEvent currentAttempt = store.append(draft(
                reviewId, 2, ReviewEventType.CONTEXT_SCOUT_DEGRADED, "current-attempt"));

        assertEquals(firstAttempt, store.findLatestByTypeAndAttempt(
                reviewId, ReviewEventType.CONTEXT_SCOUT_DEGRADED, 1).orElseThrow());
        assertEquals(currentAttempt, store.findLatestByTypeAndAttempt(
                reviewId, ReviewEventType.CONTEXT_SCOUT_DEGRADED, 2).orElseThrow());
    }

    @Test
    void projectsRecentAndLatestFactsAcrossIndependentReviews() {
        InMemoryReviewEventStore store = new InMemoryReviewEventStore();
        ReviewId firstReview = new ReviewId(UUID.randomUUID());
        ReviewId secondReview = new ReviewId(UUID.randomUUID());
        Instant baseline = Instant.parse("2026-08-01T08:00:00Z");
        ReviewEvent firstInitial = store.append(draft(firstReview, 1, "first-initial", baseline));
        ReviewEvent secondLatest = store.append(draft(secondReview, 1, "second-latest", baseline.plusSeconds(1)));
        ReviewEvent firstLatest = store.append(draft(firstReview, 1, "first-latest", baseline.plusSeconds(2)));

        assertTrue(store.findLatestAcrossReviews(10).containsAll(List.of(firstLatest, secondLatest)));
        assertTrue(store.findRecentAcrossReviews(2).containsAll(List.of(firstLatest, secondLatest)));
        assertTrue(store.findRecentAcrossReviews(10).contains(firstInitial));
    }

    private ReviewEvent get(Future<ReviewEvent> future) {
        try {
            return future.get();
        } catch (Exception exception) {
            throw new AssertionError("event append should complete", exception);
        }
    }

    private ReviewEventDraft draft(ReviewId reviewId, int attemptNo, String value) {
        return draft(reviewId, attemptNo, ReviewEventType.PLAN_CREATED, value, null);
    }

    private ReviewEventDraft draft(ReviewId reviewId, int attemptNo, ReviewEventType type, String value) {
        return draft(reviewId, attemptNo, type, value, null);
    }

    private ReviewEventDraft draft(ReviewId reviewId, int attemptNo, String value, Instant occurredAt) {
        return draft(reviewId, attemptNo, ReviewEventType.PLAN_CREATED, value, occurredAt);
    }

    private ReviewEventDraft draft(
            ReviewId reviewId, int attemptNo, ReviewEventType type, String value, Instant occurredAt) {
        return new ReviewEventDraft(
                reviewId,
                attemptNo,
                type,
                ReviewStage.PLANNING,
                null,
                null,
                null,
                null,
                null,
                1,
                20,
                occurredAt,
                1,
                Map.of("value", value));
    }
}
