/**
 * [AIREVIEW-PLAN-042#1] 答辩 Claim 汇入辩论对话流投影：异议答辩型议题中，答辩人以 SUPPORT Claim
 * 回应质疑，并不产生 REBUTTAL 回合 turn，导致“辩论对话流”恒为空。本投影把事实事件流里的
 * 辩论轮答辩 Claim 提交（CLAIM_SUBMITTED，stage 以 DEBATE_ROUND 开头）与议题成员（claims）关联，
 * 合成标准 REBUTTAL 回合条目，供 ReviewLiveView 的多轮辩论对话流渲染。
 */

function debounceRoundFor(stage) {
    const value = String(stage ?? '');
    if (!value.startsWith('DEBATE_ROUND')) return null;
    if (value.endsWith('1')) return 1;
    if (value.endsWith('2')) return 2;
    return null;
}

/**
 * 输入：
 *  - debates：辩论议题数组，topic 含 subjectKey 与 claims（claim 含 claimId/role/statement/severity）；
 *  - events：事实事件数组，仅消费 type === 'CLAIM_SUBMITTED' 且 stage 以 'DEBATE_ROUND' 开头且
 *    claimId 非空的辩论轮提交事件。
 * 输出：按 round 升序稳定排序的合成回合数组；每一项为
 *  { turnId: claimId, actorRole: claim.role, type: 'REBUTTAL', round, subject: topic.subjectKey,
 *    content: claim.statement, severity: claim.severity }（无 targetRole / stanceBefore / stanceAfter）。
 * 脆弱性：非数组入参返回 []；单个 claimId 只输出一次；仅输出命中 claims 成员的合成回合。
 */
export function buildDefenseTurns(debates, events) {
    if (!Array.isArray(debates) || !Array.isArray(events)) return [];
    const roundByClaimId = new Map();
    events.forEach((event) => {
        if (!event || typeof event !== 'object') return;
        if (event.type !== 'CLAIM_SUBMITTED') return;
        const claimId = event.claimId;
        if (claimId == null || claimId === '') return;
        const round = debounceRoundFor(event.stage);
        if (round == null) return;
        if (!roundByClaimId.has(claimId)) roundByClaimId.set(claimId, round);
    });

    const turns = [];
    const emittedClaimIds = new Set();
    debates.forEach((topic) => {
        if (!topic || typeof topic !== 'object') return;
        const claims = Array.isArray(topic.claims) ? topic.claims : [];
        claims.forEach((claim) => {
            if (!claim || typeof claim !== 'object') return;
            const claimId = claim.claimId;
            if (claimId == null || claimId === '') return;
            if (emittedClaimIds.has(claimId)) return;
            const round = roundByClaimId.get(claimId);
            if (round == null) return;
            emittedClaimIds.add(claimId);
            turns.push({
                turnId: claimId,
                actorRole: claim.role,
                type: 'REBUTTAL',
                round,
                subject: topic.subjectKey,
                content: claim.statement,
                severity: claim.severity
            });
        });
    });

    // 稳定排序：round 升序；同 round 保持议题/成员出现顺序（附注入序兜底稳定）。
    return turns
        .map((turn, index) => ({ turn, index }))
        .sort((left, right) => (left.turn.round - right.turn.round) || (left.index - right.index))
        .map((entry) => entry.turn);
}
