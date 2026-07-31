<script setup>
import { computed, onUnmounted, ref, watch } from 'vue';
import { RouterLink } from 'vue-router';
import LiveAgentConversation from '../components/LiveAgentConversation.vue';
import { createReviewStore } from '../stores/review-store';
import { createRuntimeTraceStore } from '../stores/runtime-trace-store';

const props = defineProps({ reviewId: { type: String, required: true } });
const store = createReviewStore();
const runtimeTrace = createRuntimeTraceStore();
let loadGeneration = 0;
const stage = computed(() => store.state.summary?.stage ?? 'PENDING');
const participatingRoles = computed(() => new Set(
    runtimeTrace.state.events
        .map((event) => event?.value?.role)
        .filter((role) => typeof role === 'string' && role.trim())
).size);

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
                <p class="eyebrow">AG-UI 实时对话</p>
                <h1>评审 Agent 执行流</h1>
                <p class="connection" :data-status="runtimeTrace.state.status"><span aria-hidden="true">●</span>{{ runtimeTrace.state.status === 'connected' ? '运行流已连接' : '正在连接运行流' }}</p>
            </div>
            <div class="live-session-meta"><span>阶段 {{ stage }}</span><span>{{ participatingRoles }} 个 Agent 已出现</span><RouterLink class="button secondary" :to="{ name: 'review-workbench', params: { reviewId } }">返回评审工作台</RouterLink></div>
        </header>

        <p v-if="store.state.error" class="error-banner" role="alert">加载评审观察数据失败，请返回工作台查看正式状态。</p>
        <div v-else-if="store.state.loading" class="loading-grid" aria-label="正在连接评审运行流"><span></span><span></span><span></span></div>
        <template v-else>
            <p class="live-observatory-note">按实际到达顺序展示 Agent 思考、回答和工具调用。工具参数与结果保留原始内容；正式评审结论仍以工作台领域事件为准。</p>
            <LiveAgentConversation :events="runtimeTrace.state.events" :status="runtimeTrace.state.status" />
        </template>
    </section>
</template>
