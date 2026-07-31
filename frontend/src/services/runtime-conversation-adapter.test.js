import { describe, expect, it } from 'vitest';
import { buildRuntimeConversation } from './runtime-conversation-adapter';

describe('runtime conversation adapter', () => {
    it('builds an ordered agent conversation with visible thinking and raw tool input/output', () => {
        const conversation = buildRuntimeConversation([
            { type: 'RUN_STARTED', runId: 'runtime-1:PRODUCT', threadId: 'review:1' },
            {
                type: 'CUSTOM', runId: 'runtime-1:PRODUCT', name: 'chongming.runtime-event.v1',
                value: { role: 'PRODUCT', agentId: 'PRODUCT', eventType: 'AGENT_START' }
            },
            { type: 'REASONING_MESSAGE_START', runId: 'runtime-1:PRODUCT', messageId: 'thought-1', role: 'assistant' },
            { type: 'REASONING_MESSAGE_CONTENT', runId: 'runtime-1:PRODUCT', messageId: 'thought-1', delta: '先核对需求文档与当前实现。' },
            { type: 'REASONING_MESSAGE_END', runId: 'runtime-1:PRODUCT', messageId: 'thought-1' },
            { type: 'TOOL_CALL_START', runId: 'runtime-1:PRODUCT', toolCallId: 'call-1', toolCallName: 'readLines' },
            {
                type: 'CUSTOM', runId: 'runtime-1:PRODUCT', name: 'chongming.tool-call.v1',
                value: {
                    toolCallId: 'call-1', toolName: 'readLines',
                    input: { path: 'src/main/App.java', startLine: 1, api_key: 'debug-key' },
                    output: null, status: 'RUNNING', phase: 'started'
                }
            },
            {
                type: 'CUSTOM', runId: 'runtime-1:PRODUCT', name: 'chongming.tool-call.v1',
                value: {
                    toolCallId: 'call-1', toolName: 'readLines',
                    input: { path: 'src/main/App.java', startLine: 1, api_key: 'debug-key' },
                    output: { text: 'token=raw-tool-result\\nclass App {}' },
                    status: 'SUCCESS', phase: 'completed', elapsedMs: 37
                }
            },
            { type: 'TEXT_MESSAGE_START', runId: 'runtime-1:PRODUCT', messageId: 'answer-1', role: 'assistant' },
            { type: 'TEXT_MESSAGE_CONTENT', runId: 'runtime-1:PRODUCT', messageId: 'answer-1', delta: '产品风险已确认。' },
            { type: 'TEXT_MESSAGE_END', runId: 'runtime-1:PRODUCT', messageId: 'answer-1' }
        ]);

        expect(conversation).toMatchObject([
            { kind: 'thinking', role: 'PRODUCT', content: '先核对需求文档与当前实现。', status: 'completed' },
            {
                kind: 'tool', role: 'PRODUCT', toolName: 'readLines',
                input: { path: 'src/main/App.java', startLine: 1, api_key: 'debug-key' },
                output: { text: 'token=raw-tool-result\\nclass App {}' }, status: 'SUCCESS', elapsedMs: 37
            },
            { kind: 'message', role: 'PRODUCT', content: '产品风险已确认。', status: 'completed' }
        ]);
    });

    it('keeps interleaved tool calls from different agents separate by run and tool call id', () => {
        const conversation = buildRuntimeConversation([
            {
                type: 'CUSTOM', runId: 'runtime-1:DIRECTOR', name: 'chongming.runtime-lifecycle.v1',
                value: { role: 'DIRECTOR', agentId: 'DIRECTOR', lifecycle: 'STARTED' }
            },
            { type: 'TOOL_CALL_START', runId: 'runtime-1:DIRECTOR', toolCallId: 'call-1', toolCallName: 'plan_write' },
            {
                type: 'CUSTOM', runId: 'runtime-1:DIRECTOR', name: 'chongming.tool-call.v1',
                value: {
                    toolCallId: 'call-1', toolName: 'plan_write', input: { plan: '评审计划' },
                    output: { id: 'plan-1' }, status: 'SUCCESS', phase: 'completed', elapsedMs: 12
                }
            },
            {
                type: 'CUSTOM', runId: 'runtime-1:PRODUCT', name: 'chongming.runtime-event.v1',
                value: { role: 'PRODUCT', agentId: 'PRODUCT', eventType: 'AGENT_START' }
            },
            { type: 'TOOL_CALL_START', runId: 'runtime-1:PRODUCT', toolCallId: 'call-1', toolCallName: 'searchText' },
            {
                type: 'CUSTOM', runId: 'runtime-1:PRODUCT', name: 'chongming.tool-call.v1',
                value: {
                    toolCallId: 'call-1', toolName: 'searchText', input: { query: '验收条件' },
                    output: { matches: ['docs/requirement.md'] }, status: 'SUCCESS', phase: 'completed', elapsedMs: 24
                }
            }
        ]);

        expect(conversation).toMatchObject([
            {
                kind: 'tool', runId: 'runtime-1:DIRECTOR', role: 'DIRECTOR', toolCallId: 'call-1',
                toolName: 'plan_write', input: { plan: '评审计划' }, output: { id: 'plan-1' }, elapsedMs: 12
            },
            {
                kind: 'tool', runId: 'runtime-1:PRODUCT', role: 'PRODUCT', toolCallId: 'call-1',
                toolName: 'searchText', input: { query: '验收条件' },
                output: { matches: ['docs/requirement.md'] }, elapsedMs: 24
            }
        ]);
    });
});
