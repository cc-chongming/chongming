package ai.cc.chongming.review.application;

import ai.cc.chongming.review.config.ReviewRuntimeTraceProperties;
import ai.cc.chongming.review.domain.model.ReviewTypes.ReviewId;
import ai.cc.chongming.review.domain.repository.RuntimeTraceStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.agui.event.AguiEvent;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * [AIREVIEW-PLAN-017#4.2][AIREVIEW-PLAN-022#5.3] Bounded AG-UI runtime trace per review attempt.
 *
 * <p>Since PLAN-022 the registry can durably persist the main review runtime
 * ({@code review-{reviewId}-attempt-{attemptNo}}) and lazily rehydrate it after a restart.
 * Persistence is best-effort observability: a failed write is logged and never blocks the
 * review run or the real-time SSE stream. When no {@link RuntimeTraceStore} is configured the
 * registry degrades to the original pure in-memory behavior.
 *
 * @author wangli
 */
@Component
public class ReviewRuntimeTraceRegistry {

    private static final int DEFAULT_MAX_EVENTS_PER_RUNTIME = 1000;

    /**
     * Matches exactly the main attempt runtime {@code review-{uuid}-attempt-{n}}. Auxiliary
     * runtimes such as the Context Scout preview ({@code ...-attempt-{n}:scout-preview:{id}})
     * share the {@code review-} prefix but carry a suffix and therefore never match.
     */
    private static final Pattern REVIEW_RUNTIME_PATTERN =
            Pattern.compile("^review-([0-9a-fA-F-]+)-attempt-(\\d+)$");

    private final Map<String, RuntimeTrace> traces = new ConcurrentHashMap<>();
    private final ExecutorService emitterExecutor = Executors.newVirtualThreadPerTaskExecutor();
    private final ExecutorService persistExecutor = Executors.newVirtualThreadPerTaskExecutor();
    private final ObjectMapper objectMapper;
    private final RuntimeTraceStore persistenceStore;
    private final int maxEvents;
    private final boolean persistenceEnabled;

    public ReviewRuntimeTraceRegistry() {
        this(new ObjectMapper(), null, new ReviewRuntimeTraceProperties(false, DEFAULT_MAX_EVENTS_PER_RUNTIME));
    }

    @Autowired
    public ReviewRuntimeTraceRegistry(
            ObjectMapper objectMapper,
            @Autowired(required = false) RuntimeTraceStore persistenceStore,
            ReviewRuntimeTraceProperties properties) {
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null");
        this.persistenceStore = persistenceStore;
        Objects.requireNonNull(properties, "properties must not be null");
        this.maxEvents = properties.maxEvents();
        this.persistenceEnabled = properties.enabled() && persistenceStore != null;
    }

    public void publish(String runtimeId, AguiEvent event) {
        // resolveTrace keeps the durable sequence cursor monotonic across restarts: a persisted
        // main runtime without an in-memory trace is rehydrated first, so new publishes continue
        // from MAX(sequence) instead of reusing sequences already stored in the database.
        resolveTrace(runtimeId).publish(event);
    }

    /**
     * Creates a buffering subscription and seeds it with events strictly after the supplied SSE cursor.
     */
    public Subscription subscribe(ReviewId reviewId, int attemptNo, long afterSequence) {
        if (afterSequence < 0) {
            throw new IllegalArgumentException("afterSequence must not be negative");
        }
        return subscribe(ReviewRuntimeContext.runtimeIdFor(reviewId, attemptNo), afterSequence);
    }

    /**
     * Subscribes to an application-owned auxiliary runtime, such as an isolated Context Scout
     * preview. Callers must authorize the runtime identifier before exposing this subscription.
     */
    public Subscription subscribe(String runtimeId, long afterSequence) {
        if (runtimeId == null || runtimeId.isBlank()) {
            throw new IllegalArgumentException("runtimeId must not be blank");
        }
        if (afterSequence < 0) {
            throw new IllegalArgumentException("afterSequence must not be negative");
        }
        return resolveTrace(runtimeId).subscribe(afterSequence);
    }

    /** Activates delivery only after the HTTP layer has completed registration. */
    public void activate(Subscription subscription) {
        subscription.trace().activate(subscription);
    }

    /**
     * Releases an auxiliary runtime after its bounded replay window has elapsed. A later
     * subscription receives a fresh, empty trace rather than stale tool output from a completed
     * preview.
     */
    public void remove(String runtimeId) {
        if (runtimeId == null || runtimeId.isBlank()) {
            return;
        }
        RuntimeTrace trace = traces.remove(runtimeId);
        if (trace != null) {
            trace.close();
        }
    }

    /**
     * Replays the in-memory event history of a runtime strictly after the cursor, hydrating a
     * persisted main runtime on demand. Exposed for observability and tests; the SSE path uses
     * the equivalent {@link #subscribe} contract.
     */
    List<AguiEvent> replayHistory(String runtimeId, long afterSequence) {
        return resolveTrace(runtimeId).eventsAfter(afterSequence);
    }

    /**
     * Resolves the trace for a runtime, lazily rehydrating a persisted main runtime when the
     * process no longer holds it in memory (for example after a restart).
     */
    private RuntimeTrace resolveTrace(String runtimeId) {
        RuntimeTrace trace = traces.get(runtimeId);
        if (trace == null && persistable(runtimeId)) {
            trace = hydrate(runtimeId);
            RuntimeTrace existing = traces.putIfAbsent(runtimeId, trace);
            trace = existing == null ? trace : existing;
        }
        return trace == null
                ? traces.computeIfAbsent(runtimeId, id -> new RuntimeTrace(id, 0L, List.of()))
                : trace;
    }

    private boolean persistable(String runtimeId) {
        return persistenceEnabled && REVIEW_RUNTIME_PATTERN.matcher(runtimeId).matches();
    }

    private RuntimeTrace hydrate(String runtimeId) {
        try {
            long persistedMax = persistenceStore.maxSequence(runtimeId);
            List<StampedEvent> persisted = persistenceStore
                    .findAfter(runtimeId, 0L, Integer.MAX_VALUE)
                    .stream()
                    .map(row -> new StampedEvent(row.sequence(), deserialize(row.payloadJson())))
                    .toList();
            List<StampedEvent> kept = persisted.size() > maxEvents
                    ? List.copyOf(persisted.subList(persisted.size() - maxEvents, persisted.size()))
                    : persisted;
            return new RuntimeTrace(runtimeId, persistedMax, kept);
        } catch (RuntimeException exception) {
            LOGGER.warn("RUNTIME_TRACE_HYDRATE_FAILED runtimeId={} error={}",
                    runtimeId, exception.getMessage());
            return new RuntimeTrace(runtimeId, 0L, List.of());
        }
    }

    private AguiEvent deserialize(String payloadJson) {
        try {
            return objectMapper.readValue(payloadJson, AguiEvent.class);
        } catch (IOException exception) {
            throw new IllegalStateException("runtime trace payload could not be deserialized", exception);
        }
    }

    /**
     * Derives a stable dedupe key for events that carry a natural id (message/tool call/run);
     * returns {@code null} for events without one so the nullable unique index stays unconstrained.
     */
    static String deriveEventId(AguiEvent event) {
        String type = event.getType() == null ? null : event.getType().name();
        String discriminator = switch (event) {
            case AguiEvent.TextMessageStart e -> e.messageId();
            case AguiEvent.TextMessageContent e -> e.messageId();
            case AguiEvent.TextMessageEnd e -> e.messageId();
            case AguiEvent.ReasoningMessageStart e -> e.messageId();
            case AguiEvent.ReasoningMessageContent e -> e.messageId();
            case AguiEvent.ReasoningMessageEnd e -> e.messageId();
            case AguiEvent.ToolCallStart e -> e.toolCallId();
            case AguiEvent.ToolCallEnd e -> e.toolCallId();
            case AguiEvent.ToolCallResult e -> e.toolCallId();
            case AguiEvent.RunStarted e -> e.runId();
            case AguiEvent.RunFinished e -> e.runId();
            default -> null;
        };
        return discriminator == null || type == null ? null : type + ":" + discriminator;
    }

    private Optional<ReviewRuntimeRef> parseReviewRuntime(String runtimeId) {
        Matcher matcher = REVIEW_RUNTIME_PATTERN.matcher(runtimeId);
        if (!matcher.matches()) {
            return Optional.empty();
        }
        try {
            return Optional.of(new ReviewRuntimeRef(UUID.fromString(matcher.group(1)), Integer.parseInt(matcher.group(2))));
        } catch (IllegalArgumentException invalid) {
            return Optional.empty();
        }
    }

    @PreDestroy
    void close() {
        emitterExecutor.close();
        persistExecutor.close();
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
        private final String runtimeId;
        private final AtomicLong sequence = new AtomicLong();
        private final List<StampedEvent> events = new ArrayList<>();
        private final Map<UUID, Subscription> subscriptions = new ConcurrentHashMap<>();

        private RuntimeTrace(String runtimeId, long initialSequence, List<StampedEvent> initialEvents) {
            this.runtimeId = runtimeId;
            this.sequence.set(initialSequence);
            this.events.addAll(initialEvents);
        }

        private void publish(AguiEvent event) {
            StampedEvent stamped;
            synchronized (this) {
                // Allocation, buffer append, and subscription enqueue are one critical section so every subscriber sees 1, 2, 3.
                stamped = new StampedEvent(sequence.incrementAndGet(), event);
                events.add(stamped);
                if (events.size() > maxEvents) {
                    events.removeFirst();
                }
                subscriptions.values().forEach(subscription -> enqueue(subscription, stamped));
            }
            persist(stamped);
        }

        /**
         * Best-effort durable append outside the critical section. A failed write is logged and
         * never propagates to the publisher, keeping the review run and real-time SSE unaffected.
         */
        private void persist(StampedEvent stamped) {
            if (persistenceStore == null || !persistenceEnabled) {
                return;
            }
            parseReviewRuntime(runtimeId).ifPresent(ref -> {
                try {
                    persistExecutor.execute(() -> {
                        try {
                            String payloadJson = objectMapper.writeValueAsString(stamped.event());
                            String eventType = stamped.event().getType() == null
                                    ? "CUSTOM"
                                    : stamped.event().getType().name();
                            persistenceStore.append(
                                    runtimeId,
                                    stamped.sequence(),
                                    deriveEventId(stamped.event()),
                                    eventType,
                                    payloadJson,
                                    new ReviewId(ref.reviewId()),
                                    ref.attemptNo());
                            persistenceStore.trim(runtimeId, maxEvents);
                        } catch (Exception exception) {
                            LOGGER.warn("RUNTIME_TRACE_PERSIST_FAILED runtimeId={} sequence={} error={}",
                                    runtimeId, stamped.sequence(), exception.getMessage());
                        }
                    });
                } catch (RuntimeException rejected) {
                    // Registry is shutting down; the tail write is intentionally dropped (R3).
                    LOGGER.warn("RUNTIME_TRACE_PERSIST_SKIPPED runtimeId={} sequence={} error={}",
                            runtimeId, stamped.sequence(), rejected.getMessage());
                }
            });
        }

        private List<AguiEvent> eventsAfter(long afterSequence) {
            synchronized (this) {
                return events.stream()
                        .filter(event -> event.sequence() > afterSequence)
                        .map(StampedEvent::event)
                        .toList();
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

        private synchronized void close() {
            subscriptions.values().forEach(subscription -> subscription.emitter().complete());
            subscriptions.clear();
            events.clear();
        }
    }

    private record StampedEvent(long sequence, AguiEvent event) {
    }

    private record ReviewRuntimeRef(UUID reviewId, int attemptNo) {
    }

    private static final org.slf4j.Logger LOGGER =
            org.slf4j.LoggerFactory.getLogger(ReviewRuntimeTraceRegistry.class);
}
