package ai.cc.chongming.review.application;

import ai.cc.chongming.review.domain.event.ReviewEvent;
import ai.cc.chongming.review.domain.model.ReviewTypes.ReviewId;
import org.springframework.http.MediaType;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

/**
 * [AIREVIEW-PLAN-010#1.4] Coordinates gap-free history replay and live SSE delivery.
 *
 * @author wangli
 */
@Component
public class ReviewSseRegistry implements ReviewEventListener {

    private final ReviewSseProperties properties;
    private final Map<ReviewId, Map<UUID, Subscription>> subscriptionsByReview = new ConcurrentHashMap<>();
    private final AtomicLong deliveredEvents = new AtomicLong();
    private final AtomicLong failedDeliveries = new AtomicLong();

    public ReviewSseRegistry(ReviewSseProperties properties) {
        this.properties = properties;
    }

    /**
     * Registers an emitter in buffering mode before its history is queried.
     *
     * @param reviewId review identity
     * @return the buffering subscription
     */
    public Subscription subscribe(ReviewId reviewId) {
        SseEmitter emitter = new SseEmitter(properties.timeout().toMillis());
        Subscription subscription = new Subscription(UUID.randomUUID(), reviewId, emitter);
        subscriptionsByReview.computeIfAbsent(reviewId, ignored -> new ConcurrentHashMap<>())
                .put(subscription.id(), subscription);
        emitter.onCompletion(() -> remove(subscription));
        emitter.onTimeout(() -> {
            remove(subscription);
            emitter.complete();
        });
        emitter.onError(ignored -> remove(subscription));
        return subscription;
    }

    /**
     * Sends an already queried history page while the subscription still buffers live events.
     *
     * @param subscription SSE subscription
     * @param events history events ordered by sequence
     */
    public void replay(Subscription subscription, Collection<ReviewEvent> events) {
        synchronized (subscription) {
            events.stream()
                    .sorted(Comparator.comparingLong(ReviewEvent::sequence))
                    .forEach(event -> deliver(subscription, event));
        }
    }

    /**
     * Atomically drains events received during replay and enables direct live delivery.
     *
     * @param subscription SSE subscription
     */
    public void activate(Subscription subscription) {
        synchronized (subscription) {
            subscription.bufferedEvents().stream()
                    .sorted(Comparator.comparingLong(ReviewEvent::sequence))
                    .forEach(event -> deliver(subscription, event));
            subscription.bufferedEvents().clear();
            subscription.setBuffering(false);
        }
    }

    @Override
    public void onCommitted(ReviewEvent event) {
        Map<UUID, Subscription> subscriptions = subscriptionsByReview.get(event.reviewId());
        if (subscriptions == null) {
            return;
        }
        for (Subscription subscription : subscriptions.values()) {
            synchronized (subscription) {
                if (subscription.buffering()) {
                    subscription.bufferedEvents().add(event);
                } else {
                    deliver(subscription, event);
                }
            }
        }
    }

    /**
     * Sends a transport-only heartbeat; it never consumes a business event sequence.
     */
    @Scheduled(fixedDelayString = "${review.sse.heartbeat-interval:PT15S}")
    public void heartbeat() {
        for (Map<UUID, Subscription> subscriptions : subscriptionsByReview.values()) {
            for (Subscription subscription : subscriptions.values()) {
                sendHeartbeat(subscription);
            }
        }
    }

    public SseMetrics metrics() {
        long active = subscriptionsByReview.values().stream().mapToLong(Map::size).sum();
        return new SseMetrics(active, deliveredEvents.get(), failedDeliveries.get());
    }

    private void deliver(Subscription subscription, ReviewEvent event) {
        if (event.sequence() <= subscription.lastDeliveredSequence()) {
            return;
        }
        try {
            subscription.emitter().send(SseEmitter.event()
                    .id(Long.toString(event.sequence()))
                    .name(event.type().name())
                    .data(toSseView(event), MediaType.APPLICATION_JSON));
            subscription.setLastDeliveredSequence(event.sequence());
            deliveredEvents.incrementAndGet();
        } catch (IOException exception) {
            failedDeliveries.incrementAndGet();
            remove(subscription);
            subscription.emitter().completeWithError(exception);
        }
    }

    /**
     * Flattens strong-typed identity records into plain JSON values. Browsers compare reviewId
     * against the path string and would silently drop every event serialized as an object wrapper.
     */
    private SseEventView toSseView(ReviewEvent event) {
        return new SseEventView(
                event.eventId(),
                event.sequence(),
                event.reviewId().value(),
                event.attemptNo(),
                event.type().name(),
                event.category().name(),
                event.stage().name(),
                event.actorRole() == null ? null : event.actorRole().name(),
                event.targetRole() == null ? null : event.targetRole().name(),
                event.topicId() == null ? null : event.topicId().value(),
                event.claimId() == null ? null : event.claimId().value(),
                event.turnId() == null ? null : event.turnId().value(),
                event.round(),
                event.progress(),
                event.occurredAt().toString(),
                event.payloadVersion(),
                event.payload());
    }

    /** @author wangli */
    public record SseEventView(
            UUID eventId,
            long sequence,
            UUID reviewId,
            int attemptNo,
            String type,
            String category,
            String stage,
            String actorRole,
            String targetRole,
            UUID topicId,
            UUID claimId,
            UUID turnId,
            Integer round,
            Integer progress,
            String occurredAt,
            int payloadVersion,
            Map<String, String> payload) {
    }

    private void sendHeartbeat(Subscription subscription) {
        synchronized (subscription) {
            try {
                subscription.emitter().send(SseEmitter.event().comment("heartbeat"));
            } catch (IOException exception) {
                failedDeliveries.incrementAndGet();
                remove(subscription);
                subscription.emitter().completeWithError(exception);
            }
        }
    }

    private void remove(Subscription subscription) {
        Map<UUID, Subscription> subscriptions = subscriptionsByReview.get(subscription.reviewId());
        if (subscriptions == null) {
            return;
        }
        subscriptions.remove(subscription.id());
        if (subscriptions.isEmpty()) {
            subscriptionsByReview.remove(subscription.reviewId(), subscriptions);
        }
    }

    /**
     * A subscription starts buffering to close the race between registration and history replay.
     *
     * @author wangli
     */
    public static final class Subscription {
        private final UUID id;
        private final ReviewId reviewId;
        private final SseEmitter emitter;
        private final List<ReviewEvent> bufferedEvents = new CopyOnWriteArrayList<>();
        private long lastDeliveredSequence;
        private boolean buffering = true;

        private Subscription(UUID id, ReviewId reviewId, SseEmitter emitter) {
            this.id = id;
            this.reviewId = reviewId;
            this.emitter = emitter;
        }

        public UUID id() {
            return id;
        }

        public ReviewId reviewId() {
            return reviewId;
        }

        public SseEmitter emitter() {
            return emitter;
        }

        private List<ReviewEvent> bufferedEvents() {
            return bufferedEvents;
        }

        private long lastDeliveredSequence() {
            return lastDeliveredSequence;
        }

        private void setLastDeliveredSequence(long sequence) {
            this.lastDeliveredSequence = sequence;
        }

        private boolean buffering() {
            return buffering;
        }

        private void setBuffering(boolean buffering) {
            this.buffering = buffering;
        }
    }

    /**
     * [AIREVIEW-PLAN-010#1.4] Lightweight observability counters until deployment metrics are wired.
     *
     * @author wangli
     */
    public record SseMetrics(long activeEmitters, long deliveredEvents, long failedDeliveries) {
    }
}
