// [AIREVIEW-PLAN-023#6.1] Readable labels and dynamic role projection for /live.
const REVIEW_META_ROLES = new Set(['CONTEXT_SCOUT', 'DIRECTOR', 'JUDGE', 'AGENT']);

function normalizedRole(value) {
    const role = typeof value === 'string' ? value : value?.role;
    return String(role ?? '').trim().toUpperCase();
}

export function reviewRoles(activations = [], claims = [], runtimeItems = []) {
    const seen = new Set();
    return [...activations, ...claims, ...runtimeItems]
        .map(normalizedRole)
        .filter((role) => role && !REVIEW_META_ROLES.has(role) && !seen.has(role) && seen.add(role));
}

export function completedReviewRoles(activations = [], events = [], currentAttempt = null) {
    const completedActivations = activations.filter((entry) => entry?.initialReviewCompleted);
    const completedEvents = events
        .filter((event) => {
            if (event?.type !== 'ROLE_COMPLETED' || !event.actorRole) return false;
            if (currentAttempt == null) return true;
            const eventAttempt = event.attemptNo ?? event.attempt;
            return eventAttempt != null && Number(eventAttempt) === Number(currentAttempt);
        })
        .map((event) => ({ role: event.actorRole }));
    return reviewRoles([...completedActivations, ...completedEvents]);
}

// [AIREVIEW-PLAN-039#1][2026-08-27 口径修订：用户确认“一切支持都显示支持”] 立场优先：
// SUPPORT 一律显示“支持”，其余立场按严重度映射。
const CLAIM_CATEGORY_LABELS = { P0: '阻断', P1: '高风险', P2: '改进', P3: '提示' };

export function claimCategoryLabel(claim) {
    if (claim?.position === 'SUPPORT') return '支持';
    return CLAIM_CATEGORY_LABELS[claim?.severity] ?? '提示';
}

export function claimOverview(claims = []) {
    if (!claims.length) return '';
    const count = (predicate) => claims.filter(predicate).length;
    // [AIREVIEW-PLAN-039#1][2026-08-27 口径修订] 一切支持计入“支持”，严重度分桶只统计非支持类。
    const supports = count((claim) => claim?.position === 'SUPPORT');
    const blockers = count((claim) => claim?.severity === 'P0' && claim?.position !== 'SUPPORT');
    const highRisk = count((claim) => claim?.severity === 'P1' && claim?.position !== 'SUPPORT');
    const improvements = count((claim) => claim?.severity === 'P2' && claim?.position !== 'SUPPORT');
    const hints = count((claim) => claim?.severity === 'P3' && claim?.position !== 'SUPPORT');
    const overview = `发现 ${claims.length} 项：${blockers} 项阻断、${highRisk} 项高风险、${improvements} 项改进建议、${supports} 项支持`;
    return hints > 0 ? `${overview}、${hints} 项提示` : overview;
}

const ENUM_LABELS = {
    HUMAN_REQUIRED: '需要人工决策', PASS: '通过', CONDITIONAL: '有条件通过', BLOCK: '驳回',
    RETURN: '退回修改', OVERRIDE: '人工覆盖', DRAFT: '草案', FINAL: '最终结论',
    SUPPORT: '支持', OPPOSE: '质疑', NEUTRAL: '中立'
};

export function gateLabel(value) {
    return ENUM_LABELS[value] ?? value ?? '—';
}
