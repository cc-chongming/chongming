import { afterEach, describe, expect, it } from 'vitest';
import { createScoutPreviewSubscription } from './scout-preview-sse';

class FakeEventSource {
    static instances = [];

    constructor(url) {
        this.url = url;
        FakeEventSource.instances.push(this);
    }

    close() { this.closed = true; }
    emit(value) { this.onmessage?.({ data: JSON.stringify(value) }); }
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

describe('scout preview subscription', () => {
    it('forwards events for one isolated preview run', () => {
        const received = [];
        const subscription = createScoutPreviewSubscription({
            reviewId: 'r1',
            attemptNo: 1,
            previewId: 'p1',
            EventSourceImpl: FakeEventSource,
            onEvent: (event) => received.push(event)
        });

        const source = FakeEventSource.instances.at(-1);
        source.emit({ type: 'SCOUT_TRACE', note: 'step' });

        expect(source.url).toBe('/api/reviews/r1/attempts/1/scout-previews/p1/events');
        expect(received).toEqual([{ type: 'SCOUT_TRACE', note: 'step' }]);
        subscription.close();
        expect(source.closed).toBe(true);
    });

    it('reconnects with the freshest stored token instead of the one frozen in the URL', () => {
        const backing = installLocalStorage();
        const firstToken = makeJwt({ sub: 'alice', exp: 4102444800 });
        backing.set('chongming-auth', JSON.stringify({ token: firstToken, user: null }));
        const scheduled = [];
        const subscription = createScoutPreviewSubscription({
            reviewId: 'r1',
            attemptNo: 1,
            previewId: 'p1',
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
        const subscription = createScoutPreviewSubscription({
            reviewId: 'r1',
            attemptNo: 1,
            previewId: 'p1',
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
