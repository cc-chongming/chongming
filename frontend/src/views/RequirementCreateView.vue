<script setup>
import { reactive, ref } from 'vue';
import { useRouter } from 'vue-router';
import { formatApiError, reviewApi } from '../api/review-api';

const router = useRouter();
const file = ref(null);
const submitting = ref(false);
const error = ref('');
const savedDraftId = ref(null);
const form = reactive({
    title: '', description: '', assigneeId: '', repositoryPath: '', priority: 'P1', branch: '', commit: '', submitter: 'demo-reviewer',
    publicTasks: '核对需求范围、验收标准与实现风险', changeReason: '初始评审计划', initialMessage: '请根据公开计划开始需求评审。'
});

function tasks() { return form.publicTasks.split(/\r?\n/).map((item) => item.trim()).filter(Boolean); }
function onFileChange(event) { file.value = event.target.files?.[0] ?? null; }
function idempotencyKey() { return globalThis.crypto?.randomUUID?.() ?? `requirement-start-${Date.now()}`; }

async function submit() {
    error.value = '';
    if (!file.value?.name.toLowerCase().endsWith('.md')) { error.value = '请上传 Markdown 格式的评审需求文档。'; return; }
    if (!form.title.trim() || !form.repositoryPath.trim() || !form.submitter.trim() || !tasks().length) { error.value = '请填写需求标题、仓库、提交人和至少一项公开计划。'; return; }
    submitting.value = true;
    try {
        const requirement = await reviewApi.createRequirement({
            title: form.title.trim(), description: form.description, assigneeId: form.assigneeId, repositoryPath: form.repositoryPath.trim(), priority: form.priority
        });
        savedDraftId.value = requirement.id;
        const review = await reviewApi.createReview({ requirementFile: file.value, repositoryPath: form.repositoryPath.trim(), branch: form.branch.trim(), commit: form.commit.trim(), submitter: form.submitter.trim() });
        if (review.reused) {
            error.value = '该 Markdown 快照已对应既有评审。需求草稿已保存，不能覆盖或重启该评审；请先进入草稿修改后再提交。';
            return;
        }
        const submitted = await reviewApi.submitRequirement(requirement.id, { reviewId: review.reviewId, expectedVersion: requirement.version });
        await reviewApi.startReview(review.reviewId, {
            expectedVersion: 0, idempotencyKey: idempotencyKey(), userId: form.submitter.trim(), publicTasks: tasks(), changeReason: form.changeReason.trim(), initialMessage: form.initialMessage.trim()
        });
        await router.push({ name: 'requirement-detail', params: { requirementId: submitted.id } });
    } catch (requestError) {
        error.value = formatApiError(requestError);
    } finally { submitting.value = false; }
}

async function saveDraft() {
    error.value = '';
    if (!form.title.trim()) { error.value = '请先填写需求标题。'; return; }
    submitting.value = true;
    try {
        const requirement = await reviewApi.createRequirement({
            title: form.title.trim(), description: form.description, assigneeId: form.assigneeId, repositoryPath: form.repositoryPath.trim(), priority: form.priority
        });
        await router.push({ name: 'requirement-detail', params: { requirementId: requirement.id } });
    } catch (requestError) {
        error.value = formatApiError(requestError);
    } finally { submitting.value = false; }
}
</script>

<template>
    <section class="platform-page"><header class="platform-page-header"><div><p class="eyebrow">New Requirement</p><h1>新建需求并发起评审</h1><p class="muted">先创建需求聚合，再受理评审快照并绑定本次评审。</p></div></header>
        <form class="review-form create-form" @submit.prevent="submit"><p v-if="error" class="error-banner full" role="alert">{{ error }} <RouterLink v-if="savedDraftId" :to="`/requirements/${savedDraftId}`">查看已保存草稿</RouterLink></p>
            <label class="full">需求标题<input v-model="form.title" required maxlength="256" /></label><label class="full">需求描述<textarea v-model="form.description" /></label>
            <label>优先级<select v-model="form.priority"><option>P0</option><option>P1</option><option>P2</option><option>P3</option></select></label><label>负责人（可选）<input v-model="form.assigneeId" /></label>
            <label class="full">仓库标识<input v-model="form.repositoryPath" required placeholder="服务端白名单中的仓库路径或标识" /></label><label>分支（可选）<input v-model="form.branch" placeholder="main" /></label><label>Commit（可选）<input v-model="form.commit" placeholder="40 位 SHA" /></label>
            <label class="full">评审需求文档（.md）<input type="file" accept=".md,text/markdown" required @change="onFileChange" /></label><label class="full">提交人<input v-model="form.submitter" required /></label>
            <label class="full">公开评审计划（每行一项）<textarea v-model="form.publicTasks" required /></label><label class="full">计划原因<input v-model="form.changeReason" required /></label><label class="full">启动说明<textarea v-model="form.initialMessage" required /></label>
            <div class="form-actions full"><button class="button" type="submit" :disabled="submitting">{{ submitting ? '正在创建与启动…' : '创建需求并启动评审' }}</button><button class="button secondary" type="button" :disabled="submitting" @click="saveDraft">保存草稿</button><RouterLink class="button secondary" to="/requirements">取消</RouterLink></div>
        </form>
    </section>
</template>
