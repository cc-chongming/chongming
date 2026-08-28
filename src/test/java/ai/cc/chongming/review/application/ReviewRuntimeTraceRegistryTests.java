package ai.cc.chongming.review.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import ai.cc.chongming.review.config.ReviewRuntimeTraceProperties;
import ai.cc.chongming.review.domain.model.ReviewTypes.ReviewId;
import ai.cc.chongming.review.domain.repository.RuntimeTraceStore;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.agui.event.AguiEvent;

import java.lang.reflect.Field;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * [AIREVIEW-PLAN-022#7.1][AIREVIEW-PLAN-023#8][AIREVIEW-PLAN-024#6] Verifies durable runtime trace persistence,
 * restart replay, sequence continuation, retention trimming, the auxiliary-runtime guard, and the
 * PLAN-024 observability metrics (five independent failure categories plus stage metrics).
 *
 * @author zyj
 */
class ReviewRuntimeTraceRegistryTests {

    @Test
    void persistsAndReplaysAfterNewInstance() {
        FakeTraceStore store = new FakeTraceStore();
        ReviewRuntimeTraceRegistry first = registry(store, 1000);
        String runtimeId = runtimeId(1);
        List<AguiEvent> published = List.of(start(), text("m-1"), custom("chongming.runtime-lifecycle.v1"));
        published.forEach(event -> first.publish(runtimeId, event));
        awaitTrue(() -> store.maxSequence(runtimeId) == published.size()
                && store.rowsByRuntime.get(runtimeId).size() == published.size());

        ReviewRuntimeTraceRegistry restarted = registry(store, 1000);
        List<AguiEvent> replayed = restarted.replayHistory(runtimeId, 0);

        assertThat(replayed).containsExactlyElementsOf(published);
    }

    @Test
    void continuesSequenceAfterRestart() {
        FakeTraceStore store = new FakeTraceStore();
        ReviewRuntimeTraceRegistry first = registry(store, 1000);
        String runtimeId = runtimeId(1);
        first.publish(runtimeId, start());
        first.publish(runtimeId, text("m-1"));
        first.publish(runtimeId, text("m-2"));
        awaitTrue(() -> store.maxSequence(runtimeId) == 3);

        ReviewRuntimeTraceRegistry restarted = registry(store, 1000);
        restarted.publish(runtimeId, text("m-3"));
        restarted.publish(runtimeId, text("m-4"));
        awaitTrue(() -> store.maxSequence(runtimeId) == 5
                && store.rowsByRuntime.get(runtimeId).size() == 5);

        assertThat(store.rowsByRuntime.get(runtimeId).keySet()).containsExactly(1L, 2L, 3L, 4L, 5L);
        assertThat(restarted.replayHistory(runtimeId, 0)).hasSize(5);
    }

    @Test
    void trimsOldestBeyondLimit() {
        FakeTraceStore store = new FakeTraceStore();
        ReviewRuntimeTraceRegistry registry = registry(store, 3);
        String runtimeId = runtimeId(1);
        for (int i = 1; i <= 5; i++) {
            registry.publish(runtimeId, text("m-" + i));
        }
        // Appends and trims are async and may interleave; wait for the converged final state in
        // which every append has landed and the last trim has kept exactly the newest three rows.
        awaitTrue(() -> {
            List<RuntimeTraceStore.RuntimeTraceRow> retained = store.findAfter(runtimeId, 0, 100);
            return store.maxSequence(runtimeId) == 5
                    && retained.size() == 3
                    && retained.get(0).sequence() == 3L;
        });

        List<RuntimeTraceStore.RuntimeTraceRow> retained = store.findAfter(runtimeId, 0, 100);
        assertThat(retained).hasSize(3);
        assertThat(retained.get(0).sequence()).isEqualTo(3L);
        assertThat(retained.get(2).sequence()).isEqualTo(5L);
    }

    @Test
    void doesNotPersistAuxiliaryRuntime() {
        FakeTraceStore store = new FakeTraceStore();
        ReviewRuntimeTraceRegistry registry = registry(store, 1000);
        String previewRuntime = runtimeId(1) + ":scout-preview:" + UUID.randomUUID();
        registry.publish(previewRuntime, text("m-1"));
        registry.publish(previewRuntime, text("m-2"));

        assertThat(store.maxSequence(previewRuntime)).isZero();
    }

    @Test
    void writeFailureDoesNotBlockPublish() {
        FakeTraceStore store = new FakeTraceStore();
        store.failWrites.set(true);
        ReviewRuntimeTraceRegistry registry = registry(store, 1000);
        String runtimeId = runtimeId(1);

        assertThatCode(() -> {
            for (int i = 1; i <= 5; i++) {
                registry.publish(runtimeId, text("m-" + i));
            }
        }).doesNotThrowAnyException();

        // Events stay visible in the in-memory trace regardless of durable write failures.
        assertThat(registry.replayHistory(runtimeId, 0)).hasSize(5);
    }

    @Test
    void derivesStableDedupeKeys() {
        assertThat(ReviewRuntimeTraceRegistry.deriveEventId(
                new AguiEvent.TextMessageStart("thread", "run", "m-1", "assistant")))
                .isEqualTo("TEXT_MESSAGE_START:m-1");
        assertThat(ReviewRuntimeTraceRegistry.deriveEventId(
                new AguiEvent.TextMessageContent("thread", "run", "m-1", "delta")))
                .isNull();
        assertThat(ReviewRuntimeTraceRegistry.deriveEventId(
                new AguiEvent.ToolCallResult("thread", "run", "call-1", "summary", "tool", "reply-1")))
                .isEqualTo("TOOL_CALL_RESULT:call-1");
        assertThat(ReviewRuntimeTraceRegistry.deriveEventId(
                new AguiEvent.Custom("thread", "run", "chongming.tool-call.v1", Map.of())))
                .isNull();
        assertThat(ReviewRuntimeTraceRegistry.deriveEventId(
                runtimeId(1),
                7,
                new AguiEvent.TextMessageStart("thread", "run", "m-1", "assistant")))
                .isEqualTo(runtimeId(1) + ":TEXT_MESSAGE_START:m-1");
    }

    @Test
    void repeatedRunBoundariesKeepDistinctStableIdsAndReplayAfterRestart() {
        FakeTraceStore store = new FakeTraceStore();
        ReviewRuntimeTraceRegistry first = registry(store, 1000);
        String runtimeId = runtimeId(1);
        List<AguiEvent> boundaries = List.of(
                start(),
                new AguiEvent.RunFinished("thread-1", "run-1"),
                start(),
                new AguiEvent.RunFinished("thread-1", "run-1"));

        boundaries.forEach(event -> first.publish(runtimeId, event));
        awaitTrue(() -> store.appendAttempts.get() == boundaries.size());

        List<RuntimeTraceStore.RuntimeTraceRow> rows = store.findAfter(runtimeId, 0, 100);
        assertThat(rows).hasSize(4);
        assertThat(rows).extracting(RuntimeTraceStore.RuntimeTraceRow::eventId)
                .doesNotHaveDuplicates()
                .allSatisfy(eventId -> assertThat(eventId).contains(runtimeId));
        assertThat(store.eventIdCollisions.get()).isZero();

        ReviewRuntimeTraceRegistry restarted = registry(store, 1000);
        assertThat(restarted.replayHistory(runtimeId, 0)).containsExactlyElementsOf(boundaries);
    }

    @Test
    void serializesPersistencePerRuntimeEvenWhenWritesAreSlow() {
        FakeTraceStore store = new FakeTraceStore();
        store.writeDelayMillis = 15;
        ReviewRuntimeTraceRegistry registry = registry(store, 100);
        String runtimeId = runtimeId(1);

        for (int i = 1; i <= 20; i++) {
            registry.publish(runtimeId, text("m-" + i));
        }
        awaitTrue(() -> store.appendAttempts.get() == 20);

        assertThat(store.maxConcurrentAppends.get()).isEqualTo(1);
        assertThat(store.findAfter(runtimeId, 0, 100))
                .extracting(RuntimeTraceStore.RuntimeTraceRow::sequence)
                .containsExactlyElementsOf(java.util.stream.LongStream.rangeClosed(1, 20).boxed().toList());
    }

    @Test
    void countsFiveFailureCategoriesIndependentlyInsteadOfOneDegradationBucket() {
        ReviewRuntimeTraceRegistry registry = registry(new FakeTraceStore(), 1000);
        String runtimeId = runtimeId(1);
        registry.recordFailure(runtimeId, RuntimeFailureCategory.MODEL_TIMEOUT, Map.of("profile", "role-reviewer"));
        registry.recordFailure(runtimeId, RuntimeFailureCategory.MODEL_TIMEOUT, null);
        registry.recordFailure(runtimeId, RuntimeFailureCategory.NON_JSON_RESPONSE, Map.of());
        registry.recordFailure(runtimeId, RuntimeFailureCategory.TOOL_PARAMETER_REJECTED, Map.of("tool", "readLines"));
        registry.recordFailure(runtimeId, RuntimeFailureCategory.REPOSITORY_ACCESS_DENIED, Map.of("errorCode", "FILE_REF_NOT_GRANTED"));
        registry.recordFailure(runtimeId, RuntimeFailureCategory.READ_BUDGET_EXHAUSTED, Map.of("role", "BACKEND"));

        ReviewRuntimeTraceRegistry.RuntimeMetricsSnapshot snapshot = registry.metricsSnapshot(runtimeId);

        assertThat(snapshot.failureCounts())
                .hasSize(5)
                .containsEntry(RuntimeFailureCategory.MODEL_TIMEOUT, 2L)
                .containsEntry(RuntimeFailureCategory.NON_JSON_RESPONSE, 1L)
                .containsEntry(RuntimeFailureCategory.TOOL_PARAMETER_REJECTED, 1L)
                .containsEntry(RuntimeFailureCategory.REPOSITORY_ACCESS_DENIED, 1L)
                .containsEntry(RuntimeFailureCategory.READ_BUDGET_EXHAUSTED, 1L);
        // Every recorded failure is also durable and replayable as its own custom event.
        assertThat(registry.replayHistory(runtimeId, 0)).hasSize(6);
    }

    @Test
    void recordsStageMetricsAndRebuildsThemAfterRestart() {
        FakeTraceStore store = new FakeTraceStore();
        ReviewRuntimeTraceRegistry registry = registry(store, 1000);
        String runtimeId = runtimeId(1);
        registry.recordMetric(runtimeId, ReviewRuntimeTraceRegistry.METRIC_STAGE_DURATION,
                Map.of("stage", "INITIAL_REVIEW", "durationMillis", 12345));
        registry.recordMetric(runtimeId, ReviewRuntimeTraceRegistry.METRIC_ROLE_FIRST_TOKEN,
                Map.of("role", "BACKEND", "firstTokenMillis", 800));
        registry.recordMetric(runtimeId, ReviewRuntimeTraceRegistry.METRIC_TOOL_CALLS,
                Map.of("role", "BACKEND", "succeeded", 9, "failed", 2));
        registry.recordMetric(runtimeId, ReviewRuntimeTraceRegistry.METRIC_ASSESSMENT_COVERAGE_COMPLETED,
                Map.of("role", "BACKEND", "atSequence", 42));
        registry.recordMetric(runtimeId, ReviewRuntimeTraceRegistry.METRIC_DISPATCH_WAIT,
                Map.of("topicId", "topic-1", "waitMillis", 300));
        registry.recordMetric(runtimeId, ReviewRuntimeTraceRegistry.METRIC_ROUND_EFFECTIVE_ACTIONS,
                Map.of("round", 1, "effectiveActions", 4));
        registry.recordFailure(runtimeId, RuntimeFailureCategory.MODEL_TIMEOUT, Map.of());
        awaitTrue(() -> store.appendAttempts.get() >= 7);

        ReviewRuntimeTraceRegistry restarted = registry(store, 1000);
        ReviewRuntimeTraceRegistry.RuntimeMetricsSnapshot snapshot = restarted.metricsSnapshot(runtimeId);

        assertThat(snapshot.metrics()).containsKeys(
                ReviewRuntimeTraceRegistry.METRIC_STAGE_DURATION,
                ReviewRuntimeTraceRegistry.METRIC_ROLE_FIRST_TOKEN,
                ReviewRuntimeTraceRegistry.METRIC_TOOL_CALLS,
                ReviewRuntimeTraceRegistry.METRIC_ASSESSMENT_COVERAGE_COMPLETED,
                ReviewRuntimeTraceRegistry.METRIC_DISPATCH_WAIT,
                ReviewRuntimeTraceRegistry.METRIC_ROUND_EFFECTIVE_ACTIONS);
        assertThat(snapshot.metrics().get(ReviewRuntimeTraceRegistry.METRIC_STAGE_DURATION))
                .containsEntry("stage", "INITIAL_REVIEW");
        assertThat(snapshot.metrics().get(ReviewRuntimeTraceRegistry.METRIC_TOOL_CALLS))
                .containsEntry("succeeded", 9)
                .containsEntry("failed", 2);
        assertThat(snapshot.failureCounts()).containsEntry(RuntimeFailureCategory.MODEL_TIMEOUT, 1L);
    }

    @Test
    void metricsDefaultToZeroForTracesRecordedBeforePlan024() {
        FakeTraceStore store = new FakeTraceStore();
        ReviewRuntimeTraceRegistry first = registry(store, 1000);
        String runtimeId = runtimeId(1);
        // Legacy payload only: no metric events exist in the persisted trace.
        first.publish(runtimeId, text("m-1"));
        awaitTrue(() -> store.maxSequence(runtimeId) == 1);

        ReviewRuntimeTraceRegistry restarted = registry(store, 1000);
        ReviewRuntimeTraceRegistry.RuntimeMetricsSnapshot snapshot = restarted.metricsSnapshot(runtimeId);

        assertThat(snapshot.failureCounts()).hasSize(5);
        assertThat(snapshot.failureCounts().values()).containsOnly(0L);
        assertThat(snapshot.metrics()).isEmpty();
        assertThat(restarted.replayHistory(runtimeId, 0)).hasSize(1);
    }

    @Test
    void liveSsePayloadCarriesParseableObservedAt() throws Exception {
        FakeTraceStore store = new FakeTraceStore();
        ReviewRuntimeTraceRegistry registry = registry(store, 1000);
        String runtimeId = runtimeId(1);
        ReviewRuntimeTraceRegistry.Subscription subscription = registry.subscribe(runtimeId, 0);
        registry.activate(subscription);

        registry.publish(runtimeId, text("m-1"));
        registry.publish(runtimeId, custom("chongming.runtime-lifecycle.v1"));

        // [AIREVIEW-PLAN-068#2] Live SSE payloads are the JSON event plus a parseable createdAt.
        awaitTrue(() -> sseDataPayloads(subscription.emitter()).size() == 2);
        List<String> payloads = sseDataPayloads(subscription.emitter());
        assertThat(payloads).allSatisfy(payload -> {
            try {
                JsonNode node = new ObjectMapper().readTree(payload);
                assertThat(node.has("createdAt")).isTrue();
                assertThat(Instant.parse(node.get("createdAt").asText())).isAfter(Instant.EPOCH);
            } catch (Exception exception) {
                throw new AssertionError("SSE payload is not JSON with a parseable createdAt: " + payload,
                        exception);
            }
        });
    }

    @Test
    void replayedSsePayloadCarriesParseableObservedAt() throws Exception {
        FakeTraceStore store = new FakeTraceStore();
        ReviewRuntimeTraceRegistry first = registry(store, 1000);
        String runtimeId = runtimeId(1);
        first.publish(runtimeId, start());
        first.publish(runtimeId, text("m-1"));
        awaitTrue(() -> store.maxSequence(runtimeId) == 2);

        // A fresh registry hydrates the persisted trace on subscribe, so the replayed history hits
        // the same SSE serialization path with the stored created_at mapped into observedAt.
        ReviewRuntimeTraceRegistry restarted = registry(store, 1000);
        ReviewRuntimeTraceRegistry.Subscription subscription = restarted.subscribe(runtimeId, 0);
        restarted.activate(subscription);

        awaitTrue(() -> sseDataPayloads(subscription.emitter()).size() == 2);
        List<String> payloads = sseDataPayloads(subscription.emitter());
        assertThat(payloads).allSatisfy(payload -> {
            try {
                JsonNode node = new ObjectMapper().readTree(payload);
                assertThat(node.has("createdAt")).isTrue();
                assertThat(Instant.parse(node.get("createdAt").asText())).isAfter(Instant.EPOCH);
            } catch (Exception exception) {
                throw new AssertionError("SSE payload is not JSON with a parseable createdAt: " + payload,
                        exception);
            }
        });
    }

    private static ReviewRuntimeTraceRegistry registry(FakeTraceStore store, int maxEvents) {
        return new ReviewRuntimeTraceRegistry(
                new ObjectMapper(), store, new ReviewRuntimeTraceProperties(true, maxEvents));
    }

    private static String runtimeId(int attemptNo) {
        return "review-11111111-1111-1111-1111-111111111111-attempt-" + attemptNo;
    }

    private static AguiEvent.RunStarted start() {
        return new AguiEvent.RunStarted("thread-1", "run-1");
    }

    private static AguiEvent.TextMessageStart text(String messageId) {
        return new AguiEvent.TextMessageStart("thread-1", "run-1", messageId, "assistant");
    }

    private static AguiEvent.Custom custom(String name) {
        return new AguiEvent.Custom("thread-1", "run-1", name, Map.of("agentId", "DIRECTOR", "lifecycle", name));
    }

    /**
     * [AIREVIEW-PLAN-068#2] Reads the SSE frames SseEmitter buffered before a container handler
     * was attached (unit tests never initialize one), returning the JSON event data payloads.
     */
    private static List<String> sseDataPayloads(SseEmitter emitter) {
        try {
            Field earlySendAttempts = ResponseBodyEmitter.class.getDeclaredField("earlySendAttempts");
            earlySendAttempts.setAccessible(true);
            Set<?> attempts = (Set<?>) earlySendAttempts.get(emitter);
            List<String> payloads = new ArrayList<>();
            for (Object attempt : attempts) {
                if (attempt instanceof ResponseBodyEmitter.DataWithMediaType item
                        && item.getData() instanceof String text
                        && text.startsWith("{")) {
                    payloads.add(text);
                }
            }
            return payloads;
        } catch (Exception exception) {
            throw new AssertionError("could not read SseEmitter early send attempts", exception);
        }
    }

    private static void awaitTrue(BooleanSupplier condition) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("condition not met within 5 seconds");
            }
            try {
                Thread.sleep(5);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new AssertionError("interrupted while awaiting condition", interrupted);
            }
        }
    }

    /**
     * In-memory stand-in for the MyBatis store: same contract, synchronous append so tests can
     * assert durable state without a database.
     */
    static final class FakeTraceStore implements RuntimeTraceStore {

        final Map<String, TreeMap<Long, RuntimeTraceRow>> rowsByRuntime = new ConcurrentHashMap<>();
        final AtomicBoolean failWrites = new AtomicBoolean();
        final Set<String> eventIds = new HashSet<>();
        final AtomicInteger eventIdCollisions = new AtomicInteger();
        final AtomicInteger appendAttempts = new AtomicInteger();
        final AtomicInteger concurrentAppends = new AtomicInteger();
        final AtomicInteger maxConcurrentAppends = new AtomicInteger();
        volatile long writeDelayMillis;

        @Override
        public void append(
                String runtimeId,
                long sequence,
                String eventId,
                String eventType,
                String payloadJson,
                ReviewId reviewId,
                int attemptNo) {
            int active = concurrentAppends.incrementAndGet();
            maxConcurrentAppends.accumulateAndGet(active, Math::max);
            try {
                if (writeDelayMillis > 0) {
                    Thread.sleep(writeDelayMillis);
                }
                if (failWrites.get()) {
                    throw new RuntimeException("simulated write failure");
                }
                synchronized (this) {
                    if (eventId != null && !eventIds.add(eventId)) {
                        eventIdCollisions.incrementAndGet();
                        return;
                    }
                    rowsByRuntime.computeIfAbsent(runtimeId, ignored -> new TreeMap<>())
                            .put(sequence, new RuntimeTraceRow(sequence, eventId, eventType, payloadJson, Instant.now()));
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new RuntimeException("simulated write interrupted", interrupted);
            } finally {
                concurrentAppends.decrementAndGet();
                appendAttempts.incrementAndGet();
            }
        }

        @Override
        public synchronized List<RuntimeTraceRow> findAfter(String runtimeId, long afterSequence, int limit) {
            TreeMap<Long, RuntimeTraceRow> rows = rowsByRuntime.get(runtimeId);
            if (rows == null) {
                return List.of();
            }
            return List.copyOf(rows.tailMap(afterSequence, false).values().stream().limit(limit).toList());
        }

        @Override
        public synchronized long maxSequence(String runtimeId) {
            TreeMap<Long, RuntimeTraceRow> rows = rowsByRuntime.get(runtimeId);
            return rows == null || rows.isEmpty() ? 0L : rows.lastKey();
        }

        @Override
        public synchronized void trim(String runtimeId, int keep) {
            TreeMap<Long, RuntimeTraceRow> rows = rowsByRuntime.get(runtimeId);
            if (rows == null) {
                return;
            }
            while (rows.size() > keep) {
                RuntimeTraceRow removed = rows.pollFirstEntry().getValue();
                if (removed.eventId() != null) {
                    eventIds.remove(removed.eventId());
                }
            }
        }
    }
}
