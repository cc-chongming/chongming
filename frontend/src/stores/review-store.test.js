import { describe, expect, it, vi } from 'vitest';
import fixture from '../test/events-golden.json';
import { createReviewStore } from './review-store';

function createApi() {
    return {
        getSummary: async () => ({ reviewId: fixture.reviewId, attempt: 1, reviewVersion: 4, lastSequence: 3, stage: 'DEBATE_ROUND_1', progress: 52 }),
        getPlans: async () => [fixture.events[0]],
        getDebates: async () => [],
        getClaims: async () => [],
        getHumanItems: async () => [],
        getHumanGateVersions: async () => [],
        getAssessments: async () => null,
        getReport: async () => null,
        getReportVersions: async () => [],
        getNotifications: async () => []
    };
}

class FakeEventSource {
    static instances = [];

    constructor(url) {
        this.url = url;
        this.listeners = new Map();
        FakeEventSource.instances.push(this);
    }

    addEventListener(type, callback) {
        this.listeners.set(type, callback);
    }

    close() {
        this.closed = true;
    }

    emit(type, event) {
        this.listeners.get(type)?.({ data: JSON.stringify(event) });
    }
}

describe('review store', () => {
    it('merges replay and live events strictly by reviewId plus sequence', async () => {
        const storage = new Map();
        const store = createReviewStore({
            api: createApi(),
            EventSourceImpl: FakeEventSource,
            storage: { getItem: (key) => storage.get(key), setItem: (key, value) => storage.set(key, value) }
        });

        await store.load(fixture.reviewId);
        expect(FakeEventSource.instances.at(-1).url).toContain('afterSequence=0');
        store.mergeEvent(fixture.events[2]);
        store.mergeEvent(fixture.events[0]);
        store.mergeEvent(fixture.events[2]);
        store.mergeEvent({ ...fixture.events[1], reviewId: 'different-review' });

        expect(store.events.value.map((event) => event.sequence)).toEqual([1, 3]);
        expect(store.state.lastSequence).toBe(3);
        expect(storage.get(`chongming.review.last-sequence.${fixture.reviewId}`)).toBe('3');
    });

    it('keeps a persisted Context Scout degradation visible after the live event arrives', async () => {
        const store = createReviewStore({ api: createApi(), EventSourceImpl: FakeEventSource });

        await store.load(fixture.reviewId);
        store.mergeEvent({
            reviewId: fixture.reviewId,
            sequence: 4,
            attemptNo: 1,
            type: 'CONTEXT_SCOUT_DEGRADED',
            category: 'ERROR',
            occurredAt: '2026-07-29 18:00:00',
            payload: {
                status: 'DEGRADED',
                reasonCode: 'MODEL_CALL_TIMEOUT',
                publicSummary: 'Context Scout 模型调用超时，已跳过项目上下文预处理，Director 将继续评审。'
            }
        });

        expect(store.state.summary.contextScout).toEqual({
            status: 'DEGRADED',
            reasonCode: 'MODEL_CALL_TIMEOUT',
            publicSummary: 'Context Scout 模型调用超时，已跳过项目上下文预处理，Director 将继续评审。',
            occurredAt: '2026-07-29 18:00:00'
        });
    });

    it('refreshes the summary live when the scout conclusion completes', async () => {
        let summaryReads = 0;
        const api = {
            ...createApi(),
            getSummary: async () => {
                summaryReads += 1;
                if (summaryReads === 1) {
                    return { reviewId: fixture.reviewId, attempt: 1, reviewVersion: 4, lastSequence: 3, stage: 'PLANNING', progress: 10 };
                }
                return {
                    reviewId: fixture.reviewId, attempt: 1, reviewVersion: 4, lastSequence: 4, stage: 'PLANNING', progress: 10,
                    contextScout: { status: 'COMPLETED', conclusion: { summary: '结构化结论已生成。' } }
                };
            }
        };
        const store = createReviewStore({ api, EventSourceImpl: FakeEventSource });
        await store.load(fixture.reviewId);
        expect(store.state.summary.contextScout).toBeUndefined();

        store.mergeEvent({
            reviewId: fixture.reviewId, sequence: 4, attemptNo: 1, type: 'CONTEXT_SCOUT_COMPLETED',
            category: 'INFO', occurredAt: '2026-08-19 15:31:00', payload: { status: 'COMPLETED' }
        });

        await vi.waitFor(() => expect(store.state.summary.contextScout?.status).toBe('COMPLETED'));
        expect(store.state.summary.contextScout?.conclusion?.summary).toBe('结构化结论已生成。');
    });

    it('refreshes the notification panel live when delivery events arrive', async () => {
        let notificationReads = 0;
        const api = {
            ...createApi(),
            getNotifications: async () => {
                notificationReads += 1;
                if (notificationReads === 1) return [];
                return [{ notificationId: 'n-1', deliveryStatus: 'SENT', command: { channel: 'smtp-mail', gateVersion: 1 } }];
            }
        };
        const store = createReviewStore({ api, EventSourceImpl: FakeEventSource });
        await store.load(fixture.reviewId);
        expect(store.state.notifications).toEqual([]);

        store.mergeEvent({
            reviewId: fixture.reviewId, sequence: 4, attemptNo: 1, type: 'NOTIFICATION_SENT',
            category: 'NOTIFICATION', stage: 'COMPLETED', progress: 100,
            occurredAt: '2026-08-21 18:00:00', payload: {}
        });

        await vi.waitFor(() => expect(store.state.notifications).toHaveLength(1));
        expect(store.state.notifications[0].deliveryStatus).toBe('SENT');
    });

    it('ignores a replayed Scout degradation from an earlier attempt', async () => {
        const api = {
            ...createApi(),
            getSummary: async () => ({
                reviewId: fixture.reviewId,
                attempt: 2,
                reviewVersion: 5,
                lastSequence: 8,
                stage: 'PLANNING',
                progress: 20
            })
        };
        const store = createReviewStore({ api, EventSourceImpl: FakeEventSource });
        await store.load(fixture.reviewId);

        store.mergeEvent({
            reviewId: fixture.reviewId,
            sequence: 4,
            attemptNo: 1,
            type: 'CONTEXT_SCOUT_DEGRADED',
            category: 'ERROR',
            occurredAt: '2026-07-29 18:00:00',
            payload: { status: 'DEGRADED', reasonCode: 'MODEL_CALL_TIMEOUT', publicSummary: '旧尝试' }
        });

        expect(store.state.summary).not.toHaveProperty('contextScout');
    });

    it('clears a previous attempt Context Scout degradation when retry starts a fresh attempt', async () => {
        const api = {
            ...createApi(),
            retryReview: async () => ({ attemptNo: 2, version: 5 })
        };
        const store = createReviewStore({ api, EventSourceImpl: FakeEventSource });
        await store.load(fixture.reviewId);
        store.state.summary.contextScout = {
            status: 'DEGRADED',
            reasonCode: 'MODEL_CALL_TIMEOUT',
            publicSummary: '旧尝试的 Scout 降级。'
        };

        await store.retryReview(4);

        expect(store.state.summary.attempt).toBe(2);
        expect(store.state.summary).not.toHaveProperty('contextScout');
    });

    it('updates the facts timeline reactively as live events arrive', async () => {
        const store = createReviewStore({ api: createApi(), EventSourceImpl: FakeEventSource });
        await store.load(fixture.reviewId);

        // Read once before any event, exactly as the live page's sidebar does on first render, so
        // the computed must invalidate when the plain events map mutates (reactive Map regression).
        expect(store.events.value).toEqual([]);

        store.mergeEvent({
            reviewId: fixture.reviewId, sequence: 4, attemptNo: 1, type: 'ROLE_ACTIVATED', category: 'ROLE',
            stage: 'INITIAL_REVIEW', progress: 40, actorRole: 'PRODUCT',
            occurredAt: '2026-08-05 11:00:00', payload: {}
        });

        await vi.waitFor(() => expect(store.events.value.map((event) => event.sequence)).toEqual([4]));
    });

    it('marks the role complete and refreshes persisted claims as live review facts arrive', async () => {
        let claimsReads = 0;
        const claim = { claimId: '30000000-0000-0000-0000-000000000001', role: 'FRONTEND', subjectKey: '增量展示可行', severity: 'P2', position: 'SUPPORT', statement: '前端已有 DiffViewer。', reasonSummary: '组件成熟。', status: 'SUBMITTED', evidenceIds: [] };
        const api = {
            ...createApi(),
            getSummary: async () => ({
                reviewId: fixture.reviewId, attempt: 1, reviewVersion: 4, lastSequence: 3, stage: 'INITIAL_REVIEW', progress: 40,
                activatedRoles: [{ role: 'FRONTEND', agentLabel: 'frontend-reviewer', initialReviewCompleted: false }]
            }),
            getClaims: async () => {
                claimsReads += 1;
                return claimsReads === 1 ? [] : [claim];
            }
        };
        const store = createReviewStore({ api, EventSourceImpl: FakeEventSource });
        await store.load(fixture.reviewId);

        store.mergeEvent({
            reviewId: fixture.reviewId, sequence: 4, attemptNo: 1, type: 'CLAIM_SUBMITTED', category: 'CLAIM',
            actorRole: 'FRONTEND', occurredAt: '2026-08-05 11:00:00', payload: {}
        });
        await vi.waitFor(() => expect(store.state.claims).toEqual([claim]));

        store.mergeEvent({
            reviewId: fixture.reviewId, sequence: 5, attemptNo: 1, type: 'ROLE_COMPLETED', category: 'ROLE',
            actorRole: 'FRONTEND', occurredAt: '2026-08-05 11:05:00', payload: { summary: '初审完成。' }
        });

        expect(store.state.summary.activatedRoles).toEqual([
            { role: 'FRONTEND', agentLabel: 'frontend-reviewer', initialReviewCompleted: true }
        ]);
    });

    it('keeps the header stage live from the domain event stream', async () => {
        const store = createReviewStore({ api: createApi(), EventSourceImpl: FakeEventSource });
        await store.load(fixture.reviewId);
        expect(store.state.summary.stage).toBe('DEBATE_ROUND_1');

        store.mergeEvent({
            reviewId: fixture.reviewId, sequence: 4, attemptNo: 1, type: 'ROLE_STARTED', category: 'ROLE',
            stage: 'INITIAL_REVIEW', progress: 40, actorRole: 'PRODUCT',
            occurredAt: '2026-08-05 11:00:00', payload: {}
        });

        expect(store.state.summary.stage).toBe('INITIAL_REVIEW');
        expect(store.state.summary.progress).toBe(40);
    });

    it('refreshes human Gate versions when another reviewer finalizes the Gate', async () => {
        let gateReads = 0;
        const finalGate = {
            gateVersion: 1,
            result: 'BLOCK',
            reason: '人工复核发现高风险问题未关闭。',
            decidedAt: '2026-08-10T10:00:00Z'
        };
        const api = {
            ...createApi(),
            getHumanGateVersions: async () => {
                gateReads += 1;
                return gateReads === 1 ? [] : [finalGate];
            }
        };
        const store = createReviewStore({ api, EventSourceImpl: FakeEventSource });
        await store.load(fixture.reviewId);

        store.mergeEvent({
            reviewId: fixture.reviewId,
            sequence: 4,
            attemptNo: 1,
            type: 'HUMAN_GATE_FINALIZED',
            category: 'GATE',
            stage: 'NOTIFYING',
            occurredAt: '2026-08-10T10:00:00Z',
            payload: { result: 'BLOCK' }
        });

        await vi.waitFor(() => expect(store.state.humanGateVersions).toEqual([finalGate]));
        expect(gateReads).toBe(2);
    });

    it('refreshes debates and plans when projection-relevant events arrive', async () => {
        let debatesReads = 0;
        let plansReads = 0;
        const topic = { topicId: '50000000-0000-0000-0000-000000000001', subjectKey: '主题', currentRound: 1, claims: [], turns: [] };
        const api = {
            ...createApi(),
            getDebates: async () => {
                debatesReads += 1;
                return debatesReads === 1 ? [] : [topic];
            },
            getPlans: async () => {
                plansReads += 1;
                return plansReads === 1 ? [fixture.events[0]] : [fixture.events[0], fixture.events[1]];
            }
        };
        const store = createReviewStore({ api, EventSourceImpl: FakeEventSource });
        await store.load(fixture.reviewId);

        store.mergeEvent({
            reviewId: fixture.reviewId, sequence: 4, attemptNo: 1, type: 'DEBATE_TOPIC_OPENED', category: 'DEBATE',
            occurredAt: '2026-08-05 11:00:00', payload: {}
        });
        await vi.waitFor(() => expect(store.state.debates).toEqual([topic]));

        store.mergeEvent({
            reviewId: fixture.reviewId, sequence: 5, attemptNo: 1, type: 'PLAN_REVISED', category: 'PLAN',
            occurredAt: '2026-08-05 11:05:00', payload: {}
        });
        await vi.waitFor(() => expect(store.state.plans).toHaveLength(2));
    });

    it('hydrates a missing summary from the live event stream', async () => {
        let summaryReads = 0;
        const api = {
            ...createApi(),
            getSummary: async () => {
                summaryReads += 1;
                if (summaryReads === 1) return null; // initial snapshot 404 (review just started)
                return {
                    reviewId: fixture.reviewId, attempt: 1, reviewVersion: 4, lastSequence: 3,
                    stage: 'INITIAL_REVIEW', progress: 40,
                    activatedRoles: [{ role: 'PRODUCT', agentLabel: 'product-reviewer', initialReviewCompleted: false }]
                };
            }
        };
        const store = createReviewStore({ api, EventSourceImpl: FakeEventSource });
        await store.load(fixture.reviewId);
        expect(store.state.summary).toBeNull();

        store.mergeEvent({
            reviewId: fixture.reviewId, sequence: 1, attemptNo: 1, type: 'ROLE_ACTIVATED', category: 'ROLE',
            stage: 'INITIAL_REVIEW', progress: 40, actorRole: 'PRODUCT',
            occurredAt: '2026-08-05 10:00:00', payload: {}
        });

        await vi.waitFor(() => expect(store.state.summary).not.toBeNull());
        expect(store.state.summary.stage).toBe('INITIAL_REVIEW');
        expect(store.state.summary.activatedRoles).toHaveLength(1);
    });

    it('derives role coverage progress and five-status breakdown from the assessments projection', async () => {
        const api = {
            ...createApi(),
            getAssessments: async () => ({
                attempt: 1,
                coverage: {
                    required: 20, covered: 17, confirmed: 9, partial: 2, gap: 1, unknown: 4, notApplicable: 1,
                    uncoveredCheckpoints: ['BACKEND:error_codes', 'BACKEND:idempotency', 'FRONTEND:ui_state_handling']
                },
                assessments: [
                    { role: 'FRONTEND', checkpointKey: 'build_safety', status: 'UNKNOWN', summary: '未授权', reasonSummary: '', evidenceIds: [], createdAt: '2026-08-11T10:00:00Z' },
                    { role: 'BACKEND', checkpointKey: 'api_contract', status: 'CONFIRMED', summary: '已确认', reasonSummary: '', evidenceIds: [], createdAt: '2026-08-11T10:01:00Z' },
                    { role: 'BACKEND', checkpointKey: 'persistence', status: 'GAP', summary: '有缺口', reasonSummary: '', evidenceIds: [], createdAt: '2026-08-11T10:02:00Z' }
                ]
            })
        };
        const store = createReviewStore({ api, EventSourceImpl: FakeEventSource });

        await store.load(fixture.reviewId);

        expect(store.assessmentCoverage.value.required).toBe(20);
        expect(store.assessmentCoverage.value.covered).toBe(17);
        expect(store.assessmentBreakdown.value).toEqual({ notExecuted: 3, executedUnknown: 4, confirmed: 9, gap: 1 });
        expect(store.roleAssessmentProgress.value).toEqual([
            { role: 'BACKEND', submitted: 2, uncovered: 2, statuses: { CONFIRMED: 1, PARTIAL: 0, GAP: 1, UNKNOWN: 0, NOT_APPLICABLE: 0 }, total: 4 },
            { role: 'FRONTEND', submitted: 1, uncovered: 1, statuses: { CONFIRMED: 0, PARTIAL: 0, GAP: 0, UNKNOWN: 1, NOT_APPLICABLE: 0 }, total: 2 }
        ]);
    });

    it('refreshes the five-status projection when a role completes its initial review', async () => {
        let assessmentReads = 0;
        const api = {
            ...createApi(),
            getAssessments: async () => {
                assessmentReads += 1;
                if (assessmentReads === 1) return null;
                return {
                    attempt: 1,
                    coverage: { required: 5, covered: 5, confirmed: 5, partial: 0, gap: 0, unknown: 0, notApplicable: 0, uncoveredCheckpoints: [] },
                    assessments: [
                        { role: 'PRODUCT', checkpointKey: 'scope_clarity', status: 'CONFIRMED', summary: '已确认', reasonSummary: '', evidenceIds: [], createdAt: '2026-08-11T10:00:00Z' }
                    ]
                };
            }
        };
        const store = createReviewStore({ api, EventSourceImpl: FakeEventSource });
        await store.load(fixture.reviewId);
        expect(store.assessmentView.value.attempt).toBeNull();

        store.mergeEvent({
            reviewId: fixture.reviewId, sequence: 4, attemptNo: 1, type: 'ROLE_COMPLETED', category: 'ROLE',
            actorRole: 'PRODUCT', occurredAt: '2026-08-11T10:00:00Z', payload: { summary: '初审完成。' }
        });

        await vi.waitFor(() => expect(store.assessmentView.value.attempt).toBe(1));
        expect(assessmentReads).toBe(2);
        expect(store.assessmentCoverage.value.confirmed).toBe(5);
        expect(store.assessmentBreakdown.value).toEqual({ notExecuted: 0, executedUnknown: 0, confirmed: 5, gap: 0 });
    });

    it('ignores replayed stage events from an earlier attempt', async () => {
        const api = {
            ...createApi(),
            getSummary: async () => ({
                reviewId: fixture.reviewId, attempt: 2, reviewVersion: 5, lastSequence: 8,
                stage: 'PLANNING', progress: 20
            })
        };
        const store = createReviewStore({ api, EventSourceImpl: FakeEventSource });
        await store.load(fixture.reviewId);

        store.mergeEvent({
            reviewId: fixture.reviewId, sequence: 9, attemptNo: 1, type: 'ROLE_COMPLETED', category: 'ROLE',
            stage: 'CONFLICT_DETECTION', progress: 50, actorRole: 'PRODUCT',
            occurredAt: '2026-08-05 10:00:00', payload: {}
        });

        expect(store.state.summary.stage).toBe('PLANNING');
    });
});
