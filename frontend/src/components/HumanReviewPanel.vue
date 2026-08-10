<script setup>
import { computed, reactive, ref } from 'vue';
import { formatApiError, reviewApi } from '../api/review-api';
import { gateLabel } from '../services/review-live-presenter';
import {
    compareGateDecision,
    latestGateDecision,
    presentDebateJudgement
} from '../services/review-conclusion-presenter';

const props = defineProps({
    reviewId: { type: String, required: true },
    items: { type: Array, default: () => [] },
    gateVersions: { type: Array, default: () => [] },
    gateDraft: { type: Object, default: null },
    debates: { type: Array, default: () => [] },
    claims: { type: Array, default: () => [] },
    reviewVersion: { type: Number, default: null }
});
const emit = defineEmits(['changed', 'error']);
const busy = ref(false);
const editingId = ref(null);
const message = ref('');
const draft = reactive(emptyDraft());
// [AIREVIEW-PLAN-023#6.3] A human must explicitly choose the final Gate result.
const decision = reactive({ result: '', reason: '', conditionsText: '', overrideReason: '' });
// [AIREVIEW-PLAN-023#6.3] Keep Judge facts, the deterministic AI draft and the human result together.
const judgeSummaries = computed(() => props.debates
    .map((debate) => presentDebateJudgement(debate, props.claims))
    .filter(Boolean));
const latestHumanGate = computed(() => latestGateDecision(props.gateVersions));
const gateComparison = computed(() => compareGateDecision(props.gateDraft, latestHumanGate.value));

function emptyDraft() {
    return { type: 'RISK', severity: 'P2', title: '', content: '', action: '', claimIdsText: '', evidenceIdsText: '' };
}

function idList(value) {
    return value.split(/[\s,]+/).map((item) => item.trim()).filter(Boolean);
}

function payload(source) {
    return {
        type: source.type,
        severity: source.severity,
        title: source.title.trim(),
        content: source.content.trim(),
        action: source.action.trim(),
        claimIds: idList(source.claimIdsText ?? ''),
        evidenceIds: idList(source.evidenceIdsText ?? '')
    };
}

function assignDraft(source) {
    Object.assign(draft, source);
}

function reportError(error) {
    message.value = formatApiError(error);
    emit('error', error);
}

async function saveDraft() {
    busy.value = true;
    message.value = '';
    try {
        if (editingId.value) {
            const current = props.items.find((item) => item.itemId === editingId.value);
            await reviewApi.updateHumanItem(props.reviewId, editingId.value, current.version, payload(draft));
        } else {
            await reviewApi.createHumanItem(props.reviewId, payload(draft));
        }
        assignDraft(emptyDraft());
        editingId.value = null;
        message.value = '草稿已保存。';
        emit('changed');
    } catch (error) {
        reportError(error);
    } finally {
        busy.value = false;
    }
}

function edit(item) {
    editingId.value = item.itemId;
    assignDraft({
        type: item.type,
        severity: item.severity,
        title: item.title,
        content: item.content,
        action: item.action,
        claimIdsText: (item.claimIds ?? []).join(', '),
        evidenceIdsText: (item.evidenceIds ?? []).map((id) => typeof id === 'string' ? id : id.value).join(', ')
    });
}

function cancelEdit() {
    editingId.value = null;
    assignDraft(emptyDraft());
}

async function remove(item) {
    busy.value = true;
    message.value = '';
    try {
        await reviewApi.deleteHumanItem(props.reviewId, item.itemId, item.version);
        message.value = '草稿已删除。';
        if (editingId.value === item.itemId) cancelEdit();
        emit('changed');
    } catch (error) {
        reportError(error);
    } finally {
        busy.value = false;
    }
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
        <p class="muted">提交后版本只读；草稿修改和删除均使用服务端乐观锁版本。</p>
        <p v-if="message" class="inline-message" role="status">{{ message }}</p>

        <section class="decision-basis" aria-labelledby="decision-basis-title">
            <div class="decision-basis-heading">
                <div><p class="eyebrow">决策依据</p><h3 id="decision-basis-title">Judge → AI Gate 草案 → 人工最终 Gate</h3></div>
                <span v-if="gateComparison.differs" class="gate-difference-badge">人工结论与 AI 草案不一致</span>
            </div>

            <article class="decision-stage">
                <header><span class="decision-step">1</span><div><small>Judge 议题裁决</small><strong>{{ judgeSummaries.length ? `${judgeSummaries.length} 个议题已裁决` : '尚未形成裁决' }}</strong></div></header>
                <ul v-if="judgeSummaries.length" class="judge-summary-list">
                    <li v-for="judge in judgeSummaries" :key="judge.topicId">
                        <div><strong>{{ judge.resultLabel }}</strong><code>{{ judge.subjectKey }}</code></div>
                        <p>{{ judge.reason }}</p>
                        <small>采信 {{ judge.accepted.length }} 项 · 拒绝 {{ judge.rejected.length }} 项</small>
                    </li>
                </ul>
                <p v-else class="muted">等待 Judge 对终态议题给出公开裁决。</p>
            </article>

            <article class="decision-stage">
                <header><span class="decision-step">2</span><div><small>确定性 AI Gate 草案</small><strong>{{ gateComparison.draftLabel }}</strong></div></header>
                <p v-if="gateDraft">{{ gateComparison.draftReason }}</p>
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

        <form class="review-form compact" @submit.prevent="saveDraft">
            <label>类型<select v-model="draft.type"><option value="RISK">风险</option><option value="REQUIREMENT">需求</option><option value="EVIDENCE">证据</option><option value="GATE">Gate</option></select></label>
            <label>严重度<select v-model="draft.severity"><option value="P0">P0</option><option value="P1">P1</option><option value="P2">P2</option><option value="P3">P3</option></select></label>
            <label class="full">标题<input v-model="draft.title" maxlength="256" required /></label>
            <label class="full">公开描述<textarea v-model="draft.content" maxlength="8000" required></textarea></label>
            <label class="full">建议动作<input v-model="draft.action" maxlength="256" required /></label>
            <label>Claim ID（逗号分隔）<input v-model="draft.claimIdsText" /></label>
            <label>证据 ID（逗号分隔）<input v-model="draft.evidenceIdsText" /></label>
            <div class="form-actions full">
                <button class="button" :disabled="busy" type="submit">{{ editingId ? '保存修改' : '新增草稿' }}</button>
                <button v-if="editingId" class="button secondary" type="button" :disabled="busy" @click="cancelEdit">取消编辑</button>
            </div>
        </form>

        <ul v-if="items.length" class="human-items">
            <li v-for="item in items" :key="item.itemId">
                <div><span :class="['severity', item.severity]">{{ item.severity }}</span><strong>{{ item.title }}</strong><span class="muted">v{{ item.version }} · {{ item.status }}</span></div>
                <p>{{ item.content }}</p><p class="muted">动作：{{ item.action }}</p>
                <div class="item-actions"><button class="text-button" type="button" :disabled="busy" @click="edit(item)">编辑</button><button class="text-button danger" type="button" :disabled="busy" @click="remove(item)">删除</button></div>
            </li>
        </ul>
        <p v-else class="empty-note">暂无人工审核草稿。</p>

        <div class="gate-form">
            <h3>最终 Gate</h3>
            <p class="muted">当前评审版本：{{ reviewVersion ?? '加载中' }}。最终决定会触发报告与通知状态更新。</p>
            <form class="review-form compact" @submit.prevent="finalizeDecision">
                <label>结论<select v-model="decision.result" required><option value="" disabled>请选择</option><option value="PASS">通过</option><option value="CONDITIONAL">有条件通过</option><option value="BLOCK">驳回</option><option value="RETURN">退回修改</option><option value="OVERRIDE">人工覆盖</option></select></label>
                <label class="full">理由<textarea v-model="decision.reason" required></textarea></label>
                <label v-if="decision.result === 'CONDITIONAL'" class="full">条件（逗号或换行分隔）<textarea v-model="decision.conditionsText" required></textarea></label>
                <label v-if="decision.result === 'OVERRIDE'" class="full">Override 理由<textarea v-model="decision.overrideReason" required></textarea></label>
                <div class="form-actions full"><button class="button" type="submit" :disabled="busy || reviewVersion === null || !decision.result">提交最终 Gate</button></div>
            </form>
            <ol v-if="gateVersions.length" class="plan-history"><li v-for="gate in gateVersions" :key="gate.gateVersion"><strong>v{{ gate.gateVersion }} · {{ gateLabel(gate.result) }}</strong><span>{{ gate.decidedAt }}</span><p>{{ gate.reason }}</p></li></ol>
        </div>
    </section>
</template>

<style scoped>
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
.judge-summary-list p { margin: 6px 0; line-height: 1.55; }
.judge-summary-list small { color: #57534e; }
.human-result.different { border-color: #fca5a5; background: #fff7f7; box-shadow: inset 3px 0 #dc2626; }
.override-reason { color: #7c2d12; }
.difference-copy { padding: 9px 10px; color: #991b1b; background: #fee2e2; border-radius: 7px; font-weight: 700; }
@media (max-width: 720px) {
    .decision-basis-heading, .judge-summary-list li > div { align-items: flex-start; flex-direction: column; }
}
</style>
