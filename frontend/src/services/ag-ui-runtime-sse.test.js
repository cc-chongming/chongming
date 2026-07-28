import { describe, expect, it } from 'vitest';
import { createAgUiRuntimeSubscription } from './ag-ui-runtime-sse';

class FakeEventSource {
    static instances = [];

    constructor(url) {
        this.url = url;
        FakeEventSource.instances.push(this);
    }

    close() { this.closed = true; }
    emit(value) { this.onmessage?.({ data: JSON.stringify(value) }); }
}

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

        expect(source.url).toBe('/api/reviews/11111111-1111-1111-1111-111111111111/attempts/2/runtime/ag-ui');
        expect(received).toEqual([{ type: 'RUN_STARTED', runId: 'review:2:director' }]);
        subscription.close();
        expect(source.closed).toBe(true);
    });
});
