import { computed, reactive } from 'vue';
import { EventType } from '@ag-ui/core';
import { ReviewApiError, reviewApi } from '../api/review-api';
import {
    applyAgUiEvent,
    createAgUiConversation,
    reviewEventFromAgUiEvent,
    reviewEventToAgUiEvents
} from '../services/ag-ui-review-adapter';
import { createReviewSseSubscription } from '../services/review-sse';

const STORAGE_PREFIX = 'chongming.review.last-sequence.';

function optional(promise, fallback) {
    return promise.catch((error) => {
        if (error instanceof ReviewApiError && error.status === 404) return fallback;
        throw error;
    });
}

function defaultStorage() {
    return globalThis.localStorage ?? { getItem: () => null, setItem: () => {} };
}

/**
 * [AIREVIEW-PLAN-012#1.3,#1.4] Per-review view state. Sequence, not attempt, is the idempotency key.
 */
export function createReviewStore({ api = reviewApi, storage = defaultStorage(), EventSourceImpl } = {}) {
    const state = reactive({
        reviewId: null,
        loading: false,
        error: null,
        summary: null,
        plans: [],
        debates: [],
        humanItems: [],
        humanGateVersions: [],
        report: null,
        reportMarkdown: '',
        reportVersions: [],
        notifications: [],
        selectedEvidence: null,
        evidenceById: {},
        connection: { status: 'idle', retryDelayMs: null },
        lastSequence: 0,
        agUi: createAgUiConversation(null)
    });
    const eventsBySequence = new Map();
    let subscription;

    const events = computed(() => [...eventsBySequence.values()].sort((left, right) => left.sequence - right.sequence));
    const roles = computed(() => {
        const byRole = new Map();
        events.value.filter((event) => event.actorRole && event.category === 'ROLE').forEach((event) => {
            byRole.set(event.actorRole, event);
        });
        return [...byRole.entries()].map(([role, event]) => ({ role, type: event.type, occurredAt: event.occurredAt }));
    });

    function key(reviewId) {
        return `${STORAGE_PREFIX}${reviewId}`;
    }

    function reset(reviewId) {
        eventsBySequence.clear();
        state.reviewId = reviewId;
        state.error = null;
        state.summary = null;
        state.plans = [];
        state.debates = [];
        state.humanItems = [];
        state.humanGateVersions = [];
        state.report = null;
        state.reportMarkdown = '';
        state.reportVersions = [];
        state.notifications = [];
        state.selectedEvidence = null;
        state.evidenceById = {};
        // A fresh page must rebuild the full public event timeline from the server. The persisted cursor
        // is kept for diagnostics and reconnects during the current page lifetime, never as a substitute
        // for server replay.
        state.lastSequence = 0;
        state.agUi = createAgUiConversation(`review:${reviewId}`);
    }

    function mergeDomainEvent(event) {
        if (!event || event.reviewId !== state.reviewId || !Number.isInteger(event.sequence) || event.sequence < 1) return false;
        if (eventsBySequence.has(event.sequence)) return false;
        eventsBySequence.set(event.sequence, event);
        state.lastSequence = Math.max(state.lastSequence, event.sequence);
        storage.setItem(key(state.reviewId), String(state.lastSequence));
        return true;
    }

    function mergeEvent(event) {
        const domainEvent = reviewEventFromAgUiEvent(event) ?? event;
        if (domainEvent?.reviewId && Number.isInteger(domainEvent.sequence)) {
            if (!mergeDomainEvent(domainEvent)) return false;
            reviewEventToAgUiEvents(domainEvent).forEach((agUiEvent) => applyAgUiEvent(state.agUi, agUiEvent));
            if (domainEvent.type === 'REVIEW_FAILED') {
                applyAgUiEvent(state.agUi, {
                    type: EventType.RUN_ERROR,
                    message: domainEvent.payload?.publicSummary ?? '评审运行失败。',
                    code: domainEvent.type
                });
            }
            if (domainEvent.type === 'REVIEW_CANCELLED') {
                applyAgUiEvent(state.agUi, { type: EventType.RUN_FINISHED, threadId: state.agUi.threadId, runId: state.agUi.runId });
            }
            return true;
        }
        applyAgUiEvent(state.agUi, event);
        return Boolean(event?.type);
    }

    async function refreshHumanData() {
        const [items, gates] = await Promise.all([
            api.getHumanItems(state.reviewId),
            api.getHumanGateVersions(state.reviewId)
        ]);
        state.humanItems = items;
        state.humanGateVersions = gates;
    }

    async function refreshNotifications() {
        state.notifications = await api.getNotifications(state.reviewId);
    }

    async function refreshReports() {
        const [report, markdown, versions] = await Promise.all([
            optional(api.getReport(state.reviewId), null),
            optional(api.getReport(state.reviewId, { format: 'markdown' }), ''),
            api.getReportVersions(state.reviewId)
        ]);
        state.report = report;
        state.reportMarkdown = markdown;
        state.reportVersions = versions;
    }

    async function load(reviewId) {
        subscription?.close();
        reset(reviewId);
        state.loading = true;
        try {
            const [summary, plans, debates] = await Promise.all([
                api.getSummary(reviewId), api.getPlans(reviewId), api.getDebates(reviewId)
            ]);
            state.summary = summary;
            applyAgUiEvent(state.agUi, {
                type: EventType.RUN_STARTED,
                threadId: `review:${reviewId}`,
                runId: `review-${reviewId}-attempt-${summary.attempt ?? 1}`
            });
            state.plans = plans;
            state.debates = debates;
            await Promise.all([refreshHumanData(), refreshReports(), refreshNotifications()]);
            subscription = createReviewSseSubscription({
                reviewId,
                afterSequence: state.lastSequence,
                EventSourceImpl,
                onEvent: mergeEvent,
                onState: (connection) => { state.connection = connection; }
            });
        } catch (error) {
            state.error = error;
        } finally {
            state.loading = false;
        }
    }

    async function selectEvidence(evidenceId) {
        if (!evidenceId) return;
        try {
            const evidence = state.evidenceById[evidenceId] ?? await api.getEvidence(state.reviewId, evidenceId);
            state.evidenceById[evidenceId] = evidence;
            state.selectedEvidence = evidence;
        } catch (error) {
            state.error = error;
        }
    }

    function dispose() {
        subscription?.close();
        subscription = undefined;
    }

    return {
        state,
        events,
        roles,
        load,
        dispose,
        mergeEvent,
        refreshHumanData,
        refreshNotifications,
        refreshReports,
        selectEvidence
    };
}
