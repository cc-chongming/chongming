# AIREVIEW-PLAN-036 协调者计划修订闭环

> **状态**: ✅ 全部完成（补齐文档，实施先行）
> **创建日期**: 2026-08-27
> **目标**: 打通"协调者修订评审计划"的端到端闭环——plan 模式写入 plans/PLAN.md 后，服务端检测差异、落库 PLAN_REVISED 事件、刷新计划卡，并在公开运行流留痕。

## 背景

评审启动时的初始计划由服务端用操作员传入的 publicTasks 直接生成（PLAN_CREATED），"评审规划"面板的计划卡因此"啪地出现"；
而 ReviewOrchestrationService.revisePlan()（appendPlan + writePlan + emit PLAN_REVISED）虽已实现，却没有任何调用方。
协调者 harness 启用了 plan 模式（plan_enter/plan_write/plan_exit），但即使模型修订了计划，修订也不会提升为公开事件——
"协调者修订计划"这条回路端到端未接通，规划过程不可见。用户已批准打通该机制（"乙"方案）。

## 勘察结论（agentscope-harness 2.0.0）

- PlanModeManager 固定把计划文档写到工作区相对路径 plans/PLAN.md（单个 markdown，plan_write 每次整体覆盖，不是 plan-vN.json）。
- plan_enter/plan_write/plan_exit 是普通工具调用，plan_write 的 ToolResultEndEvent 在事件流中可观测。
- 服务端 writePlan 写的是 attempts/<n>/plans/plan-v1.json（WorkspaceArtifact 信封），与 PLAN.md 是两套文件。

## 分段方案

### #1 提升器（DirectorPlanRevisionPromoter）

- 只扫描 plans/PLAN.md：服务端初始 v1（plan-v1.json）永不作为修订提升，不误提升初始版本。
- 内容 SHA-256 摘要幂等：同一文档只提升一次；内容变化才提升为下一版本（按 runtimeId 记录水位）。
- 解析 markdown：清单项（- / * / 1. 等）→ publicTasks；"修订原因/变更原因/原因/reason" 行 → changeReason（缺省中文兜底）；无任务清单则跳过。
- 委托既有 ReviewOrchestrationService.revisePlan()（自带 appendPlan + writePlan + emit PLAN_REVISED → 前端计划卡自动刷新）。
- 版本上限等拒绝场景记录摘要防循环重试；异常不打断 Director 运行流。

### #2 双钩子接入（AgentScopeReviewRuntimeAdapter.run()）

- 主钩子：Director 事件流中 ToolResultEndEvent 且工具名 == plan_write、状态非 ERROR/DENIED 时立即提升——
  覆盖"模型确实写完计划文件"的精确时刻，计划卡即时更新。
- 兜底钩子：Director 每轮结束再扫描一次文件差异，覆盖其它写文件路径。
- 两钩子归一到 promoteIfChanged，靠摘要去重保证同一内容只提升一次。
- 经 ObjectProvider<ReviewOrchestrationService> 惰性注入，避免编排服务↔运行时适配器的构造器循环。

### #3 公开运行流留痕

- 提升成功后经 ReviewAgUiEventMapper.publicNotice() 发布 TextMessage 三元组（归属 DIRECTOR），
  文案"协调者修订评审计划至 vN"，评审规划面板/运行流可见；计划卡更新仍走 PLAN_REVISED 域事件通道。
- Director 提示词补充 plan 模式修订流程说明（plan_enter→plan_write 重写 plans/PLAN.md→plan_exit，
  内容变更会提升为 PLAN_REVISED 并更新计划卡）；未动答辩拓扑段落。

## 文件清单

### 新建

| 文件 | 计划段 | 状态 |
|------|--------|------|
| src/main/java/ai/cc/chongming/review/application/DirectorPlanRevisionPromoter.java | #1 | ✅ |
| src/test/java/ai/cc/chongming/review/application/DirectorPlanRevisionPromoterTests.java | #1 | ✅ |

### 修改

| 文件 | 计划段 | 状态 |
|------|--------|------|
| src/main/java/ai/cc/chongming/review/infrastructure/agentscope/AgentScopeReviewRuntimeAdapter.java | #2 #3 | ✅ |
| src/main/java/ai/cc/chongming/review/infrastructure/agentscope/ReviewAgUiEventMapper.java | #3 | ✅ |
| src/main/java/ai/cc/chongming/review/infrastructure/agentscope/ReviewDirectorHarnessFactory.java | #3 | ✅ |

## 实施顺序

1. **步骤 1** ✅ 勘察 harness plan 模式写文件机制
2. **步骤 2** ✅ 提升器 + 单测（依赖步骤 1 的结论）
3. **步骤 3** ✅ 双钩子接入 + 运行流留痕 + 提示词（依赖步骤 2）

## 风险与应对

- 模型目前未必主动调用 plan_write（历史运行中 plan_* 计数为 0）：机制已就绪，提示词已引导；一旦模型修订计划即可见。
- 循环修订：摘要幂等 + 版本上限拒绝后不再重试，防止 Director 反复锤击修订路径。
- Bean 循环：ObjectProvider 惰性解析规避构造器循环。

## 变更记录

- 2026-08-27：实施完成；子代理实现、主代理独立审查（提升器幂等/钩子异常安全/提示词未动答辩拓扑），新增单测 6 个，全量 742 绿。计划段标记修正为 AIREVIEW-PLAN-036（子代理原误标 037）。
