# AIREVIEW-PLAN-047 议题级辩论生命周期

> **状态**: ✅ 全部完成
> **创建日期**: 2026-08-27
> **目标**: 辩论轮次从全局阶段降为议题级生命周期：单一 DEBATE 阶段、议题异步推进、闸门按议题校验、全部议题终态后进入裁决。

## 背景

- PLAN-009/003 把辩论编码为全局阶段 `DEBATE_ROUND_1 → DEBATE_ROUND_2 → JUDGING`，隐含假设“一场辩论≈一个争议主题”，两轮是全局深度预算（Guard 保证）。
- 多议题成为常态后（本次评审 5 议题）出现四个错位：节奏强耦合（快议题等慢议题、单议题二轮需搬动整个评审）；全局 stage 与 topic.currentRound 双份事实源；全局轮次放大收敛压力（协调者倾向整轮终结，与零质询早收敛相关）；R2 可能沦为“无新 Turn 的仪式性标记”（PLAN-009 变更记录自述）。
- 仍然保留的设计：每议题最多两轮交锋的深度预算（Guard 硬保证）；质询/答辩信封的派发-消费协议；全部议题终态才进裁决的收敛守卫（DebateConvergenceGuard 已有雏形）。
- 前置：PLAN-046（质询确定性派发）先落地；其阶段检查（DEBATE_ROUND_*）在本计划中一行适配为 DEBATE。

## 分段方案

### 段 1：领域与状态机（后端核心）

**状态机**：`ReviewStage` 新增 `DEBATE`；转移改为 `CONFLICT_DETECTION → DEBATE → JUDGING`（保留 FAILED/CANCELLING 分支与既有直连裁决语义）。旧值 `DEBATE_ROUND_1/2` 保留在枚举（存量事件/评审引用），状态机保留其走向 JUDGING 的旧转移，保证暂停中的旧评审仍可收尾；新流程不再进入旧值。
**DebateService**：
- `registerTopicsLocked` 开题转移目标改为 `DEBATE`（现约 165 行）。
- 新增议题级二轮：`beginTopicSecondRound(review, metadata, topicId)`——校验议题非终态且 `currentRound == 1`，置 2 并发出携带 topicId 的 `DEBATE_ROUND_2_STARTED` 事件（事件类型复用，语义改为“该议题第二轮开始”）；Guard 保留两轮上限（第三轮请求拒绝）。
- 质询/答辩/立场调整等回合闸门的阶段判断改为 `DEBATE`（并兼容旧值），回合号取 `topic.currentRound`。
**ClaimService**：辩论期 Claim 闸门阶段判断改为 `DEBATE`（兼容旧值），其余（DEFENSE 命令校验）不变。
**ReviewDispatchService**：
- 派发受理的阶段闸门改为 `DEBATE`（兼容旧值）；
- 命令轮次推导由“全局 stage 映射”改为“按 command.topicId 读议题 currentRound”（无议题回退 1；已持有 debateStore 依赖）。
**工具契约（ReviewDebateToolFactory）**：`begin_second_round` 增加必填 `topicId` 参数（schema + 解析 + 执行改走议题级方法）；`begin_judging`/`close_debate_topic` 语义不变；阶段推进工具集相应清理。

### 段 2：编排面（提示词/唤醒语/046 适配）

- `ReviewDirectorHarnessFactory.directorPrompt()`：轮次表述改为议题级（“每个议题独立推进至多两轮；用 begin_second_round(topicId) 给单个议题开第二轮；全部议题终态后 begin_judging”），与 046 的服务端质询口径合并成一致文案。
- `ReviewWorkflowDispatcher` 唤醒语：`DEBATE_ROUND_2_STARTED` 改为携带议题上下文的措辞；其余唤醒语微调。
- 046 适配：质询派发的阶段检查 `DEBATE_ROUND_*` → `DEBATE`（含 event.stage() 判断）。

### 段 3：前端阶段映射

- `review-phase-presenter.js`：`PHASE_INDEX_BY_STAGE` 增 `DEBATE: 4`（`DEBATE_ROUND_1/2` 保留映射兼容存量）；
- `ReviewLiveView.vue`：stageLabel 增 `DEBATE: '辩论中'`；`phaseList` 辩论副标题“第 X 轮”改按议题最大轮次；其余视图（议题 Tab、回合 Tab 已是议题级，045 就位）不动。
- `live-run-status`/头部状态芯片随 stage 字符串自然生效。

## 文件清单

### 修改

| 文件 | 计划段 | 状态 |
|------|--------|------|
| `ReviewTypes.java`（ReviewStage）/ `ReviewStateMachine.java` / 协议守卫相关 | #1 | ⏳ |
| `DebateService.java` / `ClaimService.java` / `ReviewDispatchService.java` | #1 | ⏳ |
| `ReviewDebateToolFactory.java`（begin_second_round 议题化） | #1 | ⏳ |
| `ReviewDirectorHarnessFactory.java` / `ReviewWorkflowDispatcher.java` | #2 | ⏳ |
| `frontend/src/services/review-phase-presenter.js` / `ReviewLiveView.vue` | #3 | ⏳ |
| 对应单测与契约测试（状态机/闸门/工具/派发/前端） | #1-3 | ⏳ |

## 实施顺序

1. **步骤 0** ✅ → PLAN-046 落地（a32162d）。
2. **步骤 1** ✅ → 后端子代理实施段 1+2，全量 783/0/30（净增 21 用例）；不提交。
3. **步骤 2** ✅ → 独立审查通过（状态机新旧转移对照、beginTopicSecondRound 幂等、闸门 helper、派发轮次议题级推导、046 适配、提示词议题级表述）；前端段 3 内联（PHASE_INDEX_BY_STAGE/stageLabel 增 DEBATE + 落位单测）；158 绿、构建部署。
4. **步骤 3** ✅ → 提交推送；后端需 IDEA 重启。

## 风险与应对

- **风险**：存量评审停在 `DEBATE_ROUND_*` → 旧转移与闸门兼容旧值，旧评审可继续收尾；新评审走新路径。
- **风险**：`DEBATE_ROUND_2_STARTED` 语义变化影响回放/抽屉展示 → 事件携带 topicId，前端按议题消费；旧事件无 topicId 时回退全局解释。
- **风险**：议题异步推进后协调者唤醒次数增多 → 唤醒机制本就是事件驱动，每次唤醒一个议题动作的既有约束天然适配。
- **风险**：测试面大（状态机/闸门/工具/派发联动）→ 段 1 完成即全量回归，失败定位按闸门维度拆分。

## 变更记录

- 2026-08-27：创建计划（用户确认“这个做没问题”）；等 PLAN-046 落地后派发后端子代理。
- 2026-08-27：段 1+2 交付（783/0/30）并通过独立审查。契约偏差均合理：工具名保留 `begin_second_debate_round`（避免 RolePackRegistry 白名单联动）；“第三轮拒绝”实现为幂等重放（currentRound==2 → replayed）；保留议题级“禁空回合”守卫（PLAN-024 延续）。前端段 3 完成：`DEBATE` 落位索引 4、中文标签“辩论中”，旧值兼容。全部完成。
