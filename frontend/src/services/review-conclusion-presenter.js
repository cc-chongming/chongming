// [AIREVIEW-PLAN-023#6.3] Projects Judge, AI Gate and human Gate facts into readable UI models.
const GATE_LABELS = {
    AI_PASS: 'AI 通过',
    PASS: '通过',
    CONDITIONAL: '有条件通过',
    RETURN: '退回修改',
    BLOCK: '驳回',
    HUMAN_REQUIRED: '需要人工决策',
    OVERRIDE: '人工覆盖'
};

export function conclusionLabel(result) {
    return GATE_LABELS[result] ?? result ?? '尚未形成';
}

function claimsForIds(ids = [], claims = []) {
    const byId = new Map(claims.map((claim) => [String(claim.claimId), claim]));
    return ids.map((id) => byId.get(String(id)) ?? { claimId: id, missing: true });
}

export function presentDebateJudgement(debate, claims = []) {
    const judgement = debate?.judgement ?? null;
    if (!judgement) return null;
    return {
        topicId: debate.topicId ?? debate.subjectKey,
        subjectKey: debate.subjectKey ?? '未命名议题',
        result: judgement.result,
        resultLabel: conclusionLabel(judgement.result),
        reason: judgement.reasonSummary?.trim() || '未提供公开裁决理由。',
        accepted: claimsForIds(judgement.acceptedClaimIds, claims),
        rejected: claimsForIds(judgement.rejectedClaimIds, claims),
        createdAt: judgement.createdAt ?? null
    };
}

export function latestGateDecision(decisions = []) {
    return decisions.reduce((latest, decision) => (
        !latest || Number(decision.gateVersion) > Number(latest.gateVersion) ? decision : latest
    ), null);
}

export function resolveAiGateDraft(summaryGate, humanDecisions = [], events = []) {
    const summaryIsAiDraft = summaryGate
        && String(summaryGate.actor ?? '').toUpperCase() !== 'HUMAN'
        && String(summaryGate.status ?? '').toUpperCase() !== 'FINAL';
    if (summaryIsAiDraft) {
        return summaryGate ? { ...summaryGate, source: 'summary' } : null;
    }
    const event = events
        .filter((entry) => entry?.type === 'GATE_DRAFTED')
        .reduce((latest, entry) => !latest || Number(entry.sequence) > Number(latest.sequence) ? entry : latest, null);
    if (!event) return null;
    return {
        result: event.payload?.result ?? null,
        status: event.payload?.status ?? 'DRAFT',
        reasonSummary: event.payload?.reasonSummary ?? '',
        decidedAt: event.occurredAt ?? null,
        source: 'event'
    };
}

export function compareGateDecision(draft, human) {
    const hasBoth = Boolean(draft && human);
    return {
        hasBoth,
        differs: hasBoth && draft.result !== human.result,
        draftLabel: conclusionLabel(draft?.result),
        humanLabel: human ? conclusionLabel(human.result) : '尚未提交',
        draftReason: draft?.reasonSummary?.trim() || '暂无公开理由。',
        humanReason: human?.reason?.trim() || '暂无人工理由。',
        overrideReason: human?.overrideReason?.trim() || null
    };
}
