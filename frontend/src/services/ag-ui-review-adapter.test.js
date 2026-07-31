import { describe, expect, it } from 'vitest';
import { EventType } from '@ag-ui/core';
import {
    applyAgUiEvent,
    createAgUiConversation,
    reviewEventToAgUiEvents,
    SCOUT_TOOL_CALL_EVENT_NAME
} from './ag-ui-review-adapter';

describe('AG-UI review adapter', () => {
    it('maps a public domain fact to CUSTOM and text events without rendering reasoning', () => {
        const reviewEvent = {
            reviewId: '11111111-1111-1111-1111-111111111111',
            sequence: 7,
            type: 'CHALLENGE_SUBMITTED',
            actorRole: 'BACKEND',
            payload: { publicSummary: '接口需要明确幂等键。' }
        };
        const events = reviewEventToAgUiEvents(reviewEvent);
        const conversation = createAgUiConversation('review:11111111-1111-1111-1111-111111111111');
        events.forEach((event) => applyAgUiEvent(conversation, event));
        applyAgUiEvent(conversation, {
            type: EventType.REASONING_MESSAGE_CONTENT,
            messageId: 'hidden',
            delta: 'this must never be displayed'
        });

        expect(events.map((event) => event.type)).toEqual([
            EventType.CUSTOM,
            EventType.TEXT_MESSAGE_START,
            EventType.TEXT_MESSAGE_CONTENT,
            EventType.TEXT_MESSAGE_END
        ]);
        expect(conversation.messages).toEqual([{
            id: 'review-11111111-1111-1111-1111-111111111111-sequence-7',
            role: 'assistant',
            name: 'BACKEND',
            content: '接口需要明确幂等键。',
            status: 'completed'
        }]);
    });

    it('merges one Scout tool call from started to completed without creating duplicate timeline items', () => {
        const conversation = createAgUiConversation('review:scout');
        const started = {
            type: EventType.CUSTOM,
            name: SCOUT_TOOL_CALL_EVENT_NAME,
            value: {
                toolCallId: 'call-1', toolName: 'grep_files', input: { pattern: 'ReviewService' },
                output: null, status: 'RUNNING', phase: 'started', elapsedMs: null, truncated: false
            }
        };
        const completed = {
            ...started,
            value: {
                ...started.value, output: { text: 'src/main/ReviewService.java:42' },
                status: 'SUCCESS', phase: 'completed', elapsedMs: 42
            }
        };

        applyAgUiEvent(conversation, started);
        applyAgUiEvent(conversation, completed);

        expect(conversation.items).toEqual([{
            type: 'toolCall', id: 'tool:call-1', toolCallId: 'call-1', toolName: 'grep_files',
            input: { pattern: 'ReviewService' }, output: { text: 'src/main/ReviewService.java:42' },
            status: 'SUCCESS', phase: 'completed', elapsedMs: 42, truncated: false
        }]);
    });
});
