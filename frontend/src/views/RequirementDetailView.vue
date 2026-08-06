<script setup>
import { computed, onMounted, reactive, ref } from 'vue';
import { RouterLink, useRouter } from 'vue-router';
import { formatApiError, reviewApi } from '../api/review-api';

const props = defineProps({ requirementId: { type: String, required: true } });
const router = useRouter();
const requirement = ref(null);
const error = ref('');
const loading = ref(true);
const changing = ref(false);
const editing = ref(false);
const reviewSummary = ref(null);
const gateVersions = ref([]);
const claims = ref([]);
const editForm = reactive({ title: '', description: '', assigneeId: '', repositoryPath: '', priority: 'P1' });

const lifecycleSteps = [
    { key: 'DRAFT', label: '草稿' },
    { key: 'PENDING_REVIEW', label: '待评审' },
    { key: 'REVIEWING', label: '评审中' },
    { key: 'APPROVED', label: 'Gate 决策' },
    { key: 'DEVELOPING', label: '开发' },
    { key: 'DONE', label: '完成' }
];
const statusMeta = {
    DRAFT: { tag: 'tag-draft', label: '草稿' }, PENDING_REVIEW: { tag: 'tag-pending', label: '待评审' },
    REVIEWING: { tag: 'tag-review', label: '评审中' }, APPROVED: { tag: 'tag-approved', label: '已通过' },
    DEVELOPING: { tag: 'tag-dev', label: '开发中' }, DONE: { tag: 'tag-done', label: '已完成' },
    RETURNED: { tag: 'tag-blocked', label: '已退回' }, CANCELLED: { tag: 'tag-draft', label: '已取消' }
};
const stageTag = {
    PENDING: 'tag-draft', SNAPSHOTTING: 'tag-pending', PLANNING: 'tag-pending',
    INITIAL_REVIEW: 'tag-review', CONFLICT_DETECTION: 'tag-review',
    DEBATE_ROUND_1: 'tag-review', DEBATE_ROUND_2: 'tag-review',
    JUDGING: 'tag-dev', WAITING_HUMAN: 'tag-pending', NOTIFYING: 'tag-pending',
    COMPLETED: 'tag-done', CANCELLED: 'tag-draft', FAILED: 'tag-blocked'
};
const stageLabel = {
    PENDING: '待处理', SNAPSHOTTING: '快照中', PLANNING: '规划中',
    INITIAL_REVIEW: '初审中', CONFLICT_DETECTION: '冲突检测',
    DEBATE_ROUND_1: '辩论 R1', DEBATE_ROUND_2: '辩论 R2',
    JUDGING: '裁决中', WAITING_HUMAN: '待人工', NOTIFYING: '通知中',
    COMPLETED: '已完成', CANCELLED: '已取消', FAILED: '已失败'
};
const gateMeta = {
    AI_PASS: { label: 'AI 通过', icon: '✅', tone: 'gn' }, PASS: { label: '通过', icon: '✅', tone: 'gn' },
    CONDITIONAL: { label: '有条件通过', icon: '⚠', tone: 'yl' }, RETURN: { label: '退回修改', icon: '↩', tone: 'yl' },
    BLOCK: { label: '驳回', icon: '⛔', tone: 'rd' }, HUMAN_REQUIRED: { label: '需人工裁决', icon: '🙋', tone: 'yl' },
    OVERRIDE: { label: '人工覆盖', icon: '✋', tone: 'pu' }
};
const roleMeta = {
    PRODUCT: { icon: '💡', label: '产品' }, PROJECT: { icon: '📋', label: '项目' },
    FRONTEND: { icon: '🎨', label: '前端' }, BACKEND: { icon: '⚙', label: '后端' },
    SECURITY: { icon: '🔒', label: '安全' }, ARCHITECTURE: { icon: '🏗', label: '架构' },
    TESTING: { icon: '🧪', label: '测试' }, PERFORMANCE: { icon: '📊', label: '性能' },
    DIRECTOR: { icon: '🎯', label: '协调' }, JUDGE: { icon: '⚖', label: '裁决' },
    CONTEXT_SCOUT: { icon: '🔍', label: 'Scout' }
};

const statusIndex = computed(() => {
    const index = lifecycleSteps.findIndex((step) => step.key === requirement.value?.status);
    return index === -1 ? 0 : index;
});
const currentStatus = computed(() => statusMeta[requirement.value?.status]
    ?? { tag: 'tag-draft', label: requirement.value?.status ?? '—' });

const canStartDevelopment = computed(() => requirement.value?.status === 'APPROVED');
const canComplete = computed(() => requirement.value?.status === 'DEVELOPING');
const canCancel = computed(() => requirement.value?.status === 'DRAFT');
const canEdit = computed(() => ['DRAFT', 'RETURNED'].includes(requirement.value?.status));
const canDelete = computed(() => requirement.value !== null);

const scout = computed(() => reviewSummary.value?.contextScout ?? null);
const scoutDone = computed(() => scout.value?.status === 'COMPLETED');
const activatedRoles = computed(() => reviewSummary.value?.activatedRoles ?? []);
const reviewStage = computed(() => reviewSummary.value?.stage ?? null);
const gate = computed(() => reviewSummary.value?.gate ?? null);
const claimsByRole = computed(() => {
    const map = {};
    for (const claim of claims.value ?? []) {
        const entry = map[claim.role] ?? { support: 0, oppose: 0, total: 0 };
        entry.total += 1;
        if (claim.position === 'SUPPORT') entry.support += 1;
        else if (claim.position === 'OPPOSE') entry.oppose += 1;
        map[claim.role] = entry;
    }
    return map;
});
const consensus = computed(() => {
    const total = claims.value?.length ?? 0;
    if (!total) return null;
    const oppose = claims.value.filter((claim) => claim.position === 'OPPOSE').length;
    return Math.round(((total - oppose) / total) * 100);
});

function roleLabel(role) {
    const meta = roleMeta[role];
    return meta ? `${meta.icon} ${meta.label}` : (role ?? '—');
}
function roleIcon(role) {
    return roleMeta[role]?.icon ?? '•';
}
function severityClass(severity) {
    return `b-${String(severity || 'P2').toLowerCase()}`;
}
function gateInfo(result) {
    return gateMeta[result] ?? { label: result ?? '—', icon: '•', tone: 'yl' };
}
function shortId(id) {
    return id ? `#${String(id).slice(0, 8)}` : '—';
}

async function load() {
    loading.value = true;
    error.value = '';
    try {
        requirement.value = await reviewApi.getRequirement(props.requirementId);
        if (requirement.value.reviewId) {
            const [summary, gates, claimItems] = await Promise.all([
                reviewApi.getSummary(requirement.value.reviewId).catch(() => null),
                reviewApi.getHumanGateVersions(requirement.value.reviewId).catch(() => []),
                reviewApi.getClaims(requirement.value.reviewId).catch(() => [])
            ]);
            reviewSummary.value = summary;
            gateVersions.value = gates;
            claims.value = Array.isArray(claimItems) ? claimItems : [];
        }
    } catch (requestError) {
        error.value = formatApiError(requestError);
    } finally {
        loading.value = false;
    }
}

function beginEdit() {
    if (!requirement.value) return;
    Object.assign(editForm, {
        title: requirement.value.title,
        description: requirement.value.description,
        assigneeId: requirement.value.assigneeId ?? '',
        repositoryPath: requirement.value.repositoryPath ?? '',
        priority: requirement.value.priority ?? 'P1'
    });
    editing.value = true;
    error.value = '';
}

function cancelEdit() {
    editing.value = false;
}

async function saveEdit() {
    if (!requirement.value) return;
    changing.value = true;
    error.value = '';
    try {
        requirement.value = await reviewApi.reviseRequirement(props.requirementId, {
            title: editForm.title.trim(),
            description: editForm.description,
            assigneeId: editForm.assigneeId,
            repositoryPath: editForm.repositoryPath,
            priority: editForm.priority,
            expectedVersion: requirement.value.version
        });
        editing.value = false;
    } catch (requestError) {
        error.value = formatApiError(requestError);
    } finally {
        changing.value = false;
    }
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
    } catch (requestError) {
        error.value = formatApiError(requestError);
    } finally {
        changing.value = false;
    }
}

async function deleteRequirement() {
    if (!requirement.value || !globalThis.confirm(`确定删除需求“${requirement.value.title}”吗？需求会从列表移除，关联评审历史会保留。`)) return;
    changing.value = true;
    error.value = '';
    try {
        await reviewApi.deleteRequirement(props.requirementId, requirement.value.version);
        await router.push({ name: 'requirements' });
    } catch (requestError) {
        error.value = formatApiError(requestError);
    } finally {
        changing.value = false;
    }
}

onMounted(load);
</script>

<template>
    <section class="platform-page">
        <p v-if="error" class="error-banner" role="alert">{{ error }}</p>
        <p v-if="loading" class="empty-note">正在读取需求详情…</p>
        <template v-else-if="requirement">
            <div class="rd-top">
                <div class="rd-info">
                    <h1>{{ requirement.title }}</h1>
                    <div class="rd-meta">
                        <span class="rd-meta-item"><span class="rd-mono">{{ shortId(requirement.id) }}</span></span>
                        <span class="rd-meta-item"><span class="tag" :class="currentStatus.tag">{{ currentStatus.label }}</span></span>
                        <span class="rd-meta-item"><span class="badge" :class="severityClass(requirement.priority)">{{ requirement.priority || '—' }}</span></span>
                        <span class="rd-meta-item">👤 {{ requirement.assigneeId || '未指派' }}</span>
                        <span v-if="requirement.repositoryPath" class="rd-meta-item">📁 {{ requirement.repositoryPath }}</span>
                        <span class="rd-meta-item">🕐 {{ requirement.updatedAt }}</span>
                    </div>
                </div>
                <div class="rd-actions">
                    <RouterLink class="button secondary" to="/requirements">返回列表</RouterLink>
                    <RouterLink v-if="requirement.reviewId" class="button secondary" :to="`/reviews/${requirement.reviewId}/live`">查看实时评审</RouterLink>
                </div>
            </div>

            <div class="lc-wrap">
                <div class="lc-title">生命周期</div>
                <div class="lc-track">
                    <template v-for="(step, index) in lifecycleSteps" :key="step.key">
                        <span v-if="index > 0" class="lc-arrow" aria-hidden="true">→</span>
                        <span class="lc-step" :class="index < statusIndex ? 'done' : index === statusIndex ? 'active' : 'pending'">
                            <span aria-hidden="true">{{ index < statusIndex ? '✓' : index === statusIndex ? '●' : '' }}</span>{{ step.label }}
                        </span>
                    </template>
                </div>
            </div>

            <section v-if="editing" class="panel requirement-edit-panel" aria-labelledby="requirement-edit-title" style="margin-bottom:16px">
                <h2 id="requirement-edit-title">编辑需求</h2>
                <form class="review-form compact" @submit.prevent="saveEdit">
                    <label class="full">需求标题<input v-model="editForm.title" maxlength="256" required /></label>
                    <label class="full">需求描述<textarea v-model="editForm.description" /></label>
                    <label>优先级<select v-model="editForm.priority"><option>P0</option><option>P1</option><option>P2</option><option>P3</option></select></label>
                    <label>负责人（可选）<input v-model="editForm.assigneeId" /></label>
                    <label class="full">仓库标识<input v-model="editForm.repositoryPath" /></label>
                    <div class="form-actions full">
                        <button class="button" type="submit" :disabled="changing">保存修改</button>
                        <button class="button secondary" type="button" :disabled="changing" @click="cancelEdit">取消编辑</button>
                    </div>
                </form>
            </section>

            <div class="rd-grid">
                <div>
                    <div class="card" style="margin-bottom:16px">
                        <div class="card-hd"><span class="card-ico">📄</span><div class="card-nm">需求文档</div></div>
                        <div class="card-bd"><pre class="rd-doc">{{ requirement.description || '未填写需求描述。' }}</pre></div>
                    </div>

                    <div class="card">
                        <div class="card-hd"><span class="card-ico">⚖</span><div class="card-nm">评审记录</div>
                            <RouterLink v-if="requirement.reviewId" class="button secondary sm" style="margin-left:auto" :to="`/reviews/${requirement.reviewId}`">进入工作台 →</RouterLink>
                        </div>
                        <div class="card-bd" style="padding:14px">
                            <RouterLink v-if="requirement.reviewId" class="re-entry" :to="`/reviews/${requirement.reviewId}`">
                                <div class="re-top">
                                    <span class="re-ico">⚖</span>
                                    <div class="re-title">多角色对抗评审 #{{ reviewSummary?.attempt ?? 1 }}</div>
                                    <span class="tag" :class="stageTag[reviewStage] ?? 'tag-review'">{{ stageLabel[reviewStage] ?? (reviewStage ?? '进行中') }}</span>
                                </div>
                                <div class="re-summary">
                                    <template v-if="reviewSummary">尝试 #{{ reviewSummary.attempt ?? 1 }} · 进度 {{ reviewSummary.progress ?? 0 }}% · {{ stageLabel[reviewStage] ?? reviewStage }}</template>
                                    <template v-else>评审已发起，摘要尚未生成或服务端暂不可用。</template>
                                </div>
                                <div v-if="activatedRoles.length" class="re-roles">
                                    <span>角色: </span>
                                    <span v-for="role in activatedRoles" :key="role.role" style="margin-right:8px">{{ roleIcon(role.role) }} {{ role.agentLabel }}</span>
                                    <span v-if="consensus != null" style="margin-left:4px">· 共识度 {{ consensus }}%</span>
                                </div>
                            </RouterLink>
                            <p v-else class="dash-empty">该需求尚未发起评审。</p>
                        </div>
                    </div>
                </div>

                <div>
                    <div class="card" style="margin-bottom:16px">
                        <div class="card-hd"><span class="card-ico">◈</span><div class="card-nm">Scout 发现</div>
                            <span class="tag" :class="scoutDone ? 'tag-done' : 'tag-pending'" style="margin-left:auto">{{ scoutDone ? '已完成' : (scout ? '进行中' : '未开始') }}</span>
                        </div>
                        <div class="card-bd" style="padding:14px">
                            <p v-if="scout?.publicSummary" class="rd-scout">{{ scout.publicSummary }}</p>
                            <p v-else class="dash-empty">Context Scout 尚未产出公开发现。</p>
                            <p v-if="scout?.reasonCode" class="rd-scout-reason">原因: {{ scout.reasonCode }}</p>
                        </div>
                    </div>

                    <div class="card" style="margin-bottom:16px">
                        <div class="card-hd"><span class="card-ico">◉</span><div class="card-nm">参与角色</div>
                            <span class="card-hint">Director 自动选择</span>
                        </div>
                        <div class="card-bd" style="padding:8px 16px">
                            <div v-if="activatedRoles.length" class="sb-section">
                                <div v-for="role in activatedRoles" :key="role.role" class="sb-row">
                                    <span class="role-ico">{{ roleIcon(role.role) }}</span>
                                    <span class="role-name">{{ role.agentLabel }}</span>
                                    <span class="role-count">
                                        <template v-if="claimsByRole[role.role]">
                                            <span style="color:#059669">✅ {{ claimsByRole[role.role].support }}</span>
                                            <span style="color:#dc2626"> ❌ {{ claimsByRole[role.role].oppose }}</span>
                                        </template>
                                        <template v-else>{{ role.initialReviewCompleted ? '✅ 初审完成' : '⏳ 执行中' }}</template>
                                    </span>
                                </div>
                            </div>
                            <p v-else class="dash-empty">角色尚未激活。</p>
                        </div>
                    </div>

                    <div class="card" style="margin-bottom:16px">
                        <div class="card-hd"><span class="card-ico">⚖</span><div class="card-nm">Gate 决策</div>
                            <span v-if="gateVersions.length" class="card-hint">共 {{ gateVersions.length }} 个版本</span>
                        </div>
                        <div class="card-bd" style="padding:0">
                            <div v-if="gate" class="gate-box" :class="`t-${gateInfo(gate.result).tone}`" style="margin:14px">
                                <div class="gate-tag" :class="`t-${gateInfo(gate.result).tone}`">{{ gateInfo(gate.result).icon }} {{ gateInfo(gate.result).label }}</div>
                                <div class="gate-meta">
                                    <div v-if="gate.actor"><strong>决策者:</strong> {{ gate.actor }}<span v-if="gate.decidedAt"> · {{ gate.decidedAt }}</span></div>
                                    <div v-if="gate.reasonSummary"><strong>理由:</strong> {{ gate.reasonSummary }}</div>
                                </div>
                            </div>
                            <div v-else class="gate-empty">
                                <div class="g-icon">⏳</div>
                                <div>{{ reviewStage === 'WAITING_HUMAN' ? 'AI 裁决已完成，等待人工最终决策' : '尚未形成 Gate 决策' }}</div>
                                <RouterLink v-if="requirement.reviewId" class="button secondary sm" style="margin-top:10px" :to="`/reviews/${requirement.reviewId}`">前往工作台 →</RouterLink>
                            </div>
                        </div>
                    </div>

                    <div class="card">
                        <div class="card-hd"><span class="card-ico">▣</span><div class="card-nm">操作</div></div>
                        <div class="card-bd">
                            <div class="rd-op-actions">
                                <button v-if="canEdit" class="button secondary" type="button" :disabled="changing || editing" @click="beginEdit">编辑需求</button>
                                <button v-if="canDelete" class="button danger-button" type="button" :disabled="changing" @click="deleteRequirement">删除需求</button>
                                <button v-if="canStartDevelopment" class="button" type="button" :disabled="changing" @click="apply('development')">开始开发</button>
                                <button v-if="canComplete" class="button" type="button" :disabled="changing" @click="apply('complete')">标记完成</button>
                                <button v-if="canCancel" class="text-button danger" type="button" :disabled="changing" @click="apply('cancel')">取消草稿</button>
                                <p v-if="!canStartDevelopment && !canComplete && !canCancel && !canEdit" class="dash-empty" style="margin:0">当前状态没有可执行的人工生命周期操作。</p>
                            </div>
                        </div>
                    </div>
                </div>
            </div>
        </template>
    </section>
</template>
