/** [AIREVIEW-PLAN-017#4.2] Subscribes to the bounded, current-attempt AG-UI runtime stream. */
import { isStoredTokenUsable, redirectToLogin, withAuthToken } from './auth-token';

export function createAgUiRuntimeSubscription({
    reviewId,
    attemptNo,
    afterSequence = 0,
    onEvent,
    onState = () => {},
    EventSourceImpl = globalThis.EventSource,
    setTimeoutImpl = globalThis.setTimeout,
    clearTimeoutImpl = globalThis.clearTimeout
}) {
    if (!EventSourceImpl || !attemptNo) {
        onState({ status: 'unavailable' });
        return { close() {} };
    }

    let source;
    let retryTimer;
    let closed = false;
    let retries = 0;
    // [AIREVIEW-PLAN-091#2] Advance the replay cursor as events arrive so a reconnect resumes after the freshest sequence.
    let cursor = Number(afterSequence) || 0;

    const open = () => {
        if (closed) return;
        // Rebuild the URL on every (re)open so a refreshed token is picked up instead of
        // retrying forever with an expired one frozen in the query string.
        onState({ status: retries === 0 ? 'connecting' : 'reconnecting' });
        // [AIREVIEW-PLAN-091#2] withAuthToken appends `&access_token` for URLs that already contain a query string.
        const url = `/api/reviews/${encodeURIComponent(reviewId)}/attempts/${attemptNo}/runtime/ag-ui?afterSequence=${cursor}`;
        source = new EventSourceImpl(withAuthToken(url));
        source.onopen = () => {
            retries = 0;
            onState({ status: 'connected' });
        };
        source.onmessage = (message) => {
            // [AIREVIEW-PLAN-091#2] Track the highest emitted sequence for the next reconnect URL.
            const seq = Number(message.lastEventId);
            if (Number.isFinite(seq) && seq > cursor) cursor = seq;
            try {
                const event = { ...JSON.parse(message.data), id: message.lastEventId || undefined };
                if (event?.type) onEvent(event);
            } catch {
                onState({ status: 'malformed-event' });
            }
        };
        source.onerror = () => {
            source.close();
            if (closed) return;
            // An expired token cannot recover through backoff: stop retrying and let the guard sign out.
            if (!isStoredTokenUsable()) {
                closed = true;
                onState({ status: 'auth-expired' });
                redirectToLogin();
                return;
            }
            const delay = Math.min(30_000, 1_000 * (2 ** Math.min(retries, 5)));
            retries += 1;
            onState({ status: 'reconnecting' });
            retryTimer = setTimeoutImpl(open, delay);
        };
    };

    open();
    return {
        close() {
            closed = true;
            if (retryTimer) clearTimeoutImpl(retryTimer);
            if (source) source.close();
            onState({ status: 'closed' });
        }
    };
}
