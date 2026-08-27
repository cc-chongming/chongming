# AIREVIEW-PLAN-038 协调者编排过程可视化

> **状态**: ✅ 全部完成
> **创建日期**: 2026-08-27
> **目标**: 让评审 Live 页能看到协调者创建评审、决策启用哪些子代理、以及调用启用子代理的完整编排过程。

## 背景

- 用户批注（评审 #feea9306…/live，INITIAL_REVIEW 时点）：评审规划阶段显示“协调者尚未广播运行时对话 · 0 条运行记录”，协调者创建评审、决策使用哪些子代理、启用子代理的过程完全没有体现。
- 现状勘察：角色激活是编排层确定性决策——`ReviewOrchestrationService.startReview` 在 `runtimeAdapter.start` 后按 `CORE_ROLES` + `JUDGE` 逐个 `registerAndActivate`（`ActivationSource.PLAN`），并发出 `ROLE_ACTIVATED` 领域事件；没有 Director 模型工具调用参与，也没有向 Director 运行流广播任何过程通知（`publicNotice` 机制目前仅用于计划修订）。
- 已有基础：`ReviewAgUiEventMapper.publicNotice(context, agentId, discriminator, text)` 生成幂等的服务端文本事件；`ReviewRuntimeTraceRegistry.publish(runtimeId, event)` 广播并持久化；前端 `store.events` 已含 `ROLE_ACTIVATED/ROLE_STARTED/ROLE_COMPLETED` 事实（EventView：type/actorRole/stage/occurredAt 等）。
- 设计立场：如实呈现编排层代表协调者做出的决策（通知 + 结构化卡片），不伪造模型工具调用；若未来要让 Director 模型自主决策角色集，另立计划。

## 分段方案

### 段 1：后端编排过程通知（RuntimeNoticeBroadcaster）

**目标**：编排关键点向 Director AG-UI 运行流广播中文过程通知，评审规划阶段流不再为空。

**涉及文件**：
- 新建：`src/main/java/ai/cc/chongming/review/application/RuntimeNoticeBroadcaster.java`（应用层端口，`void publish(ReviewRuntimeContext context, String agentId, String discriminator, String text)`，含 `noop()`）
- 新建：`src/main/java/ai/cc/chongming/review/infrastructure/agentscope/AgUiRuntimeNoticeBroadcaster.java`（@Component，经 `ReviewAgUiEventMapper.publicNotice` + `ReviewRuntimeTraceRegistry.publish` 实现；对 registry/mapper 缺失容错）
- 修改：`src/main/java/ai/cc/chongming/review/application/ReviewOrchestrationService.java`（新增可空广播器字段与构造重载，保持既有构造与测试兼容）
- 新建/修改：对应单测

**通知文案契约**（含中文角色名映射）：
- 计划发布（startReview，PLAN_CREATED 之后）：`评审已创建：协调者发布评审计划 v{version}，按评审协议决策启用 {n} 个子代理`，discriminator `review-created-v{version}`
- 角色激活决策（registerAndActivate，ROLE_ACTIVATED 时）：`决策启用子代理「{中文角色名}」：{中文原因}，运行体已注册`，discriminator `role-activated-{ROLE}`
- 角色派发（runRoleRound 派发前）：`已调用子代理「{中文角色名}」开始执行初审任务`，discriminator `role-dispatched-{ROLE}`
- 中文原因映射：`Core first-round review required by protocol` → `协议要求的核心初审角色`；Judge 预注册原因 → `裁决者预注册，待辩论议题全部终态后裁决`；未命中原样透传。

**关键实现细节**：
- 广播器为可选依赖：缺省 `RuntimeNoticeBroadcaster.noop()`，不影响现有构造与单测；`@Autowired` 主构造追加该参数（ObjectProvider 或可空注入，避免 Bean 环——广播器实现只依赖 registry 与 mapper）。
- 通知失败只记日志不阻断编排（广播器实现内吞异常）。
- 幂等：discriminator 稳定，重启回放不产生重复消息（沿用 publicNotice 语义）。

### 段 2：前端“子代理启用决策”卡片与空态更新

**目标**：评审规划阶段可视化角色启用决策与进度；通知自然流入协调者对话流。

**涉及文件**：
- 修改：`frontend/src/views/ReviewLiveView.vue`
- 修改：`frontend/src/styles/review.css`（flow-activation-* 卡片样式，DSH 浅色系）
- 新建：`frontend/src/services/review-activation-presenter.js` + 单测（纯函数：`buildActivationRows(events)` 输入事实事件，输出每角色 { role, label, state: ACTIVATED|RUNNING|COMPLETED, activatedAt, reasonCode/stage }，按 actorRole 归并三类事件）

**关键实现细节**：
- 卡片仅在 `activePhase === 'director'` 且有激活事件时渲染，置于流式面板与计划卡之间；复用 `roleTitle/roleInitial/formatChinaTime`。
- 状态推进：`ROLE_ACTIVATED`→已启用，`ROLE_STARTED`→初审中，`ROLE_COMPLETED`→初审完成；无 started/completed 事件保持已启用。
- 空态文案更新：提及编排过程将以协调者通知形式实时出现（替换“仅在编排层广播 AG-UI 事件时出现”的旧说明）。
- 通知消息经由现有 `buildRuntimeConversation` 按 runId 归入 DIRECTOR 流（计划修订通知同机制，无需改 adapter）。

## 文件清单

### 新建

| 文件 | 计划段 | 状态 |
|------|--------|------|
| `src/main/java/ai/cc/chongming/review/application/RuntimeNoticeBroadcaster.java` | #1 | ✅ |
| `src/main/java/ai/cc/chongming/review/infrastructure/agentscope/AgUiRuntimeNoticeBroadcaster.java` | #1 | ✅ |
| `frontend/src/services/review-activation-presenter.js` | #2 | ✅ |
| `frontend/src/services/review-activation-presenter.test.js` | #2 | ✅ |

### 修改

| 文件 | 计划段 | 状态 |
|------|--------|------|
| `src/main/java/ai/cc/chongming/review/application/ReviewOrchestrationService.java` | #1 | ✅ |
| 后端对应单测（编排服务/广播器） | #1 | ✅ |
| `frontend/src/views/ReviewLiveView.vue` | #2 | ✅ |
| `frontend/src/styles/review.css` | #2 | ✅ |

## 实施顺序

1. **步骤 1（并行）** ✅ → 后端子代理实施段 1（全量 751/0/30 绿）；前端子代理实施段 2（vitest 145 绿）；均通过独立审查（广播器无 Bean 环、异常隔离、幂等通知、resolveTrace 惰性建 trace 保证 start 前通知不丢）。
2. **步骤 2** ✅ → 独立审查两路交付；后端全量 750/0/30 绿；vitest 148 全绿（含 039 增量）。
3. **步骤 3** ✅ → 前端构建（index-CCBdmygV.js / index-W11qJlkF.css）并同步 `target/classes/static/review`，运行服务已验证提供新 bundle（含“子代理启用决策”）；提交推送。后端通知链需 IDEA 重启生效。

## 风险与应对

- **风险**：广播器注入引入 Bean 环（adapter 已惰性依赖 orchestration）→ 广播器实现只依赖 `ReviewRuntimeTraceRegistry` + `ReviewAgUiEventMapper`，不触碰 adapter/orchestration；orchestration 经构造参数持有端口。
- **风险**：通知在重启回放中重复 → discriminator 稳定（role-activated-{ROLE} 等），沿用 publicNotice 的幂等 messageId。
- **风险**：ROLE_STARTED/ROLE_COMPLETED 与激活事件跨尝试混杂 → 前端 presenter 只按当前事件集归并（store.events 已按当前尝试拉取）。
- **风险**：用户期望的是 Director 模型“调用工具”激活 → 本计划如实呈现编排层决策；模型驱动的角色选择如需要另立计划。

## 变更记录

- 2026-08-27：创建计划；并行派发后端/前端实施子代理。
- 2026-08-27：段 1/段 2 交付。偏差：noticeBroadcaster 为 final 非空字段（null→noop 回落），行为与“可空”等价；@Autowired 构造采用 ObjectProvider<RuntimeNoticeBroadcaster>。独立审查额外验证：评审已创建通知虽在 runtimeAdapter.start 之前发布，但 resolveTrace 对未知 runtimeId 惰性建 trace，通知不丢。
- 2026-08-27：全部完成并部署。前端构建产物含启用决策卡片与 039 口径修正；后端通知链待 IDEA 重启。
