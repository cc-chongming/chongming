import { computed, reactive } from 'vue';
import { createAgUiRuntimeSubscription } from '../services/ag-ui-runtime-sse';

/** [AIREVIEW-PLAN-017#4.2] Reduces standard AG-UI events into per-role execution detail. */
export function createRuntimeTraceStore({ EventSourceImpl } = {}) {
    const state = reactive({ status: 'idle', events: [], selectedRole: null });
    let subscription;
    const runs = new Map();
    const seenEventIds = new Set();
    const byRole = computed(() => {
        const grouped = new Map();
        state.events.forEach((event) => {
            const role = event?.value?.role ?? runs.get(event.runId) ?? 'DIRECTOR';
            if (!grouped.has(role)) grouped.set(role, []);
            grouped.get(role).push(event);
        });
        return grouped;
    });

    function merge(event) {
        const eventId = event?.id;
        if (eventId && seenEventIds.has(eventId)) return;
        if (eventId) seenEventIds.add(eventId);
        if (event?.type === 'CUSTOM' && event?.value?.role) runs.set(event.runId, event.value.role);
        state.events.push(event);
        if (state.events.length > 500) {
            const removed = state.events.shift();
            if (removed?.id) seenEventIds.delete(removed.id);
        }
    }

    function start(reviewId, attemptNo) {
        subscription?.close();
        state.events = [];
        runs.clear();
        seenEventIds.clear();
        subscription = createAgUiRuntimeSubscription({
            reviewId,
            attemptNo,
            EventSourceImpl,
            onEvent: merge,
            onState: ({ status }) => { state.status = status; }
        });
    }

    function dispose() { subscription?.close(); subscription = undefined; }
    return { state, byRole, start, dispose };
}
