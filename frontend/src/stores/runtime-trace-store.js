import { computed, reactive } from 'vue';
import { createAgUiRuntimeSubscription } from '../services/ag-ui-runtime-sse';

/**
 * [AIREVIEW-PLAN-017#4.2] Reduces standard AG-UI events into per-role execution detail.
 * Replay after a restart can deliver tens of thousands of historical events in one burst;
 * they are buffered and flushed in batches so Vue reacts a few dozen times instead of once
 * per event (each reaction rebuilds the whole runtime conversation).
 */
const FLUSH_BATCH_SIZE = 600;
const FLUSH_INTERVAL_MS = 400;
const EVENT_RETENTION = 20000;
// [AIREVIEW-PLAN-091#3] Per-attempt durable replay cursor for resuming the bounded runtime stream.
const cursorKey = (reviewId, attemptNo) => `dsh-review-trace-cursor:${reviewId}:${attemptNo}`;

export function createRuntimeTraceStore({ EventSourceImpl, setTimeoutImpl = globalThis.setTimeout, clearTimeoutImpl = globalThis.clearTimeout } = {}) {
    const state = reactive({ status: 'idle', events: [], selectedRole: null });
    let subscription;
    const runs = new Map();
    const seenEventIds = new Set();
    const pendingEvents = [];
    let flushTimer = null;
    let currentReviewId;
    let currentAttemptNo;
    const byRole = computed(() => {
        const grouped = new Map();
        state.events.forEach((event) => {
            const role = event?.value?.role ?? runs.get(event.runId) ?? 'DIRECTOR';
            if (!grouped.has(role)) grouped.set(role, []);
            grouped.get(role).push(event);
        });
        return grouped;
    });

    function flush() {
        flushTimer = null;
        if (!pendingEvents.length) return;
        state.events.push(...pendingEvents.splice(0));
        // Keep the reactive window bounded; the durable database copy stays authoritative.
        if (state.events.length > EVENT_RETENTION) {
            const removed = state.events.splice(0, state.events.length - EVENT_RETENTION);
            removed.forEach((event) => { if (event?.id) seenEventIds.delete(event.id); });
        }
    }

    function scheduleFlush() {
        if (flushTimer != null || pendingEvents.length >= FLUSH_BATCH_SIZE) return;
        flushTimer = setTimeoutImpl(flush, FLUSH_INTERVAL_MS);
    }

    function merge(event) {
        // [AIREVIEW-PLAN-091#3] Persist the highest seen sequence so a later start() resumes after it.
        const seq = Number(event?.id);
        if (Number.isFinite(seq)) {
            try {
                const key = cursorKey(currentReviewId, currentAttemptNo);
                globalThis.localStorage.setItem(key, String(Math.max(Number(globalThis.localStorage.getItem(key)) || 0, seq)));
            } catch {
                // LocalStorage persistence is best-effort; replay dedupe still works in memory.
            }
        }
        const eventId = event?.id;
        if (eventId && seenEventIds.has(eventId)) return;
        if (eventId) seenEventIds.add(eventId);
        if (event?.type === 'CUSTOM' && event?.value?.role) runs.set(event.runId, event.value.role);
        pendingEvents.push(event);
        if (pendingEvents.length >= FLUSH_BATCH_SIZE) {
            if (flushTimer != null) { clearTimeoutImpl(flushTimer); flushTimer = null; }
            flush();
            return;
        }
        scheduleFlush();
    }

    function start(reviewId, attemptNo) {
        subscription?.close();
        if (flushTimer != null) { clearTimeoutImpl(flushTimer); flushTimer = null; }
        currentReviewId = reviewId;
        currentAttemptNo = attemptNo;
        pendingEvents.length = 0;
        state.events = [];
        runs.clear();
        seenEventIds.clear();
        // [AIREVIEW-PLAN-091#3] Resume from the best-effort persisted cursor when available.
        let afterSequence = 0;
        try {
            afterSequence = Number(globalThis.localStorage?.getItem(cursorKey(reviewId, attemptNo))) || 0;
        } catch {
            // Persisted cursor is best-effort.
        }
        subscription = createAgUiRuntimeSubscription({
            reviewId,
            attemptNo,
            afterSequence,
            EventSourceImpl,
            onEvent: merge,
            onState: ({ status }) => { state.status = status; }
        });
    }

    function dispose() {
        subscription?.close();
        subscription = undefined;
        if (flushTimer != null) { clearTimeoutImpl(flushTimer); flushTimer = null; }
        pendingEvents.length = 0;
    }
    return { state, byRole, start, dispose };
}
