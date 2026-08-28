<script setup>
// [AIREVIEW-PLAN-023#7.1] Unified public AI conversation for every review Agent.
// [AIREVIEW-PLAN-034#1#2#4#6] De-bubbled answers, Codex-style tool groups, scroll-follow fix, Chinese role labels.
import { computed, nextTick, onUnmounted, ref, watch } from 'vue';
import { buildRuntimeConversation } from '../services/runtime-conversation-adapter';
import { formatChinaClock } from '../services/china-time';
import AgUiToolCallMessage from './AgUiToolCallMessage.vue';
import SafeMarkdown from './SafeMarkdown.vue';
// [AIREVIEW-PLAN-049#1] 角色头像资产组件：替换消息行 agent-avatar 的字符内容（缺图时组件内回退字符）。
import RoleAvatar from './RoleAvatar.vue';

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
// [AIREVIEW-PLAN-054#1] 单工具也折叠成组：摘要复用 AgUiToolCallMessage 的中文工具名与状态口径。
const TOOL_LABELS = {
    list_files: '列出文件', glob_files: '按模式查找文件', grep_files: '检索文件内容',
    read_file: '读取文件', search_text: '检索代码', open_debate_topic: '创建辩论议题'
};
const TOOL_STATUS_LABEL = {
    RUNNING: '进行中', STREAMING: '进行中', SUCCESS: '已完成', COMPLETED: '已完成',
    ERROR: '失败', FAILED: '失败', DENIED: '已拒绝', INTERRUPTED: '已中断'
};
function toolLabel(tool) {
    return TOOL_LABELS[tool.toolName] ?? tool.toolName ?? '未知工具';
}
function toolEffectiveStatus(tool) {
    // 与 AgUiToolCallMessage.effectiveStatus 同口径：终态 status 优先于生命周期 phase；phase/output 失败系归 ERROR。
    const status = String(tool.status ?? 'RUNNING').toUpperCase();
    if (status === 'SUCCESS') return 'SUCCESS';
    const phase = String(tool.phase ?? '').toUpperCase();
    const outputState = String(tool.output?.state ?? tool.output?.status ?? tool.output?.resultState ?? '').toUpperCase();
    if (['FAILED', 'ERROR'].includes(phase) || ['FAILED', 'ERROR', 'DENIED', 'INTERRUPTED'].includes(outputState)) return 'ERROR';
    return status;
}
function toolStatusLabel(tool) {
    return TOOL_STATUS_LABEL[toolEffectiveStatus(tool)] ?? toolEffectiveStatus(tool) ?? '未知状态';
}
const rows = computed(() => {
    const out = [];
    let buffer = [];
    const flush = () => {
        if (buffer.length) out.push({ kind: 'tool-group', id: `tool-group:${buffer[0].id}`, tools: [...buffer] });
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
                        <!-- [AIREVIEW-PLAN-049#1] 头像资产替换字符内容（省略占位行保持“…”不变） -->
                        <div class="agent-avatar" :data-role="row.role" aria-hidden="true"><RoleAvatar :role="row.role" /></div>
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
                    <!-- [AIREVIEW-PLAN-054#1] 防御分支：rows 已不再产出单工具直通行，保留以防历史事件流直接携带 kind=tool。 -->
                    <template v-else-if="row.kind === 'tool'">
                        <span class="agent-entry-spacer" aria-hidden="true"></span>
                        <AgUiToolCallMessage :item="row" />
                    </template>
                    <template v-else-if="row.kind === 'tool-group'">
                        <span class="agent-entry-spacer" aria-hidden="true"></span>
                        <details class="tool-group">
                            <!-- [AIREVIEW-PLAN-054#1] 单工具组摘要：{工具中文名} · {状态文案}；多工具保持“N 个工具调用 + groupStatusLabel”。 -->
                            <summary :aria-label="row.tools.length === 1
                                ? `${toolLabel(row.tools[0])}，${toolStatusLabel(row.tools[0])}`
                                : `共 ${row.tools.length} 个工具调用，${groupStatusLabel(row)}`">
                                <span class="tool-call-symbol" aria-hidden="true"><svg class="tool-call-icon" viewBox="0 0 16 16" fill="none" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round"><rect x="1.75" y="2.75" width="12.5" height="10.5" rx="1.75"/><path d="M4.75 6.25 6.75 8l-2 1.75"/><path d="M8.25 10.25h3"/></svg></span>
                                <strong>{{ row.tools.length === 1
                                    ? `${toolLabel(row.tools[0])} · ${toolStatusLabel(row.tools[0])}`
                                    : `${row.tools.length} 个工具调用` }}</strong>
                                <span v-if="row.tools.length > 1" class="tool-call-status">{{ groupStatusLabel(row) }}</span>
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
