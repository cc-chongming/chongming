import { beforeEach, describe, expect, it } from 'vitest';
import { createRuntimeTraceStore } from './runtime-trace-store';
// [AIREVIEW-PLAN-098#2] 回归断言直接走公开对话投影，确保截断后投影不冻结。
import { buildRuntimeConversation } from '../services/runtime-conversation-adapter';

class FakeEventSource {
    constructor(url) {
        this.url = url;
        this.onmessage = null;
        this.onopen = null;
        this.closed = false;
        FakeEventSource.instances.push(this);
    }

    close() {
        this.closed = true;
    }

    emit(event, id) {
        this.onmessage?.({ data: JSON.stringify(event), lastEventId: id });
    }
}
FakeEventSource.instances = [];

function createFakeTimers() {
    const scheduled = [];
    return {
        setTimeout: (fn) => {
            scheduled.push(fn);
            return scheduled.length;
        },
        clearTimeout: (handle) => {
            scheduled[handle - 1] = null;
        },
        flush() {
            const pending = scheduled.splice(0);
            pending.forEach((fn) => fn?.());
        }
    };
}

describe('runtime trace store', () => {
    beforeEach(() => {
        FakeEventSource.instances = [];
    });

    it('buffers replay events and flushes on the timer instead of per event', () => {
        const timers = createFakeTimers();
        const store = createRuntimeTraceStore({
            EventSourceImpl: FakeEventSource,
            setTimeoutImpl: timers.setTimeout,
            clearTimeoutImpl: timers.clearTimeout
        });
        store.start('review-1', 1);
        const source = FakeEventSource.instances.at(-1);

        source.emit({ type: 'TEXT_MESSAGE_CONTENT', runId: 'runtime', delta: 'a' }, '1');
        source.emit({ type: 'TEXT_MESSAGE_CONTENT', runId: 'runtime', delta: 'b' }, '2');
        expect(store.state.events).toHaveLength(0);

        timers.flush();
        expect(store.state.events).toHaveLength(2);
        store.dispose();
    });

    it('flushes immediately once the batch size is reached', () => {
        const timers = createFakeTimers();
        const store = createRuntimeTraceStore({
            EventSourceImpl: FakeEventSource,
            setTimeoutImpl: timers.setTimeout,
            clearTimeoutImpl: timers.clearTimeout
        });
        store.start('review-1', 1);
        const source = FakeEventSource.instances.at(-1);

        for (let index = 1; index <= 600; index += 1) {
            source.emit({ type: 'TEXT_MESSAGE_CONTENT', runId: 'runtime', delta: 'x' }, String(index));
        }
        expect(store.state.events).toHaveLength(600);
        store.dispose();
    });

    it('keeps the replay window bounded after huge replays', () => {
        const timers = createFakeTimers();
        const store = createRuntimeTraceStore({
            EventSourceImpl: FakeEventSource,
            setTimeoutImpl: timers.setTimeout,
            clearTimeoutImpl: timers.clearTimeout
        });
        store.start('review-1', 1);
        const source = FakeEventSource.instances.at(-1);

        for (let index = 1; index <= 20300; index += 1) {
            source.emit({ type: 'TEXT_MESSAGE_CONTENT', runId: 'runtime', delta: 'x' }, String(index));
        }
        // 尾部未凑满批次的缓冲事件需先经定时器 flush，再校验保留窗口边界。
        timers.flush();
        expect(store.state.events).toHaveLength(20000);

        source.emit({ type: 'TEXT_MESSAGE_CONTENT', runId: 'runtime', delta: 'tail' }, '20301');
        timers.flush();
        expect(store.state.events.at(-1).delta).toBe('tail');
        store.dispose();
    });

    // [AIREVIEW-PLAN-098#2] 保留上限截断后新事件仍必须进入公开对话投影：
    // 旧实现原地 splice 使数组引用与长度都不变，增量缓存快路径永久命中，对话冻结。
    it('keeps the public conversation live after the retention window saturates', () => {
        const timers = createFakeTimers();
        const store = createRuntimeTraceStore({
            EventSourceImpl: FakeEventSource,
            setTimeoutImpl: timers.setTimeout,
            clearTimeoutImpl: timers.clearTimeout
        });
        store.start('review-1', 1);
        const source = FakeEventSource.instances.at(-1);

        for (let index = 1; index <= 20000; index += 1) {
            source.emit({ type: 'TEXT_MESSAGE_CONTENT', runId: 'review-1-attempt-1-director', messageId: 'm1', delta: 'x' }, String(index));
        }
        timers.flush();
        // 先消费一次投影，建立增量缓存的 consumed 基线（模拟线上长评审的既有对话）。
        buildRuntimeConversation(store.state.events);

        source.emit({ type: 'TEXT_MESSAGE_CONTENT', runId: 'review-1-attempt-1-judge', messageId: 'm2', delta: '裁决者新消息' }, '20001');
        timers.flush();
        expect(store.state.events).toHaveLength(20000);
        const conversation = buildRuntimeConversation(store.state.events);
        expect(conversation.some((item) => String(item.runId ?? '').includes('judge')
            && item.content.includes('裁决者新消息'))).toBe(true);
        store.dispose();
    });

    it('ignores replayed events carrying an already applied id', () => {
        const timers = createFakeTimers();
        const store = createRuntimeTraceStore({
            EventSourceImpl: FakeEventSource,
            setTimeoutImpl: timers.setTimeout,
            clearTimeoutImpl: timers.clearTimeout
        });
        store.start('review-1', 1);
        const source = FakeEventSource.instances.at(-1);

        source.emit({ type: 'TEXT_MESSAGE_CONTENT', runId: 'runtime', delta: 'a' }, '9');
        source.emit({ type: 'TEXT_MESSAGE_CONTENT', runId: 'runtime', delta: 'a' }, '9');
        timers.flush();
        expect(store.state.events).toHaveLength(1);
        store.dispose();
    });

    it('resets buffered events when switching reviews', () => {
        const timers = createFakeTimers();
        const store = createRuntimeTraceStore({
            EventSourceImpl: FakeEventSource,
            setTimeoutImpl: timers.setTimeout,
            clearTimeoutImpl: timers.clearTimeout
        });
        store.start('review-1', 1);
        FakeEventSource.instances.at(-1).emit({ type: 'TEXT_MESSAGE_CONTENT', runId: 'runtime', delta: 'a' }, '1');
        store.start('review-2', 1);
        timers.flush();
        expect(store.state.events).toHaveLength(0);
        store.dispose();
    });
});
