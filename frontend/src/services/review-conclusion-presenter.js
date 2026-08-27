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

// [AIREVIEW-PLAN-023#6.3] 结论下拉框的五选一含义说明（纯展示文案，不影响提交值）。
export const GATE_CONCLUSION_HINTS = [
    { result: 'PASS', label: '通过', description: '评审放行，需求进入后续流程。' },
    { result: 'CONDITIONAL', label: '有条件通过', description: '仅当所列条件全部满足时放行（需在「条件」里写明）。' },
    { result: 'BLOCK', label: '驳回', description: '评审不通过，需整改后重新评审。' },
    { result: 'RETURN', label: '退回修改', description: '需求文档本身不完备，退回起草方补充。' },
    { result: 'OVERRIDE', label: '人工覆盖', description: '人工给出与 AI 草案不一致的强制结论，需说明覆盖理由。' }
];

// [AIREVIEW-PLAN-023#6.3] GatePolicy.java 会产出的触发原因（分号后的 detail）中英对照；
// 未命中的字符串原样保留，保证展示层不会损坏原文。
const GATE_TRIGGER_REASONS = new Map([
    ['P0/P1 claim lacks verified evidence', 'P0/P1 级主张缺少已验证证据'],
    ['P0/P1 GAP lacks tracked disposition or evidence', 'P0/P1 级缺口缺少可追踪的处置或证据'],
    ['high-risk UNKNOWN on required checkpoint', '必填检查点存在高风险未知项'],
    ['Judge requires human review', 'Judge 裁决要求人工复核'],
    ['P0 blocking risk remains', '仍存在 P0 级阻断风险'],
    ['Judge requested requirement revision', 'Judge 裁决要求退回修改需求'],
    ['Judge requires a tracked condition', 'Judge 裁决要求附加可追踪的条件'],
    ['P1 risk requires configured conservative handling', 'P1 级风险需按配置采取保守处理'],
    ['required checkpoints fully covered with no blocking item', '必填检查点全部覆盖且无阻断项'],
    ['No unresolved blocking Claim or Judge condition', '无未解决的主张或 Judge 条件阻碍放行']
]);

// 带参数的触发原因（如覆盖不完整时后面跟着未被覆盖的检查点列表），只翻译前缀。
const GATE_TRIGGER_PREFIXES = [
    ['required checkpoint coverage incomplete: uncovered=', '必填检查点覆盖不完整，未覆盖：']
];

const COVERAGE_FIELDS = [
    { key: 'confirmed', label: '确认' },
    { key: 'partial', label: '部分确认' },
    { key: 'gap', label: '缺口' },
    { key: 'unknown', label: '未知' },
    { key: 'notApplicable', label: '不适用' }
];

/**
 * 把后端 GatePolicy 生成的机器理由转成中文人话。
 * 支持两种输入：
 *  1) "required=29, confirmed=9, partial=18, gap=3, unknown=3, notApplicable=0; P0/P1 claim lacks verified evidence"
 *     -> "29 项必填检查点：9 项确认、18 项部分确认、3 项缺口、3 项未知；P0/P1 级主张缺少已验证证据"
 *  2) 纯英文触发原因（无覆盖统计前缀），命中映射表则翻译，未命中原样返回。
 * 任意解析失败都原样返回，保证健壮。
 */
export function humanizeGateReason(reasonSummary) {
    if (typeof reasonSummary !== 'string' || !reasonSummary.trim()) {
        return '';
    }
    const trimmed = reasonSummary.trim();
    // 覆盖统计与触发原因以分号分隔；也可能只有其中一个。
    const segments = trimmed.split(';').map((segment) => segment.trim()).filter(Boolean);
    if (!segments.length) {
        return '';
    }

    let coverageText = '';
    let triggerIndex = 0;
    const coverageHead = segments[0].match(/^required=[0-9]+,/);
    if (coverageHead) {
        triggerIndex = 1;
        const numbers = {};
        let match;
        const numberPattern = /(required|confirmed|partial|gap|unknown|notApplicable)=([0-9]+)/g;
        while ((match = numberPattern.exec(segments[0])) !== null) {
            numbers[match[1]] = Number(match[2]);
        }
        const required = Number.isFinite(numbers.required) ? numbers.required : null;
        const items = COVERAGE_FIELDS
            .map((field) => ({ field, value: numbers[field.key] }))
            .filter(({ value }) => Number.isFinite(value) && value > 0)
            .map(({ field, value }) => value + ' 项' + field.label);
        if (required !== null && required > 0) {
            coverageText = items.length
                ? required + ' 项必填检查点：' + items.join('、')
                : required + ' 项必填检查点均已覆盖';
        } else if (items.length) {
            coverageText = items.join('、');
        } else if (required === 0) {
            coverageText = '无必填检查点';
        }
    }

    const triggers = segments.slice(triggerIndex).map((segment) => {
        if (GATE_TRIGGER_REASONS.has(segment)) {
            return GATE_TRIGGER_REASONS.get(segment);
        }
        for (const [prefix, translation] of GATE_TRIGGER_PREFIXES) {
            if (segment.startsWith(prefix)) {
                return translation + segment.slice(prefix.length);
            }
        }
        return segment;
    });

    const readable = [coverageText, ...triggers].filter(Boolean);
    return readable.length ? readable.join('；') : trimmed;
}