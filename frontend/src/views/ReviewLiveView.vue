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
const sidebarTab = ref('facts');
let loadGeneration = 0;
const stage = computed(() => store.state.summary?.stage ?? 'PENDING');
const liveRunState = computed(() => describeLiveRunState(stage.value, runtimeTrace.state.status));
const runtimeItems = computed(() => buildRuntimeConversation(runtimeTrace.state.events));
const participatingRoles = computed(() => new Set(runtimeItems.value.map((item) => item.role)).size);

const phases = [
    { id: 'scout', icon: '⌕', name: 'Context Scout', subtitle: '项目信息收集' },
    { id: 'director', icon: '◆', name: 'Director 规划', subtitle: '创建评审计划' },
    { id: 'review', icon: '◎', name: '独立审查', subtitle: '多角色并行评审' },
    { id: 'conflict', icon: '⚡', name: '冲突检测', subtitle: '识别分歧与风险' },
    { id: 'debate', icon: '⚖', name: '多轮辩论', subtitle: '围绕争议收敛' },
    { id: 'judge', icon: '✓', name: 'Judge 裁决', subtitle: '形成评审判断' },
    { id: 'human', icon: '●', name: '人工决策', subtitle: '确认最终 Gate' }
];
const coreRoles = ['PRODUCT', 'PROJECT', 'FRONTEND', 'BACKEND'];
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
const phaseItems = computed(() => {
    const phase = activePhase.value;
    if (phase === 'scout') return runtimeItems.value.filter((item) => item.role === 'CONTEXT_SCOUT');
    if (phase === 'director') return runtimeItems.value.filter((item) => item.role === 'DIRECTOR');
    if (phase === 'review') return runtimeItems.value.filter((item) => coreRoles.includes(item.role));
    if (phase === 'judge') return runtimeItems.value.filter((item) => item.role === 'JUDGE' || item.role === 'DIRECTOR');
    return runtimeItems.value.filter((item) => item.role !== 'CONTEXT_SCOUT');
});
const roleCards = computed(() => coreRoles.map((role) => {
    const items = runtimeItems.value.filter((item) => item.role === role);
    const activation = store.state.summary?.activatedRoles?.find((entry) => entry.role === role);
    const latest = items.at(-1);
    const running = latest?.status === 'streaming' || latest?.status === 'RUNNING';
    return {
        role,
        label: roleTitle(role),
        initials: roleInitial(role),
        state: running ? '运行中' : activation?.initialReviewCompleted ? '初审完成' : latest ? '已产生事件' : '等待分配',
        summary: latest ? itemSummary(latest) : activation ? '角色已激活，等待公开运行事件。' : '等待 Director 分配评审任务。',
        tone: running ? 'running' : activation?.initialReviewCompleted ? 'done' : latest ? 'observed' : 'pending'
    };
}));
const directorSummary = computed(() => {
    const item = runtimeItems.value.filter((entry) => entry.role === 'DIRECTOR').at(-1);
    if (item) return itemSummary(item);
    return stage.value === 'PENDING' ? '等待受理完成并启动 Context Scout。' : '等待 Director 创建计划并分派角色。';
});
const factTimeline = computed(() => store.events.value.slice(-14).reverse());
const debugItems = computed(() => runtimeItems.value.slice(-24).reverse());

function phaseState(index) {
    if (index === activePhaseIndex.value && stage.value === 'FAILED') return 'failed';
    if (index < activePhaseIndex.value) return 'done';
    if (index === activePhaseIndex.value) return 'running';
    return 'pending';
}

function selectPhase(phase) {
    selectedPhase.value = phase.id;
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
        INITIAL_REVIEW_COMPLETED: '初审已完成', DEBATE_TOPIC_OPENED: '创建辩论议题',
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
    sidebarTab.value = 'facts';
    await store.load(reviewId);
    if (generation !== loadGeneration || reviewId !== props.reviewId) return;
    runtimeTrace.start(reviewId, store.state.summary?.attempt ?? 1);
}

watch(() => props.reviewId, load, { immediate: true });
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
                <template v-for="(phase, index) in phases" :key="phase.id">
                    <button type="button" :class="['flow-phase-button', phaseState(index), { active: activePhase === phase.id }]" @click="selectPhase(phase)">
                        <span class="flow-phase-icon">{{ phase.icon }}</span>
                        <span><strong>{{ phase.name }}</strong><small>{{ phase.subtitle }}</small></span>
                    </button>
                    <span v-if="index < phases.length - 1" :class="['flow-phase-connector', phaseState(index) === 'done' ? 'done' : '']"></span>
                </template>
            </nav>

            <main class="flow-content" aria-live="polite">
                <header class="flow-phase-header">
                    <div><p>{{ activePhaseDefinition.subtitle }}</p><h1>{{ activePhaseDefinition.icon }} {{ activePhaseDefinition.name }}</h1></div>
                    <span :class="['flow-phase-badge', phaseState(phases.findIndex((phase) => phase.id === activePhase))]">{{ phaseState(phases.findIndex((phase) => phase.id === activePhase)) === 'done' ? '已完成' : phaseState(phases.findIndex((phase) => phase.id === activePhase)) === 'failed' ? '已失败' : '进行中' }}</span>
                </header>

                <section class="flow-director-card">
                    <div class="flow-agent-avatar director">D</div>
                    <div><strong>Director 协调者</strong><span>评审流程编排 Agent</span></div>
                    <p>{{ directorSummary }}</p>
                </section>

                <section class="flow-stream-panel" aria-label="当前阶段公开运行流">
                    <header><div class="flow-agent-avatar" :data-role="activePhase === 'scout' ? 'CONTEXT_SCOUT' : activePhase === 'judge' ? 'JUDGE' : 'DIRECTOR'">{{ activePhase === 'scout' ? 'S' : activePhase === 'judge' ? 'J' : 'D' }}</div><div><strong>{{ activePhaseDefinition.name }}</strong><span>公开消息与受限工具调用</span></div><small>{{ phaseItems.length }} 条运行记录</small></header>
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
                    <div v-else class="flow-empty"><strong>{{ liveRunState.emptyState?.title ?? '等待当前阶段的公开运行事件' }}</strong><p>{{ liveRunState.emptyState?.message ?? '运行事件会按照实际到达顺序显示；工具参数与完整结果仅在展开后可见。' }}</p></div>
                </section>

                <section class="flow-agent-section" aria-labelledby="flow-agents-title">
                    <div class="flow-section-heading"><div><p>多角色独立审查</p><h2 id="flow-agents-title">评审席位</h2></div><span>{{ participatingRoles }} 个 Agent 已出现</span></div>
                    <div class="flow-agent-grid">
                        <article v-for="card in roleCards" :key="card.role" :class="['flow-agent-card', card.tone]">
                            <header><span class="flow-agent-avatar" :data-role="card.role">{{ card.initials }}</span><div><strong>{{ card.label }}</strong><small>{{ card.state }}</small></div><span class="flow-card-state">{{ card.tone === 'running' ? '执行中' : card.tone === 'done' ? '完成' : card.tone === 'observed' ? '已连接' : '等待' }}</span></header>
                            <p>{{ card.summary }}</p>
                        </article>
                    </div>
                </section>
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
