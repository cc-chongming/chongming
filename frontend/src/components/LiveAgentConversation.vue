<script setup>
import { computed } from 'vue';
import { buildRuntimeConversation } from '../services/runtime-conversation-adapter';

const props = defineProps({
    events: { type: Array, default: () => [] },
    status: { type: String, default: 'idle' },
    emptyState: { type: Object, default: null }
});

const conversation = computed(() => buildRuntimeConversation(props.events));

function roleTitle(role) {
    return {
        DIRECTOR: '主持人 Director',
        CONTEXT_SCOUT: 'Context Scout',
        PRODUCT: '产品经理',
        PROJECT: '项目经理',
        ARCHITECTURE: '架构师',
        BACKEND: '后端工程师',
        FRONTEND: '前端工程师',
        TESTING: '测试工程师',
        PERFORMANCE: '性能工程师',
        SECURITY: '安全工程师',
        JUDGE: 'Judge'
    }[role] ?? role;
}

function roleInitial(role) {
    return role === 'CONTEXT_SCOUT' ? 'S' : String(role ?? 'A').slice(0, 1);
}

function toolStatus(status) {
    return {
        RUNNING: '正在调用', SUCCESS: '调用完成', ERROR: '调用失败',
        DENIED: '调用被拒绝', INTERRUPTED: '调用中断'
    }[status] ?? status ?? '正在调用';
}

function thoughtSummary(content) {
    const compact = String(content ?? '').replaceAll(/\s+/g, ' ').trim();
    return compact ? `思考过程：${compact.slice(0, 96)}${compact.length > 96 ? '…' : ''}` : '正在思考…';
}

function format(value) {
    if (value == null) return '等待工具返回…';
    if (typeof value === 'string') return value;
    try {
        return JSON.stringify(value, null, 2);
    } catch {
        return String(value);
    }
}
</script>

<template>
    <section class="live-agent-conversation" aria-label="评审 Agent 实时对话">
        <ol v-if="conversation.length" class="live-agent-timeline" aria-live="polite">
            <li v-for="item in conversation" :key="item.id" :class="['live-agent-entry', `is-${item.kind}`]">
                <div class="agent-avatar" :data-role="item.role" aria-hidden="true">{{ roleInitial(item.role) }}</div>
                <article class="agent-dialogue">
                    <header class="agent-dialogue-header">
                        <strong>{{ roleTitle(item.role) }}</strong>
                        <span v-if="item.kind === 'thinking' && item.status === 'streaming'">思考中</span>
                        <span v-else-if="item.kind === 'message' && item.status === 'streaming'">输出中</span>
                    </header>

                    <details v-if="item.kind === 'thinking'" class="agent-thinking" :open="item.status === 'streaming'">
                        <summary>{{ thoughtSummary(item.content) }}</summary>
                        <pre>{{ item.content || '正在等待模型返回可展示的思考内容…' }}</pre>
                    </details>

                    <div v-else-if="item.kind === 'message'" class="agent-answer">{{ item.content }}</div>

                    <details v-else-if="item.kind === 'tool'" class="agent-tool-call" :data-status="item.status" :open="item.status === 'RUNNING'">
                        <summary>
                            <span class="tool-call-symbol" aria-hidden="true">›</span>
                            <strong>调用工具</strong>
                            <code>{{ item.toolName }}</code>
                            <span class="tool-call-status">{{ toolStatus(item.status) }}</span>
                            <span v-if="item.elapsedMs != null" class="tool-call-elapsed">{{ item.elapsedMs }}ms</span>
                        </summary>
                        <div class="agent-tool-details">
                            <section>
                                <h3>参数</h3>
                                <pre>{{ format(item.input) }}</pre>
                            </section>
                            <section>
                                <h3>结果</h3>
                                <pre>{{ format(item.output) }}</pre>
                            </section>
                        </div>
                    </details>

                    <p v-else class="agent-runtime-notice">{{ item.content }}</p>
                </article>
            </li>
        </ol>
        <div v-else class="live-agent-empty" :class="{ 'is-terminal-notice': emptyState }" :role="emptyState ? 'alert' : undefined">
            <span class="agent-avatar" aria-hidden="true">…</span>
            <div><strong>{{ emptyState?.title ?? '等待 Agent 运行事件' }}</strong><p>{{ emptyState?.message ?? (status === 'connected' ? '实时连接已建立，新的思考、回答和工具调用会直接出现在这里。' : '正在建立同一评审尝试的实时连接。') }}</p></div>
        </div>
    </section>
</template>
