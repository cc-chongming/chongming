<script setup>
import { reactive, ref } from 'vue';
import { useRouter } from 'vue-router';
import { formatApiError, reviewApi } from '../api/review-api';
import RepositorySelect from '../components/RepositorySelect.vue';

// [AIREVIEW-PLAN-023#2] Repository input is constrained to the active backend configuration.

const router = useRouter();
const requirementFile = ref(null);
const submitting = ref(false);
const error = ref('');
const acceptedReview = ref(null);
const startIdempotencyKey = ref(createIdempotencyKey());
const form = reactive({
    repositoryPath: '',
    branch: '',
    commit: '',
    submitter: 'demo-reviewer',
    forceNewAttempt: false,
    publicTasks: '核对需求范围、验收标准与实现风险',
    changeReason: '初始评审计划',
    initialMessage: '请根据公开计划开始需求评审。'
});

function createIdempotencyKey() {
    return globalThis.crypto?.randomUUID?.() ?? `start-${Date.now()}-${Math.random().toString(36).slice(2)}`;
}

function publicTasks() {
    return form.publicTasks.split(/\r?\n/).map((task) => task.trim()).filter(Boolean);
}

function onFileChange(event) {
    requirementFile.value = event.target.files?.[0] ?? null;
}

async function submit() {
    error.value = '';
    if (!requirementFile.value) {
        error.value = '请选择一个 .md 需求文件。';
        return;
    }
    if (!requirementFile.value.name.toLowerCase().endsWith('.md')) {
        error.value = '需求文件必须使用 .md 扩展名。';
        return;
    }
    if (!form.repositoryPath.trim() || !form.submitter.trim()) {
        error.value = '请填写仓库路径和提交人。';
        return;
    }
    if (publicTasks().length === 0 || !form.changeReason.trim() || !form.initialMessage.trim()) {
        error.value = '请至少填写一项公开计划、计划原因和启动说明。';
        return;
    }
    submitting.value = true;
    try {
        if (!acceptedReview.value) {
            acceptedReview.value = await reviewApi.createReview({
                requirementFile: requirementFile.value,
                repositoryPath: form.repositoryPath.trim(),
                branch: form.branch.trim(),
                commit: form.commit.trim(),
                submitter: form.submitter.trim(),
                forceNewAttempt: form.forceNewAttempt
            });
        }
        await reviewApi.startReview(acceptedReview.value.reviewId, {
            expectedVersion: 0,
            idempotencyKey: startIdempotencyKey.value,
            userId: form.submitter.trim(),
            publicTasks: publicTasks(),
            changeReason: form.changeReason.trim(),
            initialMessage: form.initialMessage.trim()
        });
        await router.push({ name: 'review-workbench', params: { reviewId: acceptedReview.value.reviewId } });
    } catch (requestError) {
        error.value = formatApiError(requestError);
    } finally {
        submitting.value = false;
    }
}
</script>

<template>
    <section class="create-page">
        <div class="hero">
            <p class="eyebrow">可回放 · 可追溯 · 人工 Gate</p>
            <h1>创建一场需求评审</h1>
            <p>上传 Markdown 需求、选择已受服务端白名单保护的仓库标识，然后进入实时辩论工作台。</p>
        </div>
        <form class="review-form create-form" @submit.prevent="submit">
            <p v-if="error" class="error-banner" role="alert">{{ error }}</p>
            <label class="full">需求文档（.md）<input type="file" accept=".md,text/markdown" required @change="onFileChange" /></label>
            <RepositorySelect v-model="form.repositoryPath" class="full" required />
            <label>分支（可选）<input v-model="form.branch" placeholder="main" /></label>
            <label>Commit（可选）<input v-model="form.commit" placeholder="40 位 SHA" /></label>
            <label class="full">提交人<input v-model="form.submitter" maxlength="128" required /></label>
            <label class="full">公开评审计划（每行一项）<textarea v-model="form.publicTasks" required /></label>
            <label class="full">计划原因<input v-model="form.changeReason" maxlength="512" required /></label>
            <label class="full">启动说明<textarea v-model="form.initialMessage" required /></label>
            <label class="checkbox full"><input v-model="form.forceNewAttempt" type="checkbox" />即使快照相同也创建新的评审尝试</label>
            <p class="muted full">先受理快照，再使用同一幂等键启动评审；启动成功后工作台通过 SSE 恢复状态。前端校验不会替代服务端权限与路径校验。</p>
            <div class="form-actions full"><button class="button" type="submit" :disabled="submitting">{{ submitting ? '正在提交…' : '创建并启动评审' }}</button></div>
        </form>
    </section>
</template>
