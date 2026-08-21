import { computed, reactive } from 'vue';
import { EventType } from '@ag-ui/core';
import { ReviewApiError, parseAssessmentsView, reviewApi } from '../api/review-api';
import {
    applyAgUiEvent,
    createAgUiConversation,
    reviewEventFromAgUiEvent,
    reviewEventToAgUiEvents
} from '../services/ag-ui-review-adapter';
import { createReviewSseSubscription } from '../services/review-sse';

const STORAGE_PREFIX = 'chongming.review.last-sequence.';

const DEBATE_AFFECTING_EVENTS = new Set([
    'CLAIM_SUBMITTED', 'POSITION_CHANGED', 'DEBATE_TOPIC_OPENED', 'DEBATE_ROUND_2_STARTED',
    'DEBATE_TOPIC_CLOSED', 'DEBATE_SKIPPED', 'JUDGING_STARTED',
    'CHALLENGE_SUBMITTED', 'REBUTTAL_SUBMITTED', 'EVIDENCE_CAPTURED', 'JUDGEMENT_SUBMITTED'
]);
const SUMMARY_AFFECTING_EVENTS = new Set([
    'GATE_DRAFTED', 'HUMAN_GATE_FINALIZED', 'REVIEW_FAILED', 'REVIEW_CANCELLED',
    'REVIEW_RETRIED', 'REVIEW_RECOVERED',
    // The structured scout conclusion is persisted when the scout stream ends; without a live
    // summary refresh the conclusion panel stays on "waiting" until a manual page reload.
    'CONTEXT_SCOUT_COMPLETED'
]);
// [AIREVIEW-PLAN-024#方案5] Assessments are persisted server-side as roles complete their
// checkpoint coverage; those facts and attempt resets invalidate the five-status projection.
const ASSESSMENT_AFFECTING_EVENTS = new Set([
    'ROLE_COMPLETED', 'INITIAL_REVIEW_COMPLETED', 'REVIEW_RETRIED'
]);
// [AIREVIEW-PLAN-011#1.7] Outbox delivery facts change the notification panel; without a live
// refresh the SENDING/DEAD badges stay stale until a manual page reload.
const NOTIFICATION_AFFECTING_EVENTS = new Set([
    'NOTIFICATION_QUEUED', 'NOTIFICATION_SENT', 'NOTIFICATION_FAILED', 'NOTIFICATION_DEAD',
    'NOTIFICATION_RETRY_REQUESTED'
]);

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
        claims: [],
        humanItems: [],
        humanGateVersions: [],
        assessments: null,
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
    const eventsBySequence = reactive(new Map());
    let subscription;

    const events = computed(() => [...eventsBySequence.values()].sort((left, right) => left.sequence - right.sequence));
    const roles = computed(() => {
        const byRole = new Map();
        events.value.filter((event) => event.actorRole && event.category === 'ROLE').forEach((event) => {
            byRole.set(event.actorRole, event);
        });
        return [...byRole.entries()].map(([role, event]) => ({ role, type: event.type, occurredAt: event.occurredAt }));
    });

    // [AIREVIEW-PLAN-024#方案5] Five-status projection and coverage derived from the server
    // payload; counters are never recomputed client-side so they always match the backend.
    const assessmentView = computed(() => parseAssessmentsView(state.assessments));
    const assessmentCoverage = computed(() => assessmentView.value.coverage);
    const assessmentBreakdown = computed(() => ({
        notExecuted: assessmentCoverage.value.uncoveredCheckpoints.length,
        executedUnknown: assessmentCoverage.value.unknown,
        confirmed: assessmentCoverage.value.confirmed,
        gap: assessmentCoverage.value.gap
    }));
    const roleAssessmentProgress = computed(() => {
        const emptyStatuses = () => ({ CONFIRMED: 0, PARTIAL: 0, GAP: 0, UNKNOWN: 0, NOT_APPLICABLE: 0 });
        const byRole = new Map();
        const bucketFor = (role) => {
            if (!byRole.has(role)) {
                byRole.set(role, { role, submitted: 0, uncovered: 0, statuses: emptyStatuses() });
            }
            return byRole.get(role);
        };
        assessmentView.value.assessments.forEach((entry) => {
            const bucket = bucketFor(entry.role ?? 'UNKNOWN');
            bucket.submitted += 1;
            if (Object.prototype.hasOwnProperty.call(bucket.statuses, entry.status)) {
                bucket.statuses[entry.status] += 1;
            }
        });
        assessmentCoverage.value.uncoveredCheckpoints.forEach((slot) => {
            bucketFor(String(slot).split(':')[0] ?? 'UNKNOWN').uncovered += 1;
        });
        return [...byRole.values()]
            .sort((left, right) => left.role.localeCompare(right.role))
            .map((bucket) => ({ ...bucket, total: bucket.submitted + bucket.uncovered }));
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
        state.claims = [];
        state.humanItems = [];
        state.humanGateVersions = [];
        state.assessments = null;
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
            if (state.summary == null) {
                // The initial snapshot can 404 when the review just started before its first
                // domain event was persisted. Hydrate the summary from the live event stream so
                // the header stage and activated roles stay live without a manual refresh.
                refreshSummary().catch(() => {});
            }
            reviewEventToAgUiEvents(domainEvent).forEach((agUiEvent) => applyAgUiEvent(state.agUi, agUiEvent));
            if (domainEvent.type === 'REVIEW_FAILED') {
                applyAgUiEvent(state.agUi, {
                    type: EventType.RUN_ERROR,
                    message: domainEvent.payload?.publicSummary ?? '评审运行失败。',
                    code: domainEvent.type
                });
            }
            if (domainEvent.type === 'CONTEXT_SCOUT_DEGRADED') {
                const eventAttempt = domainEvent.attemptNo ?? domainEvent.attempt;
                if (Number(eventAttempt) === Number(state.summary?.attempt)) {
                    state.summary = {
                        ...state.summary,
                        contextScout: {
                            status: domainEvent.payload?.status ?? 'DEGRADED',
                            reasonCode: domainEvent.payload?.reasonCode ?? 'CONTEXT_SCOUT_UNAVAILABLE',
                            publicSummary: domainEvent.payload?.publicSummary
                                ?? 'Context Scout 未能完成项目上下文预处理，Director 将继续评审。',
                            occurredAt: domainEvent.occurredAt
                        }
                    };
                }
            }
            if (domainEvent.type === 'REVIEW_CANCELLED') {
                applyAgUiEvent(state.agUi, { type: EventType.RUN_FINISHED, threadId: state.agUi.threadId, runId: state.agUi.runId });
            }
            if (domainEvent.type === 'ROLE_COMPLETED' && domainEvent.actorRole && state.summary) {
                const entries = state.summary.activatedRoles ?? [];
                const roleEntry = entries.find((entry) => entry.role === domainEvent.actorRole);
                if (!roleEntry) {
                    // The summary snapshot can predate this role's activation; record the completion
                    // directly so the N/4 role counter stays live without a manual refresh.
                    state.summary = {
                        ...state.summary,
                        activatedRoles: [...entries,
                            { role: domainEvent.actorRole, agentLabel: null, initialReviewCompleted: true }]
                    };
                } else if (!roleEntry.initialReviewCompleted) {
                    state.summary = {
                        ...state.summary,
                        activatedRoles: entries.map((entry) => entry.role === domainEvent.actorRole
                            ? { ...entry, initialReviewCompleted: true }
                            : entry)
                    };
                }
            }
            applyStageFromEvent(domainEvent);
            if (DEBATE_AFFECTING_EVENTS.has(domainEvent.type)) {
                refreshDebates().catch(() => {});
            }
            if (domainEvent.type === 'PLAN_CREATED' || domainEvent.type === 'PLAN_REVISED') {
                refreshPlans().catch(() => {});
            }
            if (SUMMARY_AFFECTING_EVENTS.has(domainEvent.type)) {
                refreshSummary().catch(() => {});
            }
            if (domainEvent.type === 'HUMAN_GATE_FINALIZED') {
                // [AIREVIEW-PLAN-023#6.3] Another reviewer can finalize the Gate while this page is open.
                // Refresh the versioned human result independently so summary.gate is never presented alone.
                refreshHumanGateVersions().catch(() => {});
            }
            if (domainEvent.type === 'CLAIM_SUBMITTED' || domainEvent.type === 'POSITION_CHANGED') {
                refreshClaims().catch(() => {});
            }
            if (ASSESSMENT_AFFECTING_EVENTS.has(domainEvent.type)) {
                refreshAssessments().catch(() => {});
            }
            if (NOTIFICATION_AFFECTING_EVENTS.has(domainEvent.type)) {
                refreshNotifications().catch(() => {});
            }
            if (domainEvent.type === 'NOTIFICATION_SENT') {
                // Delivery completes the review (NOTIFYING -> COMPLETED); keep the header banner
                // live alongside the notification panel.
                refreshSummary().catch(() => {});
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

    async function refreshHumanGateVersions() {
        state.humanGateVersions = await api.getHumanGateVersions(state.reviewId);
    }

    async function refreshClaims() {
        const claims = await optional(api.getClaims(state.reviewId), []);
        state.claims = Array.isArray(claims) ? claims : [];
    }

    async function refreshAssessments() {
        state.assessments = await optional(api.getAssessments(state.reviewId), null);
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

    async function refreshSummary() {
        const summary = await optional(api.getSummary(state.reviewId), null);
        if (summary) state.summary = summary;
        return summary;
    }

    async function refreshDebates() {
        state.debates = await optional(api.getDebates(state.reviewId), []);
    }

    async function refreshPlans() {
        state.plans = await optional(api.getPlans(state.reviewId), []);
    }

    /**
     * [AIREVIEW-PLAN-020#4.2] Keeps the header stage/progress live from the domain event stream
     * instead of only the initial summary snapshot, and ignores replayed events from an earlier
     * attempt so a retried review cannot paint stale phase state.
     */
    function applyStageFromEvent(domainEvent) {
        if (!state.summary || typeof domainEvent.stage !== 'string') return;
        const eventAttempt = Number(domainEvent.attemptNo ?? domainEvent.attempt ?? state.summary.attempt);
        const currentAttempt = Number(state.summary.attempt);
        if (Number.isFinite(eventAttempt) && Number.isFinite(currentAttempt) && eventAttempt !== currentAttempt) return;
        const progress = domainEvent.progress ?? state.summary.progress;
        if (domainEvent.stage === state.summary.stage && progress === state.summary.progress) return;
        state.summary = { ...state.summary, stage: domainEvent.stage, progress };
    }

    function applyLifecycleResult(result, stage) {
        state.summary = {
            ...(state.summary ?? { reviewId: state.reviewId, lastSequence: state.lastSequence }),
            attempt: result.attemptNo,
            reviewVersion: result.version,
            stage,
            progress: stage === 'CANCELLED' ? 100 : (state.summary?.progress ?? 0)
        };
    }

    async function startReview(command) {
        const result = await api.startReview(state.reviewId, command);
        applyLifecycleResult(result, result.stage);
        return result;
    }

    async function cancelReview(expectedVersion) {
        const result = await api.cancelReview(state.reviewId, expectedVersion);
        applyLifecycleResult(result, 'CANCELLED');
        return result;
    }

    async function retryReview(expectedVersion) {
        const result = await api.retryReview(state.reviewId, expectedVersion);
        applyLifecycleResult(result, 'PENDING');
        if (state.summary) {
            const nextSummary = { ...state.summary };
            delete nextSummary.contextScout;
            state.summary = nextSummary;
        }
        return result;
    }

    async function load(reviewId) {
        subscription?.close();
        reset(reviewId);
        state.loading = true;
        try {
            const [summary, plans, debates, claims, assessments] = await Promise.all([
                optional(api.getSummary(reviewId), null), api.getPlans(reviewId), api.getDebates(reviewId),
                optional(api.getClaims(reviewId), []), optional(api.getAssessments(reviewId), null)
            ]);
            state.summary = summary;
            applyAgUiEvent(state.agUi, {
                type: EventType.RUN_STARTED,
                threadId: `review:${reviewId}`,
                runId: `review-${reviewId}-attempt-${summary?.attempt ?? 1}`
            });
            state.plans = plans;
            state.debates = debates;
            state.claims = Array.isArray(claims) ? claims : [];
            state.assessments = assessments;
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
        assessmentView,
        assessmentCoverage,
        assessmentBreakdown,
        roleAssessmentProgress,
        load,
        dispose,
        mergeEvent,
        startReview,
        cancelReview,
        retryReview,
        refreshSummary,
        refreshHumanData,
        refreshClaims,
        refreshAssessments,
        refreshNotifications,
        refreshReports,
        selectEvidence
    };
}
