# AIREVIEW-PLAN-037 初次进入停留上下文侦察阶段

> **状态**: ✅ 全部完成
> **创建日期**: 2026-08-27
> **目标**: 评审 Live 页第一次进入时停在正在运行的“上下文侦察”，而不是尚未开始的“评审规划”。

## 背景

- Context Scout 在 SNAPSHOTTING → PLANNING 窗口内持续产生公开运行事件；阶段机进入 `PLANNING` 后，协调者才刚开始规划，Scout 流通常仍在进行。
- 现状：`ReviewLiveView.vue` 的 `activePhaseIndex` 直接按 `phaseIndexByStage` 落位，`PLANNING` → 索引 1（评审规划）。侧边栏自身已经把 Scout 显示为“进行中”、把评审规划显示为“未开始”，但页面却停在“未开始”的评审规划上，体验割裂。
- 用户反馈（评审 #feea9306…/live）：“第一次进去页面是停在评审规划的，应该停在第一个上下文侦测。”
- 已有基础：`phaseState` 已用 `scoutComplete` 区分 PLANNING 窗口内 Scout 是否结束；后端 `ReviewQueryService.ContextScoutView` 仅在 Scout 结束（结论落库 COMPLETED / COMPLETED 事件 / DEGRADED）后填充 `summary.contextScout`。

## 分段方案

### 段 1：纯函数化初次落位决策并在 SFC 使用

**目标**：把“初次进入停在哪个阶段”抽成可测试的纯函数，并让 PLANNING 窗口内 Scout 未结束时落位上下文侦察（索引 0），Scout 结束后自动切回评审规划（索引 1）。

**涉及文件**：
- 新建：`frontend/src/services/review-phase-presenter.js`
- 新建：`frontend/src/services/review-phase-presenter.test.js`
- 修改：`frontend/src/views/ReviewLiveView.vue`

**关键实现细节**：
- `isScoutConcluded({ contextScout, scoutRunFinished })`：`summary.contextScout != null`（结论落库/COMPLETED/DEGRADED）或 RUN_FINISHED 运行事件到达，二者任一即视为 Scout 结束。
- `resolvePhaseLanding({ stage, runtimeItems, scoutConcluded })`：
  - `PENDING`：有 Scout 运行记录 → 0，否则 1（维持现状）；
  - 其余阶段按 `PHASE_INDEX_BY_STAGE` 落位，仅当结果为 1（PLANNING）且 Scout 未结束时回落到 0；
  - `FAILED` 仍由 SFC 按最后一条运行记录的角色回推（维持现状）。
- SFC 的 `scoutComplete` 改为 `scoutConcluded || activePhaseIndex >= 2`，语义覆盖原“DEGRADED/RUN_FINISHED/阶段已过”三分支且不引入计算环（`activePhaseIndex` 不再依赖 `scoutComplete`）。
- 手动点选阶段（`selectedPhase`）优先级不变；Scout 结束后被动观看会自动跟进到评审规划。

### 段 2：运行流对话窗固定视口高度、内部滚动

**目标**：上下文侦察（含 Director/Judge）对话流不再把页面撑出很长；面板固定为视口剩余高度，内部滚动。

**涉及文件**：
- 修改：`frontend/src/styles/review.css`

**关键实现细节**：
- `.review-flow-layout` 由 `min-height: calc(100vh - 3.8rem)` 改为 `height: calc(100vh - 3.8rem)`，页面整体不再被内容撑高；≤760px 的 block 布局回退 `height: auto`。
- `.flow-content > * { flex: 0 0 auto }`：其余区块保持自然高度；仅 `.flow-content > .flow-stream-panel` 为 `flex: 1 1 auto; min-height: 0`，逐级给 `.live-agent-conversation` / `.live-agent-scroll` 补 `min-height: 0`，使 flex 收缩链真正生效（原链条缺 `min-height: 0`，面板随内容无限长高）。
- `.live-agent-scroll` 自身已有 `overflow-y: auto` 与 follow-latest 逻辑，成为唯一滚动容器；非流式阶段（审查/辩论/人工决策）内容超长时在 `.flow-content` 内部滚动，左侧流程与头部保持固定可见。

## 文件清单

### 新建

| 文件 | 计划段 | 状态 |
|------|--------|------|
| `frontend/src/services/review-phase-presenter.js` | #1 | ✅ |
| `frontend/src/services/review-phase-presenter.test.js` | #1 | ✅ |

### 修改

| 文件 | 计划段 | 状态 |
|------|--------|------|
| `frontend/src/views/ReviewLiveView.vue` | #1 | ✅ |
| `frontend/src/styles/review.css` | #2 | ✅ |

## 实施顺序

1. **步骤 1** ✅ → 新建 presenter 与单测；接入 `ReviewLiveView.vue`；vitest 全绿（138 = 128 基线 + 10 新增）。
2. **步骤 2** ✅ → 依赖步骤 1：构建产物复制到 `src/main/resources/static/review` 与 `target/classes/static/review`；运行中的 8080 服务已直接提供新 bundle（index-Cy6OvCHl.js，200），刷新浏览器即生效，无需重启。
3. **步骤 3** ✅ → 依赖步骤 2：提交并推送 gitlab/master。
4. **步骤 4** ✅ → 段 2：review.css 视口锁定与滚动链修复；vitest 138 复绿；构建产物同步运行服务（index-ovMPqhOc.css，200）。

## 风险与应对

- **风险**：回放/重启场景缺失 RUN_FINISHED 事件 → 由 `summary.contextScout != null` 兜底（结论落库即结束信号），双信号任一命中即切换。
- **风险**：阶段为 PLANNING 但 Scout 尚未产生任何事件的极端窗口（理论上 Scout 先于 PLANNING 启动）→ 落位 0 显示 0 条运行记录的空态，与侧边栏“进行中”一致，可接受。
- **风险**：`scoutComplete` 语义变化影响 `phaseState` 徽章 → 单测覆盖 PLANNING/COMPLETED/DEGRADED 分支；新语义下“结论已落库”即显示完成，更准确。
- **风险**：段 2 视口锁定后小屏/移动端空间不足 → ≤760px 回退 `height: auto`；桌面极矮视口下面板可收缩至 0，结论卡片等保持自然高度。
- **风险**：非流式阶段（人工决策等）内容很长 → 由 `.flow-content` 内部滚动承接，头部与左侧流程固定。

## 变更记录

- 2026-08-27：创建计划，开始段 1 实施。
- 2026-08-27：段 1 完成。`review-phase-presenter.js` 提供 `PHASE_INDEX_BY_STAGE`/`isScoutConcluded`/`resolvePhaseLanding`，SFC 删除内联 `phaseIndexByStage`；`scoutComplete` 重写为 `scoutConcluded || activePhaseIndex >= 2`，消除计算环。vitest 138 全绿；构建产物已同步到运行服务。实际与计划无偏差。
- 2026-08-27：新增并完成段 2。用户反馈对话窗超出页面很多；`.review-flow-layout` 锁定视口高度 + flex 收缩链补 `min-height: 0`，运行流面板内部滚动。根因是既有拉伸规则（#604-609）缺收缩前提。
