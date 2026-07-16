package ai.cc.chongming.review.application;

import ai.cc.chongming.review.domain.event.ReviewEvent;
import ai.cc.chongming.review.domain.event.ReviewEventDraft;
import ai.cc.chongming.review.domain.repository.ReviewEventStore;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import static ai.cc.chongming.review.domain.model.ReviewTypes.ReviewId;

/**
 * [AIREVIEW-PLAN-010#1.2,#1.4] Appends authoritative facts before notifying transient SSE listeners.
 *
 * @author wangli
 */
@Service
public class ReviewEventService implements ReviewEventPublisher {

    private final ReviewEventStore eventStore;
    private final List<ReviewEventListener> listeners;

    public ReviewEventService(ReviewEventStore eventStore) {
        this(eventStore, List.of());
    }

    @Autowired
    public ReviewEventService(ReviewEventStore eventStore, List<ReviewEventListener> listeners) {
        this.eventStore = Objects.requireNonNull(eventStore, "eventStore must not be null");
        this.listeners = List.copyOf(listeners);
    }

    @Override
    public void publish(ReviewEventDraft draft) {
        ReviewEvent event = eventStore.append(Objects.requireNonNull(draft, "draft must not be null"));
        listeners.forEach(listener -> listener.onCommitted(event));
    }

    public List<ReviewEvent> replay(ReviewId reviewId, long afterSequence, int limit) {
        return eventStore.findAfter(Objects.requireNonNull(reviewId, "reviewId must not be null"), afterSequence, limit);
    }

    public Optional<ReviewEvent> latest(ReviewId reviewId) {
        return eventStore.findLatest(Objects.requireNonNull(reviewId, "reviewId must not be null"));
    }
}
