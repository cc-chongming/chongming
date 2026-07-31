<script setup>
import AgUiToolCallMessage from './AgUiToolCallMessage.vue';

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
                <h2 id="ag-ui-title">{{ variant === 'scout' ? 'Context Scout 执行流' : '公开对话流' }}</h2>
            </div>
            <span class="topic-status">{{ conversation.status }}</span>
        </div>
        <p class="muted">线程 {{ conversation.threadId }}。仅显示公开消息与受限原生工具的脱敏调用记录；协议中的 REASONING 事件不会写入或渲染。</p>
        <p v-if="conversation.error" class="error-banner" role="alert">{{ conversation.error.message }}（{{ conversation.error.code || 'RUN_ERROR' }}）</p>
        <ol v-if="variant === 'scout' && conversation.items.length" class="ag-ui-messages scout-conversation-timeline" aria-live="polite">
            <li v-for="item in conversation.items" :key="item.id" :class="['scout-timeline-item', item.type]">
                <AgUiToolCallMessage v-if="item.type === 'toolCall'" :item="item" />
                <article v-else class="ag-ui-message assistant">
                    <header><strong>{{ item.name || 'Context Scout' }}</strong><span>{{ item.status }}</span></header>
                    <p>{{ item.content }}</p>
                </article>
            </li>
        </ol>
        <ol v-else-if="variant !== 'scout' && conversation.messages.length" class="ag-ui-messages">
            <li v-for="message in conversation.messages" :key="message.id" :class="['ag-ui-message', message.role]">
                <header><strong>{{ message.name || message.role }}</strong><span>{{ message.status }}</span></header>
                <p>{{ message.content }}</p>
            </li>
        </ol>
        <p v-else class="empty-note">{{ variant === 'scout' ? '等待 Context Scout 开始受限快照检索。' : '等待公开 Claim、质询、答辩或裁决事件。人工决定请使用下方审核表单。' }}</p>
    </section>
</template>
