<script setup>
import { computed, onUnmounted, reactive, ref, watch } from 'vue';
import { RouterLink } from 'vue-router';
import AgUiConversationPanel from '../components/AgUiConversationPanel.vue';
import { formatApiError, reviewApi } from '../api/review-api';
import { applyAgUiEvent, createAgUiConversation } from '../services/ag-ui-review-adapter';
import { createScoutPreviewSubscription } from '../services/scout-preview-sse';
import { createReviewStore } from '../stores/review-store';

const props = defineProps({ reviewId: { type: String, required: true } });
const store = createReviewStore();
const conversation = reactive(createAgUiConversation(`review:${props.reviewId}:scout-preview`));
const preview = ref(null);
const busy = ref(false);
const connection = ref('idle');
const error = ref(null);
let subscription;

const attemptNo = computed(() => store.state.summary?.attempt ?? null);
const statusText = computed(() => ({
    idle: '等待启动', connected: '实时流已连接', reconnecting: '正在恢复连接',
    'malformed-event': '收到无效运行事件', unavailable: '浏览器不支持实时连接', closed: '连接已关闭'
}[connection.value] ?? connection.value));

function resetRun() {
    subscription?.close();
    conversation.threadId = `review:${props.reviewId}:scout-preview`;
    conversation.runId = null;
    conversation.status = 'idle';
    conversation.error = null;
    conversation.messages = [];
    conversation.items = [];
}

function onEvent(event) {
    applyAgUiEvent(conversation, event);
}

async function load(reviewId) {
    resetRun();
    preview.value = null;
    error.value = null;
    await store.load(reviewId);
}

async function startPreview() {
    if (!attemptNo.value) return;
    busy.value = true;
    error.value = null;
    resetRun();
    try {
        preview.value = await reviewApi.startScoutPreview(props.reviewId, attemptNo.value, {
            userId: 'scout-preview', traceId: `scout-preview-${crypto.randomUUID?.() ?? Date.now()}`
        });
        subscription = createScoutPreviewSubscription({
            reviewId: props.reviewId,
            attemptNo: attemptNo.value,
            previewId: preview.value.previewId,
            onEvent,
            onState: ({ status }) => { connection.value = status; }
        });
    } catch (cause) {
        error.value = cause;
    } finally {
        busy.value = false;
    }
}

watch(() => props.reviewId, load, { immediate: true });
onUnmounted(() => { subscription?.close(); store.dispose(); });
</script>

<template>
    <section class="live-observatory-page">
        <header class="workbench-header">
            <div>
                <p class="eyebrow">上下文侦察独立预览</p>
                <h1>项目上下文侦察</h1>
                <p class="connection" :data-status="connection"><span aria-hidden="true">●</span>{{ statusText }}</p>
            </div>
            <RouterLink class="button secondary" :to="{ name: 'review-live', params: { reviewId } }">返回实时观察台</RouterLink>
        </header>

        <p class="live-observatory-note">此入口只读取当前评审尝试绑定的冻结快照；不会启动协调者、角色、辩论或关口，也不会把预览结果写入正式角色上下文。</p>
        <p v-if="store.state.error || error" class="error-banner" role="alert">{{ formatApiError(store.state.error || error) }}</p>
        <div v-else-if="store.state.loading" class="loading-grid" aria-label="正在加载评审数据"><span></span><span></span><span></span></div>
        <template v-else>
            <section class="panel">
                <div class="panel-heading">
                    <div><p class="eyebrow">受限 AS2 工作区</p><h2>执行侦察</h2></div>
                    <span class="topic-status">尝试 #{{ attemptNo }}</span>
                </div>
                <p class="muted">执行策略：根目录与构建识别、需求定向检索、高相关文件读取，最多三轮收敛。模型仅输出中文项目概览。</p>
                <div class="form-actions"><button class="button" type="button" :disabled="busy" @click="startPreview">{{ busy ? '正在启动…' : '运行上下文侦察' }}</button><span v-if="preview" class="muted">预览 {{ preview.previewId }}</span></div>
            </section>
            <AgUiConversationPanel variant="scout" :conversation="conversation" />
        </template>
    </section>
</template>
