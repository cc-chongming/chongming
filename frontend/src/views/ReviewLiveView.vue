<script setup>
import { computed, onUnmounted, ref, watch } from 'vue';
import { RouterLink } from 'vue-router';
import AgentTraceDrawer from '../components/AgentTraceDrawer.vue';
import ReviewRoundtable from '../components/ReviewRoundtable.vue';
import { createReviewStore } from '../stores/review-store';
import { createRuntimeTraceStore } from '../stores/runtime-trace-store';

const props = defineProps({ reviewId: { type: String, required: true } });
const store = createReviewStore();
const runtimeTrace = createRuntimeTraceStore();
let loadGeneration = 0;
const activeRole = ref('DIRECTOR');

const roundtableRoles = computed(() => {
    const roles = new Map(store.roles.value.map((role) => [role.role, role]));
    roles.set('DIRECTOR', roles.get('DIRECTOR') ?? { role: 'DIRECTOR', type: '主持中' });
    runtimeTrace.byRole.value.forEach((events, role) => {
        roles.set(role, { ...(roles.get(role) ?? {}), role, type: events.length ? '执行中' : '等待事件' });
    });
    return [...roles.values()];
});

const scoutStatus = computed(() => {
    const degradation = store.state.summary?.contextScout;
    if (degradation?.status === 'DEGRADED') {
        return `Context Scout 已降级（${degradation.reasonCode}）：${degradation.publicSummary}`;
    }
    const events = runtimeTrace.byRole.value.get('CONTEXT_SCOUT') ?? [];
    if (!events.length) return '等待 Context Scout 初始化共享项目上下文';
    const last = events.at(-1);
    if (last?.type === 'RUN_FINISHED') return 'Context Scout 已完成共享项目上下文准备';
    if (last?.type === 'RUN_ERROR') return 'Context Scout 出现运行异常，正在降级为由 Director 继续评审';
    return 'Context Scout 正在只读检索冻结快照，准备角色定向上下文';
});

async function load(reviewId) {
    const generation = ++loadGeneration;
    await store.load(reviewId);
    if (generation !== loadGeneration || reviewId !== props.reviewId) return;
    runtimeTrace.start(reviewId, store.state.summary?.attempt ?? 1);
}

watch(() => props.reviewId, load, { immediate: true });
onUnmounted(() => { store.dispose(); runtimeTrace.dispose(); });
</script>

<template>
    <section class="live-observatory-page">
        <header class="workbench-header">
            <div>
                <p class="eyebrow">AG-UI 实时观察台</p>
                <h1>Director 主持评审</h1>
                <p class="connection" :data-status="runtimeTrace.state.status"><span aria-hidden="true">●</span>{{ runtimeTrace.state.status === 'connected' ? '运行流已连接' : '正在连接运行流' }}</p>
            </div>
            <RouterLink class="button secondary" :to="{ name: 'review-workbench', params: { reviewId } }">返回评审工作台</RouterLink>
        </header>

        <p v-if="store.state.error" class="error-banner" role="alert">加载评审观察数据失败，请返回工作台查看正式状态。</p>
        <div v-else-if="store.state.loading" class="loading-grid" aria-label="正在连接评审运行流"><span></span><span></span><span></span></div>
        <template v-else>
            <p class="live-observatory-note" data-testid="context-scout-status">{{ scoutStatus }}</p>
            <ReviewRoundtable :events="store.events.value" :roles="roundtableRoles" @inspect-role="(role) => { activeRole = role; }" />
            <AgentTraceDrawer embedded :role="activeRole" :events="runtimeTrace.byRole.value.get(activeRole) ?? []" />
            <p class="live-observatory-note">正式结论以评审工作台的领域事件为准；本页面只观察同一评审尝试的实时执行流。</p>
        </template>
    </section>
</template>
