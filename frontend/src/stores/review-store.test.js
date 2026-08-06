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
