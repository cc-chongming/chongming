<script setup>
// [AIREVIEW-PLAN-034#1#6] De-bubbled stream panels, tool-call grouping layout and Chinese role/phase labels.
import { computed, onUnmounted, ref, watch } from 'vue';
import { RouterLink } from 'vue-router';
import { formatChinaTime } from '../services/china-time';
import LiveAgentConversation from '../components/LiveAgentConversation.vue';
import HumanReviewPanel from '../components/HumanReviewPanel.vue';
import DebateTimeline from '../components/DebateTimeline.vue';
import EvidenceDrawer from '../components/EvidenceDrawer.vue';
import ReviewClaimList from '../components/ReviewClaimList.vue';
import ReviewConversationDrawer from '../components/ReviewConversationDrawer.vue';
import ReviewLifecyclePanel from '../components/ReviewLifecyclePanel.vue';
import ScoutConclusionPanel from '../components/ScoutConclusionPanel.vue';
import {
    buildRuntimeConversation,
    createLatestOnlyLoadQueue,
    gateDifference,
    isReasoningEvent,
    partitionClaimsByPosition
} from '../services/runtime-conversation-adapter';
import { describeLiveRunState } from '../services/live-run-status';
// [AIREVIEW-PLAN-038#2] 子代理启用决策行由事实事件投影（见 review-activation-presenter.js）。
import { buildActivationRows } from '../services/review-activation-presenter';
// [AIREVIEW-PLAN-042#1] 异议答辩议题的答辩 Claim（SUPPORT）由事实事件投影为对话流 REBUTTAL 回合（见 review-debate-presenter.js）。
import { buildDefenseTurns } from '../services/review-debate-presenter';
import { isScoutConcluded, resolvePhaseLanding } from '../services/review-phase-presenter';
import { claimOverview, completedReviewRoles, gateLabel, reviewRoles } from '../services/review-live-presenter';
import { resolveAiGateDraft } from '../services/review-conclusion-presenter';
import { reviewApi } from '../api/review-api';
import { createReviewStore } from '../stores/review-store';
import { createRuntimeTraceStore } from '../stores/runtime-trace-store';
// [AIREVIEW-PLAN-041#1] 阶段流程节点图标：import.meta.glob 收集 phase-icons 资产（192×192），
// 按「阶段 id → 资产前缀」与「phaseState → 资产状态」映射取图；failed 状态复用 running 图。
const phaseIconAssets = import.meta.glob('../assets/phase-icons/*.png', { eager: true, import: 'default' });
const PHASE_ASSET_PREFIX = {
    scout: 'intake', director: 'planning', review: 'review', conflict: 'conflict',
    debate: 'debate', judge: 'judging', human: 'human'
};
const PHASE_STATE_ASSET = { pending: 'pending', running: 'running', done: 'done', failed: 'running' };

function phaseIconUrl(phaseId, state) {
    const prefix = PHASE_ASSET_PREFIX[phaseId] ?? phaseId;
    const assetState = PHASE_STATE_ASSET[state] ?? 'pending';
    return phaseIconAssets[`../assets/phase-icons/${prefix}.${assetState}.png`]
        ?? phaseIconAssets[`../assets/phase-icons/${prefix}.pending.png`]
        ?? '';
}

const props = defineProps({ reviewId: { type: String, required: true } });
const store = createReviewStore();
const runtimeTrace = createRuntimeTraceStore();
const selectedPhase = ref(null);
const selectedRound = ref(1);
// [AIREVIEW-PLAN-045#1] 辩论议题 Tab：记录当前聚焦议题；议题列表变化（新增/重放）时按 topicId 稳定选择，选中项消失自动回落第一个。
const selectedTopicId = ref(null);
const expandedRole = ref(null);
const drawerOpen = ref(true);
const latestReviewReady = ref(false);
const humanPanelError = ref('');
const commandBusy = ref(false);
const commandMessage = ref('');
const lifecycleError = ref('');
const stage = computed(() => store.state.summary?.stage ?? 'PENDING');
// [AIREVIEW-PLAN-030] The lifecycle card only earns its space when it is actionable:
// pending start, a terminal failure/cancellation needing retry, or a real command error.
// Transient "command accepted" notes during STARTING no longer summon the card — running
// reviews keep refresh/cancel as compact header buttons instead.
const lifecycleCardRelevant = computed(() => ['PENDING', 'FAILED', 'CANCELLED'].includes(stage.value)
    || lifecycleError.value !== '');
const liveCancelable = computed(() => store.state.summary?.reviewVersion != null
    && !['COMPLETED', 'FAILED', 'CANCELLED'].includes(stage.value));
const liveRunState = computed(() => describeLiveRunState(stage.value, runtimeTrace.state.status));
const runtimeItems = computed(() => buildRuntimeConversation(runtimeTrace.state.events));
const loadQueue = createLatestOnlyLoadQueue(() => {
    store.dispose();
    runtimeTrace.dispose();
}, (ready) => { latestReviewReady.value = ready; });

function showLifecycleError(error) {
    lifecycleError.value = error?.message ?? String(error);
}

async function startReview(command) {
    commandBusy.value = true;
    commandMessage.value = '';
    lifecycleError.value = '';
    try {
        const result = await store.startReview(command);
        commandMessage.value = result.replayed ? '启动命令已重放，正在等待服务端事件。' : '启动命令已受理，正在等待服务端事件。';
    } catch (error) {
        showLifecycleError(error);
    } finally {
        commandBusy.value = false;
    }
}

async function cancelReview() {
    commandBusy.value = true;
    commandMessage.value = '';
    lifecycleError.value = '';
    try {
        const result = await store.cancelReview(store.state.summary?.reviewVersion);
        commandMessage.value = result.replayed ? '取消命令已重放。' : '评审已取消。';
    } catch (error) {
        showLifecycleError(error);
    } finally {
        commandBusy.value = false;
    }
}

function cancelLiveWithConfirm() {
    if (window.confirm('确定取消当前评审？该操作不可撤销。')) {
        cancelReview();
    }
}

async function retryReview() {
    commandBusy.value = true;
    commandMessage.value = '';
    lifecycleError.value = '';
    try {
        const result = await store.retryReview(store.state.summary?.reviewVersion);
        runtimeTrace.start(props.reviewId, result.attemptNo);
        commandMessage.value = '已创建新的评审尝试，请填写公开计划后启动。';
    } catch (error) {
        showLifecycleError(error);
    } finally {
        commandBusy.value = false;
    }
}

async function retryNotification(entry) {
    lifecycleError.value = '';
    try {
        await reviewApi.retryNotification(props.reviewId, entry.notificationId, entry.version);
        await store.refreshNotifications();
    } catch (error) {
        showLifecycleError(error);
    }
}

const phases = [
    { id: 'scout', icon: '侦', name: '上下文侦察', subtitle: '项目信息收集' },
    { id: 'director', icon: '协', name: '评审规划', subtitle: '创建评审计划' },
    { id: 'review', icon: '审', name: '独立审查', subtitle: '多角色并行' },
    { id: 'conflict', icon: '冲', name: '冲突检测', subtitle: '识别分歧与风险' },
    { id: 'debate', icon: '辩', name: '多轮辩论', subtitle: '围绕争议收敛' },
    { id: 'judge', icon: '裁', name: '裁决者裁决', subtitle: '形成评审判断' },
    { id: 'human', icon: '人', name: '人工决策', subtitle: '最终关口' }
];
const streamPhases = ['scout', 'director', 'judge'];
const severityRank = { P0: 0, P1: 1, P2: 2, P3: 3 };

function roleTitle(role) {
    return {
        DIRECTOR: '协调者', CONTEXT_SCOUT: '上下文侦察', PRODUCT: '产品经理', PROJECT: '项目经理',
        FRONTEND: '前端工程师', BACKEND: '后端工程师', ARCHITECTURE: '架构师', SECURITY: '安全工程师',
        TESTING: '测试工程师', PERFORMANCE: '性能工程师', JUDGE: '裁决者'
    }[role] ?? role ?? '智能体';
}

function roleInitial(role) {
    return {
        CONTEXT_SCOUT: '侦', DIRECTOR: '协', PRODUCT: '产', PROJECT: '项', FRONTEND: '前', BACKEND: '后',
        ARCHITECTURE: '架', SECURITY: '安', TESTING: '测', PERFORMANCE: '能', JUDGE: '裁'
    }[role] ?? String(role ?? '审').slice(0, 1);
}

// [AIREVIEW-PLAN-038#2] 子代理启用状态徽章文案。
function activationBadgeText(state) {
    return { activated: '已启用', running: '初审中', completed: '初审完成' }[state] ?? '已启用';
}

function phaseForRole(role) {
    if (role === 'CONTEXT_SCOUT') return 0;
    if (role === 'DIRECTOR') return 1;
    if (role === 'JUDGE') return 5;
    return 2;
}

// [AIREVIEW-PLAN-037#1] 初次进入停留在上下文侦察：PLANNING 窗口内 Scout 仍在流式输出，
// 只有它结束（scoutConcluded）后被动观看才切换到评审规划；手动点选阶段不受影响。
const activePhaseIndex = computed(() => {
    if (stage.value === 'FAILED') {
        const lastItem = runtimeItems.value.at(-1);
        return lastItem ? phaseForRole(lastItem.role) : 1;
    }
    return resolvePhaseLanding({ stage: stage.value, runtimeItems: runtimeItems.value, scoutConcluded: scoutConcluded.value });
});
const activePhase = computed(() => selectedPhase.value ?? phases[activePhaseIndex.value].id);
const activePhaseDefinition = computed(() => phases.find((phase) => phase.id === activePhase.value) ?? phases[activePhaseIndex.value]);

const debateTopics = computed(() => store.state.debates ?? []);
// [AIREVIEW-PLAN-045#1] 当前聚焦议题：按 selectedTopicId 匹配，匹配不到（选中项消失/重放/换评审）自动回落第一个议题。
const selectedTopic = computed(() => debateTopics.value.find((topic) => topic.topicId === selectedTopicId.value)
    ?? debateTopics.value[0] ?? null);
const allClaims = computed(() => debateTopics.value.flatMap((topic) => topic.claims ?? []));
const roleClaims = computed(() => {
    const byId = new Map();
    [...(Array.isArray(store.state.claims) ? store.state.claims : []), ...allClaims.value]
        .forEach((claim) => { if (claim?.claimId) byId.set(claim.claimId, claim); });
    return [...byId.values()];
});
const reviewRoleCodes = computed(() => {
    return reviewRoles(store.state.summary?.activatedRoles, roleClaims.value, runtimeItems.value);
});
const completedRoleCodes = computed(() => completedReviewRoles(
    store.state.summary?.activatedRoles,
    store.events.value,
    store.state.summary?.attempt
));
const completedRoles = computed(() => completedRoleCodes.value.length);
const completedRoleSet = computed(() => new Set(completedRoleCodes.value));
const activatedRoleSet = computed(() => new Set(
    reviewRoles(store.state.summary?.activatedRoles ?? [], [], [])));
const scoutHasActivity = computed(() => runtimeTrace.state.events.some((event) =>
    String(event?.runId ?? '').includes(':context-scout')));
const scoutRunFinished = computed(() => runtimeTrace.state.events.some((event) =>
    event?.type === 'RUN_FINISHED' && String(event?.runId ?? '').includes(':context-scout')));
// [AIREVIEW-PLAN-037#1] Scout 结束双信号：运行流 RUN_FINISHED 或 summary.contextScout 落库（COMPLETED/DEGRADED 均使其非空）。
const scoutConcluded = computed(() => isScoutConcluded({
    contextScout: store.state.summary?.contextScout ?? null,
    scoutRunFinished: scoutRunFinished.value
}));
const scoutComplete = computed(() => scoutConcluded.value || activePhaseIndex.value >= 2);
const maxDebateRound = computed(() => Math.max(1,
    store.state.summary?.stage === 'DEBATE_ROUND_2' ? 2 : 1,
    ...debateTopics.value.map((topic) => topic.currentRound ?? 1),
    ...debateTopics.value.flatMap((topic) => (topic.turns ?? []).map((turn) => turn.round ?? 1))));
// [AIREVIEW-PLAN-045#1] 当前聚焦议题的最大辩论回合：回合选项卡与轮次上限随议题联动（至少 1 轮）。
const topicMaxRound = computed(() => {
    const topic = selectedTopic.value;
    const topicTurns = topic?.turns ?? [];
    return Math.max(1, topic?.currentRound ?? 1, ...topicTurns.map((turn) => turn.round ?? 1));
});
const conflicts = computed(() => debateTopics.value.flatMap((topic, index) => {
    const claims = topic.claims ?? [];
    const supports = claims.filter((claim) => claim.position === 'SUPPORT');
    const opposes = claims.filter((claim) => claim.position === 'OPPOSE');
    if (!supports.length || !opposes.length) return [];
    const severity = [...claims].sort((left, right) => (severityRank[left.severity] ?? 9) - (severityRank[right.severity] ?? 9))[0]?.severity ?? 'P2';
    return [{ index: index + 1, topicId: topic.topicId, subject: topic.subjectKey, severity, support: supports[0], oppose: opposes[0] }];
}));
// [AIREVIEW-PLAN-034#5 延伸] 每个已登记议题都进入冲突视图：立场对立或纯异议答辩，
// 与需求答辩机制（大量议题为"只有反对方 + 答辩人待回应"）保持一致。
const conflictTopics = computed(() => debateTopics.value.map((topic, index) => {
    const claims = topic.claims ?? [];
    const supports = claims.filter((claim) => claim.position === 'SUPPORT');
    const opposes = claims.filter((claim) => claim.position === 'OPPOSE');
    const severity = [...claims].sort((left, right) => (severityRank[left.severity] ?? 9) - (severityRank[right.severity] ?? 9))[0]?.severity ?? 'P2';
    return {
        index: index + 1,
        topicId: topic.topicId,
        subject: topic.subjectKey,
        // [AIREVIEW-PLAN-044#2] 协调者给出的中文议题标题（可空，前端回退 subjectKey）。
        title: topic.title ?? null,
        severity,
        supports,
        opposes,
        opposed: supports.length > 0 && opposes.length > 0,
        round: topic.currentRound ?? 1
    };
}));
const opposingCount = computed(() => conflictTopics.value.filter((topic) => topic.opposed).length);
const dissentCount = computed(() => conflictTopics.value.length - opposingCount.value);
const phaseList = computed(() => phases.map((phase) => {
    if (phase.id === 'review') return { ...phase, subtitle: `${completedRoles.value}/${reviewRoleCodes.value.length} 角色初审完成` };
    if (phase.id === 'conflict' && conflictTopics.value.length) return { ...phase, subtitle: `${conflictTopics.value.length} 个议题登记` };
    if (phase.id === 'debate' && debateTopics.value.length) return { ...phase, subtitle: `第 ${maxDebateRound.value} 轮 · ${debateTopics.value.length} 个议题` };
    return phase;
}));

const claimPartitions = computed(() => partitionClaimsByPosition(allClaims.value));
// [AIREVIEW-PLAN-045#1] 法庭改为按当前聚焦议题聚类：议题级分区驱动支持/质疑/中立方与共识度渲染；
// allClaims/claimPartitions 保留为全局视图（角色汇总等）使用，不受议题切换影响。
const topicClaims = computed(() => selectedTopic.value?.claims ?? []);
const topicClaimPartitions = computed(() => partitionClaimsByPosition(topicClaims.value));
const proClaims = computed(() => topicClaimPartitions.value.support);
const conClaims = computed(() => topicClaimPartitions.value.oppose);
const neutralClaims = computed(() => topicClaimPartitions.value.neutral);
// [AIREVIEW-PLAN-045#1] 共识度基于议题级 support/oppose 分区计算。
const consensusPercent = computed(() => {
    const directionalClaims = proClaims.value.length + conClaims.value.length;
    if (!directionalClaims) return 0;
    return Math.round((proClaims.value.length / directionalClaims) * 100);
});
const consensusOffset = computed(() => 264 - Math.round((264 * consensusPercent.value) / 100));
const consensusStroke = computed(() => {
    if (consensusPercent.value > 70) return 'var(--flow-green)';
    return consensusPercent.value > 40 ? 'var(--flow-yellow)' : 'var(--flow-red)';
});
// [AIREVIEW-PLAN-042#1] 答辩 Claim 合成回合：既有 turns 映射结果与 defenseTurns 合并后按 selectedRound 过滤，
// 使异议答辩议题的答辩 Claim 也能出现在“辩论对话流”中（合成条目无 stance 字段，立场行自然不渲染）。
const defenseTurns = computed(() => buildDefenseTurns(store.state.debates ?? [], store.events.value));
// [AIREVIEW-PLAN-045#1] 对话流聚焦当前议题：仅聚合该议题的 turns 与该议题的合成答辩回合，再按 selectedRound 过滤。
const roundTurns = computed(() => {
    const topic = selectedTopic.value;
    if (!topic) return [];
    return [
        ...(topic.turns ?? []).map((turn) => ({ ...turn, subject: topic.subjectKey })),
        ...defenseTurns.value.filter((turn) => turn.subject === topic.subjectKey)
    ].filter((turn) => (turn.round ?? 1) === selectedRound.value);
});
const judgements = computed(() => debateTopics.value.filter((topic) => topic.judgement));
// Topics that already escalated to the judging layer but carry no judgement yet; showing them in
// the conclusion chain keeps the human-decision view honest while the stage machine lags behind.
const pendingJudgementTopics = computed(() => debateTopics.value.filter(
    (topic) => !topic.judgement && ['ESCALATED', 'RESOLVED', 'SETTLED'].includes(topic.status ?? '')));
const HUMAN_REACHABLE_STAGES = new Set(['JUDGING', 'WAITING_HUMAN', 'NOTIFYING', 'COMPLETED']);
const humanPhaseReachable = computed(() => HUMAN_REACHABLE_STAGES.has(stage.value));
const stageLabel = {
    PENDING: '待处理', SNAPSHOTTING: '快照中', PLANNING: '规划中', INITIAL_REVIEW: '初审中',
    CONFLICT_DETECTION: '冲突检测',
    // [AIREVIEW-PLAN-047#3] 单一 DEBATE 阶段（议题级轮次）；旧轮次标签兼容存量评审。
    DEBATE: '辩论中', DEBATE_ROUND_1: '辩论第 1 轮', DEBATE_ROUND_2: '辩论第 2 轮',
    JUDGING: '裁决中', WAITING_HUMAN: '待人工决策', NOTIFYING: '通知中',
    COMPLETED: '已完成', CANCELLED: '已取消', FAILED: '已失败'
};
// [AIREVIEW-PLAN-045#1] 页头标题跟随聚焦议题（title 为 PLAN-044 在途可选字段，缺失时自然回退 subjectKey）。
const debateSubject = computed(() => {
    if (!selectedTopic.value) return null;
    return selectedTopic.value.title ?? selectedTopic.value.subjectKey;
});
// [AIREVIEW-PLAN-023#6.3] Summary.gate becomes the human result after finalization, so recover
// the earlier AI draft from the replayed GATE_DRAFTED fact when needed.
const gateDraft = computed(() => resolveAiGateDraft(
    store.state.summary?.gate ?? null,
    store.state.humanGateVersions,
    store.events.value
));
const gateOverride = computed(() => gateDifference(gateDraft.value, store.state.humanGateVersions));
const scoutConclusion = computed(() => store.state.summary?.contextScout?.conclusion
    ?? store.state.summary?.contextScoutConclusion
    ?? store.state.summary?.contextScout
    ?? null);

const phaseItems = computed(() => {
    const phase = activePhase.value;
    if (phase === 'scout') return runtimeItems.value.filter((item) => item.role === 'CONTEXT_SCOUT');
    if (phase === 'director') return runtimeItems.value.filter((item) => item.role === 'DIRECTOR');
    // [AIREVIEW-PLAN-034#5] 裁决阶段只展示裁决者事件；协调者编排事件归属评审规划流。
    if (phase === 'judge') return runtimeItems.value.filter((item) => item.role === 'JUDGE');
    return [];
});
const streamOwner = computed(() => {
    if (activePhase.value === 'scout') return { initial: '侦', role: 'CONTEXT_SCOUT', name: '上下文侦察', roleDesc: '项目上下文探索' };
    if (activePhase.value === 'judge') return { initial: '裁', role: 'JUDGE', name: '裁决者', roleDesc: '仅基于已持久化的 Claim 和证据裁决' };
    return { initial: '协', role: 'DIRECTOR', name: '协调者', roleDesc: '评审流程编排 · 贯穿规划/冲突/调度' };
});
const reviewCards = computed(() => reviewRoleCodes.value.map((role) => {
    const items = runtimeItems.value.filter((item) => item.role === role);
    const claims = roleClaims.value.filter((claim) => claim.role === role);
    const latest = items.at(-1);
    const running = latest?.status === 'streaming' || latest?.status === 'RUNNING';
    const stance = claims.some((claim) => claim.position === 'OPPOSE') ? 'oppose'
        : claims.some((claim) => claim.position === 'SUPPORT') ? 'support' : null;
    const completed = completedRoleSet.value.has(role);
    const activated = activatedRoleSet.value.has(role);
    return {
        role,
        label: roleTitle(role),
        initials: roleInitial(role),
        // [AIREVIEW-PLAN-034#5] 有运行记录（含两次动作间短暂"已完成"）即视为进行中；
        // "等待分配"仅在角色从未产生任何运行记录时兜底显示。
        badge: completed ? '✅ 初审完成' : running ? '⏳ 进行中'
            : stance === 'oppose' ? '❌ 反对' : stance === 'support' ? '✅ 支持' : items.length > 0 ? '⏳ 进行中' : '等待分配',
        tone: completed || stance === 'support' ? 'done' : running || items.length > 0 ? 'running' : stance === 'oppose' ? 'opposed' : 'pending',
        summary: claims.length
            ? claimOverview(claims)
            : completed ? '初审已完成，等待冲突检测汇总各方论点。'
            : latest ? itemSummary(latest) : activated ? '角色已激活，等待公开运行事件。' : '等待协调者分配评审任务。',
        claims,
        items
    };
}));
// The /plans projection also carries scout/initial-review milestones; only plan events render as
// plan cards, otherwise non-plan rows appear as empty "v—" cards.
const planCards = computed(() => (store.state.plans ?? []).filter((plan) => plan.type === 'PLAN_CREATED' || plan.type === 'PLAN_REVISED'));
// [AIREVIEW-PLAN-038#2] 子代理启用决策：由事实流（ROLE_ACTIVATED/ROLE_STARTED/ROLE_COMPLETED）投影各角色启用状态。
const activationRows = computed(() => buildActivationRows(store.events.value));
const streamEmpty = computed(() => {
    if (liveRunState.value.emptyState) return liveRunState.value.emptyState;
    // [AIREVIEW-PLAN-038#2] Director 阶段空态聚焦编排通知；子代理启用决策由下方启用卡片呈现。
    if (activePhase.value === 'director') {
        return {
            title: '协调者编排通知将实时出现在这里',
            message: '创建评审、决策启用哪些子代理、调用派发子代理的过程由编排层以协调者通知形式广播；评审计划与任务清单见下方计划卡。'
        };
    }
    if (activePhase.value === 'judge') {
        return { title: '等待裁决者开始裁决', message: '裁决者仅基于已持久化的 Claim 与证据工作，运行事件到达后按顺序展示。' };
    }
    return { title: '等待当前阶段的公开运行事件', message: '运行事件会按照实际到达顺序显示；工具参数与完整结果仅在展开后可见。' };
});
const factTimeline = computed(() => store.events.value.slice().reverse().map((event) => ({
    ...event, title: eventTitle(event), detail: eventDetail(event)
})));
const DEBUG_VISIBLE_LIMIT = 400;
const debugItems = computed(() => {
    const visibleEvents = runtimeTrace.state.events.filter((event) => !isReasoningEvent(event));
    // A long replay can carry tens of thousands of events; rendering every one as a debug row
    // freezes the drawer, so only the most recent window is shown.
    const truncatedCount = Math.max(0, visibleEvents.length - DEBUG_VISIBLE_LIMIT);
    const shownEvents = truncatedCount ? visibleEvents.slice(-DEBUG_VISIBLE_LIMIT) : visibleEvents;
    const lastEvent = visibleEvents.at(-1);
    return [
        { id: 'runtime-connection', role: 'SYSTEM', summary: `连接状态：${runtimeTrace.state.status}` },
        { id: 'runtime-cursor', role: 'SYSTEM', summary: `当前游标：${lastEvent?.sequence ?? lastEvent?.id ?? '尚无事件'}` },
        ...(truncatedCount ? [{ id: 'runtime-debug-truncated', role: 'SYSTEM', summary: `更早的 ${truncatedCount} 条运行事件已省略，仅展示最近 ${DEBUG_VISIBLE_LIMIT} 条。` }] : []),
        ...shownEvents.map((event, index) => ({
            id: `debug:${event.id ?? event.sequence ?? index}`,
            role: event.value?.role ?? String(event.runId ?? '').split(':').at(-1)?.toUpperCase() ?? 'SYSTEM',
            summary: runtimeDebugSummary(event)
        }))
    ];
});

function isTopicTerminal(topic) {
    return ['RESOLVED', 'ESCALATED'].includes(topic?.status ?? '');
}

function phaseState(index) {
    if (index === 0 && activePhaseIndex.value === 1) {
        // Context Scout runs inside the PLANNING window. It must stay "running" (not "done") while
        // its runtime stream is still active, instead of flipping the moment the stage leaves
        // SNAPSHOTTING.
        return scoutComplete.value ? 'done' : 'running';
    }
    // [AIREVIEW-PLAN-034#5] The Director only starts after Context Scout finishes or degrades;
    // show it as pending instead of a second "running" phase while scout is still in flight.
    if (index === 1 && activePhaseIndex.value === 1 && !scoutComplete.value) return 'pending';
    if (index === activePhaseIndex.value && stage.value === 'FAILED') return 'failed';
    if (index < activePhaseIndex.value) return 'done';
    if (index === activePhaseIndex.value) return 'running';
    return 'pending';
}

function phaseBadgeText(state) {
    return { done: '✓ 完成', running: '● 进行中', failed: '✕ 已失败', pending: '未开始' }[state] ?? '进行中';
}

function selectPhase(phase) {
    selectedPhase.value = phase.id;
    expandedRole.value = null;
    if (phase.id === 'debate') selectedRound.value = maxDebateRound.value;
}

function toggleRole(role) {
    expandedRole.value = expandedRole.value === role ? null : role;
}

function switchRound(round) {
    selectedRound.value = round;
}

function isTopicAdjudicated(topic) {
    return ['RESOLVED', 'ESCALATED', 'SETTLED'].includes(topic?.status ?? '');
}

// [AIREVIEW-PLAN-045#1] 切换聚焦议题：仅当所选回合超出该议题最大回合时回落（不强行重置回合）。
function switchTopic(topic) {
    selectedTopicId.value = topic?.topicId ?? null;
    const topicTurns = topic?.turns ?? [];
    const topicMax = Math.max(1, topic?.currentRound ?? 1, ...topicTurns.map((turn) => turn.round ?? 1));
    if (selectedRound.value > topicMax) selectedRound.value = topicMax;
}

function turnTypeLabel(type) {
    return { CHALLENGE: '⚔️ 质询', REBUTTAL: '🛡️ 答辩', EVIDENCE: '📎 补充证据', POSITION_SHIFT: '🤝 立场调整' }[type] ?? type ?? '发言';
}

function planVersion(plan) {
    return plan.payload?.planVersion ?? plan.payload?.referenceVersion ?? '—';
}

function planTasks(plan) {
    const tasks = plan.payload?.publicTasks ?? plan.payload?.tasks;
    if (Array.isArray(tasks)) return tasks;
    if (typeof tasks === 'string' && tasks.trim()) {
        try {
            const parsed = JSON.parse(tasks);
            return Array.isArray(parsed) ? parsed : [];
        } catch {
            return tasks.split(/\n+/).map((line) => line.replace(/^[-*\s]+/, '')).filter(Boolean);
        }
    }
    return [];
}

function itemSummary(item) {
    if (item.kind === 'tool') return `${item.toolName ?? '工具调用'} · ${toolStatus(item.status)}`;
    const text = String(item.content ?? '').replaceAll(/\s+/g, ' ').trim();
    return text ? `${text.slice(0, 92)}${text.length > 92 ? '…' : ''}` : '等待 Agent 输出公开内容。';
}

function toolStatus(status) {
    return { RUNNING: '调用中', SUCCESS: '已完成', ERROR: '失败', DENIED: '被拒绝', INTERRUPTED: '中断' }[status] ?? status ?? '调用中';
}

function claimsForIds(ids) {
    const requested = new Set(ids ?? []);
    return roleClaims.value.filter((claim) => requested.has(claim.claimId));
}

function runtimeDebugSummary(event) {
    if (event.type === 'CUSTOM') {
        const state = event.value?.phase ?? event.value?.lifecycle ?? event.value?.eventType ?? event.value?.status;
        return `${event.name ?? 'CUSTOM'}${state ? ` · ${state}` : ''}`;
    }
    if (event.type === 'RUN_ERROR') return `${event.type} · ${event.message ?? '运行异常'}`;
    const identity = event.messageId ?? event.toolCallId ?? event.runId;
    return `${event.type}${identity ? ` · ${identity}` : ''}`;
}

function eventTitle(event) {
    return {
        PLAN_CREATED: '协调者发布评审计划', ROLE_ACTIVATED: `激活 ${roleTitle(event.actorRole)} 角色`,
        ROLE_STARTED: `${roleTitle(event.actorRole)} 开始运行`, ROLE_COMPLETED: `${roleTitle(event.actorRole)} 初审已完成`,
        INITIAL_REVIEW_COMPLETED: '全部角色初审已完成',
        CLAIM_SUBMITTED: `${roleTitle(event.actorRole)} 提交 Claim`,
        EVIDENCE_CAPTURED: '登记证据', DEBATE_TOPIC_OPENED: '创建辩论议题',
        DEBATE_TOPIC_CLOSED: '关闭辩论议题', CHALLENGE_SUBMITTED: '提交质询',
        REBUTTAL_SUBMITTED: '提交答辩', POSITION_CHANGED: '更新立场',
        JUDGEMENT_SUBMITTED: '裁决者提交裁决', GATE_DRAFTED: '形成关口草案',
        HUMAN_GATE_FINALIZED: '人工确认最终 Gate', REVIEW_FAILED: '评审运行失败',
        REVIEW_CANCELLED: '评审已取消'
    }[event.type] ?? event.type;
}

function eventDetail(event) {
    if (event.payload?.publicSummary) return event.payload.publicSummary;
    if (event.topicId) return `议题 ${event.topicId}`;
    return `${event.stage ?? 'PENDING'} · 尝试 #${event.attemptNo ?? 1}`;
}

function format(value) {
    if (value == null) return '暂无可展示的参数或结果。';
    if (typeof value === 'string') return value;
    try { return JSON.stringify(value, null, 2); } catch { return String(value); }
}

async function load(reviewId) {
    selectedPhase.value = null;
    selectedRound.value = 1;
    selectedTopicId.value = null; // [AIREVIEW-PLAN-045#1] 切换评审时重置聚焦议题，回落第一个。
    expandedRole.value = null;
    await loadQueue.run(
        reviewId,
        (targetReviewId) => store.load(targetReviewId),
        (targetReviewId) => runtimeTrace.start(targetReviewId, store.state.summary?.attempt ?? 1)
    );
}

watch(() => props.reviewId, load, { immediate: true });
// Keep the live debate view on the latest round: entering the debate phase, or the round counter
// advancing to round two (via DEBATE_ROUND_2_STARTED), moves the selected round forward without
// overwriting a manual R1 selection once the counter is stable.
watch([() => activePhase.value, maxDebateRound], ([phase, round]) => {
    if (phase === 'debate' && round > selectedRound.value) selectedRound.value = round;
}, { immediate: true });
onUnmounted(() => loadQueue.dispose());
</script>

<template>
    <section class="review-flow-page">
        <header class="review-flow-header">
            <RouterLink class="flow-header-link flow-back-link" :to="{ name: 'reviews' }">← 评审列表</RouterLink>
            <RouterLink class="review-flow-brand" :to="{ name: 'dashboard' }">重明</RouterLink>
            <div class="review-flow-title"><strong>需求评审全流程</strong><span>评审 #{{ reviewId }}</span></div>
            <div class="review-flow-header-status" :data-status="runtimeTrace.state.status"><span aria-hidden="true"></span>{{ liveRunState.connectionText }} · {{ stage }}</div>
            <span class="flow-header-button flow-stage-chip" aria-label="评审阶段">{{ stage }}</span>
            <button class="flow-drawer-toggle" type="button" :aria-expanded="drawerOpen" @click="drawerOpen = !drawerOpen">{{ drawerOpen ? '收起观察' : '展开观察' }}</button>
            <button v-if="liveCancelable" class="flow-header-button" type="button" :disabled="commandBusy" @click="cancelLiveWithConfirm">取消评审</button>
            <button class="flow-header-button" type="button" :disabled="commandBusy" @click="() => store.refreshSummary().catch(() => {})">刷新状态</button>
            <RouterLink class="flow-header-link" :to="{ name: 'review-report', params: { reviewId } }">查看最终报告</RouterLink>
        </header>

        <p v-if="latestReviewReady && store.state.error" class="flow-error" role="alert">加载评审观察数据失败，请稍后重试。</p>
        <div v-else-if="!latestReviewReady || store.state.loading" class="flow-loading" aria-label="正在连接评审运行流"><span></span><span></span><span></span></div>
        <div v-else :class="['review-flow-layout', { 'drawer-closed': !drawerOpen }]">
            <nav class="flow-pipeline" aria-label="评审流程">
                <p>评审流程</p>
                <template v-for="(phase, index) in phaseList" :key="phase.id">
                    <button type="button" :class="['flow-phase-button', phaseState(index), { active: activePhase === phase.id }]" @click="selectPhase(phase)">
                        <span class="flow-phase-icon"><img :src="phaseIconUrl(phase.id, phaseState(index))" :alt="phase.name"></span>
                        <span><strong>{{ phase.name }}</strong><small>{{ phase.subtitle }}</small></span>
                    </button>
                    <span v-if="index < phaseList.length - 1" :class="['flow-phase-connector', phaseState(index) === 'done' ? 'done' : '']"></span>
                </template>
            </nav>

            <main class="flow-content" aria-live="polite">
                <header class="flow-phase-header">
                    <div>
                        <p>{{ activePhaseDefinition.subtitle }}</p>
                        <!-- [AIREVIEW-PLAN-041#2] 阶段大标题同步使用三状态图标（状态与左侧流程一致）。 -->
                        <h1><img class="flow-phase-title-icon" :src="phaseIconUrl(activePhaseDefinition.id, phaseState(phases.findIndex((phase) => phase.id === activePhase)))" :alt="activePhaseDefinition.name">{{ activePhaseDefinition.name }}<template v-if="activePhase === 'debate' && debateSubject"> — {{ debateSubject }}</template></h1>
                    </div>
                    <span :class="['flow-phase-badge', phaseState(phases.findIndex((phase) => phase.id === activePhase))]">{{ phaseBadgeText(phaseState(phases.findIndex((phase) => phase.id === activePhase))) }}</span>
                </header>

                <section v-if="lifecycleCardRelevant" class="flow-lifecycle" aria-label="评审生命周期">
                    <ReviewLifecyclePanel
                        :summary="store.state.summary"
                        :busy="commandBusy"
                        :message="commandMessage"
                        @start="startReview"
                        @cancel="cancelReview"
                        @retry="retryReview"
                        @refresh="() => store.refreshSummary().catch(() => {})"
                    />
                    <p v-if="lifecycleError" class="flow-error" role="alert">{{ lifecycleError }}</p>
                </section>

                <!-- ── 流式阶段：Scout / Director / Judge ── -->
                <template v-if="streamPhases.includes(activePhase)">
                    <!-- [AIREVIEW-PLAN-037#2] flow-stream-live：仅流式阶段面板参与视口拉伸与内部滚动，冲突阶段的协调者卡片不参与。 -->
                    <section class="flow-stream-panel flow-stream-live" aria-label="当前阶段公开运行流">
                        <header>
                            <div class="flow-agent-avatar" :data-role="streamOwner.role">{{ streamOwner.initial }}</div>
                            <div><strong>{{ streamOwner.name }}</strong><span>{{ streamOwner.roleDesc }}</span></div>
                            <small>{{ phaseItems.length }} 条运行记录</small>
                        </header>
                        <LiveAgentConversation :items="phaseItems" :status="runtimeTrace.state.status" :empty-state="streamEmpty" />
                    </section>

                    <!-- [AIREVIEW-PLAN-038#2] 子代理启用决策：仅评审规划阶段展示，行由事实事件投影。 -->
                    <section v-if="activePhase === 'director' && activationRows.length" class="flow-activation-section" aria-label="子代理启用决策">
                        <header>
                            <h2>子代理启用决策</h2>
                            <small>{{ activationRows.length }} 个子代理</small>
                        </header>
                        <article v-for="row in activationRows" :key="row.role" class="flow-activation-row">
                            <span class="flow-agent-avatar" :data-role="row.role">{{ roleInitial(row.role) }}</span>
                            <div class="flow-activation-name"><strong>{{ roleTitle(row.role) }}</strong><time v-if="row.activatedAt">{{ formatChinaTime(row.activatedAt) }}</time></div>
                            <span :class="['flow-activation-badge', row.state]">{{ activationBadgeText(row.state) }}</span>
                        </article>
                    </section>

                    <ScoutConclusionPanel v-if="activePhase === 'scout'" :conclusion="scoutConclusion" />

                    <section v-if="activePhase === 'director' && planCards.length" class="flow-plan-section" aria-label="评审计划">
                        <article v-for="plan in planCards" :key="plan.sequence" class="flow-plan-card">
                            <header><strong>{{ plan.type === 'PLAN_REVISED' ? '计划修订' : '评审计划' }} v{{ planVersion(plan) }}</strong><span>{{ formatChinaTime(plan.occurredAt) }}</span></header>
                            <ol v-if="planTasks(plan).length" class="flow-plan-tasks"><li v-for="(task, taskIndex) in planTasks(plan)" :key="taskIndex">{{ task }}</li></ol>
                            <p v-if="plan.payload?.changeReason">修订原因：{{ plan.payload.changeReason }}</p>
                        </article>
                    </section>

                    <section v-if="activePhase === 'judge' && judgements.length" class="flow-judgement-section" aria-label="议题裁决">
                        <article v-for="topic in judgements" :key="topic.topicId" class="flow-judgement-card">
                            <!-- [AIREVIEW-PLAN-044#2] 裁决卡优先显示中文议题标题。 -->
                            <header><span class="flow-judgement-badge">{{ gateLabel(topic.judgement.result) }}</span><strong>{{ topic.title ?? topic.subjectKey }}</strong></header>
                            <p>{{ topic.judgement.reasonSummary }}</p>
                            <small>接受 {{ topic.judgement.acceptedClaimIds?.length ?? 0 }} 项 · 拒绝 {{ topic.judgement.rejectedClaimIds?.length ?? 0 }} 项</small>
                            <details v-if="topic.judgement.acceptedClaimIds?.length"><summary>查看采信的 Claim</summary><ReviewClaimList :claims="claimsForIds(topic.judgement.acceptedClaimIds)" /></details>
                            <details v-if="topic.judgement.rejectedClaimIds?.length"><summary>查看拒绝的 Claim</summary><ReviewClaimList :claims="claimsForIds(topic.judgement.rejectedClaimIds)" /></details>
                        </article>
                    </section>
                </template>

                <!-- [AIREVIEW-PLAN-023#7.1] 独立审查角色来自实际激活和运行身份。 -->
                <template v-else-if="activePhase === 'review'">
                    <div class="flow-review-grid">
                        <article v-for="card in reviewCards" :key="card.role" :class="['flow-review-card', card.tone, { expanded: expandedRole === card.role }]">
                            <button type="button" class="flow-review-card-head" @click="toggleRole(card.role)">
                                <span class="flow-agent-avatar" :data-role="card.role">{{ card.initials }}</span>
                                <strong>{{ card.label }}</strong>
                                <span class="flow-review-badge">{{ card.badge }}</span>
                            </button>
                            <p class="flow-review-summary">{{ card.summary }}</p>
                            <div v-if="expandedRole === card.role" class="flow-review-body">
                                <ReviewClaimList :claims="card.claims" />
                                <p v-if="!card.claims.length && !card.items.length" class="flow-review-empty">该角色尚未公开 Claim 或运行事件。</p>
                                <section class="flow-review-conversation"><h2>公开对话</h2><LiveAgentConversation compact :items="card.items" :empty-state="{ title: '等待公开对话', message: '该角色的正式 AI 输出到达后显示在这里。' }" /></section>
                            </div>
                        </article>
                        <p v-if="!reviewCards.length" class="flow-empty flow-review-grid-empty"><strong>尚未激活独立审查角色</strong><span>协调者发布角色激活结果后，这里只展示实际参与评审的角色。</span></p>
                    </div>

                </template>

                <!-- ── 冲突检测 ── -->
                <template v-else-if="activePhase === 'conflict'">
                    <section v-if="conflictTopics.length" class="flow-conflict-section" aria-label="冲突列表">
                        <p class="flow-conflict-heading">⚡ 已登记 {{ conflictTopics.length }} 个辩论议题<span v-if="opposingCount > 0"> · {{ opposingCount }} 组立场对立</span></p>
                        <article v-for="topic in conflictTopics" :key="topic.topicId" :class="['flow-conflict-card', topic.severity, { 'dissent-only': !topic.opposed }]">
                            <!-- [AIREVIEW-PLAN-044#2] 议题优先显示中文标题，subjectKey 降为技术标识。 -->
                            <header>议题 #{{ topic.index }} [{{ topic.severity }}] · {{ topic.title ?? topic.subject }}<small v-if="topic.title" class="flow-conflict-tech">技术标识：{{ topic.subject }}</small></header>
                            <template v-if="topic.opposed">
                                <p><span class="flow-conflict-role">{{ roleTitle(topic.supports[0].role) }}</span> “{{ topic.supports[0].statement }}” vs <span class="flow-conflict-role">{{ roleTitle(topic.opposes[0].role) }}</span> “{{ topic.opposes[0].statement }}”</p>
                                <small>类型: 立场对立 · 需进入辩论</small>
                            </template>
                            <template v-else>
                                <p>{{ topic.opposes.length }} 条反对主张（{{ topic.opposes.map((claim) => roleTitle(claim.role)).join('、') }}）——等待需求答辩人逐条回应。</p>
                                <small>类型: 异议答辩 · 第 {{ topic.round }} 轮</small>
                            </template>
                        </article>
                    </section>
                    <div v-else class="flow-empty"><strong>尚未检测到立场冲突</strong><p>当支持方与质疑方同时提交 Claim 后，ConflictDetector 会在此汇总冲突组。</p></div>

                    <section class="flow-stream-panel flow-conflict-director" aria-label="协调者冲突处置">
                        <header><div class="flow-agent-avatar director">协</div><div><strong>协调者</strong><span>冲突处置决策</span></div></header>
                        <div class="flow-conflict-director-body">
                            <p v-if="conflictTopics.length">已合并为 {{ debateTopics.length }} 个辩论议题（{{ opposingCount }} 组立场对立 + {{ dissentCount }} 个异议答辩），进入结构化辩论流程。</p>
                            <p v-else>暂无需要处置的冲突；如后续出现立场对立，将自动合并为辩论议题。</p>
                            <p v-if="runtimeItems.filter((item) => item.role === 'DIRECTOR').length" class="flow-conflict-director-latest">{{ itemSummary(runtimeItems.filter((item) => item.role === 'DIRECTOR').at(-1)) }}</p>
                        </div>

                    </section>
                </template>

                <!-- ── 多轮辩论 ── -->
                <template v-else-if="activePhase === 'debate'">
                    <template v-if="debateTopics.length">
                        <!-- [AIREVIEW-PLAN-045#1] 议题 Tab 栏：聚焦议题切换（法庭、轮次上限与对话流均随议题联动），位于回合选项卡上方。 -->
                        <div class="flow-topic-tabs" role="tablist" aria-label="辩论议题">
                            <button v-for="(topic, index) in debateTopics" :key="topic.topicId" type="button" role="tab" :aria-selected="selectedTopic?.topicId === topic.topicId" :class="['flow-topic-tab', { active: selectedTopic?.topicId === topic.topicId }]" @click="switchTopic(topic)">
                                <strong>议题 {{ index + 1 }}</strong><span class="flow-topic-title">{{ topic.title ?? topic.subjectKey }}</span><small :class="['flow-topic-status', isTopicAdjudicated(topic) ? 'adjudicated' : 'ongoing']">{{ isTopicAdjudicated(topic) ? '已裁决' : '进行中' }}</small>
                            </button>
                        </div>
                        <div class="flow-round-tabs" role="tablist" aria-label="辩论回合">
                            <button v-for="round in topicMaxRound" :key="round" type="button" role="tab" :aria-selected="selectedRound === round" :class="['flow-round-tab', { active: selectedRound === round }]" @click="switchRound(round)">
                                R{{ round }} <span>{{ round === topicMaxRound && !isTopicTerminal(selectedTopic) ? '进行中' : '已完成' }}</span>
                            </button>
                        </div>

                        <!-- [AIREVIEW-PLAN-043#4] 辩论页分两块独立定高内滚：对抗看板（法庭+中立方）与辩论对话流。 -->
                        <div class="flow-debate-split">
                        <div class="flow-debate-board">
                        <div class="flow-debate-court">
                            <div class="flow-debate-side pro">
                                <p class="flow-debate-label">🟢 支持方</p>
                                <article v-for="claim in proClaims" :key="claim.claimId" class="flow-debate-claim">
                                    <header><span class="flow-agent-avatar" :data-role="claim.role">{{ roleInitial(claim.role) }}</span><strong>{{ roleTitle(claim.role) }}</strong><span :class="['flow-severity', claim.severity]">{{ claim.severity }}</span></header>
                                    <p>{{ claim.statement || '该 Claim 暂无公开正文' }}</p><small v-if="claim.subjectKey">技术标识：{{ claim.subjectKey }}</small>
                                </article>
                                <p v-if="!proClaims.length" class="flow-debate-empty">暂无支持方 Claim。</p>
                            </div>
                            <div class="flow-debate-center">
                                <div class="flow-consensus-gauge">
                                    <svg viewBox="0 0 100 100" aria-hidden="true">
                                        <circle class="track" cx="50" cy="50" r="42"></circle>
                                        <circle class="fill" cx="50" cy="50" r="42" :style="{ strokeDashoffset: consensusOffset, stroke: consensusStroke }" stroke-dasharray="264"></circle>
                                    </svg>
                                    <strong>{{ consensusPercent }}%</strong>
                                </div>
                                <small>共识度（支持 Claim 占比）</small>
                                <p v-if="!proClaims.length && conClaims.length" class="flow-debate-empty">议题当前仅含反对方论点，暂无支持方。</p>
                                <p class="flow-round-display"><strong>R{{ selectedRound }}</strong> / {{ topicMaxRound }}</p>
                            </div>
                            <div class="flow-debate-side con">
                                <p class="flow-debate-label">🔴 质疑方</p>
                                <article v-for="claim in conClaims" :key="claim.claimId" class="flow-debate-claim">
                                    <header><span class="flow-agent-avatar" :data-role="claim.role">{{ roleInitial(claim.role) }}</span><strong>{{ roleTitle(claim.role) }}</strong><span :class="['flow-severity', claim.severity]">{{ claim.severity }}</span></header>
                                    <p>{{ claim.statement || '该 Claim 暂无公开正文' }}</p><small v-if="claim.subjectKey">技术标识：{{ claim.subjectKey }}</small>
                                </article>
                                <p v-if="!conClaims.length" class="flow-debate-empty">暂无质疑方 Claim。</p>
                            </div>
                        </div>

                        <section v-if="neutralClaims.length" class="flow-neutral-claims" aria-label="中立方 Claim">
                            <p class="flow-debate-label">⚪ 中立方</p>
                            <article v-for="claim in neutralClaims" :key="claim.claimId" class="flow-debate-claim">
                                <header><span class="flow-agent-avatar" :data-role="claim.role">{{ roleInitial(claim.role) }}</span><strong>{{ roleTitle(claim.role) }}</strong><span :class="['flow-severity', claim.severity]">{{ claim.severity }}</span></header>
                                <p>{{ claim.statement || '该 Claim 暂无公开正文' }}</p><small v-if="claim.subjectKey">技术标识：{{ claim.subjectKey }}</small>
                            </article>
                        </section>
                        </div><!-- /flow-debate-board [AIREVIEW-PLAN-043#4] -->

                        <section class="flow-debate-dialogue" aria-label="辩论对话流">
                            <p class="flow-conflict-heading">📜 辩论对话流 · R{{ selectedRound }}</p>
                            <ol v-if="roundTurns.length" class="flow-dialogue-list">
                                <li v-for="turn in roundTurns" :key="turn.turnId">
                                    <header><span class="flow-agent-avatar" :data-role="turn.actorRole">{{ roleInitial(turn.actorRole) }}</span><strong>{{ roleTitle(turn.actorRole) }}</strong><span class="flow-turn-type">{{ turnTypeLabel(turn.type) }}</span><span v-if="turn.targetRole" class="flow-turn-target">→ {{ roleTitle(turn.targetRole) }}</span></header>
                                    <p>{{ turn.content }}</p>
                                    <small v-if="turn.stanceBefore || turn.stanceAfter">立场：{{ turn.stanceBefore || '—' }} → {{ turn.stanceAfter || '—' }}</small>
                                </li>
                            </ol>
                            <p v-else class="flow-debate-empty">该回合暂无公开的质询或答辩记录。</p>
                        </section>
                        </div><!-- /flow-debate-split [AIREVIEW-PLAN-043#4] -->
                    </template>
                    <!-- [AIREVIEW-PLAN-034#5] 辩论时间线与法庭视图/对话流信息重复，先注释掉；如需恢复取消注释并把下方空态改回 v-else。
                    <DebateTimeline v-if="debateTopics.length" class="flow-debate-timeline" :debates="debateTopics" @open-evidence="store.selectEvidence" />
                    -->
                    <div v-if="!debateTopics.length" class="flow-empty"><strong>尚未开启辩论议题</strong><p>冲突检测完成后，协调者会将冲突组合并为辩论议题并在此展示回合对阵。</p></div>
                </template>

                <!-- ── 人工决策 ── -->
                <template v-else-if="activePhase === 'human'">
                    <p v-if="!humanPhaseReachable" class="flow-human-not-ready" role="status">
                        评审尚未进入人工决策阶段（当前阶段：{{ stageLabel[stage] ?? stage }}）。裁决者提交裁决并形成关口草案后，流程会自动推进到这里。
                    </p>
                    <section class="flow-conclusion-chain" aria-label="评审结论链">
                        <article><span>1</span><div><small>议题裁决</small><strong v-if="judgements.length">{{ judgements.length }} 个议题已裁决</strong><strong v-else-if="pendingJudgementTopics.length">{{ pendingJudgementTopics.length }} 个议题已升级，等待 Judge 裁决</strong><strong v-else>等待裁决</strong><p v-if="judgements.length">{{ judgements.map((topic) => gateLabel(topic.judgement.result)).join('、') }}</p></div></article>
                        <i aria-hidden="true">↓</i>
                        <article><span>2</span><div><small>确定性 AI Gate 草案</small><strong v-if="gateDraft">{{ gateLabel(gateDraft.result) }} · {{ gateLabel(gateDraft.status) }}</strong><strong v-else>尚未形成</strong><p v-if="gateDraft?.reasonSummary">{{ gateDraft.reasonSummary }}</p></div></article>
                        <i aria-hidden="true">↓</i>
                        <article><span>3</span><div><small>人工最终 Gate</small><strong v-if="store.state.humanGateVersions.length">{{ gateLabel(store.state.humanGateVersions.at(-1).result) }}</strong><strong v-else>等待人工决策</strong><p v-if="store.state.humanGateVersions.at(-1)?.reason">{{ store.state.humanGateVersions.at(-1).reason }}</p></div></article>
                        <i aria-hidden="true">↓</i>
                        <article><span>4</span><div><small>正式报告版本</small><strong>{{ store.state.reportVersions?.length ? `已生成 ${store.state.reportVersions.length} 个版本` : '等待最终 Gate 后生成' }}</strong></div></article>
                    </section>

                    <section v-if="gateOverride" class="flow-gate-difference" role="status">
                        <strong>人工结论与 AI Gate 草案不同</strong>
                        <p>AI 草案：{{ gateLabel(gateOverride.aiResult) }}；人工最终结论：{{ gateLabel(gateOverride.humanResult) }}。</p>
                        <p v-if="gateOverride.reason">人工理由：{{ gateOverride.reason }}</p>
                    </section>

                    <section class="flow-verdict-bar">
                        <span class="flow-verdict-badge">🧑 人工决策</span>
                        <span class="flow-verdict-text">系统已暂停 AI 输出，最终结论必须由人工在本页明确选择并提交</span>
                    </section>
                    <p v-if="humanPanelError" class="flow-error" role="alert">{{ humanPanelError }}</p>
                    <HumanReviewPanel
                        v-if="humanPhaseReachable"
                        :review-id="reviewId"
                        :gate-versions="store.state.humanGateVersions"
                        :gate-draft="gateDraft"
                        :debates="store.state.debates"
                        :claims="store.state.claims"
                        :review-version="store.state.summary?.reviewVersion ?? null"
                        @changed="async () => { await store.refreshHumanData(); await store.refreshReports(); await store.refreshNotifications(); }"
                        @error="(message) => { humanPanelError = message; }"
                    />

                    <section v-if="store.state.humanGateVersions.length" class="flow-gate-history" aria-label="Gate 版本历史">
                        <article v-for="gate in store.state.humanGateVersions" :key="gate.gateVersion">
                            <strong>v{{ gate.gateVersion }} · {{ gateLabel(gate.result) }}</strong><span>{{ formatChinaTime(gate.decidedAt) }}</span>
                            <p>{{ gate.reason }}</p>
                        </article>
                    </section>

                    <section class="flow-notification-panel" aria-labelledby="flow-notification-title">
                        <header><h2 id="flow-notification-title">通知状态</h2></header>
                        <p class="flow-notification-note">通知失败不会改变评审事实；可对 FAILED 或 DEAD 的通知发起幂等重试。</p>
                        <ul v-if="store.state.notifications.length" class="notification-list">
                            <li v-for="entry in store.state.notifications" :key="entry.notificationId">
                                <div><strong>{{ entry.deliveryStatus }}</strong><span>{{ entry.command?.channel }} · Gate v{{ entry.command?.gateVersion }}</span></div>
                                <p>{{ entry.lastErrorCode || entry.responseCode || '等待投递结果' }}</p>
                                <button v-if="['FAILED', 'DEAD'].includes(entry.deliveryStatus)" class="text-button" type="button" @click="retryNotification(entry)">重试（v{{ entry.version }}）</button>
                            </li>
                        </ul>
                        <p v-else class="flow-empty">最终 Gate 提交后将显示通知 Outbox 状态。</p>
                    </section>
                </template>
            </main>

            <ReviewConversationDrawer :open="drawerOpen" :items="runtimeItems" :facts="factTimeline" :debug-items="debugItems" @close="drawerOpen = false" />
        </div>
        <EvidenceDrawer :evidence="store.state.selectedEvidence" @close="store.state.selectedEvidence = null" />
    </section>
</template>
