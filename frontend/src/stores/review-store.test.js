import { describe, expect, it } from 'vitest';
import fixture from '../test/events-golden.json';
import { createReviewStore } from './review-store';

function createApi() {
    return {
        getSummary: async () => ({ reviewId: fixture.reviewId, reviewVersion: 4, lastSequence: 3, stage: 'DEBATE_ROUND_1', progress: 52 }),
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
});
