<script setup>
import { computed, reactive, ref } from 'vue';
import { formatApiError, reviewApi } from '../api/review-api';
import { gateLabel } from '../services/review-live-presenter';
import { formatChinaTime } from '../services/china-time';
import {
    compareGateDecision,
    conclusionLabel,
    GATE_CONCLUSION_HINTS,
    humanizeGateReason,
    latestGateDecision,
    presentDebateJudgement
} from '../services/review-conclusion-presenter';

const props = defineProps({
    reviewId: { type: String, required: true },
    gateVersions: { type: Array, default: () => [] },
    gateDraft: { type: Object, default: null },
    debates: { type: Array, default: () => [] },
    claims: { type: Array, default: () => [] },
    reviewVersion: { type: Number, default: null }
});
const emit = defineEmits(['changed', 'error']);
const busy = ref(false);
const message = ref('');
// [AIREVIEW-PLAN-023#6.3] A human must explicitly choose the final Gate result.
const decision = reactive({ result: '', reason: '', conditionsText: '', overrideReason: '' });
// [AIREVIEW-PLAN-023#6.3] Keep Judge facts, the deterministic AI draft and the human result together.
const judgeSummaries = computed(() => props.debates
    .map((debate) => presentDebateJudgement(debate, props.claims))
    .filter(Boolean));
const latestHumanGate = computed(() => latestGateDecision(props.gateVersions));
const gateComparison = computed(() => compareGateDecision(props.gateDraft, latestHumanGate.value));
// [AIREVIEW-PLAN-023#6.3] 决策引导：把 Judge 裁决按结论分组合并成一句话（只列非零项）。
const judgeTallyText = computed(() => {
    const counts = new Map();
    for (const judge of judgeSummaries.value) {
        const result = judge.result || 'OTHER';
        counts.set(result, (counts.get(result) ?? 0) + 1);
    }
    return [...counts.entries()]
        .filter(([, count]) => count > 0)
        .map(([result, count]) => count + ' ' + conclusionLabel(result))
        .join('、');
});
// [AIREVIEW-PLAN-023#6.3] AI 草案理由的中文人话版；解析失败时回退为机器原文。
const draftHumanizedReason = computed(() => humanizeGateReason(props.gateDraft?.reasonSummary));
// 仅当草案结论需要人工决策时，在引导里补充触发原因。
const draftTriggerReason = computed(() => {
    if (!props.gateDraft || gateComparison.value.draftLabel !== '需要人工决策') {
        return '';
    }
    return draftHumanizedReason.value || gateComparison.value.draftReason;
});

function idList(value) {
    return value.split(/[\s,]+/).map((item) => item.trim()).filter(Boolean);
}

function reportError(error) {
    message.value = formatApiError(error);
    emit('error', error);
}

async function finalizeDecision() {
    if (!decision.result) {
        message.value = '请选择最终 Gate 结论。';
        return;
    }
    if (props.reviewVersion === null) {
        message.value = '评审版本尚未加载，暂不能安全提交最终 Gate。';
        return;
    }
    busy.value = true;
    message.value = '';
    try {
        await reviewApi.createHumanGateDecision(props.reviewId, {
            expectedVersion: props.reviewVersion,
            result: decision.result,
            reason: decision.reason.trim(),
            conditions: idList(decision.conditionsText),
            overrideReason: decision.overrideReason.trim() || null
        });
        message.value = '最终 Gate 已提交；后续调整会创建新版本。';
        emit('changed');
    } catch (error) {
        reportError(error);
    } finally {
        busy.value = false;
    }
}
</script>

<template>
    <section class="panel human-panel" aria-labelledby="human-review-title">
        <div class="panel-heading"><div><p class="eyebrow">版本化人工介入</p><h2 id="human-review-title">人工审核</h2></div></div>
        <p class="muted">提交后版本只读；再次提交会创建新的 Gate 版本。</p>
        <p v-if="message" class="inline-message" role="status">{{ message }}</p>

        <section class="decision-guidance" aria-labelledby="decision-guidance-title">
            <div class="decision-guidance-heading"><div><p class="eyebrow">需要你做什么</p><h3 id="decision-guidance-title">本次决策：确认 AI 草案，或给出最终放行结论</h3></div></div>
            <p>AI 只负责产出「Gate 草案」，不会自行放行；正式结论必须由你从下方五个选项中选一个提交，这是本次评审的最终关口。</p>
            <p v-if="judgeSummaries.length"><strong>AI 已裁决 {{ judgeSummaries.length }} 个议题</strong><template v-if="judgeTallyText">：{{ judgeTallyText }}</template>。</p>
            <p v-else class="muted"><strong>议题裁决：</strong>尚未就绪，Judge 尚未给出公开裁决。</p>
            <p v-if="gateDraft"><strong>AI 草案结论：</strong>「{{ gateComparison.draftLabel }}」<template v-if="draftTriggerReason">；触发原因：{{ draftTriggerReason }}</template>。</p>
            <p v-else class="muted"><strong>AI 草案：</strong>尚未就绪，暂无草案可参考。</p>
            <p class="guidance-action">请基于上述依据，在下方选择本次评审的最终放行结论。</p>
        </section>

        <section class="decision-basis" aria-labelledby="decision-basis-title">
            <div class="decision-basis-heading">
                <div><p class="eyebrow">决策依据</p><h3 id="decision-basis-title">Judge → AI Gate 草案 → 人工最终 Gate</h3></div>
                <span v-if="gateComparison.differs" class="gate-difference-badge">人工结论与 AI 草案不一致</span>
            </div>

            <article class="decision-stage">
                <header><span class="decision-step">1</span><div><small>Judge 议题裁决</small><strong>{{ judgeSummaries.length ? `${judgeSummaries.length} 个议题已裁决` : '尚未形成裁决' }}</strong></div></header>
                <ul v-if="judgeSummaries.length" class="judge-summary-list">
                    <li v-for="judge in judgeSummaries" :key="judge.topicId">
                        <!-- [AIREVIEW-PLAN-087#1] -->
                        <div><strong>{{ judge.resultLabel }}</strong><span class="judge-topic-title">{{ judge.title ?? judge.subjectKey }}</span><code v-if="judge.title" class="judge-topic-tech">{{ judge.subjectKey }}</code></div>
                        <p>{{ judge.reason }}</p>
                        <small>采信 {{ judge.accepted.length }} 项 · 拒绝 {{ judge.rejected.length }} 项</small>
                    </li>
                </ul>
                <p v-else class="muted">等待 Judge 对终态议题给出公开裁决。</p>
            </article>

            <article class="decision-stage">
                <header><span class="decision-step">2</span><div><small>确定性 AI Gate 草案</small><strong>{{ gateComparison.draftLabel }}</strong></div></header>
                <div v-if="gateDraft" class="draft-reason">
                    <p>{{ draftHumanizedReason || gateComparison.draftReason }}</p>
                    <details v-if="draftHumanizedReason && draftHumanizedReason !== gateComparison.draftReason" class="draft-reason-original">
                        <summary>查看机器原文</summary>
                        <code>{{ gateComparison.draftReason }}</code>
                    </details>
                </div>
                <p v-else class="muted">尚未形成 AI Gate 草案，或正在从公开事件恢复草案事实。</p>
            </article>

            <article class="decision-stage human-result" :class="{ different: gateComparison.differs }">
                <header><span class="decision-step">3</span><div><small>人工最终 Gate</small><strong>{{ gateComparison.humanLabel }}</strong></div></header>
                <template v-if="latestHumanGate">
                    <p>{{ gateComparison.humanReason }}</p>
                    <p v-if="gateComparison.overrideReason" class="override-reason"><strong>人工覆盖说明：</strong>{{ gateComparison.overrideReason }}</p>
                    <p v-if="gateComparison.differs" class="difference-copy">AI 建议“{{ gateComparison.draftLabel }}”，人工最终决定“{{ gateComparison.humanLabel }}”。人工结果为正式结论。</p>
                </template>
                <p v-else class="muted">尚未提交；请基于上方 Judge 汇总和 AI 草案独立作出决定。</p>
            </article>
        </section>

        <div class="gate-form">
            <h3>最终 Gate</h3>
            <p class="muted">当前评审版本：{{ reviewVersion ?? '加载中' }}。最终决定会触发报告与通知状态更新。</p>
            <form class="review-form compact" @submit.prevent="finalizeDecision">
                <label>结论<select v-model="decision.result" required><option value="" disabled>请选择</option><option value="PASS">通过</option><option value="CONDITIONAL">有条件通过</option><option value="BLOCK">驳回</option><option value="RETURN">退回修改</option><option value="OVERRIDE">人工覆盖</option></select>
                    <ul v-if="GATE_CONCLUSION_HINTS.length" class="gate-option-hints" aria-label="五个结论的含义">
                        <li v-for="hint in GATE_CONCLUSION_HINTS" :key="hint.result"><strong>{{ hint.label }}</strong>：{{ hint.description }}</li>
                    </ul></label>
                <label class="full">理由<textarea v-model="decision.reason" required></textarea></label>
                <label v-if="decision.result === 'CONDITIONAL'" class="full">条件（逗号或换行分隔）<textarea v-model="decision.conditionsText" required></textarea></label>
                <label v-if="decision.result === 'OVERRIDE'" class="full">Override 理由<textarea v-model="decision.overrideReason" required></textarea></label>
                <div class="form-actions full"><button class="button" type="submit" :disabled="busy || reviewVersion === null || !decision.result">提交最终 Gate</button></div>
            </form>
            <ol v-if="gateVersions.length" class="plan-history"><li v-for="gate in gateVersions" :key="gate.gateVersion"><strong>v{{ gate.gateVersion }} · {{ gateLabel(gate.result) }}</strong><span>{{ formatChinaTime(gate.decidedAt) }}</span><p>{{ gate.reason }}</p></li></ol>
        </div>
    </section>
</template>

<style scoped>
.decision-guidance { margin: 14px 0 18px; padding: 14px 16px; border: 1px solid #e7e5e4; border-left: 3px solid #a8a29e; border-radius: 10px; background: #f5f5f4; }
.decision-guidance-heading h3 { margin: 2px 0 10px; font-size: 15px; }
.decision-guidance p { margin: 6px 0 0; color: #44403c; font-size: 13px; line-height: 1.65; }
.decision-guidance .muted { color: #78716c; font-size: 13px; }
.decision-guidance .guidance-action { margin-top: 10px; padding-top: 9px; border-top: 1px dashed #d6d3d1; color: #292524; font-weight: 700; }
.draft-reason p { margin: 10px 0 0; line-height: 1.65; }
.draft-reason-original { margin-top: 8px; }
.draft-reason-original summary { cursor: pointer; color: #78716c; font-size: 12px; }
.draft-reason-original code { display: block; margin-top: 6px; padding: 8px 10px; border: 1px solid #e7e5e4; border-radius: 7px; color: #57534e; background: #fafaf9; font-size: 12px; line-height: 1.5; white-space: pre-wrap; overflow-wrap: anywhere; }
.gate-option-hints { display: grid; gap: 4px; margin: 4px 0 0; padding: 0; list-style: none; color: #78716c; font-size: 12px; line-height: 1.55; }
.gate-option-hints strong { color: #57534e; }
.decision-basis { display: grid; gap: 12px; margin: 18px 0; padding: 16px; border: 1px solid #d6d3d1; border-radius: 12px; background: #fafaf9; }
.decision-basis-heading { display: flex; align-items: flex-start; justify-content: space-between; gap: 16px; }
.decision-basis-heading h3 { margin: 2px 0 0; font-size: 16px; }
.gate-difference-badge { padding: 5px 9px; color: #991b1b; background: #fee2e2; border: 1px solid #fecaca; border-radius: 999px; font-size: 12px; font-weight: 700; }
.decision-stage { padding: 13px 14px; border: 1px solid #e7e5e4; border-radius: 10px; background: #fff; }
.decision-stage header { display: flex; align-items: center; gap: 10px; }
.decision-stage header div { display: grid; gap: 2px; }
.decision-stage header small { color: #78716c; }
.decision-stage > p { margin: 10px 0 0; line-height: 1.65; }
.decision-step { display: grid; width: 28px; height: 28px; place-items: center; color: #1d4ed8; background: #dbeafe; border-radius: 50%; font-weight: 800; }
.judge-summary-list { display: grid; gap: 8px; margin: 12px 0 0; padding: 0; list-style: none; }
.judge-summary-list li { padding: 10px 12px; border-left: 3px solid #60a5fa; background: #f8fafc; }
.judge-summary-list li > div { display: flex; align-items: baseline; justify-content: space-between; gap: 12px; }
.judge-summary-list code { color: #78716c; font-size: 11px; overflow-wrap: anywhere; }
/* [AIREVIEW-PLAN-087#1] */
.judge-topic-title { margin-left: .5rem; color: #1c1917; font-size: .8rem; font-weight: 700; }
.judge-topic-tech { margin-left: .4rem; color: #a8a29e; font-size: .62rem; }
.judge-summary-list p { margin: 6px 0; line-height: 1.55; }
.judge-summary-list small { color: #57534e; }
.human-result.different { border-color: #fca5a5; background: #fff7f7; box-shadow: inset 3px 0 #dc2626; }
.override-reason { color: #7c2d12; }
.difference-copy { padding: 9px 10px; color: #991b1b; background: #fee2e2; border-radius: 7px; font-weight: 700; }
@media (max-width: 720px) {
    .decision-basis-heading, .judge-summary-list li > div { align-items: flex-start; flex-direction: column; }
}
</style>