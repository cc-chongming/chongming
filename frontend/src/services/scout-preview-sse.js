/** Subscribes to one isolated Context Scout preview run. */
export function createScoutPreviewSubscription({ reviewId, attemptNo, previewId, onEvent, onState = () => {}, EventSourceImpl = globalThis.EventSource }) {
    if (!EventSourceImpl || !reviewId || !attemptNo || !previewId) {
        onState({ status: 'unavailable' });
        return { close() {} };
    }
    const source = new EventSourceImpl(`/api/reviews/${encodeURIComponent(reviewId)}/attempts/${attemptNo}/scout-previews/${encodeURIComponent(previewId)}/events`);
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
