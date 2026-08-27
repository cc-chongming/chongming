<script setup>
// [AIREVIEW-PLAN-023#7.1] Unified public AI conversation for every review Agent.
// [AIREVIEW-PLAN-034#1#2#4#6] De-bubbled answers, Codex-style tool groups, scroll-follow fix, Chinese role labels.
import { computed, nextTick, onUnmounted, ref, watch } from 'vue';
import { buildRuntimeConversation } from '../services/runtime-conversation-adapter';
import { formatChinaClock } from '../services/china-time';
import AgUiToolCallMessage from './AgUiToolCallMessage.vue';
import SafeMarkdown from './SafeMarkdown.vue';

const props = defineProps({
    events: { type: Array, default: () => [] },
    items: { type: Array, default: () => [] },
    status: { type: String, default: 'idle' },
    emptyState: { type: Object, default: null },
    compact: { type: Boolean, default: false }
});

const conversation = computed(() => props.items.length ? props.items : buildRuntimeConversation(props.events));

// [AIREVIEW-PLAN-023#7.1] Consecutive tool calls collapse into one Codex-style group row.
const TOOL_TERMINAL = new Set(['SUCCESS', 'COMPLETED', 'ERROR', 'FAILED', 'DENIED', 'INTERRUPTED']);
const TOOL_FAILED = new Set(['ERROR', 'FAILED', 'DENIED', 'INTERRUPTED']);
const rows = computed(() => {
    const out = [];
    let buffer = [];
    const flush = () => {
        if (buffer.length === 1) out.push(buffer[0]);
        else if (buffer.length > 1) out.push({ kind: 'tool-group', id: `tool-group:${buffer[0].id}`, tools: [...buffer] });
        buffer = [];
    };
    for (const item of conversation.value) {
        if (item.kind === 'tool') buffer.push(item);
        else { flush(); out.push(item); }
    }
    flush();
    return out;
});

function groupDone(row) {
    return row.tools.every((tool) => TOOL_TERMINAL.has(String(tool.status ?? '').toUpperCase()));
}
function groupStatusLabel(row) {
    if (!groupDone(row)) return '进行中';
    const failed = row.tools.filter((tool) => TOOL_FAILED.has(String(tool.status ?? '').toUpperCase())).length;
    return failed ? `${failed} 项失败` : '已完成';
}
const scrollPanel = ref(null);
const followsLatest = ref(true);
// Programmatic scrolls also emit scroll events; without this guard the auto-follow
// snap-back re-arms itself and the user can never scroll away from the bottom.
let programmaticScroll = false;
let scrollGuardTimer = null;

function roleTitle(role) {
    return {
        DIRECTOR: '协调者', CONTEXT_SCOUT: '上下文侦察', PRODUCT: '产品经理', PROJECT: '项目经理',
        ARCHITECTURE: '架构师', BACKEND: '后端工程师', FRONTEND: '前端工程师', TESTING: '测试工程师',
        PERFORMANCE: '性能工程师', SECURITY: '安全工程师', JUDGE: '裁决者'
    }[role] ?? role ?? '智能体';
}

function roleInitial(role) {
    return {
        CONTEXT_SCOUT: '侦', DIRECTOR: '协', PRODUCT: '产', PROJECT: '项', FRONTEND: '前', BACKEND: '后',
        ARCHITECTURE: '架', SECURITY: '安', TESTING: '测', PERFORMANCE: '能', JUDGE: '裁'
    }[role] ?? String(role ?? '审').slice(0, 1);
}

function displayTime(value) {
    // [AIREVIEW-PLAN-025] Conversation bubbles always show China time regardless of viewer TZ.
    return formatChinaClock(value);
}
function scrollToEnd() {
    const element = scrollPanel.value;
    if (!element) return;
    programmaticScroll = true;
    element.scrollTo({ top: element.scrollHeight });
    globalThis.clearTimeout(scrollGuardTimer);
    scrollGuardTimer = globalThis.setTimeout(() => { programmaticScroll = false; }, 150);
}

function updateFollowState() {
    if (programmaticScroll) return;
    const element = scrollPanel.value;
    if (!element) return;
    followsLatest.value = element.scrollHeight - element.scrollTop - element.clientHeight < 48;
}

function onWheel(event) {
    // Wheeling up is an explicit intent to read earlier content: release auto-follow
    // immediately so the next streaming delta cannot snap the view back.
    if (event.deltaY < 0) followsLatest.value = false;
}

function goLatest() {
    followsLatest.value = true;
    nextTick(scrollToEnd);
}

watch(conversation, () => {
    if (!followsLatest.value) return;
    nextTick(scrollToEnd);
}, { deep: true });

onUnmounted(() => globalThis.clearTimeout(scrollGuardTimer));
</script>

<template>
    <section :class="['live-agent-conversation', { compact }]" aria-label="评审 Agent 公开对话">
        <div ref="scrollPanel" class="live-agent-scroll" @scroll.passive="updateFollowState" @wheel.passive="onWheel">
            <ol v-if="conversation.length" class="live-agent-timeline" aria-live="polite">
                <li v-for="row in rows" :key="row.id" :class="['live-agent-entry', row.kind === 'tool-group' ? 'is-tool-group' : `is-${row.kind}`]">
                    <template v-if="row.kind === 'message'">
                        <div class="agent-avatar" :data-role="row.role" aria-hidden="true">{{ roleInitial(row.role) }}</div>
                        <article class="agent-dialogue">
                            <header class="agent-dialogue-header">
                                <strong>{{ roleTitle(row.role) }}</strong>
                                <span v-if="row.phase">{{ row.phase }}</span>
                                <time v-if="row.createdAt">{{ displayTime(row.createdAt) }}</time>
                                <span v-if="row.status === 'streaming'" class="agent-streaming">输出中</span>
                            </header>
                            <SafeMarkdown class="agent-answer" :content="row.content" />
                        </article>
                    </template>
                    <template v-else-if="row.kind === 'tool'">
                        <span class="agent-entry-spacer" aria-hidden="true"></span>
                        <AgUiToolCallMessage :item="row" />
                    </template>
                    <template v-else-if="row.kind === 'tool-group'">
                        <span class="agent-entry-spacer" aria-hidden="true"></span>
                        <details class="tool-group">
                            <summary :aria-label="`共 ${row.tools.length} 个工具调用，${groupStatusLabel(row)}`">
                                <span class="tool-call-symbol" aria-hidden="true"><svg class="tool-call-icon" viewBox="0 0 16 16" fill="none" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round"><rect x="1.75" y="2.75" width="12.5" height="10.5" rx="1.75"/><path d="M4.75 6.25 6.75 8l-2 1.75"/><path d="M8.25 10.25h3"/></svg></span>
                                <strong>{{ row.tools.length }} 个工具调用</strong>
                                <span class="tool-call-status">{{ groupStatusLabel(row) }}</span>
                                <span class="tool-group-caret" aria-hidden="true"></span>
                            </summary>
                            <div class="tool-group-items">
                                <AgUiToolCallMessage v-for="tool in row.tools" :key="tool.id" :item="tool" />
                            </div>
                        </details>
                    </template>
                    <template v-else>
                        <span class="agent-entry-spacer" aria-hidden="true"></span>
                        <p class="agent-runtime-notice" role="status">{{ row.content }}</p>
                    </template>
                </li>
            </ol>
            <div v-else class="live-agent-empty" :class="{ 'is-terminal-notice': emptyState }" :role="emptyState ? 'status' : undefined">
                <span class="agent-avatar" aria-hidden="true">…</span>
                <div><strong>{{ emptyState?.title ?? '等待 Agent 公开消息' }}</strong><p>{{ emptyState?.message ?? (status === 'connected' ? '连接已建立；公开回答与折叠工具调用会显示在这里。' : '正在建立当前评审尝试的实时连接。') }}</p></div>
            </div>
        </div>
        <button v-if="!followsLatest && conversation.length" class="conversation-latest" type="button" @click="goLatest">回到最新消息</button>
    </section>
</template>
