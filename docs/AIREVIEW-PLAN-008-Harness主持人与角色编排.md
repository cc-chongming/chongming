# Harness 主持人与角色编排计划

> **状态**: 🟡 主体实现与受限领域工具已接入；持久化租约、跨进程恢复和生产模型联调待后续计划
> **创建日期**: 2026-07-14
> **目标**: 实现 ReviewDirectorHarness 上帝主持人，在 ProtocolGuard 边界内强计划驱动、动态激活并协调持久角色子 Agent。
> **前置计划**: PLAN-004、PLAN-006、PLAN-007；PLAN-003 已冻结
> **关联计划**: PLAN-015 负责共享仓库快照与 review 引用；Harness 只解析受控引用后的共享只读视图。
> **关联计划**: PLAN-016 已关闭通用 WorkspaceContext 注入，将核心/按需/Judge 角色预算调整为 10/8/4，并把未调用 `complete_initial_review` 的正常结束转换为 `ROLE_INCOMPLETE` 领域失败；运行迭代统计仍待后续补齐。

## 0. 背景与边界

主持人拥有“如何完成评审”的自主权，但没有绕过协议的主权。本计划使用 AgentScope Harness 的 Plan Mode、subagent、workspace
和持久会话，
业务状态仍由 MySQL 与 ReviewProtocolGuard 决定。本计划只负责编排，不实现辩论业务工具内部规则。

## 1. 分段方案

### 1.1 Harness 工厂与 RuntimeContext ✅

- 每场 review 创建稳定 director session；每个角色 label 可确定推导且跨恢复不变。
- 注入 ModelGateway、只读工具、DebateTools facade、workspace、MySQL Store 和 PermissionEngine。
- RuntimeContext 包含 reviewId、attempt、userId、traceId、cancelToken，不从 ThreadLocal 隐式获取。

### 1.2 Workspace 布局 ✅

- 固定 `reviews/{reviewId}/input|snapshot`，以及 `reviews/{reviewId}/attempts/{attempt}/plans|evidence|claims|debates|reports`。
- Agent 只可写自己的计划/公开协作文件；业务事实必须通过强类型工具落库。
- workspace 文件包含 schemaVersion 和 hash，不作为最终状态源。

### 1.3 两级 Plan Mode 🚧

- 总计划冻结需求、调查范围、核心角色、候选角色、证据目标、预算和候选争议。
- 阶段计划在首轮/辩论后修订，记录 reason、previousVersion、planVersion 并发 `PLAN_REVISED`。
- 退出 Plan Mode 前执行 Guard/人工策略确认，随后用任务清单推进。

### 1.4 子 Agent 工厂与持久角色 ✅

- 根据 RolePack 构造四核心、最多三按需和 Judge；`persistSession=true`。
- 四核心首轮允许后台并行；同一角色每次追加消息必须复用 label/session。
- 子 Agent 只能看到角色上下文和共享事实视图，不能读取其他角色私有会话。

### 1.5 动态角色激活 ✅

- 主持人提交 `requestRoleActivation(role, reason, evidenceIds)`，Guard 校验上限和重复。
- 角色选择依据需求标签、代码证据、Claim 缺口和争议类型；记录激活来源为 PLAN/RULE/HUMAN。
- 无充分理由或预算不足时拒绝激活并生成可见事件。

### 1.6 首轮评审编排 🚧

- product/project/frontend/backend 读取不同上下文视图并独立提交 Claim。
- 汇合前不共享其他角色结论，降低锚定；完成后统一发布公开 Claim。
- 单角色失败按核心/按需策略处理，超时和取消可协作传播。

### 1.7 恢复、取消与并发控制 🚧

- 应用重启从 review stage、task list 和 session label 恢复；已完成工具调用不得重复落库。
- 同一 review 只允许一个 active attempt director；使用数据库锁/乐观锁防双启动。
- cancel 传播到模型、文件扫描和后台子 Agent，最终写 CANCELLED 事件。

### 1.8 AgentScope 事件适配 ✅

- 原始事件映射 actorRole、agentId、sessionId、stage、toolName、parentId。
- 原始事件只用于运行观测；正式业务事件必须在强类型工具成功后产生。
- 未知 AgentScope 事件安全忽略并计数，不导致业务状态错误。
### 1.9 2026-07-16 实施结论

已实现的编排边界：

- `ReviewRuntimeContext` 以 reviewId、attempt、userId、traceId、取消令牌推导全部 director/role label 与 sessionId；禁止调用方伪造身份。
- `ReviewWorkspaceLayout` 固化 review/attempt/角色私有目录，公开协作 artifact 仅写入 schemaVersion、SHA-256 和公开载荷，不能替代领域状态。
- Director 与角色 Harness 显式关闭 shell、文件系统、memory、动态 skill/subagent；Director 启用 AgentScope Plan Mode，并以 `BYPASS` 跳过无人值守场景中的 `plan_exit` 人工确认，但上述高风险能力仍由显式 DENY 规则禁止；`plan_*`、`todo_write` 仅用于受控计划过程，其余 ToolSchema 须命中 RolePack 白名单。
- `ReviewOrchestrationService` 生成可版本化总计划与修订计划，先启动四个核心角色；动态角色必须由 `RoleActivationService + ReviewProtocolGuard` 批准后才创建运行时。
- `AgentScopeReviewRuntimeAdapter` 对单 review 强制一个活动 director；取消会中断全部已注册 agent 并释放下一 attempt 的本进程锁；取消后的 runtime 不能恢复。`ReviewRecoveryService` 以稳定 session/label 重建运行时句柄而不重放领域命令。
- AgentScope 原始事件被收敛为不含私有 payload 的运行观测；正式业务事件、Claim、Debate 和 Gate 仍由 PLAN-009/010 的强类型命令和事件总线负责。

本计划保留的集成项：

- 数据库 lease/多实例抢占、启动扫描恢复、持久化业务事件由 PLAN-010 实现；当前 Adapter 的活动 director 互斥为单 JVM 保护。
- 已接入：Role Harness 按其 RolePack 白名单注册 `listFiles`、`searchText`、`findSymbol`、`readLines` 和 `getFileMetadata`。工具工厂从当前 review attempt 的需求快照解析受配置白名单保护的仓库标识，创建或安全复用不可变 `RepositorySnapshot` 后再绑定给角色；模型无法提交宿主机路径、仓库标识或快照路径。`submitEvidence` 仍未接入运行时。
- 已接入：角色 Harness 会注册仅绑定当前 review/attempt/role 的 `submit_claim`、`complete_initial_review` 以及辩论工具；Director Harness 注册开题、闭题、第二轮和进入裁决工具；Judge 预注册但在 `JUDGING` 前保持待命。ToolSchema 会下传模型兼容网关，模型 Tool Call 回到 AgentScope 后由服务端重新绑定 identity、version 与幂等键。四个核心角色完成后自动进入 `CONFLICT_DETECTION` 并发布正式事件。
- 已接入：`ReviewWorkflowDispatcher` 只在正式业务事件提交后，按单个 review 的串行队列唤醒 Director、对应核心角色或 Judge；取消和失败会释放该进程内队列。它不是持久化恢复队列，进程重启后的续跑仍依赖 PLAN-010 的数据库恢复能力。
- 当前边界：`open_debate_topic` 首次开题即进入第一轮，单个 attempt 当前只支持一个活跃辩题；多个冲突的批量编排尚未落地。角色超时降级、仓库只读/证据工具和生产模型 smoke test 仍依赖后续真实 snapshot 与模型配置。
## 2. 文件清单

### 2.1 原计划新增（已实施项已同步）

| 文件                                                                                                 | 计划段      | 状态 |
|----------------------------------------------------------------------------------------------------|----------|----|
| `src/main/java/ai/cc/chongming/review/infrastructure/agentscope/ReviewDirectorHarnessFactory.java` | #1.1-1.3 | ✅  |
| `src/main/java/ai/cc/chongming/review/infrastructure/agentscope/RoleSubagentFactory.java`          | #1.4     | ✅  |
| `src/main/java/ai/cc/chongming/review/infrastructure/agentscope/ReviewWorkspaceLayout.java`        | #1.2     | ✅  |
| `src/main/java/ai/cc/chongming/review/infrastructure/agentscope/AgentEventAdapter.java`            | #1.8     | ✅  |
| `src/main/java/ai/cc/chongming/review/application/ReviewOrchestrationService.java`                 | #1.3-1.7 | ✅  |
| `src/main/java/ai/cc/chongming/review/application/RoleActivationService.java`                      | #1.5     | ✅  |
| `src/main/java/ai/cc/chongming/review/application/ReviewRecoveryService.java`                      | #1.7     | ✅  |
| `src/test/java/ai/cc/chongming/review/agentscope/AgentScopeReviewRuntimeAdapterTests.java`                   | #1.1-1.3 | ✅  |
| `src/test/java/ai/cc/chongming/review/agentscope/RoleSubagentIsolationTests.java`                   | #1.4-1.6 | ✅  |
| `src/test/java/ai/cc/chongming/review/agentscope/ReviewRecoveryServiceTests.java`  | #1.7-1.8 | ✅  |

### 2.2 原计划修改（已实施项已同步）

| 文件                                                                                        | 计划段       | 状态 |
|-------------------------------------------------------------------------------------------|-----------|----|
| `src/main/java/ai/cc/chongming/review/infrastructure/agentscope/AgentRuntimeAdapter.java` | #1.1、#1.7 | ✅  |
| `src/main/resources/application.yml`                                                      | #1.1、#1.7 | ✅  |
### 2.3 实际新增与修改（2026-07-16）

| 文件 | 状态 | 说明 |
|---|---|---|
| `review/application/ReviewRuntimeContext.java`、`ReviewCancellationToken.java` | ✅ | 稳定身份、会话标签和协作取消令牌。 |
| `review/infrastructure/agentscope/ReviewDirectorHarnessFactory.java`、`RoleSubagentFactory.java`、`AgentScopeModelBridge.java` | ✅ | Director/角色 Harness、显式权限边界和模型桥接。 |
| `review/infrastructure/agentscope/ReviewWorkspaceLayout.java`、`AgentEventAdapter.java`、`AgentScopeReviewRuntimeAdapter.java` | ✅ | 固定工作区、无私有 payload 的运行观测、实际运行时互斥/取消。 |
| `review/application/ReviewOrchestrationService.java`、`RoleActivationService.java`、`ReviewRecoveryService.java` | ✅ | 计划、角色 Guard、恢复句柄重建。 |
|
eview/config/ReviewProtocolConfiguration.java | ✅ | 将纯领域状态机与协议守卫显式装配为 Spring Bean。 |
| `review/infrastructure/agentscope/AgentRuntime*.java`、`ReviewTypes.java`、`application.yml` | ✅ | 运行时端口、DIRECTOR 角色及最大计划修订配置。 |
| `review/agentscope/*Tests.java` | ✅ | 10 个聚焦测试覆盖 workspace、模型白名单、角色隔离、编排、真实 Harness、取消及恢复。 |

## 3. 实施顺序

1. **步骤 1**：基于 Fake Adapter 写总计划和 workspace 测试。
2. **步骤 2**：实现角色工厂、上下文隔离和四角色并行。
3. **步骤 3**：实现动态角色激活和 Guard 拒绝路径。
4. **步骤 4**：接入正式 Harness Adapter 与原始事件。
5. **步骤 5**：完成双启动、取消、崩溃恢复和幂等测试。

## 4. 验证与退出标准

- 总计划和每次修订均有版本、原因和事件。
- 四核心角色必须全部启动且首轮上下文互相隔离。
- 同一 label 的 `agent_send` 保持上下文，不同角色不串话。
- 第 9 个 Agent、重复角色和越权工具确定性拒绝。
- 重启后从最后已提交阶段继续，Claim/事件不重复。
- 原始 AgentScope 事件与正式业务事件来源清晰可区分。

## 5. 风险与应对

| 风险               | 应对                                   |
|------------------|--------------------------------------|
| 后台 Agent 事件顺序不稳定 | 业务事件使用数据库 sequence；UI 不依赖原始事件排序      |
| 主持人无限修订计划        | Guard 限制阶段、预算和最大修订次数，并允许人工终止         |
| 角色上下文泄漏          | 以 session/label/workspace 子路径三重隔离并测试 |

## 6. 变更记录

| 日期         | 变更                                   |
|------------|--------------------------------------|
| 2026-07-14 | 创建 Harness、两级计划、持久子 Agent、动态激活与恢复计划。 |
| 2026-07-16 | 实现 Harness 主持人、角色隔离、计划/激活编排、取消/恢复适配和原始事件观测；记录 PLAN-009/010 依赖的集成项。 |
| 2026-07-20 | 接入首轮、辩论、Judge/Gate 的受限运行时工具，以及按正式领域事件串行唤醒角色的进程内调度；明确单辩题和非持久化恢复边界。 |
| 2026-07-22 | 将受限本地仓库读取工具接入 Role Harness；所有读取均固定到服务端创建的 review 快照，Evidence 提交保持未接入。 |
| 2026-07-22 | Director 启用 Plan Mode 并以 `BYPASS` 自动通过无人值守计划退出；shell、文件系统与动态子代理继续显式拒绝。 |
