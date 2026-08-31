/**
 * [AIREVIEW-PLAN-012#1.4] Owns one resumable SSE subscription per review.
 * Browser EventSource cannot supply arbitrary headers, so recovery uses the server's afterSequence parameter.
 * The Bearer token travels as an `access_token` query parameter instead.
 */

import { isStoredTokenUsable, redirectToLogin, withAuthToken } from './auth-token';

export const REVIEW_EVENT_TYPES = [
    'REVIEW_ACCEPTED', 'PLAN_CREATED', 'PLAN_REVISED',
    'ROLE_ACTIVATED', 'ROLE_STARTED', 'ROLE_COMPLETED', 'ROLE_FAILED', 'CONTEXT_SCOUT_DEGRADED',
    // [AIREVIEW-PLAN-080#1] 初审全完成→CONFLICT_DETECTION 的转换事件；缺它则阶段白屏期不跳转
    // （ROLE_COMPLETED 只更新卡片，stage 不动），只能等后续白名单事件迟到跳变。
    'INITIAL_REVIEW_COMPLETED',
    // The structured conclusion lands with this event; without a live listener the scout
    // conclusion panel stays empty until a manual page reload.
    'CONTEXT_SCOUT_COMPLETED',
    'EVIDENCE_CAPTURED', 'CLAIM_SUBMITTED', 'DEBATE_TOPIC_OPENED', 'DEBATE_ROUND_2_STARTED',
    'CHALLENGE_SUBMITTED',
    'REBUTTAL_SUBMITTED', 'POSITION_CHANGED', 'EVIDENCE_REQUESTED', 'DEBATE_TOPIC_CLOSED',
    'DEBATE_SKIPPED', 'JUDGING_STARTED',
    'JUDGEMENT_SUBMITTED', 'GATE_DRAFTED', 'HUMAN_REVIEW_REQUIRED',
    'HUMAN_REVIEW_ITEM_CREATED', 'HUMAN_REVIEW_ITEM_UPDATED', 'HUMAN_REVIEW_ITEM_DELETED',
    'HUMAN_GATE_FINALIZED', 'NOTIFICATION_QUEUED', 'NOTIFICATION_SENT', 'NOTIFICATION_FAILED',
    'NOTIFICATION_DEAD', 'NOTIFICATION_RETRY_REQUESTED', 'REVIEW_CANCELLED', 'REVIEW_RETRIED',
    'REVIEW_RECOVERED', 'REVIEW_FAILED'
];

export function createReviewSseSubscription({
    reviewId,
    afterSequence = 0,
    onEvent,
    onState = () => {},
    EventSourceImpl = globalThis.EventSource,
    setTimeoutImpl = globalThis.setTimeout,
    clearTimeoutImpl = globalThis.clearTimeout
}) {
    if (!EventSourceImpl) {
        onState({ status: 'unsupported', retryDelayMs: null });
        return { close() {} };
    }

    let source;
    let retryTimer;
    let closed = false;
    let retries = 0;
    let cursor = Number(afterSequence) || 0;

    const open = () => {
        if (closed) return;
        onState({ status: retries === 0 ? 'connecting' : 'reconnecting', retryDelayMs: null });
        source = new EventSourceImpl(withAuthToken(`/api/reviews/${encodeURIComponent(reviewId)}/events?afterSequence=${cursor}`));
        source.onopen = () => {
            retries = 0;
            onState({ status: 'connected', retryDelayMs: null });
        };
        const consume = (message) => {
            try {
                const event = JSON.parse(message.data);
                // Tolerate both plain UUID strings and legacy {value} object wrappers for identity fields.
                const reviewIdValue = typeof event.reviewId === 'string' ? event.reviewId : event.reviewId?.value;
                if (!Number.isInteger(event.sequence) || event.sequence < 1 || reviewIdValue !== reviewId) return;
                cursor = Math.max(cursor, event.sequence);
                onEvent({
                    ...event,
                    reviewId: reviewIdValue,
                    topicId: typeof event.topicId === 'string' ? event.topicId : event.topicId?.value ?? null,
                    claimId: typeof event.claimId === 'string' ? event.claimId : event.claimId?.value ?? null,
                    turnId: typeof event.turnId === 'string' ? event.turnId : event.turnId?.value ?? null
                });
            } catch {
                onState({ status: 'malformed-event', retryDelayMs: null });
            }
        };
        source.onmessage = consume;
        REVIEW_EVENT_TYPES.forEach((type) => source.addEventListener(type, consume));
        source.onerror = () => {
            source.close();
            if (closed) return;
            // An expired token cannot recover through backoff: stop retrying and let the guard sign out.
            if (!isStoredTokenUsable()) {
                closed = true;
                onState({ status: 'auth-expired', retryDelayMs: null });
                redirectToLogin();
                return;
            }
            const delay = Math.min(30_000, 1_000 * (2 ** Math.min(retries, 5)));
            retries += 1;
            onState({ status: 'reconnecting', retryDelayMs: delay });
            retryTimer = setTimeoutImpl(open, delay);
        };
    };

    open();
    return {
        close() {
            closed = true;
            if (retryTimer) clearTimeoutImpl(retryTimer);
            if (source) source.close();
            onState({ status: 'closed', retryDelayMs: null });
        }
    };
}
