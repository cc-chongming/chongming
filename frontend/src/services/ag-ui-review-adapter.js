import { EventType } from '@ag-ui/core';

/** [AIREVIEW-PLAN-012#1.4,#1.6] AG-UI compatibility boundary for public review dialogue. */
export const REVIEW_DOMAIN_EVENT_NAME = 'chongming.review.domain-event.v1';
export const SCOUT_TOOL_CALL_EVENT_NAME = 'chongming.tool-call.v1';

const PUBLIC_DIALOGUE_EVENT_TYPES = new Set([
    'CLAIM_SUBMITTED',
    'CHALLENGE_SUBMITTED',
    'REBUTTAL_SUBMITTED',
    'POSITION_CHANGED',
    'JUDGEMENT_SUBMITTED',
    'GATE_DRAFTED',
    'HUMAN_GATE_FINALIZED'
]);

function messageId(event) {
    return `review-${event.reviewId}-sequence-${event.sequence}`;
}

function publicContent(event) {
    const payload = event.payload ?? {};
    const text = payload.publicSummary ?? payload.publicContent ?? payload.statement ?? payload.reasonSummary;
    if (typeof text === 'string' && text.trim()) return text.trim();
    if (!PUBLIC_DIALOGUE_EVENT_TYPES.has(event.type)) return null;
    const actor = event.actorRole ?? '评审角色';
    const labels = {
        CLAIM_SUBMITTED: '提交了公开论点',
        CHALLENGE_SUBMITTED: '提出了公开质询',
        REBUTTAL_SUBMITTED: '提交了公开答辩',
        POSITION_CHANGED: '更新了公开立场',
        JUDGEMENT_SUBMITTED: '提交了裁决摘要',
        GATE_DRAFTED: '形成了 Gate 草案',
        HUMAN_GATE_FINALIZED: '确认了最终 Gate'
    };
    return `${actor}${labels[event.type] ?? `报告了 ${event.type}`}。`;
}

/**
 * Preserves a review fact as a typed AG-UI CUSTOM event and derives an assistant text triplet only
 * from whitelisted public fields. It never converts hidden reasoning into a user-visible message.
 */
export function reviewEventToAgUiEvents(event) {
    const custom = {
        type: EventType.CUSTOM,
        name: REVIEW_DOMAIN_EVENT_NAME,
        value: event,
        rawEvent: event
    };
    const content = publicContent(event);
    if (!content) return [custom];
    const id = messageId(event);
    return [
        custom,
        { type: EventType.TEXT_MESSAGE_START, messageId: id, role: 'assistant', rawEvent: event },
        { type: EventType.TEXT_MESSAGE_CONTENT, messageId: id, delta: content, rawEvent: event },
        { type: EventType.TEXT_MESSAGE_END, messageId: id, rawEvent: event }
    ];
}

export function createAgUiConversation(threadId) {
    return {
        threadId,
        runId: null,
        status: 'idle',
        error: null,
        messages: [],
        items: []
    };
}

function publicMessages(messages) {
    return (messages ?? [])
        .filter((message) => message && message.role !== 'reasoning')
        .map((message) => ({
            id: message.id,
            role: message.role,
            name: message.name ?? null,
            content: typeof message.content === 'string' ? message.content : '',
            status: 'completed'
        }));
}

/**
 * Applies only public AG-UI conversation events. REASONING_* is intentionally ignored because
 * the review product must never render a role's hidden chain of thought.
 */
export function applyAgUiEvent(conversation, event) {
    switch (event?.type) {
        case EventType.RUN_STARTED:
            conversation.threadId = event.threadId;
            conversation.runId = event.runId;
            conversation.status = 'running';
            conversation.error = null;
            return;
        case EventType.RUN_FINISHED:
            conversation.status = 'finished';
            return;
        case EventType.RUN_ERROR:
            conversation.status = 'error';
            conversation.error = { message: event.message, code: event.code ?? null };
            return;
        case EventType.MESSAGES_SNAPSHOT:
            conversation.messages = publicMessages(event.messages);
            conversation.items = conversation.messages.map((message) => ({ type: 'message', ...message }));
            return;
        case EventType.TEXT_MESSAGE_START:
            if (event.role !== 'assistant') return;
            if (conversation.messages.some((message) => message.id === event.messageId)) return;
            {
                const message = {
                id: event.messageId,
                role: event.role,
                name: event.rawEvent?.actorRole ?? null,
                content: '',
                status: 'streaming'
                };
                conversation.messages.push(message);
                conversation.items.push({ type: 'message', ...message });
            }
            return;
        case EventType.TEXT_MESSAGE_CONTENT: {
            const message = conversation.messages.find((item) => item.id === event.messageId);
            if (message && typeof event.delta === 'string') message.content += event.delta;
            const item = conversation.items.find((candidate) => candidate.type === 'message' && candidate.id === event.messageId);
            if (item && typeof event.delta === 'string') item.content += event.delta;
            return;
        }
        case EventType.TEXT_MESSAGE_END: {
            const message = conversation.messages.find((item) => item.id === event.messageId);
            if (message) message.status = 'completed';
            const item = conversation.items.find((candidate) => candidate.type === 'message' && candidate.id === event.messageId);
            if (item) item.status = 'completed';
            return;
        }
        case EventType.CUSTOM:
            applyScoutToolCallObservation(conversation, event);
            return;
        default:
            return;
    }
}

function applyScoutToolCallObservation(conversation, event) {
    if (event?.name !== SCOUT_TOOL_CALL_EVENT_NAME || !event.value || typeof event.value.toolCallId !== 'string') return;
    const value = event.value;
    const itemId = `tool:${value.toolCallId}`;
    let item = conversation.items.find((candidate) => candidate.type === 'toolCall' && candidate.id === itemId);
    if (!item) {
        item = {
            type: 'toolCall',
            id: itemId,
            toolCallId: value.toolCallId,
            toolName: value.toolName ?? 'unknown_tool',
            input: value.input ?? {},
            output: value.output ?? null,
            status: value.status ?? 'RUNNING',
            phase: value.phase ?? 'started',
            elapsedMs: value.elapsedMs ?? null,
            truncated: Boolean(value.truncated)
        };
        conversation.items.push(item);
        return;
    }
    item.toolName = value.toolName ?? item.toolName;
    item.input = value.input ?? item.input;
    item.output = value.output ?? item.output;
    item.status = value.status ?? item.status;
    item.phase = value.phase ?? item.phase;
    item.elapsedMs = value.elapsedMs ?? item.elapsedMs;
    item.truncated = Boolean(value.truncated ?? item.truncated);
}

export function reviewEventFromAgUiEvent(event) {
    if (event?.type !== EventType.CUSTOM || event.name !== REVIEW_DOMAIN_EVENT_NAME) return null;
    const value = event.value;
    return value && Number.isInteger(value.sequence) && typeof value.reviewId === 'string' ? value : null;
}
