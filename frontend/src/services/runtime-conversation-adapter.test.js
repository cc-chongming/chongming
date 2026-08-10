import { describe, expect, it } from 'vitest';
import {
    buildRuntimeConversation,
    createLatestOnlyLoadQueue,
    gateDifference,
    isReasoningEvent,
    maskSensitiveValue,
    partitionClaimsByPosition
} from './runtime-conversation-adapter';

describe('runtime conversation adapter', () => {
    it('ignores reasoning and builds an ordered public conversation', () => {
        const conversation = buildRuntimeConversation([
            {
                type: 'CUSTOM', runId: 'runtime-1:PRODUCT', name: 'chongming.runtime-event.v1', sequence: 1,
                value: { role: 'PRODUCT', agentId: 'PRODUCT', eventType: 'AGENT_START' }
            },
            { type: 'REASONING_MESSAGE_START', runId: 'runtime-1:PRODUCT', messageId: 'thought-1', sequence: 2 },
            { type: 'REASONING_MESSAGE_CONTENT', runId: 'runtime-1:PRODUCT', messageId: 'thought-1', delta: '隐藏推理', sequence: 3 },
            { type: 'REASONING_MESSAGE_END', runId: 'runtime-1:PRODUCT', messageId: 'thought-1', sequence: 4 },
            { type: 'TEXT_MESSAGE_START', runId: 'runtime-1:PRODUCT', messageId: 'answer-1', sequence: 5 },
            { type: 'TEXT_MESSAGE_CONTENT', runId: 'runtime-1:PRODUCT', messageId: 'answer-1', delta: '产品风险', sequence: 6 },
            { type: 'TEXT_MESSAGE_CONTENT', runId: 'runtime-1:PRODUCT', messageId: 'answer-1', delta: '已确认。', sequence: 7 },
            { type: 'TEXT_MESSAGE_END', runId: 'runtime-1:PRODUCT', messageId: 'answer-1', sequence: 8 }
        ]);

        expect(conversation).toEqual([
            expect.objectContaining({
                kind: 'message', role: 'PRODUCT', content: '产品风险已确认。', status: 'completed', sequence: 5
            })
        ]);
        expect(conversation.some((item) => item.kind === 'thinking')).toBe(false);
    });

    it('masks sensitive input and output keys in tool observations', () => {
        const [tool] = buildRuntimeConversation([
            { type: 'TOOL_CALL_START', runId: 'runtime-1:SECURITY', toolCallId: 'call-1', toolCallName: 'inspect' },
            {
                type: 'CUSTOM', runId: 'runtime-1:SECURITY', name: 'chongming.tool-call.v1',
                value: {
                    toolCallId: 'call-1', toolName: 'inspect', phase: 'completed', status: 'SUCCESS', elapsedMs: 37,
                    input: { path: 'src/App.java', authorization: 'Bearer private', nested: { apiKey: 'private' } },
                    output: { password: 'private', text: '公开结果' }
                }
            }
        ]);

        expect(tool).toMatchObject({
            kind: 'tool', role: 'SECURITY', toolName: 'inspect', status: 'SUCCESS', elapsedMs: 37,
            input: { path: 'src/App.java', authorization: '••••••', nested: { apiKey: '••••••' } },
            output: { password: '••••••', text: '公开结果' }
        });
    });

    it('masks complete bearer credentials and JSON-shaped secret strings', () => {
        expect(maskSensitiveValue('Authorization: Bearer sk-live-secret')).toBe('Authorization: ••••••');
        expect(maskSensitiveValue('{"apiKey":"sk-json-secret","message":"ok"}'))
            .toBe('{"apiKey":"••••••","message":"ok"}');
    });

    it('recognizes every AG-UI reasoning event as non-public', () => {
        expect([
            'REASONING_START',
            'REASONING_MESSAGE_START',
            'REASONING_MESSAGE_CONTENT',
            'REASONING_MESSAGE_END',
            'REASONING_MESSAGE_CHUNK',
            'REASONING_END',
            'REASONING_ENCRYPTED_VALUE',
            'THINKING_START',
            'THINKING_TEXT_MESSAGE_CONTENT',
            'THINKING_END'
        ].every((type) => isReasoningEvent({ type }))).toBe(true);
        expect(isReasoningEvent({ type: 'TEXT_MESSAGE_CONTENT' })).toBe(false);
    });

    it('treats a failed phase or failed result state as failure even when status says success', () => {
        const [tool] = buildRuntimeConversation([
            {
                type: 'CUSTOM', runId: 'runtime-1:DIRECTOR', name: 'chongming.tool-call.v1',
                value: {
                    toolCallId: 'call-1', toolName: 'open_debate_topic', phase: 'failed', status: 'SUCCESS',
                    output: { state: 'ERROR', errorCode: 'ILLEGAL_STATE_TRANSITION' }
                }
            }
        ]);

        expect(tool.status).toBe('ERROR');
    });

    it('keeps interleaved tool calls from different agents separate by run and tool id', () => {
        const conversation = buildRuntimeConversation([
            {
                type: 'CUSTOM', runId: 'runtime-1:DIRECTOR', name: 'chongming.tool-call.v1',
                value: { toolCallId: 'call-1', toolName: 'plan_write', input: {}, output: { id: 'plan-1' }, status: 'SUCCESS' }
            },
            {
                type: 'CUSTOM', runId: 'runtime-1:PERFORMANCE', name: 'chongming.tool-call.v1',
                value: { toolCallId: 'call-1', toolName: 'profile', input: {}, output: { duration: 12 }, status: 'SUCCESS' }
            }
        ]);

        expect(conversation).toMatchObject([
            { runId: 'runtime-1:DIRECTOR', role: 'DIRECTOR', toolName: 'plan_write' },
            { runId: 'runtime-1:PERFORMANCE', role: 'PERFORMANCE', toolName: 'profile' }
        ]);
    });

    it('keeps a dynamic review role from a replayed runtime lifecycle identity', () => {
        const conversation = buildRuntimeConversation([
            {
                type: 'CUSTOM', runId: 'runtime-1:accessibility', name: 'chongming.runtime-lifecycle.v1',
                value: { role: 'accessibility', lifecycle: 'COMPLETED' }
            },
            { type: 'TEXT_MESSAGE_START', runId: 'runtime-1:accessibility', messageId: 'answer-1' },
            { type: 'TEXT_MESSAGE_CONTENT', runId: 'runtime-1:accessibility', messageId: 'answer-1', delta: '无障碍审查完成。' },
            { type: 'TEXT_MESSAGE_END', runId: 'runtime-1:accessibility', messageId: 'answer-1' }
        ]);

        expect(conversation).toEqual([
            expect.objectContaining({ role: 'ACCESSIBILITY', content: '无障碍审查完成。', status: 'completed' })
        ]);
    });

    it('does not append the same persisted delta twice after reconnect replay', () => {
        const delta = {
            type: 'TEXT_MESSAGE_CONTENT', runId: 'runtime-1:JUDGE', messageId: 'answer-1',
            sequence: 42, delta: '退回修改。'
        };
        const [message] = buildRuntimeConversation([
            { type: 'TEXT_MESSAGE_START', runId: 'runtime-1:JUDGE', messageId: 'answer-1', sequence: 41 },
            delta,
            { ...delta },
            { type: 'TEXT_MESSAGE_END', runId: 'runtime-1:JUDGE', messageId: 'answer-1', sequence: 43 }
        ]);

        expect(message.content).toBe('退回修改。');
    });

    it('keeps neutral claims separate from support and opposition', () => {
        expect(partitionClaimsByPosition([
            { claimId: 'support', position: 'SUPPORT' },
            { claimId: 'oppose', position: 'OPPOSE' },
            { claimId: 'neutral', position: 'NEUTRAL' }
        ])).toEqual({
            support: [{ claimId: 'support', position: 'SUPPORT' }],
            oppose: [{ claimId: 'oppose', position: 'OPPOSE' }],
            neutral: [{ claimId: 'neutral', position: 'NEUTRAL' }]
        });
    });

    it('describes a human Gate override only when it differs from the AI draft', () => {
        expect(gateDifference({ result: 'PASS' }, [{ result: 'RETURN', reason: '范围需要补齐' }]))
            .toEqual({ aiResult: 'PASS', humanResult: 'RETURN', reason: '范围需要补齐' });
        expect(gateDifference({ result: 'PASS' }, [{ result: 'PASS', reason: '确认通过' }])).toBeNull();
    });

    it('serializes review loads and publishes only the latest route result', async () => {
        let releaseFirst;
        const first = new Promise((resolve) => { releaseFirst = resolve; });
        const started = [];
        const published = [];
        const disposed = [];
        const visibility = [];
        const queue = createLatestOnlyLoadQueue(
            () => disposed.push('disposed'),
            (ready) => visibility.push(ready)
        );

        const loadFirst = queue.run('review-a', async () => {
            started.push('review-a');
            await first;
        }, (reviewId) => published.push(reviewId));
        await Promise.resolve();
        const loadSecond = queue.run('review-b', async () => {
            started.push('review-b');
        }, (reviewId) => published.push(reviewId));

        releaseFirst();
        await Promise.all([loadFirst, loadSecond]);

        expect(started).toEqual(['review-a', 'review-b']);
        expect(published).toEqual(['review-b']);
        expect(disposed.length).toBeGreaterThanOrEqual(2);
        expect(visibility).toEqual([false, false, true]);
    });
});
