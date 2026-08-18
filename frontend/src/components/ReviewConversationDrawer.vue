<script setup>
// [AIREVIEW-PLAN-023#7.3] Global conversation, review facts and runtime diagnostics share one drawer.
import { computed, nextTick, onMounted, onUnmounted, ref, watch } from 'vue';
import { formatChinaTime } from '../services/china-time';
import LiveAgentConversation from './LiveAgentConversation.vue';

const props = defineProps({
    open: { type: Boolean, default: true },
    items: { type: Array, default: () => [] },
    facts: { type: Array, default: () => [] },
    debugItems: { type: Array, default: () => [] }
});
const emit = defineEmits(['close']);
const activeTab = ref('conversation');
const roleFilter = ref('ALL');
const drawer = ref(null);
const closeButton = ref(null);
const overlayMedia = globalThis.matchMedia?.('(max-width: 1200px)') ?? null;
const isOverlay = ref(overlayMedia?.matches ?? false);
let previousFocus = null;
const roles = computed(() => [...new Set(props.items.map((item) => item.role).filter(Boolean))]);
const filteredItems = computed(() => roleFilter.value === 'ALL' ? props.items : props.items.filter((item) => item.role === roleFilter.value));

function roleTitle(role) {
    return { CONTEXT_SCOUT: 'Scout', DIRECTOR: 'Director', PRODUCT: '产品', PROJECT: '项目', FRONTEND: '前端', BACKEND: '后端', ARCHITECTURE: '架构', SECURITY: '安全', TESTING: '测试', PERFORMANCE: '性能', JUDGE: 'Judge' }[role] ?? role;
}

function onKeydown(event) {
    if (event.key === 'Escape' && props.open) {
        emit('close');
        return;
    }
    if (event.key !== 'Tab' || !props.open || !isOverlay.value || !drawer.value) return;
    const focusable = [...drawer.value.querySelectorAll('button:not([disabled]), select:not([disabled]), input:not([disabled]), textarea:not([disabled]), summary, a[href], [tabindex]:not([tabindex="-1"])')];
    if (!focusable.length) return;
    const first = focusable[0];
    const last = focusable.at(-1);
    if (event.shiftKey && document.activeElement === first) {
        event.preventDefault();
        last.focus();
    } else if (!event.shiftKey && document.activeElement === last) {
        event.preventDefault();
        first.focus();
    }
}

function syncOverlay(event) {
    isOverlay.value = event.matches;
}

watch(() => props.open, (open) => {
    if (open) {
        previousFocus = document.activeElement;
        if (isOverlay.value) nextTick(() => closeButton.value?.focus());
    } else if (previousFocus?.focus) previousFocus.focus();
}, { immediate: true });
onMounted(() => {
    document.addEventListener('keydown', onKeydown);
    overlayMedia?.addEventListener?.('change', syncOverlay);
});
onUnmounted(() => {
    document.removeEventListener('keydown', onKeydown);
    overlayMedia?.removeEventListener?.('change', syncOverlay);
});
</script>

<template>
    <div v-if="open" class="flow-drawer-overlay" aria-hidden="true" @click="$emit('close')"></div>
    <aside v-show="open" ref="drawer" class="flow-sidebar flow-conversation-drawer" aria-label="评审观察抽屉" :role="isOverlay ? 'dialog' : 'complementary'" :aria-modal="isOverlay ? 'true' : undefined" tabindex="-1">
        <header class="flow-drawer-header"><strong>评审观察</strong><button ref="closeButton" type="button" aria-label="关闭观察抽屉" @click="$emit('close')">×</button></header>
        <div class="flow-sidebar-tabs" role="tablist">
            <button id="drawer-tab-conversation" type="button" role="tab" aria-controls="drawer-panel-conversation" :tabindex="activeTab === 'conversation' ? 0 : -1" :aria-selected="activeTab === 'conversation'" :class="{ active: activeTab === 'conversation' }" @click="activeTab = 'conversation'">全部对话</button>
            <button id="drawer-tab-facts" type="button" role="tab" aria-controls="drawer-panel-facts" :tabindex="activeTab === 'facts' ? 0 : -1" :aria-selected="activeTab === 'facts'" :class="{ active: activeTab === 'facts' }" @click="activeTab = 'facts'">评审事实</button>
            <button id="drawer-tab-debug" type="button" role="tab" aria-controls="drawer-panel-debug" :tabindex="activeTab === 'debug' ? 0 : -1" :aria-selected="activeTab === 'debug'" :class="{ active: activeTab === 'debug' }" @click="activeTab = 'debug'">运行调试</button>
        </div>
        <div v-if="activeTab === 'conversation'" id="drawer-panel-conversation" role="tabpanel" aria-labelledby="drawer-tab-conversation">
            <label class="flow-role-filter">角色筛选<select v-model="roleFilter"><option value="ALL">全部角色</option><option v-for="role in roles" :key="role" :value="role">{{ roleTitle(role) }}</option></select></label>
            <LiveAgentConversation compact :items="filteredItems" />
        </div>
        <ol v-else-if="activeTab === 'facts'" id="drawer-panel-facts" class="flow-fact-timeline" role="tabpanel" aria-labelledby="drawer-tab-facts">
            <li v-for="event in facts" :key="event.sequence" :data-type="event.type"><span></span><div><strong>{{ event.title }}</strong><p>{{ event.detail }}</p><small>#{{ event.sequence }} · {{ formatChinaTime(event.occurredAt) }}</small></div></li>
            <li v-if="!facts.length" class="flow-sidebar-empty">尚未收到持久化评审事实。</li>
        </ol>
        <ol v-else id="drawer-panel-debug" class="flow-debug-list" role="tabpanel" aria-labelledby="drawer-tab-debug">
            <li v-for="item in debugItems" :key="item.id"><strong>{{ roleTitle(item.role) }}</strong><span>{{ item.summary }}</span></li>
            <li v-if="!debugItems.length" class="flow-sidebar-empty">暂无连接、生命周期或错误诊断。</li>
        </ol>
    </aside>
</template>
