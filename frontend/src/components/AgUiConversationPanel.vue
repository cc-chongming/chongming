<script setup>
// [AIREVIEW-PLAN-034#3#6] Scout/review transcripts render through SafeMarkdown; names localized.
import AgUiToolCallMessage from './AgUiToolCallMessage.vue';
import SafeMarkdown from './SafeMarkdown.vue';

defineProps({
    conversation: { type: Object, required: true },
    variant: { type: String, default: 'review' }
});
</script>

<template>
    <section class="panel ag-ui-panel" aria-labelledby="ag-ui-title">
        <div class="panel-heading">
            <div>
                <p class="eyebrow">AG-UI · CUSTOM + TEXT_MESSAGE</p>
                <h2 id="ag-ui-title">{{ variant === 'scout' ? '上下文侦察执行流' : '公开对话流' }}</h2>
            </div>
            <span class="topic-status">{{ conversation.status }}</span>
        </div>
        <p class="muted">线程 {{ conversation.threadId }}。仅显示公开消息与受限原生工具的脱敏调用记录；协议中的 REASONING 事件不会写入或渲染。</p>
        <p v-if="conversation.error" class="error-banner" role="alert">{{ conversation.error.message }}（{{ conversation.error.code || 'RUN_ERROR' }}）</p>
        <ol v-if="variant === 'scout' && conversation.items.length" class="ag-ui-messages scout-conversation-timeline" aria-live="polite">
            <li v-for="item in conversation.items" :key="item.id" :class="['scout-timeline-item', item.type]">
                <AgUiToolCallMessage v-if="item.type === 'toolCall'" :item="item" />
                <article v-else class="ag-ui-message assistant">
                    <header><strong>{{ item.name || '上下文侦察' }}</strong><span>{{ item.status }}</span></header>
                    <SafeMarkdown :content="item.content" />
                </article>
            </li>
        </ol>
        <ol v-else-if="variant !== 'scout' && conversation.messages.length" class="ag-ui-messages">
            <li v-for="message in conversation.messages" :key="message.id" :class="['ag-ui-message', message.role]">
                <header><strong>{{ message.name || message.role }}</strong><span>{{ message.status }}</span></header>
                <SafeMarkdown :content="message.content" />
            </li>
        </ol>
        <p v-else class="empty-note">{{ variant === 'scout' ? '等待上下文侦察开始受限快照检索。' : '等待公开 Claim、质询、答辩或裁决事件。人工决定请使用下方审核表单。' }}</p>
    </section>
</template>
