import { describe, expect, it } from 'vitest';
import { createReviewSseSubscription } from './review-sse';

class FakeEventSource {
    static instances = [];

    constructor(url) {
        this.url = url;
        this.listeners = new Map();
        FakeEventSource.instances.push(this);
    }

    addEventListener(type, callback) { this.listeners.set(type, callback); }
    close() { this.closed = true; }
    emit(type, value) { this.listeners.get(type)?.({ data: JSON.stringify(value) }); }
}

describe('review SSE subscription', () => {
    it('reconnects with the greatest accepted sequence and ignores malformed payloads', () => {
        const scheduled = [];
        const states = [];
        const seen = [];
        const subscription = createReviewSseSubscription({
            reviewId: '11111111-1111-1111-1111-111111111111',
            afterSequence: 5,
            EventSourceImpl: FakeEventSource,
            setTimeoutImpl: (callback, delay) => { scheduled.push({ callback, delay }); return scheduled.length; },
            clearTimeoutImpl: () => {},
            onEvent: (event) => seen.push(event.sequence),
            onState: (state) => states.push(state.status)
        });
        const first = FakeEventSource.instances[0];
        first.emit('PLAN_CREATED', { reviewId: '11111111-1111-1111-1111-111111111111', sequence: 8 });
        first.emit('CONTEXT_SCOUT_DEGRADED', {
            reviewId: '11111111-1111-1111-1111-111111111111',
            sequence: 9,
            type: 'CONTEXT_SCOUT_DEGRADED'
        });
        first.emit('PLAN_CREATED', { reviewId: 'wrong', sequence: 9 });
        first.onerror();
        scheduled[0].callback();

        expect(seen).toEqual([8, 9]);
        expect(scheduled[0].delay).toBe(1000);
        expect(FakeEventSource.instances[1].url).toContain('afterSequence=9');
        expect(states).toContain('reconnecting');
        subscription.close();
    });
});
