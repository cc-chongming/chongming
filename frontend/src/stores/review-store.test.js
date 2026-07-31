import { describe, expect, it } from 'vitest';
import fixture from '../test/events-golden.json';
import { createReviewStore } from './review-store';

function createApi() {
    return {
        getSummary: async () => ({ reviewId: fixture.reviewId, attempt: 1, reviewVersion: 4, lastSequence: 3, stage: 'DEBATE_ROUND_1', progress: 52 }),
        getPlans: async () => [fixture.events[0]],
        getDebates: async () => [],
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
});
