package ai.cc.chongming.review.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import ai.cc.chongming.review.config.ReviewRuntimeTraceProperties;
import ai.cc.chongming.review.domain.model.ReviewTypes.ReviewId;
import ai.cc.chongming.review.domain.repository.RuntimeTraceStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.agui.event.AguiEvent;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;

/**
 * [AIREVIEW-PLAN-022#7.1] Verifies durable runtime trace persistence, restart replay, sequence
 * continuation, retention trimming, and the auxiliary-runtime guard.
 *
 * @author wangli
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
                new AguiEvent.ToolCallResult("thread", "run", "call-1", "summary", "tool", "reply-1")))
                .isEqualTo("TOOL_CALL_RESULT:call-1");
        assertThat(ReviewRuntimeTraceRegistry.deriveEventId(
                new AguiEvent.Custom("thread", "run", "chongming.tool-call.v1", Map.of())))
                .isNull();
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

        @Override
        public synchronized void append(
                String runtimeId,
                long sequence,
                String eventId,
                String eventType,
                String payloadJson,
                ReviewId reviewId,
                int attemptNo) {
            if (failWrites.get()) {
                throw new RuntimeException("simulated write failure");
            }
            rowsByRuntime.computeIfAbsent(runtimeId, ignored -> new TreeMap<>())
                    .put(sequence, new RuntimeTraceRow(sequence, eventId, eventType, payloadJson));
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
                rows.pollFirstEntry();
            }
        }
    }
}
