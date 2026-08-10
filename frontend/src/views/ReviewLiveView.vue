<script setup>
import { computed, onUnmounted, ref, watch } from 'vue';
import { RouterLink } from 'vue-router';
import { buildRuntimeConversation } from '../services/runtime-conversation-adapter';
import { describeLiveRunState } from '../services/live-run-status';
import { createReviewStore } from '../stores/review-store';
import { createRuntimeTraceStore } from '../stores/runtime-trace-store';

const props = defineProps({ reviewId: { type: String, required: true } });
const store = createReviewStore();
const runtimeTrace = createRuntimeTraceStore();
const selectedPhase = ref(null);
const selectedRound = ref(1);
const expandedRole = ref(null);
const sidebarTab = ref('facts');
let loadGeneration = 0;
const stage = computed(() => store.state.summary?.stage ?? 'PENDING');
const liveRunState = computed(() => describeLiveRunState(stage.value, runtimeTrace.state.status));
const runtimeItems = computed(() => buildRuntimeConversation(runtimeTrace.state.events));

const phases = [
    { id: 'scout', icon: '🔍', name: 'Context Scout', subtitle: '项目信息收集' },
    { id: 'director', icon: '🎬', name: 'Director 规划', subtitle: '创建评审计划' },
    { id: 'review', icon: '👥', name: '独立审查', subtitle: '4 角色并行' },
    { id: 'conflict', icon: '⚡', name: '冲突检测', subtitle: '识别分歧与风险' },
    { id: 'debate', icon: '⚖️', name: '多轮辩论', subtitle: '围绕争议收敛' },
    { id: 'judge', icon: '👨‍⚖️', name: 'Judge 裁决', subtitle: '形成评审判断' },
    { id: 'human', icon: '🧑', name: '人工决策', subtitle: '最终 Gate' }
];
const coreRoles = ['PRODUCT', 'PROJECT', 'FRONTEND', 'BACKEND'];
const streamPhases = ['scout', 'director', 'judge'];
const severityRank = { P0: 0, P1: 1, P2: 2, P3: 3 };
const phaseIndexByStage = {
    SNAPSHOTTING: 0,
    PLANNING: 1,
    INITIAL_REVIEW: 2,
    CONFLICT_DETECTION: 3,
    DEBATE_ROUND_1: 4,
    DEBATE_ROUND_2: 4,
    JUDGING: 5,
    WAITING_HUMAN: 6,
    NOTIFYING: 6,
    COMPLETED: 6,
    CANCELLED: 6
};

function roleTitle(role) {
    return {
        DIRECTOR: 'Director 协调者', CONTEXT_SCOUT: 'Context Scout', PRODUCT: '产品经理', PROJECT: '项目经理',
        FRONTEND: '前端工程师', BACKEND: '后端工程师', JUDGE: 'Judge 裁决者'
    }[role] ?? role ?? 'Agent';
}

function roleInitial(role) {
    return role === 'CONTEXT_SCOUT' ? 'S' : role === 'DIRECTOR' ? 'D' : role === 'JUDGE' ? 'J' : String(role ?? 'A').slice(0, 1);
}

function phaseForRole(role) {
    if (role === 'CONTEXT_SCOUT') return 0;
    if (role === 'DIRECTOR') return 1;
    if (role === 'JUDGE') return 5;
    return coreRoles.includes(role) ? 2 : 1;
}

const activePhaseIndex = computed(() => {
    if (stage.value === 'FAILED') {
        const lastItem = runtimeItems.value.at(-1);
        return lastItem ? phaseForRole(lastItem.role) : 1;
    }
    if (stage.value === 'PENDING') return runtimeItems.value.some((item) => item.role === 'CONTEXT_SCOUT') ? 0 : 1;
    return phaseIndexByStage[stage.value] ?? 1;
});
const activePhase = computed(() => selectedPhase.value ?? phases[activePhaseIndex.value].id);
const activePhaseDefinition = computed(() => phases.find((phase) => phase.id === activePhase.value) ?? phases[activePhaseIndex.value]);

const debateTopics = computed(() => store.state.debates ?? []);
const allClaims = computed(() => debateTopics.value.flatMap((topic) => topic.claims ?? []));
const roleClaims = computed(() => {
    const byId = new Map();
    [...(Array.isArray(store.state.claims) ? store.state.claims : []), ...allClaims.value]
        .forEach((claim) => { if (claim?.claimId) byId.set(claim.claimId, claim); });
    return [...byId.values()];
});
const completedRoles = computed(() => (store.state.summary?.activatedRoles ?? []).filter((entry) => entry.initialReviewCompleted).length);
const scoutHasActivity = computed(() => runtimeTrace.state.events.some((event) =>
    String(event?.runId ?? '').includes(':context-scout')));
const scoutRunFinished = computed(() => runtimeTrace.state.events.some((event) =>
    event?.type === 'RUN_FINISHED' && String(event?.runId ?? '').includes(':context-scout')));
const scoutComplete = computed(() =>
    activePhaseIndex.value >= 2
    || store.state.summary?.contextScout?.status === 'DEGRADED'
    || scoutRunFinished.value);
const maxDebateRound = computed(() => Math.max(1,
    store.state.summary?.stage === 'DEBATE_ROUND_2' ? 2 : 1,
    ...debateTopics.value.map((topic) => topic.currentRound ?? 1),
    ...debateTopics.value.flatMap((topic) => (topic.turns ?? []).map((turn) => turn.round ?? 1))));
const conflicts = computed(() => debateTopics.value.flatMap((topic, index) => {
    const claims = topic.claims ?? [];
    const supports = claims.filter((claim) => claim.position === 'SUPPORT');
    const opposes = claims.filter((claim) => claim.position === 'OPPOSE');
    if (!supports.length || !opposes.length) return [];
    const severity = [...claims].sort((left, right) => (severityRank[left.severity] ?? 9) - (severityRank[right.severity] ?? 9))[0]?.severity ?? 'P2';
    return [{ index: index + 1, topicId: topic.topicId, subject: topic.subjectKey, severity, support: supports[0], oppose: opposes[0] }];
}));
const phaseList = computed(() => phases.map((phase) => {
    if (phase.id === 'review' && completedRoles.value) return { ...phase, subtitle: `${completedRoles.value}/${coreRoles.length} 角色初审完成` };
    if (phase.id === 'conflict' && conflicts.value.length) return { ...phase, subtitle: `${conflicts.value.length} 组冲突发现` };
    if (phase.id === 'debate' && debateTopics.value.length) return { ...phase, subtitle: `第 ${maxDebateRound.value} 轮 · ${debateTopics.value.length} 个议题` };
    return phase;
}));

const proClaims = computed(() => allClaims.value.filter((claim) => claim.position === 'SUPPORT'));
const conClaims = computed(() => allClaims.value.filter((claim) => claim.position !== 'SUPPORT'));
const consensusPercent = computed(() => {
    if (!allClaims.value.length) return 0;
    return Math.round((proClaims.value.length / allClaims.value.length) * 100);
});
const consensusOffset = computed(() => 264 - Math.round((264 * consensusPercent.value) / 100));
const consensusStroke = computed(() => {
    if (consensusPercent.value > 70) return 'var(--flow-green)';
    return consensusPercent.value > 40 ? 'var(--flow-yellow)' : 'var(--flow-red)';
});
const roundTurns = computed(() => debateTopics.value
    .flatMap((topic) => (topic.turns ?? []).map((turn) => ({ ...turn, subject: topic.subjectKey })))
    .filter((turn) => (turn.round ?? 1) === selectedRound.value));
const judgements = computed(() => debateTopics.value.filter((topic) => topic.judgement));
const debateSubject = computed(() => debateTopics.value[0]?.subjectKey ?? null);
const gateDraft = computed(() => store.state.summary?.gate ?? null);

const phaseItems = computed(() => {
    const phase = activePhase.value;
    if (phase === 'scout') return runtimeItems.value.filter((item) => item.role === 'CONTEXT_SCOUT');
    if (phase === 'director') return runtimeItems.value.filter((item) => item.role === 'DIRECTOR');
    if (phase === 'judge') return runtimeItems.value.filter((item) => item.role === 'JUDGE' || item.role === 'DIRECTOR');
    return [];
});
const streamOwner = computed(() => {
    if (activePhase.value === 'scout') return { initial: 'S', role: 'CONTEXT_SCOUT', name: 'Context Scout', roleDesc: '项目上下文探索 Agent' };
    if (activePhase.value === 'judge') return { initial: 'J', role: 'JUDGE', name: 'Judge 裁决者', roleDesc: '仅基于已持久化的 Claim 和证据裁决' };
    return { initial: 'D', role: 'DIRECTOR', name: 'Director 协调者', roleDesc: '评审流程编排 Agent' };
});
const reviewCards = computed(() => coreRoles.map((role) => {
    const items = runtimeItems.value.filter((item) => item.role === role);
    const claims = roleClaims.value.filter((claim) => claim.role === role);
    const activation = store.state.summary?.activatedRoles?.find((entry) => entry.role === role);
    const latest = items.at(-1);
    const running = latest?.status === 'streaming' || latest?.status === 'RUNNING';
    const stance = claims.some((claim) => claim.position === 'OPPOSE') ? 'oppose'
        : claims.some((claim) => claim.position === 'SUPPORT') ? 'support' : null;
    const completed = Boolean(activation?.initialReviewCompleted);
    return {
        role,
        label: roleTitle(role),
        initials: roleInitial(role),
        badge: completed ? '✅ 初审完成' : running ? '⏳ 进行中'
            : stance === 'oppose' ? '❌ 反对' : stance === 'support' ? '✅ 支持' : '等待分配',
        tone: completed || stance === 'support' ? 'done' : running ? 'running' : stance === 'oppose' ? 'opposed' : 'pending',
        summary: claims.length
            ? `提交 ${claims.length} 个 Claim · ${claims.map((claim) => `${claim.severity}: ${claim.subjectKey}`).join(' · ')}`
            : completed ? '初审已完成，等待冲突检测汇总各方论点。'
            : latest ? itemSummary(latest) : activation ? '角色已激活，等待公开运行事件。' : '等待 Director 分配评审任务。',
        claims,
        items
    };
}));
const planCards = computed(() => store.state.plans ?? []);
const streamEmpty = computed(() => {
    if (liveRunState.value.emptyState) return liveRunState.value.emptyState;
    if (activePhase.value === 'director') {
        return {
            title: 'Director 尚未广播运行时对话',
            message: '评审计划以持久化事实发布（见下方计划卡的任务清单与修订原因）；Director 的运行时消息仅在编排层广播 AG-UI 事件时出现。'
        };
    }
    if (activePhase.value === 'judge') {
        return { title: '等待 Judge 开始裁决', message: 'Judge 仅基于已持久化的 Claim 与证据工作，运行事件到达后按顺序展示。' };
    }
    return { title: '等待当前阶段的公开运行事件', message: '运行事件会按照实际到达顺序显示；工具参数与完整结果仅在展开后可见。' };
});
const factTimeline = computed(() => store.events.value.slice(-14).reverse());
const debugItems = computed(() => runtimeItems.value.slice(-24).reverse());

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

function eventTitle(event) {
    return {
        PLAN_CREATED: 'Director 发布评审计划', ROLE_ACTIVATED: `激活 ${roleTitle(event.actorRole)} 角色`,
        ROLE_STARTED: `${roleTitle(event.actorRole)} 开始运行`, ROLE_COMPLETED: `${roleTitle(event.actorRole)} 初审已完成`,
        INITIAL_REVIEW_COMPLETED: '全部角色初审已完成',
        CLAIM_SUBMITTED: `${roleTitle(event.actorRole)} 提交 Claim`,
        EVIDENCE_CAPTURED: '登记证据', DEBATE_TOPIC_OPENED: '创建辩论议题',
        DEBATE_TOPIC_CLOSED: '关闭辩论议题', CHALLENGE_SUBMITTED: '提交质询',
        REBUTTAL_SUBMITTED: '提交答辩', POSITION_CHANGED: '更新立场',
        JUDGEMENT_SUBMITTED: 'Judge 提交裁决', GATE_DRAFTED: '形成 Gate 草案',
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
    const generation = ++loadGeneration;
    selectedPhase.value = null;
    selectedRound.value = 1;
    expandedRole.value = null;
    sidebarTab.value = 'facts';
    await store.load(reviewId);
    if (generation !== loadGeneration || reviewId !== props.reviewId) return;
    runtimeTrace.start(reviewId, store.state.summary?.attempt ?? 1);
}

watch(() => props.reviewId, load, { immediate: true });
// Keep the live debate view on the latest round: entering the debate phase, or the round counter
// advancing to round two (via DEBATE_ROUND_2_STARTED), moves the selected round forward without
// overwriting a manual R1 selection once the counter is stable.
watch([() => activePhase.value, maxDebateRound], ([phase, round]) => {
    if (phase === 'debate' && round > selectedRound.value) selectedRound.value = round;
}, { immediate: true });
onUnmounted(() => { store.dispose(); runtimeTrace.dispose(); });
</script>

<template>
    <section class="review-flow-page">
        <header class="review-flow-header">
            <div class="review-flow-brand">重明</div>
            <div class="review-flow-title"><strong>需求评审全流程</strong><span>评审 #{{ reviewId }}</span></div>
            <div class="review-flow-header-status" :data-status="runtimeTrace.state.status"><span aria-hidden="true"></span>{{ liveRunState.connectionText }} · {{ stage }}</div>
            <RouterLink class="flow-header-link" :to="{ name: 'review-workbench', params: { reviewId } }">返回评审工作台</RouterLink>
        </header>

        <p v-if="store.state.error" class="flow-error" role="alert">加载评审观察数据失败，请返回工作台查看正式状态。</p>
        <div v-else-if="store.state.loading" class="flow-loading" aria-label="正在连接评审运行流"><span></span><span></span><span></span></div>
        <div v-else class="review-flow-layout">
            <nav class="flow-pipeline" aria-label="评审流程">
                <p>评审流程</p>
                <template v-for="(phase, index) in phaseList" :key="phase.id">
                    <button type="button" :class="['flow-phase-button', phaseState(index), { active: activePhase === phase.id }]" @click="selectPhase(phase)">
                        <span class="flow-phase-icon">{{ phase.icon }}</span>
                        <span><strong>{{ phase.name }}</strong><small>{{ phase.subtitle }}</small></span>
                    </button>
                    <span v-if="index < phaseList.length - 1" :class="['flow-phase-connector', phaseState(index) === 'done' ? 'done' : '']"></span>
                </template>
            </nav>

            <main class="flow-content" aria-live="polite">
                <header class="flow-phase-header">
                    <div>
                        <p>{{ activePhaseDefinition.subtitle }}</p>
                        <h1>{{ activePhaseDefinition.icon }} {{ activePhaseDefinition.name }}<template v-if="activePhase === 'debate' && debateSubject"> — {{ debateSubject }}</template></h1>
                    </div>
                    <span :class="['flow-phase-badge', phaseState(phases.findIndex((phase) => phase.id === activePhase))]">{{ phaseBadgeText(phaseState(phases.findIndex((phase) => phase.id === activePhase))) }}</span>
                </header>

                <!-- ── 流式阶段：Scout / Director / Judge ── -->
                <template v-if="streamPhases.includes(activePhase)">
                    <section class="flow-stream-panel" aria-label="当前阶段公开运行流">
                        <header>
                            <div class="flow-agent-avatar" :data-role="streamOwner.role">{{ streamOwner.initial }}</div>
                            <div><strong>{{ streamOwner.name }}</strong><span>{{ streamOwner.roleDesc }}</span></div>
                            <small>{{ phaseItems.length }} 条运行记录</small>
                        </header>
                        <ol v-if="phaseItems.length" class="flow-stream-list">
                            <li v-for="item in phaseItems" :key="item.id" :class="`is-${item.kind}`">
                                <div class="flow-stream-role"><span class="flow-agent-avatar" :data-role="item.role">{{ roleInitial(item.role) }}</span><strong>{{ roleTitle(item.role) }}</strong></div>
                                <details v-if="item.kind === 'tool'" :open="item.status === 'RUNNING'" class="flow-tool-event" :data-status="item.status">
                                    <summary><code>{{ item.toolName }}</code><span>{{ toolStatus(item.status) }}</span><small v-if="item.elapsedMs != null">{{ item.elapsedMs }}ms</small></summary>
                                    <div><section><h2>参数</h2><pre>{{ format(item.input) }}</pre></section><section><h2>结果</h2><pre>{{ format(item.output) }}</pre></section></div>
                                </details>
                                <details v-else-if="item.kind === 'thinking'" class="flow-thought"><summary>{{ itemSummary(item) }}</summary><pre>{{ item.content }}</pre></details>
                                <p v-else :class="item.kind === 'notice' ? 'flow-notice' : 'flow-message'">{{ item.content }}</p>
                            </li>
                        </ol>
                        <div v-else class="flow-empty"><strong>{{ streamEmpty.title }}</strong><p>{{ streamEmpty.message }}</p></div>
                    </section>

                    <section v-if="activePhase === 'director' && planCards.length" class="flow-plan-section" aria-label="评审计划">
                        <article v-for="plan in planCards" :key="plan.sequence" class="flow-plan-card">
                            <header><strong>{{ plan.type === 'PLAN_REVISED' ? '计划修订' : '评审计划' }} v{{ planVersion(plan) }}</strong><span>{{ plan.occurredAt }}</span></header>
                            <ol v-if="planTasks(plan).length" class="flow-plan-tasks"><li v-for="(task, taskIndex) in planTasks(plan)" :key="taskIndex">{{ task }}</li></ol>
                            <p v-if="plan.payload?.changeReason">修订原因：{{ plan.payload.changeReason }}</p>
                        </article>
                    </section>

                    <section v-if="activePhase === 'judge' && judgements.length" class="flow-judgement-section" aria-label="议题裁决">
                        <article v-for="topic in judgements" :key="topic.topicId" class="flow-judgement-card">
                            <header><span class="flow-judgement-badge">{{ topic.judgement.result }}</span><strong>{{ topic.subjectKey }}</strong></header>
                            <p>{{ topic.judgement.reasonSummary }}</p>
                            <small>接受 {{ topic.judgement.acceptedClaimIds?.length ?? 0 }} 项 · 拒绝 {{ topic.judgement.rejectedClaimIds?.length ?? 0 }} 项</small>
                        </article>
                    </section>
                </template>

                <!-- ── 独立审查：4 角色卡片 ── -->
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
                                <div v-for="claim in card.claims" :key="claim.claimId" class="flow-review-claim">
                                    <span :class="['flow-severity', claim.severity]">{{ claim.severity }}</span>
                                    <div><strong>{{ claim.subjectKey }}</strong><p>{{ claim.statement }}</p><small v-if="claim.reasonSummary">{{ claim.reasonSummary }}</small></div>
                                </div>
                                <p v-if="!card.claims.length && !card.items.length" class="flow-review-empty">该角色尚未公开 Claim 或运行事件。</p>
                                <div v-for="item in card.items" :key="item.id" class="flow-review-runtime">
                                    <details v-if="item.kind === 'tool'" :open="item.status === 'RUNNING'" class="flow-tool-event" :data-status="item.status">
                                        <summary><code>{{ item.toolName }}</code><span>{{ toolStatus(item.status) }}</span><small v-if="item.elapsedMs != null">{{ item.elapsedMs }}ms</small></summary>
                                        <div><section><h2>参数</h2><pre>{{ format(item.input) }}</pre></section><section><h2>结果</h2><pre>{{ format(item.output) }}</pre></section></div>
                                    </details>
                                    <details v-else-if="item.kind === 'thinking'" class="flow-thought"><summary>{{ itemSummary(item) }}</summary><pre>{{ item.content }}</pre></details>
                                    <p v-else :class="item.kind === 'notice' ? 'flow-notice' : 'flow-message'">{{ item.content }}</p>
                                </div>
                            </div>
                        </article>
                    </div>
                </template>

                <!-- ── 冲突检测 ── -->
                <template v-else-if="activePhase === 'conflict'">
                    <section v-if="conflicts.length" class="flow-conflict-section" aria-label="冲突列表">
                        <p class="flow-conflict-heading">⚡ ConflictDetector 发现 {{ conflicts.length }} 组冲突</p>
                        <article v-for="conflict in conflicts" :key="conflict.topicId" :class="['flow-conflict-card', conflict.severity]">
                            <header>冲突 #{{ conflict.index }} [{{ conflict.severity }}] · {{ conflict.subject }}</header>
                            <p><span class="flow-conflict-role">{{ roleTitle(conflict.support.role) }}</span> “{{ conflict.support.statement }}” vs <span class="flow-conflict-role">{{ roleTitle(conflict.oppose.role) }}</span> “{{ conflict.oppose.statement }}”</p>
                            <small>类型: 立场对立 · 需进入辩论</small>
                        </article>
                    </section>
                    <div v-else class="flow-empty"><strong>尚未检测到立场冲突</strong><p>当支持方与质疑方同时提交 Claim 后，ConflictDetector 会在此汇总冲突组。</p></div>

                    <section class="flow-stream-panel flow-conflict-director" aria-label="Director 冲突处置">
                        <header><div class="flow-agent-avatar director">D</div><div><strong>Director 协调者</strong><span>冲突处置决策</span></div></header>
                        <div class="flow-conflict-director-body">
                            <p v-if="conflicts.length">检测到 {{ conflicts.length }} 组冲突，已合并为 {{ debateTopics.length }} 个辩论议题，进入结构化辩论流程。</p>
                            <p v-else>暂无需要处置的冲突；如后续出现立场对立，将自动合并为辩论议题。</p>
                            <p v-if="runtimeItems.filter((item) => item.role === 'DIRECTOR').length" class="flow-conflict-director-latest">{{ itemSummary(runtimeItems.filter((item) => item.role === 'DIRECTOR').at(-1)) }}</p>
                        </div>
                    </section>
                </template>

                <!-- ── 多轮辩论 ── -->
                <template v-else-if="activePhase === 'debate'">
                    <template v-if="debateTopics.length">
                        <div class="flow-round-tabs" role="tablist" aria-label="辩论回合">
                            <button v-for="round in maxDebateRound" :key="round" type="button" role="tab" :aria-selected="selectedRound === round" :class="['flow-round-tab', { active: selectedRound === round }]" @click="switchRound(round)">
                                R{{ round }} <span>{{ round === maxDebateRound && !isTopicTerminal(debateTopics[0]) ? '进行中' : '已完成' }}</span>
                            </button>
                        </div>

                        <div class="flow-debate-court">
                            <div class="flow-debate-side pro">
                                <p class="flow-debate-label">🟢 支持方</p>
                                <article v-for="claim in proClaims" :key="claim.claimId" class="flow-debate-claim">
                                    <header><span class="flow-agent-avatar" :data-role="claim.role">{{ roleInitial(claim.role) }}</span><strong>{{ roleTitle(claim.role) }}</strong><span :class="['flow-severity', claim.severity]">{{ claim.severity }}</span></header>
                                    {{ claim.subjectKey }}
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
                                <p v-if="!proClaims.length && allClaims.length" class="flow-debate-empty">议题当前仅含反对方论点，暂无支持方。</p>
                                <p class="flow-round-display"><strong>R{{ selectedRound }}</strong> / {{ maxDebateRound }}</p>
                            </div>
                            <div class="flow-debate-side con">
                                <p class="flow-debate-label">🔴 质疑方</p>
                                <article v-for="claim in conClaims" :key="claim.claimId" class="flow-debate-claim">
                                    <header><span class="flow-agent-avatar" :data-role="claim.role">{{ roleInitial(claim.role) }}</span><strong>{{ roleTitle(claim.role) }}</strong><span :class="['flow-severity', claim.severity]">{{ claim.severity }}</span></header>
                                    {{ claim.subjectKey }}
                                </article>
                                <p v-if="!conClaims.length" class="flow-debate-empty">暂无质疑方 Claim。</p>
                            </div>
                        </div>

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
                    </template>
                    <div v-else class="flow-empty"><strong>尚未开启辩论议题</strong><p>冲突检测完成后，Director 会将冲突组合并为辩论议题并在此展示回合对阵。</p></div>
                </template>

                <!-- ── 人工决策 ── -->
                <template v-else-if="activePhase === 'human'">
                    <section class="flow-gate-card" aria-label="AI 裁决草案">
                        <p class="flow-conflict-heading">⚖️ AI 裁决草案</p>
                        <template v-if="gateDraft">
                            <p><strong>结论:</strong> {{ gateDraft.result }} · {{ gateDraft.status }}</p>
                            <p v-if="gateDraft.reasonSummary">{{ gateDraft.reasonSummary }}</p>
                            <small v-if="gateDraft.decidedAt">{{ gateDraft.actor ?? 'Judge' }} · {{ gateDraft.decidedAt }}</small>
                        </template>
                        <p v-else class="flow-debate-empty">Judge 完成裁决后，AI Gate 草案会在此展示。</p>
                    </section>

                    <section class="flow-verdict-bar">
                        <span class="flow-verdict-badge">🧑 人工决策</span>
                        <span class="flow-verdict-text">系统已暂停 AI 输出，等待人类做出最终版本化决策</span>
                        <div class="flow-verdict-btns">
                            <RouterLink class="pass" :to="{ name: 'review-workbench', params: { reviewId } }">✅ 通过</RouterLink>
                            <RouterLink class="conditional" :to="{ name: 'review-workbench', params: { reviewId } }">⚠️ 有条件通过</RouterLink>
                            <RouterLink class="block" :to="{ name: 'review-workbench', params: { reviewId } }">❌ 驳回</RouterLink>
                            <RouterLink class="return" :to="{ name: 'review-workbench', params: { reviewId } }">↩️ 退回</RouterLink>
                            <RouterLink class="override" :to="{ name: 'review-workbench', params: { reviewId } }">🔮 覆盖</RouterLink>
                        </div>
                    </section>

                    <section v-if="store.state.humanGateVersions.length" class="flow-gate-history" aria-label="Gate 版本历史">
                        <article v-for="gate in store.state.humanGateVersions" :key="gate.gateVersion">
                            <strong>v{{ gate.gateVersion }} · {{ gate.result }}</strong><span>{{ gate.decidedAt }}</span>
                            <p>{{ gate.reason }}</p>
                        </article>
                    </section>
                </template>
            </main>

            <aside class="flow-sidebar">
                <div class="flow-sidebar-tabs"><button type="button" :class="{ active: sidebarTab === 'facts' }" @click="sidebarTab = 'facts'">评审事实</button><button type="button" :class="{ active: sidebarTab === 'debug' }" @click="sidebarTab = 'debug'">运行调试</button></div>
                <ol v-if="sidebarTab === 'facts'" class="flow-fact-timeline">
                    <li v-for="event in factTimeline" :key="event.sequence" :data-type="event.type"><span></span><div><strong>{{ eventTitle(event) }}</strong><p>{{ eventDetail(event) }}</p><small>#{{ event.sequence }} · {{ event.occurredAt }}</small></div></li>
                    <li v-if="!factTimeline.length" class="flow-sidebar-empty">尚未收到持久化评审事实。</li>
                </ol>
                <ol v-else class="flow-debug-list">
                    <li v-for="item in debugItems" :key="item.id"><strong>{{ roleTitle(item.role) }}</strong><span>{{ itemSummary(item) }}</span></li>
                    <li v-if="!debugItems.length" class="flow-sidebar-empty">暂无可展示的实时调试事件。</li>
                </ol>
            </aside>
        </div>
    </section>
</template>
