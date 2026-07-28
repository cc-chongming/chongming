/** [AIREVIEW-PLAN-017#4.2] Subscribes to the bounded, current-attempt AG-UI runtime stream. */
export function createAgUiRuntimeSubscription({ reviewId, attemptNo, onEvent, onState = () => {}, EventSourceImpl = globalThis.EventSource }) {
    if (!EventSourceImpl || !attemptNo) {
        onState({ status: 'unavailable' });
        return { close() {} };
    }
    const source = new EventSourceImpl(`/api/reviews/${encodeURIComponent(reviewId)}/attempts/${attemptNo}/runtime/ag-ui`);
    source.onopen = () => onState({ status: 'connected' });
    source.onmessage = (message) => {
        try {
            const event = { ...JSON.parse(message.data), id: message.lastEventId || undefined };
            if (event?.type) onEvent(event);
        } catch {
            onState({ status: 'malformed-event' });
        }
    };
    source.onerror = () => onState({ status: 'reconnecting' });
    return { close() { source.close(); onState({ status: 'closed' }); } };
}
