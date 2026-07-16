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

    private ReviewEvent get(Future<ReviewEvent> future) {
        try {
            return future.get();
        } catch (Exception exception) {
            throw new AssertionError("event append should complete", exception);
        }
    }

    private ReviewEventDraft draft(ReviewId reviewId, int attemptNo, String value) {
        return new ReviewEventDraft(
                reviewId,
                attemptNo,
                ReviewEventType.PLAN_CREATED,
                ReviewStage.PLANNING,
                null,
                null,
                null,
                null,
                null,
                1,
                20,
                null,
                1,
                Map.of("value", value));
    }
}
