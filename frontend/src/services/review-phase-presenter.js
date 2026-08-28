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
export function resolvePhaseLanding({ stage, runtimeItems = [], scoutConcluded = false, reviewStarted = false } = {}) {
    const effectiveStage = stage ?? 'PENDING';
    // [AIREVIEW-PLAN-037#4] PENDING（新开/刚启动、首个事件未到达）一律落在第一阶段上下文侦察：
    // 用户预期进入即见流程起点；启动面板在任何阶段都显示，不受落位影响。
    if (effectiveStage === 'PENDING') {
        return 0;
    }
    const byStage = PHASE_INDEX_BY_STAGE[effectiveStage] ?? 1;
    if (byStage === 1 && !scoutConcluded) return 0;
    // [AIREVIEW-PLAN-057#1] INITIAL_REVIEW 窗口内若尚无任何审查角色被创建/激活，继续停留评审规划：
    // 避免“独立审查 0/0”空窗跳 phase；至少一个角色可投影后才自动跳独立审查。
    if (byStage === 2 && !reviewStarted) return 1;
    return byStage;
}
