<script setup>
import { computed, onMounted, reactive, ref } from 'vue';
import { useRouter } from 'vue-router';
import { formatApiError, reviewApi } from '../api/review-api';
import { authStore } from '../stores/auth-store';
import RepositorySourcePicker from '../components/RepositorySourcePicker.vue';
import RequirementDocInput from '../components/RequirementDocInput.vue';

// [AIREVIEW-PLAN-023#2] Configured repositories stay constrained to the backend whitelist.
// [AIREVIEW-PLAN-029] Online repositories are supplied directly at creation (url + token).

const router = useRouter();
const file = ref(null);
// [AIREVIEW-PLAN-025] Requirement Markdown may be uploaded or typed directly.
const docMode = ref('file');
const manualMarkdown = ref('');
const submitting = ref(false);
const error = ref('');
const savedDraftId = ref(null);
const reusedReviewId = ref(null);
const repositoryState = ref('loading');
const configuredRepositoryIds = ref([]);
const form = reactive({
    title: '', description: '', priority: 'P1', branch: 'main', commit: '',
    publicTasks: '核对需求范围、验收标准与实现风险', changeReason: '初始评审计划', initialMessage: '请根据公开计划开始需求评审.', remark: ''
});
// The submitter is always the logged-in account; no manual entry needed.
const submitter = computed(() => authStore.currentUser.value?.username ?? '');
// [AIREVIEW-PLAN-029] Repository binding mode: configured whitelist or caller-supplied online source.
const repoSource = ref({ mode: 'configured', repositoryPath: '', remoteUrl: '', remoteRef: '', remoteToken: '' });
const isRemoteSource = computed(() => repoSource.value.mode === 'remote');

function tasks() { return form.publicTasks.split(/\r?\n/).map((item) => item.trim()).filter(Boolean); }
function idempotencyKey() { return globalThis.crypto?.randomUUID?.() ?? `requirement-start-${Date.now()}`; }
function requirementDocumentPayload() {
    return docMode.value === 'text'
        ? { requirementText: manualMarkdown.value.trim() }
        : { requirementFile: file.value };
}
const repositorySubmissionBlocked = computed(() => !isRemoteSource.value
    && ['loading', 'empty'].includes(repositoryState.value));

async function refreshRepositoryAvailability() {
    repositoryState.value = 'loading';
    try {
        const repositories = await reviewApi.listRepositories();
        configuredRepositoryIds.value = Array.isArray(repositories)
            ? repositories.map((repository) => repository?.id).filter(Boolean)
            : [];
        repositoryState.value = configuredRepositoryIds.value.length ? 'ready' : 'empty';
    } catch {
        configuredRepositoryIds.value = [];
        repositoryState.value = 'error';
    }
}

async function ensureRepositoryBinding() {
    // [AIREVIEW-PLAN-029] Remote sources only need a URL; configured ids keep the whitelist check.
    if (isRemoteSource.value) {
        if (!repoSource.value.remoteUrl.trim()) {
            error.value = '请填写线上仓库地址。';
            return false;
        }
        return true;
    }
    if (repositoryState.value !== 'ready') await refreshRepositoryAvailability();
    if (!configuredRepositoryIds.value.length) {
        error.value = repositoryState.value === 'empty'
            ? '当前没有可用配置仓库；可切换到“线上仓库”直接填写代码地址。'
            : '仓库配置读取失败，请重试。';
        return false;
    }
    if (!configuredRepositoryIds.value.includes(repoSource.value.repositoryPath.trim())) {
        error.value = '请选择当前配置中可用的仓库。';
        return false;
    }
    return true;
}

function requirementRepositoryPayload() {
    if (isRemoteSource.value) {
        return {
            repositoryPath: null,
            remote: {
                url: repoSource.value.remoteUrl.trim(),
                ref: repoSource.value.remoteRef.trim() || null,
                token: repoSource.value.remoteToken.trim() || null
            }
        };
    }
    return { repositoryPath: repoSource.value.repositoryPath.trim() };
}

function reviewRepositoryPayload() {
    if (isRemoteSource.value) {
        return {
            remoteUrl: repoSource.value.remoteUrl.trim(),
            remoteRef: repoSource.value.remoteRef.trim() || null,
            remoteToken: repoSource.value.remoteToken.trim() || null
        };
    }
    return {
        repositoryPath: repoSource.value.repositoryPath.trim(),
        branch: form.branch.trim(),
        commit: form.commit.trim()
    };
}

async function submit() {
    error.value = '';
    savedDraftId.value = null;
    reusedReviewId.value = null;
    if (docMode.value === 'text') {
        if (!manualMarkdown.value.trim()) { error.value = '请输入 Markdown 需求内容，或切换为上传文档。'; return; }
    } else if (!file.value?.name.toLowerCase().endsWith('.md')) { error.value = '请上传 Markdown 格式的评审需求文档。'; return; }
    if (!form.title.trim() || !tasks().length) { error.value = '请填写需求标题和至少一项公开计划。'; return; }
    if (!isRemoteSource.value && !repoSource.value.repositoryPath.trim()) { error.value = '请选择评审仓库或切换到线上仓库填写地址。'; return; }
    if (isRemoteSource.value && !repoSource.value.remoteUrl.trim()) { error.value = '请填写线上仓库地址。'; return; }
    submitting.value = true;
    try {
        if (!await ensureRepositoryBinding()) return;
        const requirement = await reviewApi.createRequirement({
            title: form.title.trim(), description: form.description, priority: form.priority,
            ...requirementRepositoryPayload()
        });
        savedDraftId.value = requirement.id;
        const review = await reviewApi.createReview({
            ...requirementDocumentPayload(), submitter: submitter.value, ...reviewRepositoryPayload()
        });
        if (review.reused) {
            try {
                await reviewApi.deleteRequirement(requirement.id, requirement.version);
                savedDraftId.value = null;
                reusedReviewId.value = review.reviewId;
                error.value = '该 Markdown 快照已有评审，本次未保留重复需求草稿。可直接进入既有评审查看或重试。';
            } catch (cleanupError) {
                error.value = `该 Markdown 快照已有评审，但自动清理临时草稿失败：${formatApiError(cleanupError)}`;
            }
            return;
        }
        const submitted = await reviewApi.submitRequirement(requirement.id, { reviewId: review.reviewId, expectedVersion: requirement.version });
        await reviewApi.startReview(review.reviewId, {
            expectedVersion: 0, idempotencyKey: idempotencyKey(), userId: submitter.value, publicTasks: tasks(), changeReason: form.changeReason.trim(), initialMessage: form.initialMessage.trim()
        });
        await router.push({ name: 'requirement-detail', params: { requirementId: submitted.id } });
    } catch (requestError) {
        error.value = formatApiError(requestError);
    } finally { submitting.value = false; }
}

async function saveDraft() {
    error.value = '';
    if (!form.title.trim()) { error.value = '请先填写需求标题。'; return; }
    if (!isRemoteSource.value && !repoSource.value.repositoryPath.trim()) { error.value = '请选择评审仓库或切换到线上仓库填写地址。'; return; }
    if (isRemoteSource.value && !repoSource.value.remoteUrl.trim()) { error.value = '请填写线上仓库地址。'; return; }
    submitting.value = true;
    try {
        if (!await ensureRepositoryBinding()) return;
        const requirement = await reviewApi.createRequirement({
            title: form.title.trim(), description: form.description, priority: form.priority,
            ...requirementRepositoryPayload()
        });
        await router.push({ name: 'requirement-detail', params: { requirementId: requirement.id } });
    } catch (requestError) {
        error.value = formatApiError(requestError);
    } finally { submitting.value = false; }
}

onMounted(refreshRepositoryAvailability);
</script>

<template>
    <section class="platform-page">
        <header class="platform-page-header"><div><p class="eyebrow">New Requirement</p><h1>新建需求</h1><p class="muted">创建需求聚合并绑定一次 AI 对抗评审。</p></div></header>
        <form class="create-wrap" @submit.prevent="submit">
            <p v-if="error" class="error-banner" role="alert">{{ error }} <RouterLink v-if="savedDraftId" :to="`/requirements/${savedDraftId}`">查看已保存草稿</RouterLink><RouterLink v-if="reusedReviewId" :to="`/reviews/${reusedReviewId}/live`">查看既有评审</RouterLink></p>
            <p v-if="repositoryState === 'empty' && !isRemoteSource" class="error-banner" role="status">当前没有可用配置仓库；可切换到“线上仓库”直接填写代码地址。</p>
            <div class="create-note"><span aria-hidden="true">ℹ</span> 提交后，Director AI 将自动分析需求内容并选择合适的参与角色进行对抗评审。</div>
            <div class="card"><div class="card-bd">
                <div class="form-field"><label>需求名称</label><input v-model="form.title" required maxlength="256" placeholder="简短描述需求内容" /></div>
                <div class="form-field">
                    <label>需求文档</label>
                    <RequirementDocInput v-model:mode="docMode" v-model:text="manualMarkdown" v-model:file="file" />
                </div>
                <div class="form-row">
                    <div class="form-field">
                        <label>评审仓库</label>
                        <RepositorySourcePicker v-model="repoSource" required />
                    </div>
                    <div v-if="!isRemoteSource" class="form-field"><label>分支</label><input v-model="form.branch" placeholder="main" /></div>
                </div>
                <div class="form-row">
                    <div class="form-field"><label>优先级</label><select v-model="form.priority"><option>P0</option><option>P1</option><option>P2</option><option>P3</option></select></div>
                </div>
                <div v-if="!isRemoteSource" class="form-field"><label>Commit（可选）</label><input v-model="form.commit" placeholder="40 位 SHA" /></div>
                <div class="form-field"><label>需求描述</label><textarea v-model="form.description" placeholder="详细描述需求背景、目标、验收标准..." /></div>
                <div class="form-field"><label>备注（可选）</label><textarea v-model="form.remark" placeholder="补充信息（可选）" style="min-height:56px" /></div>
            </div></div>

            <details class="review-settings">
                <summary>评审启动设置</summary>
                <div class="form-field"><label>提交人</label><input :value="submitter" disabled /></div>
                <div class="form-field"><label>公开评审计划（每行一项）</label><textarea v-model="form.publicTasks" required /></div>
                <div class="form-field"><label>计划原因</label><input v-model="form.changeReason" required /></div>
                <div class="form-field"><label>启动说明</label><textarea v-model="form.initialMessage" required /></div>
            </details>

            <div class="form-actions">
                <button class="button secondary" type="button" :disabled="submitting || repositorySubmissionBlocked" @click="saveDraft">保存草稿</button>
                <button class="button" type="submit" :disabled="submitting || repositorySubmissionBlocked">{{ submitting ? '正在创建与启动…' : '提交并启动评审 →' }}</button>
            </div>
        </form>
    </section>
</template>
