/**
 * [AIREVIEW-PLAN-038#2] Director 阶段“子代理启用决策”投影：把事实事件流中的角色生命周期事件
 * （ROLE_ACTIVATED / ROLE_STARTED / ROLE_COMPLETED）按 actorRole 归并为一行，输出
 * { role, state, activatedAt }，供评审规划视图渲染启用决策卡片。
 * state 优先级：completed（初审完成）> running（初审中）> activated（已启用）。
 */

const PROCESSED_TYPES = new Set(['ROLE_ACTIVATED', 'ROLE_STARTED', 'ROLE_COMPLETED']);

function resolveState(group) {
    if (group.hadCompleted) return 'completed';
    if (group.hadStarted) return 'running';
    return 'activated';
}

/**
 * 输入：事实事件数组（可含任意类型事件；对 null/undefined/缺字段事件健壮）。
 * 输出：按角色归并后的启用行数组；有 ROLE_ACTIVATED 事件的角色按激活事件出现顺序在前，
 * 无激活事件的角色（只有 started/completed）按首次出现顺序排在后面。
 */
export function buildActivationRows(events) {
    if (!Array.isArray(events)) return [];
    const groups = new Map();
    events.forEach((event, index) => {
        if (!event || typeof event !== 'object') return;
        if (!PROCESSED_TYPES.has(event.type)) return;
        const role = event.actorRole;
        if (role == null || role === '') return;
        let group = groups.get(role);
        if (!group) {
            group = {
                role,
                firstRelatedIndex: index,
                firstActivationIndex: null,
                hadActivated: false,
                hadStarted: false,
                hadCompleted: false,
                activatedAt: null,
                fallbackAt: null
            };
            groups.set(role, group);
        }
        const occurredAt = event.occurredAt;
        if (event.type === 'ROLE_ACTIVATED') {
            group.hadActivated = true;
            if (group.firstActivationIndex == null) group.firstActivationIndex = index;
            if (group.activatedAt == null) group.activatedAt = occurredAt ?? null;
        } else if (event.type === 'ROLE_STARTED') {
            group.hadStarted = true;
        } else {
            group.hadCompleted = true;
        }
        // 仅作为"无激活事件角色"的兜底时间：取最早相关事件的 occurredAt。
        if (group.fallbackAt == null && occurredAt != null && occurredAt !== '') {
            group.fallbackAt = occurredAt;
        }
    });

    const withActivation = [];
    const withoutActivation = [];
    groups.forEach((group) => {
        if (group.activatedAt == null && !group.hadActivated) {
            group.activatedAt = group.fallbackAt;
        }
        const row = { role: group.role, state: resolveState(group), activatedAt: group.activatedAt };
        (group.hadActivated ? withActivation : withoutActivation).push({ row, group });
    });
    const byIndex = (key) => (left, right) => left.group[key] - right.group[key];
    withActivation.sort(byIndex('firstActivationIndex'));
    withoutActivation.sort(byIndex('firstRelatedIndex'));
    return [...withActivation, ...withoutActivation].map((entry) => entry.row);
}
