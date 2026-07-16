<script setup>
import { computed, reactive, ref, watch } from 'vue';

const props = defineProps({
    summary: { type: Object, default: null },
    busy: { type: Boolean, default: false },
    message: { type: String, default: '' }
});
const emit = defineEmits(['start', 'cancel', 'retry', 'refresh']);
const formError = ref('');
const startForm = reactive({
    userId: 'demo-reviewer',
    publicTasks: '核对需求范围、验收标准与实现风险',
    changeReason: '初始评审计划',
    initialMessage: '请根据公开计划开始需求评审。',
    idempotencyKey: createIdempotencyKey()
});

const stage = computed(() => props.summary?.stage ?? 'STARTING');
const version = computed(() => props.summary?.reviewVersion ?? null);
const terminal = computed(() => ['COMPLETED', 'FAILED', 'CANCELLED'].includes(stage.value));
const canStart = computed(() => stage.value === 'PENDING' && version.value !== null);
const canCancel = computed(() => version.value !== null && !terminal.value);
const canRetry = computed(() => version.value !== null && terminal.value);

watch(() => props.summary?.attempt, () => {
    startForm.idempotencyKey = createIdempotencyKey();
});

function createIdempotencyKey() {
    return globalThis.crypto?.randomUUID?.() ?? `start-${Date.now()}-${Math.random().toString(36).slice(2)}`;
}

function start() {
    const publicTasks = startForm.publicTasks.split(/\r?\n/).map((task) => task.trim()).filter(Boolean);
    if (!startForm.userId.trim() || publicTasks.length === 0 || !startForm.changeReason.trim() || !startForm.initialMessage.trim()) {
        formError.value = '请填写操作人、至少一项公开计划、计划原因和启动说明。';
        return;
    }
    formError.value = '';
    emit('start', {
        expectedVersion: version.value,
        idempotencyKey: startForm.idempotencyKey,
        userId: startForm.userId.trim(),
        publicTasks,
        changeReason: startForm.changeReason.trim(),
        initialMessage: startForm.initialMessage.trim()
    });
}
</script>

<template>
    <section class="panel lifecycle-panel" aria-labelledby="lifecycle-title">
        <div class="panel-heading">
            <div>
                <p class="eyebrow">评审命令</p>
                <h2 id="lifecycle-title">启动与生命周期</h2>
            </div>
            <span class="topic-status">{{ stage }}</span>
        </div>
        <p class="muted">版本 {{ version ?? '等待服务端事件' }}。命令使用服务端乐观锁；SSE 才是实时进度事实。</p>
        <p v-if="message" class="inline-message" role="status">{{ message }}</p>

        <form v-if="canStart" class="review-form compact lifecycle-form" @submit.prevent="start">
            <p v-if="formError" class="error-banner full" role="alert">{{ formError }}</p>
            <label class="full">操作人<input v-model="startForm.userId" maxlength="128" required /></label>
            <label class="full">公开评审计划（每行一项）<textarea v-model="startForm.publicTasks" required /></label>
            <label class="full">计划原因<input v-model="startForm.changeReason" maxlength="512" required /></label>
            <label class="full">启动说明<textarea v-model="startForm.initialMessage" required /></label>
            <p class="muted full">重复提交会使用同一 Idempotency-Key 安全重放，不会创建第二次启动。</p>
            <div class="form-actions full"><button class="button" type="submit" :disabled="busy">{{ busy ? '正在启动…' : '开始评审' }}</button></div>
        </form>

        <div v-else class="lifecycle-actions">
            <button class="button secondary" type="button" :disabled="busy || version === null" @click="emit('refresh')">刷新状态</button>
            <button v-if="canCancel" class="button danger-button" type="button" :disabled="busy" @click="emit('cancel')">取消评审</button>
            <button v-if="canRetry" class="button" type="button" :disabled="busy" @click="emit('retry')">创建重试尝试</button>
            <p v-if="stage === 'STARTING'" class="empty-note">启动命令已受理，正在等待计划与角色事件。</p>
        </div>
    </section>
</template>
