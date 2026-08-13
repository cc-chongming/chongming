<script setup>
import { computed, onUnmounted, ref, watch } from 'vue';
import { RouterLink } from 'vue-router';
import AgUiConversationPanel from '../components/AgUiConversationPanel.vue';
import AgentTraceDrawer from '../components/AgentTraceDrawer.vue';
import ReviewRoundtable from '../components/ReviewRoundtable.vue';
import DebateTimeline from '../components/DebateTimeline.vue';
import EvidenceDrawer from '../components/EvidenceDrawer.vue';
import HumanReviewPanel from '../components/HumanReviewPanel.vue';
import ReviewLifecyclePanel from '../components/ReviewLifecyclePanel.vue';
import PlanPanel from '../components/PlanPanel.vue';
import { formatApiError, reviewApi } from '../api/review-api';
import { resolveAiGateDraft } from '../services/review-conclusion-presenter';
import { createReviewStore } from '../stores/review-store';
import { createRuntimeTraceStore } from '../stores/runtime-trace-store';

const props = defineProps({ reviewId: { type: String, required: true } });
const store = createReviewStore();
const state = store.state;
const runtimeTrace = createRuntimeTraceStore();
const commandBusy = ref(false);
const commandMessage = ref('');
let loadGeneration = 0;
// [AIREVIEW-PLAN-023#6.3] Summary.gate becomes the human result after finalization,
// so recover the earlier AI draft from the replayed GATE_DRAFTED fact when needed.
const aiGateDraft = computed(() => resolveAiGateDraft(
    state.summary?.gate ?? null,
    state.humanGateVersions,
    store.events.value
));
const roundtableRoles = computed(() => {
    const roles = new Map(store.roles.value.map((role) => [role.role, role]));
    roles.set('DIRECTOR', roles.get('DIRECTOR') ?? { role: 'DIRECTOR', type: '主持中' });
    store.assessmentView.value.assessments.forEach((assessment) => {
        if (!assessment?.role) return;
        roles.set(assessment.role, roles.get(assessment.role) ?? { role: assessment.role, type: '已提交检查点结论' });
    });
    runtimeTrace.byRole.value.forEach((events, role) => {
        roles.set(role, { ...(roles.get(role) ?? {}), role, type: events.length ? '执行中' : '等待事件' });
    });
    return [...roles.values()];
});

const connectionText = computed(() => ({
    idle: '未连接', connecting: '正在连接', connected: '实时同步中', reconnecting: '正在恢复连接',
    unsupported: '浏览器不支持实时连接', 'malformed-event': '收到无效事件', closed: '连接已关闭'
}[state.connection.status] ?? state.connection.status));

// [AIREVIEW-PLAN-024#方案5] Five-status checkpoint coverage. The note below is triggered only by
// server coverage facts (no confirmed checkpoint while UNKNOWN exists), never by hard-coded roles.
const coverage = store.assessmentCoverage;
const coverageBreakdown = store.assessmentBreakdown;
const roleCoverage = store.roleAssessmentProgress;
const coveragePercent = computed(() => {
    const required = coverage.value.required;
    if (!required) return 0;
    return Math.min(100, Math.round((coverage.value.covered / required) * 100));
});
const unauthorizedSnapshotNote = computed(() => (
    coverage.value.confirmed === 0 && coverage.value.unknown > 0
        ? 'UNKNOWN：当前评审快照未授予前端文件'
        : null
));
const fiveStatusChips = computed(() => [
    { key: 'CONFIRMED', label: '确认', count: coverage.value.confirmed },
    { key: 'PARTIAL', label: '部分满足', count: coverage.value.partial },
    { key: 'GAP', label: '风险缺口', count: coverage.value.gap },
    { key: 'UNKNOWN', label: '证据不足', count: coverage.value.unknown },
    { key: 'NOT_APPLICABLE', label: '不适用', count: coverage.value.notApplicable }
]);

const stageList = ['PENDING', 'SNAPSHOTTING', 'PLANNING', 'INITIAL_REVIEW', 'CONFLICT_DETECTION', 'DEBATE_ROUND_1', 'DEBATE_ROUND_2', 'JUDGING', 'WAITING_HUMAN', 'NOTIFYING', 'COMPLETED'];
const pipelineSteps = [
    { key: 'scout', label: 'Scout', onStages: ['SNAPSHOTTING', 'PLANNING'] },
    { key: 'director', label: 'Director', onStages: ['PLANNING'] },
    { key: 'review', label: '独立审查', onStages: ['INITIAL_REVIEW'] },
    { key: 'conflict', label: '冲突检测', onStages: ['CONFLICT_DETECTION'] },
    { key: 'debate', label: '多轮辩论', onStages: ['DEBATE_ROUND_1', 'DEBATE_ROUND_2'] },
    { key: 'judge', label: 'Judge', onStages: ['JUDGING'] },
    { key: 'human', label: '人工决策', onStages: ['WAITING_HUMAN', 'NOTIFYING'] }
];
const pipeline = computed(() => {
    const stage = state.summary?.stage;
    return pipelineSteps.map((step) => {
        let status = 'pend';
        if (stage === 'COMPLETED' || stage === 'NOTIFYING') {
            status = 'done';
        } else if (stage === 'FAILED' || stage === 'CANCELLED') {
            status = 'pend';
        } else {
            const current = stageList.indexOf(stage);
            const maxOn = Math.max(-1, ...step.onStages.map((s) => stageList.indexOf(s)));
            if (current === -1) status = 'pend';
            else if (step.onStages.includes(stage)) status = 'on';
            else status = current > maxOn ? 'done' : 'pend';
        }
        return { ...step, status };
    });
});
function pipelineIcon(step) {
    if (step.status === 'done') return '✓';
    if (step.status === 'on') return step.key === 'debate' ? (state.summary?.stage === 'DEBATE_ROUND_1' ? 'R1' : 'R2') : '●';
    return '·';
}
function pipelineSub(step) {
    if (step.status === 'pend') return '等待';
    if (step.status === 'done') return '已完成';
    if (step.key === 'debate') return state.summary?.stage === 'DEBATE_ROUND_1' ? 'R1 进行中' : 'R2 进行中';
    if (step.key === 'human') return '等待人工';
    return '进行中';
}

async function load(reviewId) {
    const generation = ++loadGeneration;
    await store.load(reviewId);
    if (generation !== loadGeneration || reviewId !== props.reviewId) return;
    runtimeTrace.start(reviewId, state.summary?.attempt ?? 1);
}

function showError(error) {
    state.error = error;
}

async function retryNotification(entry) {
    try {
        await reviewApi.retryNotification(props.reviewId, entry.notificationId, entry.version);
        await store.refreshNotifications();
    } catch (error) {
        showError(error);
    }
}

async function startReview(command) {
    commandBusy.value = true;
    commandMessage.value = '';
    try {
        const result = await store.startReview(command);
        commandMessage.value = result.replayed ? '启动命令已重放，正在等待服务端事件。' : '启动命令已受理，正在等待服务端事件。';
    } catch (error) {
        showError(error);
    } finally {
        commandBusy.value = false;
    }
}

async function cancelReview() {
    commandBusy.value = true;
    commandMessage.value = '';
    try {
        const result = await store.cancelReview(state.summary.reviewVersion);
        commandMessage.value = result.replayed ? '取消命令已重放。' : '评审已取消。';
    } catch (error) {
        showError(error);
    } finally {
        commandBusy.value = false;
    }
}

async function retryReview() {
    commandBusy.value = true;
    commandMessage.value = '';
    try {
        const result = await store.retryReview(state.summary.reviewVersion);
        runtimeTrace.start(props.reviewId, result.attemptNo);
        commandMessage.value = '已创建新的评审尝试，请填写公开计划后启动。';
    } catch (error) {
        showError(error);
    } finally {
        commandBusy.value = false;
    }
}

async function refreshSummary() {
    try {
        await store.refreshSummary();
        commandMessage.value = '已从服务端刷新评审状态。';
    } catch (error) {
        showError(error);
    }
}

watch(() => props.reviewId, load, { immediate: true });
onUnmounted(() => { store.dispose(); runtimeTrace.dispose(); });
</script>

<template>
    <section class="workbench-page">
        <header class="workbench-header">
            <div>
                <p class="eyebrow">评审 ID</p>
                <h1>{{ reviewId }}</h1>
                <p class="connection" :data-status="state.connection.status"><span aria-hidden="true">●</span>{{ connectionText }}<template v-if="state.connection.retryDelayMs">，{{ Math.ceil(state.connection.retryDelayMs / 1000) }} 秒后重试</template></p>
            </div>
            <div class="workbench-actions"><RouterLink class="button" :to="{ name: 'review-live', params: { reviewId } }">进入实时观察台</RouterLink><RouterLink class="button secondary" :to="{ name: 'context-scout-preview', params: { reviewId } }">调试 Context Scout</RouterLink><RouterLink class="button secondary" :to="{ name: 'review-report', params: { reviewId } }">查看最终报告</RouterLink></div>
        </header>

        <div v-if="state.summary" class="wb-pipe" aria-label="评审流程">
            <template v-for="(step, index) in pipeline" :key="step.key">
                <span v-if="index > 0" class="wpi-conn" :class="{ done: step.status === 'done' }" aria-hidden="true"></span>
                <div class="wpi" :class="step.status">
                    <div class="wpi-i" aria-hidden="true">{{ pipelineIcon(step) }}</div>
                    <div class="wpi-t"><div class="nm">{{ step.label }}</div><div class="sb">{{ pipelineSub(step) }}</div></div>
                </div>
            </template>
        </div>

        <p v-if="state.error" class="error-banner" role="alert">{{ formatApiError(state.error) }}</p>
        <div v-if="state.loading" class="loading-grid" aria-label="正在加载评审数据"><span></span><span></span><span></span></div>

        <template v-else>
            <div class="workbench-grid">
                <PlanPanel :summary="state.summary" :plans="state.plans" :roles="store.roles.value" />
                <ReviewLifecyclePanel
                    :summary="state.summary"
                    :busy="commandBusy"
                    :message="commandMessage"
                    @start="startReview"
                    @cancel="cancelReview"
                    @retry="retryReview"
                    @refresh="refreshSummary"
                />
                <DebateTimeline class="wide-panel" :debates="state.debates" @open-evidence="store.selectEvidence" />
                <ReviewRoundtable class="wide-panel" :events="store.events.value" :roles="roundtableRoles" :assessments="store.assessmentView.value.assessments" @inspect-role="(role) => { runtimeTrace.state.selectedRole = role; }" />
                <section class="panel coverage-panel" aria-labelledby="coverage-title">
                    <div class="panel-heading"><div><p class="eyebrow">五态检查点</p><h2 id="coverage-title">检查点覆盖</h2></div><span class="topic-status">{{ coverage.covered }}/{{ coverage.required }}</span></div>
                    <template v-if="store.assessmentView.value.attempt != null">
                        <div class="coverage-bar" role="progressbar" :aria-valuenow="coveragePercent" aria-valuemin="0" aria-valuemax="100" :aria-label="`必检检查点覆盖 ${coveragePercent}%`"><span :style="{ width: `${coveragePercent}%` }"></span></div>
                        <ul class="coverage-breakdown" aria-label="检查点结论区分">
                            <li class="cb-pend"><strong>{{ coverageBreakdown.notExecuted }}</strong><span>未执行</span></li>
                            <li class="cb-unknown"><strong>{{ coverageBreakdown.executedUnknown }}</strong><span>执行但未知</span></li>
                            <li class="cb-confirmed"><strong>{{ coverageBreakdown.confirmed }}</strong><span>确认无问题</span></li>
                            <li class="cb-gap"><strong>{{ coverageBreakdown.gap }}</strong><span>确认有缺口</span></li>
                        </ul>
                        <ul class="asmt-chips" aria-label="五态数量">
                            <li v-for="chip in fiveStatusChips" :key="chip.key" class="asmt-chip" :class="`asmt-${chip.key.toLowerCase()}`">{{ chip.label }} {{ chip.count }}</li>
                        </ul>
                        <p v-if="unauthorizedSnapshotNote" class="asmt-note" role="note">{{ unauthorizedSnapshotNote }}</p>
                        <ul v-if="roleCoverage.length" class="role-coverage-list">
                            <li v-for="entry in roleCoverage" :key="entry.role">
                                <div class="rcl-head"><strong>{{ entry.role }}</strong><span>{{ entry.submitted }}/{{ entry.total }}<template v-if="entry.uncovered"> · 未执行 {{ entry.uncovered }}</template></span></div>
                                <div class="rcl-dots" :aria-label="`${entry.role} 五态结论`">
                                    <span v-for="chip in fiveStatusChips" :key="chip.key" v-show="entry.statuses[chip.key]" class="asmt-dot" :class="`asmt-${chip.key.toLowerCase()}`" :title="`${chip.label} ${entry.statuses[chip.key]}`">{{ chip.label }} {{ entry.statuses[chip.key] }}</span>
                                </div>
                            </li>
                        </ul>
                    </template>
                    <p v-else class="empty-note">角色提交检查点结论后将展示覆盖进度与五态数量。</p>
                </section>
                <AgUiConversationPanel class="ag-ui-workbench-panel" :conversation="state.agUi" />
                <HumanReviewPanel
                    :review-id="reviewId"
                    :items="state.humanItems"
                    :gate-versions="state.humanGateVersions"
                    :gate-draft="aiGateDraft"
                    :debates="state.debates"
                    :claims="state.claims"
                    :review-version="state.summary?.reviewVersion ?? null"
                    @changed="async () => { await store.refreshHumanData(); await store.refreshReports(); await store.refreshNotifications(); }"
                    @error="showError"
                />
                <section class="panel notification-panel" aria-labelledby="notification-title">
                    <div class="panel-heading"><div><p class="eyebrow">异步投递</p><h2 id="notification-title">通知状态</h2></div></div>
                    <p class="muted">通知失败不会改变评审事实；可对 FAILED 或 DEAD 的通知发起幂等重试。</p>
                    <ul v-if="state.notifications.length" class="notification-list">
                        <li v-for="entry in state.notifications" :key="entry.notificationId">
                            <div><strong>{{ entry.deliveryStatus }}</strong><span>{{ entry.command?.channel }} · Gate v{{ entry.command?.gateVersion }}</span></div>
                            <p>{{ entry.lastErrorCode || entry.responseCode || '等待投递结果' }}</p>
                            <button v-if="['FAILED', 'DEAD'].includes(entry.deliveryStatus)" class="text-button" type="button" @click="retryNotification(entry)">重试（v{{ entry.version }}）</button>
                        </li>
                    </ul>
                    <p v-else class="empty-note">最终 Gate 提交后将显示通知 Outbox 状态。</p>
                </section>
            </div>
            <EvidenceDrawer :evidence="state.selectedEvidence" @close="state.selectedEvidence = null" />
            <AgentTraceDrawer :role="runtimeTrace.state.selectedRole" :events="runtimeTrace.byRole.value.get(runtimeTrace.state.selectedRole) ?? []" @close="runtimeTrace.state.selectedRole = null" />
        </template>
    </section>
</template>
