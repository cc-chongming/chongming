/** Subscribes to one isolated Context Scout preview run. */
import { isStoredTokenUsable, redirectToLogin, withAuthToken } from './auth-token';

export function createScoutPreviewSubscription({
    reviewId,
    attemptNo,
    previewId,
    onEvent,
    onState = () => {},
    EventSourceImpl = globalThis.EventSource,
    setTimeoutImpl = globalThis.setTimeout,
    clearTimeoutImpl = globalThis.clearTimeout
}) {
    if (!EventSourceImpl || !reviewId || !attemptNo || !previewId) {
        onState({ status: 'unavailable' });
        return { close() {} };
    }

    let source;
    let retryTimer;
    let closed = false;
    let retries = 0;

    const open = () => {
        if (closed) return;
        // Rebuild the URL on every (re)open so a refreshed token is picked up instead of
        // retrying forever with an expired one frozen in the query string.
        onState({ status: retries === 0 ? 'connecting' : 'reconnecting' });
        source = new EventSourceImpl(withAuthToken(
            `/api/reviews/${encodeURIComponent(reviewId)}/attempts/${attemptNo}/scout-previews/${encodeURIComponent(previewId)}/events`));
        source.onopen = () => {
            retries = 0;
            onState({ status: 'connected' });
        };
        source.onmessage = (message) => {
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
