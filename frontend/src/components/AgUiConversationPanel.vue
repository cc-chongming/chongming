<script setup>
defineProps({ conversation: { type: Object, required: true } });
</script>

<template>
    <section class="panel ag-ui-panel" aria-labelledby="ag-ui-title">
        <div class="panel-heading">
            <div>
                <p class="eyebrow">AG-UI · CUSTOM + TEXT_MESSAGE</p>
                <h2 id="ag-ui-title">公开对话流</h2>
            </div>
            <span class="topic-status">{{ conversation.status }}</span>
        </div>
        <p class="muted">线程 {{ conversation.threadId }}。仅显示公开消息；协议中的 REASONING 事件不会写入或渲染。</p>
        <p v-if="conversation.error" class="error-banner" role="alert">{{ conversation.error.message }}（{{ conversation.error.code || 'RUN_ERROR' }}）</p>
        <ol v-if="conversation.messages.length" class="ag-ui-messages">
            <li v-for="message in conversation.messages" :key="message.id" :class="['ag-ui-message', message.role]">
                <header><strong>{{ message.name || message.role }}</strong><span>{{ message.status }}</span></header>
                <p>{{ message.content }}</p>
            </li>
        </ol>
        <p v-else class="empty-note">等待公开 Claim、质询、答辩或裁决事件。人工决定请使用下方审核表单。</p>
    </section>
</template>
