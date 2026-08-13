// [AIREVIEW-PLAN-023#7.1] Public runtime events are reduced into a readable AI conversation.
const TOOL_OBSERVATION_EVENT = 'chongming.tool-call.v1';
const RUNTIME_IDENTITY_EVENT = 'chongming.runtime-event.v1';
const RUNTIME_LIFECYCLE_EVENT = 'chongming.runtime-lifecycle.v1';
const HIDDEN_VALUE = '••••••';
const SENSITIVE_KEY = /(?:token|api[_-]?key|authorization|password|secret|credential|access[_-]?key)/i;
const FAILED_VALUE = /^(?:ERROR|FAILED|FAILURE|DENIED|INTERRUPTED|CANCELLED)$/i;

function roleFor(runId, roles) {
    if (roles.has(runId)) return roles.get(runId);
    let suffix = String(runId ?? '').split(':').at(-1);
    if (!suffix) return 'AGENT';
    if (suffix.toLowerCase().includes('context-scout')) return 'CONTEXT_SCOUT';
    // Child harness run ids embed the attempt prefix (review-{uuid}-attempt-{n}-product);
    // only the trailing agent label identifies the role.
    const attemptMatch = suffix.match(/-attempt-\d+-(.+)$/i);
    if (attemptMatch) suffix = attemptMatch[1];
    return suffix.replace(/-finalizer$/i, '').replaceAll('-', '_').toUpperCase();
}

function eventKey(event, prefix) {
    return `${prefix}:${event.runId ?? 'unknown'}:${event.messageId ?? event.toolCallId ?? event.id ?? 'unknown'}`;
}

function redactString(value) {
    const source = String(value ?? '');
    if (/^\s*[{[]/.test(source)) {
        try {
            return JSON.stringify(maskSensitiveValue(JSON.parse(source)));
        } catch {
            // Continue with conservative plain-text masking for malformed diagnostics.
        }
    }
    return source.replace(
        /\b(token|api[_-]?key|authorization|password|secret|credential|access[_-]?key)(\s*[:=]\s*)(?:Bearer\s+|Basic\s+)?[^\s,;]+/gi,
        (_, key, separator) => `${key}${separator}${HIDDEN_VALUE}`
    );
}

export function maskSensitiveValue(value, seen = new WeakSet()) {
    if (typeof value === 'string') return redactString(value);
    if (value == null || typeof value !== 'object') return value ?? null;
    if (seen.has(value)) return '[Circular]';
    seen.add(value);
    if (Array.isArray(value)) return value.map((entry) => maskSensitiveValue(entry, seen));
    return Object.fromEntries(Object.entries(value).map(([key, entry]) => [
        key,
        SENSITIVE_KEY.test(key) ? HIDDEN_VALUE : maskSensitiveValue(entry, seen)
    ]));
}

export function isReasoningEvent(event) {
    const type = String(event?.type ?? '');
    return type.startsWith('REASONING_') || type.startsWith('THINKING_');
}

export function partitionClaimsByPosition(claims = []) {
    return {
        support: claims.filter((claim) => claim?.position === 'SUPPORT'),
        oppose: claims.filter((claim) => claim?.position === 'OPPOSE'),
        neutral: claims.filter((claim) => claim?.position === 'NEUTRAL')
    };
}

export function gateDifference(aiGate, humanGateVersions = []) {
    const humanGate = humanGateVersions.at(-1);
    if (!aiGate?.result || !humanGate?.result || aiGate.result === humanGate.result) return null;
    return {
        aiResult: aiGate.result,
        humanResult: humanGate.result,
        reason: humanGate.reason ?? ''
    };
}

export function createLatestOnlyLoadQueue(disposeCurrent = () => {}, setReady = () => {}) {
    let generation = 0;
    let tail = Promise.resolve();
    return {
        run(reviewId, load, publishLatest = () => {}) {
            const currentGeneration = ++generation;
            setReady(false);
            disposeCurrent();
            const execute = async () => {
                if (currentGeneration !== generation) return;
                try {
                    await load(reviewId);
                    if (currentGeneration !== generation) {
                        disposeCurrent();
                        return;
                    }
                    const published = await publishLatest(reviewId);
                    if (currentGeneration === generation) setReady(true);
                    return published;
                } catch (error) {
                    if (currentGeneration === generation) setReady(true);
                    throw error;
                }
            };
            const result = tail.then(execute, execute);
            tail = result.catch(() => {});
            return result;
        },
        dispose() {
            generation += 1;
            disposeCurrent();
        }
    };
}

function toolStatus(observation, currentStatus) {
    // The terminal status is authoritative: older persisted observations can carry a stale
    // "failed" phase for tools that actually succeeded (todo_write, wait_async_results, ...).
    const status = String(observation?.status ?? currentStatus ?? 'RUNNING').toUpperCase();
    if (status === 'SUCCESS') return 'SUCCESS';
    const outputState = observation?.output?.state
        ?? observation?.output?.status
        ?? observation?.output?.resultState
        ?? observation?.resultState;
    if (FAILED_VALUE.test(String(observation?.phase ?? '')) || FAILED_VALUE.test(String(outputState ?? ''))) {
        return 'ERROR';
    }
    return FAILED_VALUE.test(status) ? status : observation?.status ?? currentStatus ?? 'RUNNING';
}

/**
 * Reduces ordered AG-UI events into the public transcript. Hidden reasoning is intentionally
 * discarded, while tool diagnostics remain available in masked, collapsed UI rows.
 *
 * A long replay can hold tens of thousands of events and the live page rebuilds the transcript
 * on every arrival batch. The previous reduction is cached and reused whenever the new event
 * array extends the cached one, so replayed history is reduced exactly once and each new batch
 * only costs its own size instead of the whole history.
 */
let transcriptCache = { events: null, filtered: null, items: [], itemsByKey: new Map(), roles: new Map() };

export function buildRuntimeConversation(events) {
    const list = events ?? [];
    const cached = transcriptCache;
    // Fast paths: the live page re-evaluates on every reactive flush even when no new event
    // arrived (push keeps the same array reference), and a same-reference extension never needs
    // an element-by-element prefix comparison.
    if (cached.events === list && cached.consumed === list.length && cached.filtered) {
        return cached.filtered;
    }
    let start = 0;
    let items;
    let itemsByKey;
    let roles;
    let cachedHit = false;
    if (cached.events === list) {
        cachedHit = true;
    } else if (cached.events !== null
        && cached.events.length <= list.length
        && cached.events.every((event, index) => list[index] === event)) {
        cachedHit = true;
    }
    if (cachedHit) {
        start = cached.events === list ? cached.consumed ?? cached.events.length : cached.events.length;
        items = cached.items;
        itemsByKey = cached.itemsByKey;
        roles = cached.roles;
    } else {
        items = [];
        itemsByKey = new Map();
        roles = new Map();
    }
    collectRoles(list, start, roles);
    const handledEvents = new Set();
    reduceEvents(list, start, items, itemsByKey, roles, handledEvents);
    const filtered = items.filter((item) => (item.kind === 'tool' && item.toolCallId) || item.content.trim() || item.kind === 'notice');
    transcriptCache = { events: list, filtered, items, itemsByKey, roles, consumed: list.length };
    return filtered;
}

function collectRoles(events, start, roles) {
    for (let index = start; index < events.length; index += 1) {
        const event = events[index];
        if (!event?.runId || event.type !== 'CUSTOM') continue;
        if (![RUNTIME_IDENTITY_EVENT, RUNTIME_LIFECYCLE_EVENT].includes(event.name)) continue;
        if (typeof event.value?.role === 'string' && event.value.role.trim()) {
            roles.set(event.runId, event.value.role.trim().toUpperCase());
        }
    }
}

function reduceEvents(events, start, items, itemsByKey, roles, handledEvents) {

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
            sequence: event.sequence ?? null,
            runId: event.runId ?? null,
            role: roleFor(event.runId, roles),
            phase: event.phase ?? null,
            createdAt: event.createdAt ?? null,
            content: '',
            status: 'streaming'
        });
    }

    for (let eventIndex = start; eventIndex < events.length; eventIndex += 1) {
        const event = events[eventIndex];
        if (!event?.type) continue;
        if (isReasoningEvent(event)) continue;
        const replayKey = event.sequence != null
            ? `${event.sequence}:${event.type}:${event.runId ?? ''}`
            : event.id != null ? `${event.id}:${event.type}` : null;
        if (replayKey && handledEvents.has(replayKey)) continue;
        if (replayKey) handledEvents.add(replayKey);

        if (event.type === 'TEXT_MESSAGE_START') {
            agentItem(event, 'message', eventKey(event, 'message'));
            continue;
        }
        if (event.type === 'TEXT_MESSAGE_CONTENT') {
            const item = agentItem(event, 'message', eventKey(event, 'message'));
            item.content += typeof event.delta === 'string' ? event.delta : '';
            continue;
        }
        if (event.type === 'TEXT_MESSAGE_END') {
            agentItem(event, 'message', eventKey(event, 'message')).status = 'completed';
            continue;
        }
        if (event.type === 'TOOL_CALL_START') {
            const item = agentItem(event, 'tool', eventKey(event, 'tool'));
            item.toolCallId = event.toolCallId ?? null;
            item.toolName = event.toolCallName ?? 'unknown_tool';
            item.input = null;
            item.output = null;
            item.status = 'RUNNING';
            item.elapsedMs = null;
            continue;
        }
        if (event.type === 'TOOL_CALL_RESULT') {
            // Result events can carry a separate messageId (reply id); keying on it would fork a
            // ghost "unknown tool" item beside the one created by TOOL_CALL_START, so merge by
            // toolCallId first. An orphan result without a matching tool row adds no public
            // value and is skipped.
            const toolCallId = event.toolCallId ?? null;
            const item = (toolCallId ? itemsByKey.get(`tool:${event.runId ?? 'unknown'}:${toolCallId}`) : null)
                ?? itemsByKey.get(eventKey(event, 'tool'));
            if (item == null || item.kind !== 'tool') continue;
            if (item.output == null && event.content != null) item.output = maskSensitiveValue(event.content);
            continue;
        }
        if (event.type === 'CUSTOM' && event.name === TOOL_OBSERVATION_EVENT && event.value?.toolCallId) {
            const observation = event.value;
            const id = `tool:${event.runId ?? 'unknown'}:${observation.toolCallId}`;
            const item = agentItem(event, 'tool', id);
            item.toolCallId = observation.toolCallId;
            item.toolName = observation.toolName ?? item.toolName ?? 'unknown_tool';
            item.input = maskSensitiveValue(observation.input);
            item.output = maskSensitiveValue(observation.output);
            item.phase = observation.phase ?? item.phase;
            item.status = toolStatus(observation, item.status);
            item.elapsedMs = observation.elapsedMs ?? item.elapsedMs ?? null;
            continue;
        }
        if (event.type === 'RUN_ERROR') {
            const id = `notice:${event.runId ?? 'unknown'}:${event.id ?? event.sequence ?? items.length}`;
            const item = agentItem(event, 'notice', id);
            item.content = maskSensitiveValue(event.message ?? 'Agent 运行异常结束。');
            item.status = 'error';
        }
    }
}

export const runtimeConversationEvents = {
    TOOL_OBSERVATION_EVENT,
    RUNTIME_IDENTITY_EVENT,
    RUNTIME_LIFECYCLE_EVENT
};
