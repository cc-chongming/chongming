import { afterEach, describe, expect, it } from 'vitest';
import { createAgUiRuntimeSubscription } from './ag-ui-runtime-sse';

class FakeEventSource {
    static instances = [];

    constructor(url) {
        this.url = url;
        FakeEventSource.instances.push(this);
    }

    close() { this.closed = true; }
    emit(value, lastEventId) { this.onmessage?.({ data: JSON.stringify(value), lastEventId }); }
}

const originalLocalStorage = globalThis.localStorage;

function makeJwt(payload) {
    const encode = (value) => Buffer.from(JSON.stringify(value)).toString('base64url');
    return `${encode({ alg: 'none', typ: 'JWT' })}.${encode(payload)}.test-signature`;
}

function installLocalStorage() {
    const backing = new Map();
    globalThis.localStorage = {
        getItem: (key) => (backing.has(key) ? backing.get(key) : null),
        setItem: (key, value) => backing.set(key, String(value)),
        removeItem: (key) => backing.delete(key)
    };
    return backing;
}

afterEach(() => {
    if (originalLocalStorage === undefined) delete globalThis.localStorage;
    else globalThis.localStorage = originalLocalStorage;
    FakeEventSource.instances.length = 0;
});

describe('AG-UI runtime subscription', () => {
    it('subscribes to one concrete review attempt and forwards valid AG-UI events', () => {
        const received = [];
        const subscription = createAgUiRuntimeSubscription({
            reviewId: '11111111-1111-1111-1111-111111111111',
            attemptNo: 2,
            EventSourceImpl: FakeEventSource,
            onEvent: (event) => received.push(event)
        });

        const source = FakeEventSource.instances.at(-1);
        source.emit({ type: 'RUN_STARTED', runId: 'review:2:director' });
        source.emit({ unexpected: true });

        expect(source.url).toBe('/api/reviews/11111111-1111-1111-1111-111111111111/attempts/2/runtime/ag-ui?afterSequence=0');
        expect(received).toEqual([{ type: 'RUN_STARTED', runId: 'review:2:director' }]);
        subscription.close();
        expect(source.closed).toBe(true);
    });

    // [AIREVIEW-PLAN-091#4]
    it('forwards a supplied afterSequence cursor into the stream URL', () => {
        const subscription = createAgUiRuntimeSubscription({
            reviewId: '33333333-3333-3333-3333-333333333333',
            attemptNo: 1,
            afterSequence: 123,
            EventSourceImpl: FakeEventSource,
            onEvent: () => {}
        });

        expect(FakeEventSource.instances.at(-1).url)
            .toBe('/api/reviews/33333333-3333-3333-3333-333333333333/attempts/1/runtime/ag-ui?afterSequence=123');
        subscription.close();
    });

    // [AIREVIEW-PLAN-091#4]
    it('reconnects with afterSequence advanced from the latest lastEventId', () => {
        const scheduled = [];
        const subscription = createAgUiRuntimeSubscription({
            reviewId: 'r2',
            attemptNo: 1,
            EventSourceImpl: FakeEventSource,
            setTimeoutImpl: (callback) => { scheduled.push(callback); return scheduled.length; },
            clearTimeoutImpl: () => {},
            onEvent: () => {}
        });

        const source = FakeEventSource.instances[0];
        source.emit({ type: 'RUN_STARTED', runId: 'review:1:director' }, '200');
        source.onerror();
        expect(source.closed).toBe(true);
        expect(scheduled).toHaveLength(1);
        scheduled[0]();

        expect(FakeEventSource.instances[1].url)
            .toBe('/api/reviews/r2/attempts/1/runtime/ag-ui?afterSequence=200');
        subscription.close();
    });

    it('reconnects with the freshest stored token instead of the one frozen in the URL', () => {
        const backing = installLocalStorage();
        const firstToken = makeJwt({ sub: 'alice', exp: 4102444800 });
        backing.set('chongming-auth', JSON.stringify({ token: firstToken, user: null }));
        const scheduled = [];
        const subscription = createAgUiRuntimeSubscription({
            reviewId: 'r1',
            attemptNo: 2,
            EventSourceImpl: FakeEventSource,
            setTimeoutImpl: (callback, delay) => { scheduled.push({ callback, delay }); return scheduled.length; },
            clearTimeoutImpl: () => {},
            onEvent: () => {}
        });
        const first = FakeEventSource.instances[0];
        expect(first.url).toContain(`access_token=${encodeURIComponent(firstToken)}`);

        const refreshedToken = makeJwt({ sub: 'alice', exp: 4102444900 });
        backing.set('chongming-auth', JSON.stringify({ token: refreshedToken, user: null }));
        first.onerror();
        expect(first.closed).toBe(true);
        expect(scheduled[0].delay).toBe(1000);
        scheduled[0].callback();

        expect(FakeEventSource.instances[1].url).toContain(`access_token=${encodeURIComponent(refreshedToken)}`);
        subscription.close();
    });

    it('stops reconnecting when the stored token is already expired', () => {
        const backing = installLocalStorage();
        backing.set('chongming-auth', JSON.stringify({ token: makeJwt({ sub: 'alice', exp: 1000 }), user: null }));
        const states = [];
        const subscription = createAgUiRuntimeSubscription({
            reviewId: 'r1',
            attemptNo: 2,
            EventSourceImpl: FakeEventSource,
            setTimeoutImpl: () => { throw new Error('expired token must not schedule retries'); },
            clearTimeoutImpl: () => {},
            onEvent: () => {},
            onState: ({ status }) => states.push(status)
        });
        const source = FakeEventSource.instances[0];

        source.onerror();

        expect(source.closed).toBe(true);
        expect(states).toContain('auth-expired');
        expect(FakeEventSource.instances).toHaveLength(1);
        subscription.close();
    });
});
