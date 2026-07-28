package ai.cc.chongming.review.application;

import ai.cc.chongming.review.domain.model.ReviewTypes.ReviewId;
import io.agentscope.core.agui.event.AguiEvent;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * [AIREVIEW-PLAN-017#4.2] Bounded in-memory AG-UI runtime trace per review attempt.
 *
 * @author wangli
 */
@Component
public class ReviewRuntimeTraceRegistry {

    private static final int MAX_EVENTS_PER_RUNTIME = 500;
    private final Map<String, RuntimeTrace> traces = new ConcurrentHashMap<>();
    private final ExecutorService emitterExecutor = Executors.newVirtualThreadPerTaskExecutor();

    public void publish(String runtimeId, AguiEvent event) {
        traces.computeIfAbsent(runtimeId, RuntimeTrace::new).publish(event);
    }

    /**
     * Creates a buffering subscription and seeds it with events strictly after the supplied SSE cursor.
     */
    public Subscription subscribe(ReviewId reviewId, int attemptNo, long afterSequence) {
        if (afterSequence < 0) {
            throw new IllegalArgumentException("afterSequence must not be negative");
        }
        String runtimeId = ReviewRuntimeContext.runtimeIdFor(reviewId, attemptNo);
        return traces.computeIfAbsent(runtimeId, RuntimeTrace::new).subscribe(afterSequence);
    }

    /** Activates delivery only after the HTTP layer has completed registration. */
    public void activate(Subscription subscription) {
        subscription.trace().activate(subscription);
    }

    @PreDestroy
    void close() {
        emitterExecutor.close();
    }

    public static final class Subscription {
        private final UUID id;
        private final SseEmitter emitter;
        private final RuntimeTrace trace;
        private final Deque<StampedEvent> pending = new ArrayDeque<>();
        private boolean buffering = true;
        private boolean draining;
        private long lastDeliveredSequence;

        private Subscription(UUID id, SseEmitter emitter, RuntimeTrace trace, List<StampedEvent> history) {
            this.id = id;
            this.emitter = emitter;
            this.trace = trace;
            this.pending.addAll(history);
        }

        public SseEmitter emitter() {
            return emitter;
        }

        private UUID id() {
            return id;
        }

        private RuntimeTrace trace() {
            return trace;
        }
    }

    private final class RuntimeTrace {
        private final AtomicLong sequence = new AtomicLong();
        private final List<StampedEvent> events = new ArrayList<>();
        private final Map<UUID, Subscription> subscriptions = new ConcurrentHashMap<>();

        private RuntimeTrace(String runtimeId) {
        }

        private void publish(AguiEvent event) {
            StampedEvent stamped;
            synchronized (this) {
                // Allocation, buffer append, and subscription enqueue are one critical section so every subscriber sees 1, 2, 3.
                stamped = new StampedEvent(sequence.incrementAndGet(), event);
                events.add(stamped);
                if (events.size() > MAX_EVENTS_PER_RUNTIME) {
                    events.removeFirst();
                }
                subscriptions.values().forEach(subscription -> enqueue(subscription, stamped));
            }
        }

        private Subscription subscribe(long afterSequence) {
            synchronized (this) {
                List<StampedEvent> history = events.stream()
                        .filter(event -> event.sequence() > afterSequence)
                        .toList();
                Subscription subscription = new Subscription(
                        UUID.randomUUID(), new SseEmitter(30 * 60_000L), this, history);
                subscriptions.put(subscription.id(), subscription);
                subscription.emitter().onCompletion(() -> remove(subscription));
                subscription.emitter().onTimeout(() -> {
                    remove(subscription);
                    subscription.emitter().complete();
                });
                subscription.emitter().onError(ignored -> remove(subscription));
                return subscription;
            }
        }

        private void activate(Subscription subscription) {
            synchronized (subscription) {
                if (!subscription.buffering) {
                    return;
                }
                subscription.buffering = false;
            }
            scheduleDrain(subscription);
        }

        private void enqueue(Subscription subscription, StampedEvent event) {
            synchronized (subscription) {
                if (event.sequence() <= subscription.lastDeliveredSequence) {
                    return;
                }
                subscription.pending.addLast(event);
                if (subscription.buffering || subscription.draining) {
                    return;
                }
                subscription.draining = true;
            }
            emitterExecutor.execute(() -> drain(subscription));
        }

        private void scheduleDrain(Subscription subscription) {
            synchronized (subscription) {
                if (subscription.draining || subscription.pending.isEmpty()) {
                    return;
                }
                subscription.draining = true;
            }
            emitterExecutor.execute(() -> drain(subscription));
        }

        private void drain(Subscription subscription) {
            while (true) {
                StampedEvent event;
                synchronized (subscription) {
                    event = subscription.pending.pollFirst();
                    if (event == null) {
                        subscription.draining = false;
                        return;
                    }
                    if (event.sequence() <= subscription.lastDeliveredSequence) {
                        continue;
                    }
                }
                if (!deliver(subscription, event)) {
                    return;
                }
            }
        }

        private boolean deliver(Subscription subscription, StampedEvent stamped) {
            try {
                subscription.emitter().send(SseEmitter.event()
                        .id(Long.toString(stamped.sequence()))
                        .data(stamped.event()));
                synchronized (subscription) {
                    subscription.lastDeliveredSequence = stamped.sequence();
                }
                return true;
            } catch (IOException exception) {
                remove(subscription);
                subscription.emitter().completeWithError(exception);
                return false;
            }
        }

        private void remove(Subscription subscription) {
            subscriptions.remove(subscription.id());
        }
    }

    private record StampedEvent(long sequence, AguiEvent event) {
    }
}
