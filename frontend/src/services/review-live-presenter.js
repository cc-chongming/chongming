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

export function claimOverview(claims = []) {
    if (!claims.length) return '';
    const count = (severity) => claims.filter((claim) => claim?.severity === severity).length;
    const hints = count('P3');
    const overview = `发现 ${claims.length} 项：${count('P0')} 项阻断、${count('P1')} 项高风险、${count('P2')} 项改进建议`;
    return hints ? `${overview}、${hints} 项提示` : overview;
}

const ENUM_LABELS = {
    HUMAN_REQUIRED: '需要人工决策', PASS: '通过', CONDITIONAL: '有条件通过', BLOCK: '驳回',
    RETURN: '退回修改', OVERRIDE: '人工覆盖', DRAFT: '草案', FINAL: '最终结论',
    SUPPORT: '支持', OPPOSE: '质疑', NEUTRAL: '中立'
};

export function gateLabel(value) {
    return ENUM_LABELS[value] ?? value ?? '—';
}
