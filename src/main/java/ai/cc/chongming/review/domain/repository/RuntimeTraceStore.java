package ai.cc.chongming.review.domain.repository;

import ai.cc.chongming.review.domain.model.ReviewTypes.ReviewId;
import java.time.Instant;
import java.util.List;

/**
 * [AIREVIEW-PLAN-022#5.1] Durable append store for the main review runtime's AG-UI trace.
 *
 * <p>The implementation is conditional on {@code review.persistence.enabled}; when persistence is
 * disabled the {@code ReviewRuntimeTraceRegistry} stays purely in-memory and never calls this
 * interface. Observability writes are best-effort: a failed append must never block the review
 * run or the real-time SSE stream.
 *
 * @author wangli
 */
public interface RuntimeTraceStore {

    /**
     * Appends one sequence-numbered event for a runtime. Appending an already-persisted
     * {@code (runtimeId, sequence)} pair is idempotent.
     */
    void append(
            String runtimeId,
            long sequence,
            String eventId,
            String eventType,
            String payloadJson,
            ReviewId reviewId,
            int attemptNo);

    /** Returns events of a runtime with {@code sequence > afterSequence}, ordered ascending. */
    List<RuntimeTraceRow> findAfter(String runtimeId, long afterSequence, int limit);

    /** Returns the highest persisted sequence for a runtime, or 0 when it has no rows yet. */
    long maxSequence(String runtimeId);

    /** Deletes the oldest rows of a runtime so at most {@code keep} rows remain. */
    void trim(String runtimeId, int keep);

    /**
     * Read-only persisted trace row. {@code payloadJson} is the Jackson-serialized AG-UI event.
     * {@code createdAt} is the DB-generated {@code created_at} column (server local wall clock,
     * Asia/Shanghai) restored to an {@link Instant} by the persistence implementation per
     * [AIREVIEW-PLAN-068#2] / LRN-20260820-001; the registry maps it into replayed SSE payloads.
     */
    record RuntimeTraceRow(long sequence, String eventId, String eventType, String payloadJson, Instant createdAt) {
    }
}
