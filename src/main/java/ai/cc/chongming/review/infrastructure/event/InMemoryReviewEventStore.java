package ai.cc.chongming.review.infrastructure.event;

import ai.cc.chongming.review.domain.event.ReviewEvent;
import ai.cc.chongming.review.domain.event.ReviewEventDraft;
import ai.cc.chongming.review.domain.event.ReviewEventType;
import ai.cc.chongming.review.domain.repository.ReviewEventStore;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;

import static ai.cc.chongming.review.domain.model.ReviewTypes.ReviewId;

/**
 * [AIREVIEW-PLAN-010#1.2] Process-local event store that preserves per-review sequence atomicity for fake persistence.
 *
 * @author wangli
 */
@Repository
@ConditionalOnProperty(prefix = "review.persistence", name = "enabled", havingValue = "false", matchIfMissing = true)
public class InMemoryReviewEventStore implements ReviewEventStore {

    private final Map<ReviewId, List<ReviewEvent>> eventsByReview = new ConcurrentHashMap<>();

    @Override
    public ReviewEvent append(ReviewEventDraft draft) {
        List<ReviewEvent> events = eventsByReview.computeIfAbsent(
                draft.reviewId(), ignored -> new ArrayList<>());
        synchronized (events) {
            ReviewEvent event = ReviewEvent.committed(events.size() + 1L, draft);
            events.add(event);
            return event;
        }
    }

    @Override
    public List<ReviewEvent> findAfter(ReviewId reviewId, long afterSequence, int limit) {
        if (afterSequence < 0 || limit < 1 || limit > 10_000) {
            throw new IllegalArgumentException("event replay window is outside the allowed bounds");
        }
        List<ReviewEvent> events = eventsByReview.get(reviewId);
        if (events == null) {
            return List.of();
        }
        synchronized (events) {
            return events.stream()
                    .filter(event -> event.sequence() > afterSequence)
                    .sorted(Comparator.comparingLong(ReviewEvent::sequence))
                    .limit(limit)
                    .toList();
        }
    }

    @Override
    public Optional<ReviewEvent> findLatest(ReviewId reviewId) {
        List<ReviewEvent> events = eventsByReview.get(reviewId);
        if (events == null) {
            return Optional.empty();
        }
        synchronized (events) {
            return events.isEmpty() ? Optional.empty() : Optional.of(events.getLast());
        }
    }

    @Override
    public Optional<ReviewEvent> findLatestByType(ReviewId reviewId, ReviewEventType eventType) {
        Objects.requireNonNull(reviewId, "reviewId must not be null");
        Objects.requireNonNull(eventType, "eventType must not be null");
        List<ReviewEvent> events = eventsByReview.get(reviewId);
        if (events == null) {
            return Optional.empty();
        }
        synchronized (events) {
            for (int index = events.size() - 1; index >= 0; index--) {
                ReviewEvent event = events.get(index);
                if (event.type() == eventType) {
                    return Optional.of(event);
                }
            }
            return Optional.empty();
        }
    }

    @Override
    public Optional<ReviewEvent> findLatestByTypeAndAttempt(
            ReviewId reviewId, ReviewEventType eventType, int attemptNo) {
        Objects.requireNonNull(reviewId, "reviewId must not be null");
        Objects.requireNonNull(eventType, "eventType must not be null");
        if (attemptNo < 1) {
            throw new IllegalArgumentException("attemptNo must be positive");
        }
        List<ReviewEvent> events = eventsByReview.get(reviewId);
        if (events == null) {
            return Optional.empty();
        }
        synchronized (events) {
            for (int index = events.size() - 1; index >= 0; index--) {
                ReviewEvent event = events.get(index);
                if (event.type() == eventType && event.attemptNo() == attemptNo) {
                    return Optional.of(event);
                }
            }
            return Optional.empty();
        }
    }
}
