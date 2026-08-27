<script setup>
import { computed } from 'vue';
import SafeMarkdown from './SafeMarkdown.vue';
const props = defineProps({ role: { type: String, default: null }, events: { type: Array, default: () => [] }, embedded: { type: Boolean, default: false } });
const emit = defineEmits(['close']);
const visible = computed(() => props.events.filter((event) => ['TEXT_MESSAGE_CONTENT', 'TOOL_CALL_START', 'TOOL_CALL_RESULT', 'RUN_STARTED', 'RUN_FINISHED', 'RUN_ERROR'].includes(event.type)));
function label(event) { return event.type.replaceAll('_', ' '); }
function content(event) { return event.delta ?? event.content ?? event.message ?? event.value?.eventType ?? '运行状态已更新'; }
</script>
<template>
    <aside v-if="role" class="evidence-drawer agent-trace-drawer" :class="{ 'agent-trace-embedded': embedded }" aria-label="角色执行过程">
        <header class="drawer-heading"><div><p class="eyebrow">AG-UI 实时执行流</p><h2>{{ role }}</h2></div><button v-if="!embedded" class="button secondary" type="button" @click="emit('close')">关闭</button></header>
        <p class="muted">展示模型可见文本、工具调用与真实返回的运行事件；不展示宿主路径、密钥或其它角色的私有会话。</p>
        <ol class="ag-ui-messages"><li v-for="(event, index) in visible" :key="`${event.runId}-${index}`" class="ag-ui-message"><header><strong>{{ label(event) }}</strong><span>{{ event.runId }}</span></header><SafeMarkdown :content="String(content(event) ?? '')" /></li><li v-if="!visible.length" class="empty-note">该角色尚未产生可展示的 AG-UI 事件。</li></ol>
    </aside>
</template>
