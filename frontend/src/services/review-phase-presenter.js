/**
 * [AIREVIEW-PLAN-037#1] 评审 Live 页初次进入的阶段落位决策。
 *
 * Context Scout 在 PLANNING 窗口内仍在流式输出（阶段机已先行进入 PLANNING），
 * 第一次进入页面应停在"上下文侦察"，而不是尚未开始的"评审规划"；
 * Scout 结束（RUN_FINISHED 事件或 summary.contextScout 落库）后再切回评审规划。
 * 纯函数实现，便于单测；FAILED 回推仍由视图按最后一条运行记录处理。
 */

export const PHASE_INDEX_BY_STAGE = {
    SNAPSHOTTING: 0,
    PLANNING: 1,
    INITIAL_REVIEW: 2,
    CONFLICT_DETECTION: 3,
    // [AIREVIEW-PLAN-047#3] 单一 DEBATE 阶段落位辩论；旧轮次值兼容存量评审。
    DEBATE: 4,
    DEBATE_ROUND_1: 4,
    DEBATE_ROUND_2: 4,
    JUDGING: 5,
    WAITING_HUMAN: 6,
    NOTIFYING: 6,
    COMPLETED: 6,
    CANCELLED: 6
};

/**
 * Scout 结束信号：运行流 RUN_FINISHED 到达，或持久化结论已进入 summary
 * （COMPLETED 结论 / 旧版 COMPLETED 事件 / DEGRADED 事件，三者都使 contextScout 非空）。
 */
export function isScoutConcluded({ contextScout = null, scoutRunFinished = false } = {}) {
    return contextScout != null || Boolean(scoutRunFinished);
}

/**
 * 返回初次进入应停留的阶段索引（与 ReviewLiveView 的 phases 数组对齐）。
 */
export function resolvePhaseLanding({ stage, runtimeItems = [], scoutConcluded = false } = {}) {
    const effectiveStage = stage ?? 'PENDING';
    if (effectiveStage === 'PENDING') {
        return runtimeItems.some((item) => item?.role === 'CONTEXT_SCOUT') ? 0 : 1;
    }
    const byStage = PHASE_INDEX_BY_STAGE[effectiveStage] ?? 1;
    if (byStage === 1 && !scoutConcluded) return 0;
    return byStage;
}
