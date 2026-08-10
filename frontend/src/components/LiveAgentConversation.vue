<script setup>
// [AIREVIEW-PLAN-023#7.1] Unified public AI conversation for every review Agent.
import { computed, nextTick, ref, watch } from 'vue';
import { buildRuntimeConversation } from '../services/runtime-conversation-adapter';
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
const scrollPanel = ref(null);
const followsLatest = ref(true);

function roleTitle(role) {
    return {
        DIRECTOR: 'Director 协调者', CONTEXT_SCOUT: 'Context Scout', PRODUCT: '产品经理', PROJECT: '项目经理',
        ARCHITECTURE: '架构师', BACKEND: '后端工程师', FRONTEND: '前端工程师', TESTING: '测试工程师',
        PERFORMANCE: '性能工程师', SECURITY: '安全工程师', JUDGE: 'Judge 裁决者'
    }[role] ?? role ?? 'Agent';
}

function roleInitial(role) {
    return role === 'CONTEXT_SCOUT' ? 'S' : String(role ?? 'A').slice(0, 1);
}

function displayTime(value) {
    if (!value) return '';
    const date = new Date(value);
    return Number.isNaN(date.getTime()) ? value : date.toLocaleTimeString('zh-CN', { hour: '2-digit', minute: '2-digit', second: '2-digit' });
}

function updateFollowState() {
    const element = scrollPanel.value;
    if (!element) return;
    followsLatest.value = element.scrollHeight - element.scrollTop - element.clientHeight < 48;
}

function goLatest() {
    followsLatest.value = true;
    nextTick(() => scrollPanel.value?.scrollTo({ top: scrollPanel.value.scrollHeight, behavior: 'smooth' }));
}

watch(conversation, () => {
    if (!followsLatest.value) return;
    nextTick(() => scrollPanel.value?.scrollTo({ top: scrollPanel.value.scrollHeight }));
}, { deep: true });
</script>

<template>
    <section :class="['live-agent-conversation', { compact }]" aria-label="评审 Agent 公开对话">
        <div ref="scrollPanel" class="live-agent-scroll" @scroll.passive="updateFollowState">
            <ol v-if="conversation.length" class="live-agent-timeline" aria-live="polite">
                <li v-for="item in conversation" :key="item.id" :class="['live-agent-entry', `is-${item.kind}`]">
                    <template v-if="item.kind === 'message'">
                        <div class="agent-avatar" :data-role="item.role" aria-hidden="true">{{ roleInitial(item.role) }}</div>
                        <article class="agent-dialogue">
                            <header class="agent-dialogue-header">
                                <strong>{{ roleTitle(item.role) }}</strong>
                                <span v-if="item.phase">{{ item.phase }}</span>
                                <time v-if="item.createdAt">{{ displayTime(item.createdAt) }}</time>
                                <span v-if="item.status === 'streaming'" class="agent-streaming">输出中</span>
                            </header>
                            <SafeMarkdown class="agent-answer" :content="item.content" />
                        </article>
                    </template>
                    <template v-else-if="item.kind === 'tool'">
                        <span class="agent-entry-spacer" aria-hidden="true"></span>
                        <AgUiToolCallMessage :item="item" />
                    </template>
                    <template v-else>
                        <span class="agent-entry-spacer" aria-hidden="true"></span>
                        <p class="agent-runtime-notice" role="status">{{ item.content }}</p>
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
