const TOOL_OBSERVATION_EVENT = 'chongming.tool-call.v1';
const RUNTIME_IDENTITY_EVENT = 'chongming.runtime-event.v1';
const RUNTIME_LIFECYCLE_EVENT = 'chongming.runtime-lifecycle.v1';

function roleFor(runId, roles) {
    if (roles.has(runId)) return roles.get(runId);
    const suffix = String(runId ?? '').split(':').at(-1);
    if (!suffix) return 'AGENT';
    if (suffix.toLowerCase().includes('context-scout')) return 'CONTEXT_SCOUT';
    return suffix.replace(/-finalizer$/i, '').toUpperCase();
}

function knownRoles(events) {
    const roles = new Map();
    (events ?? []).forEach((event) => {
        if (!event?.runId || event.type !== 'CUSTOM') return;
        if (![RUNTIME_IDENTITY_EVENT, RUNTIME_LIFECYCLE_EVENT].includes(event.name)) return;
        if (typeof event.value?.role === 'string' && event.value.role.trim()) {
            roles.set(event.runId, event.value.role.trim());
        }
    });
    return roles;
}

function eventKey(event, prefix) {
    return `${prefix}:${event.runId ?? 'unknown'}:${event.messageId ?? event.toolCallId ?? event.id ?? 'unknown'}`;
}

function copyToolValue(value) {
    if (value == null || typeof value !== 'object') return value ?? null;
    return value;
}

/**
 * Reduces the raw, ordered AG-UI runtime stream into the blocks of a familiar agent chat
 * transcript. It deliberately keeps tool input/output verbatim because this page is the local
 * debug observatory requested by the reviewer.
 */
export function buildRuntimeConversation(events) {
    const roles = knownRoles(events);
    const items = [];
    const itemsByKey = new Map();

    function add(key, item) {
        const existing = itemsByKey.get(key);
        if (existing) return existing;
        itemsByKey.set(key, item);
        items.push(item);
        return item;
    }

    function agentItem(event, kind, id) {
        return add(id, {
            id,
            kind,
            runId: event.runId ?? null,
            role: roleFor(event.runId, roles),
            createdAt: event.createdAt ?? null,
            content: '',
            status: 'streaming'
        });
    }

    (events ?? []).forEach((event) => {
        if (!event?.type) return;
        if (event.type === 'REASONING_MESSAGE_START') {
            agentItem(event, 'thinking', eventKey(event, 'thinking'));
            return;
        }
        if (event.type === 'REASONING_MESSAGE_CONTENT') {
            const item = agentItem(event, 'thinking', eventKey(event, 'thinking'));
            item.content += typeof event.delta === 'string' ? event.delta : '';
            return;
        }
        if (event.type === 'REASONING_MESSAGE_END') {
            agentItem(event, 'thinking', eventKey(event, 'thinking')).status = 'completed';
            return;
        }
        if (event.type === 'TEXT_MESSAGE_START') {
            agentItem(event, 'message', eventKey(event, 'message'));
            return;
        }
        if (event.type === 'TEXT_MESSAGE_CONTENT') {
            const item = agentItem(event, 'message', eventKey(event, 'message'));
            item.content += typeof event.delta === 'string' ? event.delta : '';
            return;
        }
        if (event.type === 'TEXT_MESSAGE_END') {
            agentItem(event, 'message', eventKey(event, 'message')).status = 'completed';
            return;
        }
        if (event.type === 'TOOL_CALL_START') {
            const item = agentItem(event, 'tool', eventKey(event, 'tool'));
            item.toolCallId = event.toolCallId ?? null;
            item.toolName = event.toolCallName ?? 'unknown_tool';
            item.input = null;
            item.output = null;
            item.status = 'RUNNING';
            item.elapsedMs = null;
            return;
        }
        if (event.type === 'TOOL_CALL_RESULT') {
            const item = agentItem(event, 'tool', eventKey(event, 'tool'));
            if (item.output == null && event.content != null) item.output = event.content;
            return;
        }
        if (event.type === 'CUSTOM' && event.name === TOOL_OBSERVATION_EVENT && event.value?.toolCallId) {
            const observation = event.value;
            const id = `tool:${event.runId ?? 'unknown'}:${observation.toolCallId}`;
            const item = agentItem(event, 'tool', id);
            item.toolCallId = observation.toolCallId;
            item.toolName = observation.toolName ?? item.toolName ?? 'unknown_tool';
            item.input = copyToolValue(observation.input);
            item.output = copyToolValue(observation.output);
            item.status = observation.status ?? item.status ?? 'RUNNING';
            item.elapsedMs = observation.elapsedMs ?? item.elapsedMs ?? null;
            return;
        }
        if (event.type === 'RUN_ERROR') {
            const id = `notice:${event.runId ?? 'unknown'}:${event.id ?? items.length}`;
            const item = agentItem(event, 'notice', id);
            item.content = event.message ?? 'Agent 运行异常结束。';
            item.status = 'error';
        }
    });

    return items.filter((item) => item.kind === 'tool' || item.content.trim() || item.kind === 'notice');
}

export const runtimeConversationEvents = {
    TOOL_OBSERVATION_EVENT,
    RUNTIME_IDENTITY_EVENT,
    RUNTIME_LIFECYCLE_EVENT
};
