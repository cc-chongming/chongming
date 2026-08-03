<script setup>
import { computed, onMounted, ref } from 'vue';
import { formatApiError, reviewApi } from '../api/review-api';

const props = defineProps({ requirementId: { type: String, required: true } });
const requirement = ref(null);
const error = ref('');
const loading = ref(true);
const changing = ref(false);
const reviewSummary = ref(null);
const gateVersions = ref([]);

const canStartDevelopment = computed(() => requirement.value?.status === 'APPROVED');
const canComplete = computed(() => requirement.value?.status === 'DEVELOPING');
const canCancel = computed(() => requirement.value?.status === 'DRAFT');

async function load() {
    loading.value = true;
    error.value = '';
    try {
        requirement.value = await reviewApi.getRequirement(props.requirementId);
        if (requirement.value.reviewId) {
            const [summary, gates] = await Promise.all([
                reviewApi.getSummary(requirement.value.reviewId).catch(() => null),
                reviewApi.getHumanGateVersions(requirement.value.reviewId).catch(() => [])
            ]);
            reviewSummary.value = summary;
            gateVersions.value = gates;
        }
    }
    catch (requestError) { error.value = formatApiError(requestError); }
    finally { loading.value = false; }
}

async function apply(action) {
    if (!requirement.value) return;
    changing.value = true;
    error.value = '';
    try {
        requirement.value = action === 'development'
            ? await reviewApi.startRequirementDevelopment(props.requirementId, requirement.value.version)
            : action === 'complete'
                ? await reviewApi.completeRequirement(props.requirementId, requirement.value.version)
                : await reviewApi.cancelRequirement(props.requirementId, requirement.value.version);
    } catch (requestError) { error.value = formatApiError(requestError); }
    finally { changing.value = false; }
}

onMounted(load);
</script>

<template>
    <section class="platform-page"><p v-if="error" class="error-banner" role="alert">{{ error }}</p><p v-if="loading" class="empty-note">正在读取需求详情…</p>
        <template v-else-if="requirement"><header class="platform-page-header"><div><p class="eyebrow">{{ requirement.status }}</p><h1>{{ requirement.title }}</h1><p class="muted">需求 ID：{{ requirement.id }}</p></div><div class="workbench-actions"><RouterLink class="button secondary" to="/requirements">返回列表</RouterLink><RouterLink v-if="requirement.reviewId" class="button" :to="`/reviews/${requirement.reviewId}/live`">查看实时评审</RouterLink></div></header>
            <div class="lifecycle-track" aria-label="需求生命周期"><span v-for="step in ['DRAFT','PENDING_REVIEW','REVIEWING','APPROVED','DEVELOPING','DONE']" :key="step" :data-active="requirement.status === step">{{ step }}</span></div>
            <div class="platform-grid"><section class="panel"><h2>需求说明</h2><pre class="requirement-description">{{ requirement.description || '未填写需求描述。' }}</pre></section>
                <aside class="panel"><h2>生命周期</h2><dl class="detail-list"><div><dt>状态</dt><dd><span class="status-pill">{{ requirement.status }}</span></dd></div><div><dt>优先级</dt><dd>{{ requirement.priority || '—' }}</dd></div><div><dt>仓库</dt><dd>{{ requirement.repositoryPath || '—' }}</dd></div><div><dt>负责人</dt><dd>{{ requirement.assigneeId || '—' }}</dd></div><div><dt>关联评审</dt><dd>{{ requirement.reviewId || '尚未发起' }}</dd></div><div><dt>版本</dt><dd>{{ requirement.version }}</dd></div></dl>
                    <div class="lifecycle-actions"><button v-if="canStartDevelopment" class="button" type="button" :disabled="changing" @click="apply('development')">开始开发</button><button v-if="canComplete" class="button" type="button" :disabled="changing" @click="apply('complete')">标记完成</button><button v-if="canCancel" class="text-button danger" type="button" :disabled="changing" @click="apply('cancel')">取消草稿</button><p v-if="!canStartDevelopment && !canComplete && !canCancel" class="empty-note">当前状态没有可执行的人工生命周期操作。</p></div>
                </aside></div>
            <section v-if="requirement.reviewId" class="platform-grid requirement-review-grid"><article class="panel"><h2>评审进度</h2><p v-if="reviewSummary" class="empty-note">{{ reviewSummary.stage }} · {{ reviewSummary.progress ?? 0 }}% · 尝试 #{{ reviewSummary.attempt }}</p><p v-else class="empty-note">评审摘要尚未生成或服务端暂不可用。</p><div v-if="reviewSummary?.contextScout" class="context-scout-warning"><strong>Scout 发现</strong><p>{{ reviewSummary.contextScout.publicSummary }}</p></div></article><article class="panel"><h2>参与角色与 Gate</h2><ul v-if="reviewSummary?.activatedRoles?.length" class="platform-list"><li v-for="role in reviewSummary.activatedRoles" :key="role.role"><strong>{{ role.role }}</strong><span>{{ role.agentLabel }} · {{ role.initialReviewCompleted ? '初审已完成' : '执行中' }}</span></li></ul><p v-else class="empty-note">角色尚未激活。</p><p v-if="gateVersions.length" class="empty-note">已记录 {{ gateVersions.length }} 个 Gate 版本。</p><p v-else class="empty-note">尚未形成最终 Gate。</p></article></section>
        </template>
    </section>
</template>
