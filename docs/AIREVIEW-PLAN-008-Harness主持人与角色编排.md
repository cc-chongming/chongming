# Harness 主持人与角色编排计划

> **状态**: ⏳ 待实施
> **创建日期**: 2026-07-14
> **目标**: 实现 ReviewDirectorHarness 上帝主持人，在 ProtocolGuard 边界内强计划驱动、动态激活并协调持久角色子 Agent。
> **前置计划**: PLAN-004、PLAN-006、PLAN-007；PLAN-003 已冻结

## 0. 背景与边界

主持人拥有“如何完成评审”的自主权，但没有绕过协议的主权。本计划使用 AgentScope Harness 的 Plan Mode、subagent、workspace
和持久会话，
业务状态仍由 MySQL 与 ReviewProtocolGuard 决定。本计划只负责编排，不实现辩论业务工具内部规则。

## 1. 分段方案

### 1.1 Harness 工厂与 RuntimeContext ⏳

- 每场 review 创建稳定 director session；每个角色 label 可确定推导且跨恢复不变。
- 注入 ModelGateway、只读工具、DebateTools facade、workspace、MySQL Store 和 PermissionEngine。
- RuntimeContext 包含 reviewId、attempt、userId、traceId、cancelToken，不从 ThreadLocal 隐式获取。

### 1.2 Workspace 布局 ⏳

- 固定 `reviews/{reviewId}/{attempt}/input|snapshot|plans|evidence|claims|debates|reports`。
- Agent 只可写自己的计划/公开协作文件；业务事实必须通过强类型工具落库。
- workspace 文件包含 schemaVersion 和 hash，不作为最终状态源。

### 1.3 两级 Plan Mode ⏳

- 总计划冻结需求、调查范围、核心角色、候选角色、证据目标、预算和候选争议。
- 阶段计划在首轮/辩论后修订，记录 reason、previousVersion、planVersion 并发 `PLAN_REVISED`。
- 退出 Plan Mode 前执行 Guard/人工策略确认，随后用任务清单推进。

### 1.4 子 Agent 工厂与持久角色 ⏳

- 根据 RolePack 构造四核心、最多三按需和 Judge；`persistSession=true`。
- 四核心首轮允许后台并行；同一角色每次追加消息必须复用 label/session。
- 子 Agent 只能看到角色上下文和共享事实视图，不能读取其他角色私有会话。

### 1.5 动态角色激活 ⏳

- 主持人提交 `requestRoleActivation(role, reason, evidenceIds)`，Guard 校验上限和重复。
- 角色选择依据需求标签、代码证据、Claim 缺口和争议类型；记录激活来源为 PLAN/RULE/HUMAN。
- 无充分理由或预算不足时拒绝激活并生成可见事件。

### 1.6 首轮评审编排 ⏳

- product/project/frontend/backend 读取不同上下文视图并独立提交 Claim。
- 汇合前不共享其他角色结论，降低锚定；完成后统一发布公开 Claim。
- 单角色失败按核心/按需策略处理，超时和取消可协作传播。

### 1.7 恢复、取消与并发控制 ⏳

- 应用重启从 review stage、task list 和 session label 恢复；已完成工具调用不得重复落库。
- 同一 review 只允许一个 active attempt director；使用数据库锁/乐观锁防双启动。
- cancel 传播到模型、文件扫描和后台子 Agent，最终写 CANCELLED 事件。

### 1.8 AgentScope 事件适配 ⏳

- 原始事件映射 actorRole、agentId、sessionId、stage、toolName、parentId。
- 原始事件只用于运行观测；正式业务事件必须在强类型工具成功后产生。
- 未知 AgentScope 事件安全忽略并计数，不导致业务状态错误。

## 2. 文件清单

### 2.1 新建

| 文件                                                                                                 | 计划段      | 状态 |
|----------------------------------------------------------------------------------------------------|----------|----|
| `src/main/java/ai/cc/chongming/review/infrastructure/agentscope/ReviewDirectorHarnessFactory.java` | #1.1-1.3 | ⏳  |
| `src/main/java/ai/cc/chongming/review/infrastructure/agentscope/RoleSubagentFactory.java`          | #1.4     | ⏳  |
| `src/main/java/ai/cc/chongming/review/infrastructure/agentscope/ReviewWorkspaceLayout.java`        | #1.2     | ⏳  |
| `src/main/java/ai/cc/chongming/review/infrastructure/agentscope/AgentEventAdapter.java`            | #1.8     | ⏳  |
| `src/main/java/ai/cc/chongming/review/application/ReviewOrchestrationService.java`                 | #1.3-1.7 | ⏳  |
| `src/main/java/ai/cc/chongming/review/application/RoleActivationService.java`                      | #1.5     | ⏳  |
| `src/main/java/ai/cc/chongming/review/application/ReviewRecoveryService.java`                      | #1.7     | ⏳  |
| `src/test/java/ai/cc/chongming/review/agentscope/ReviewDirectorHarnessTest.java`                   | #1.1-1.3 | ⏳  |
| `src/test/java/ai/cc/chongming/review/agentscope/RoleSubagentIsolationTest.java`                   | #1.4-1.6 | ⏳  |
| `src/test/java/ai/cc/chongming/review/agentscope/ReviewOrchestrationRecoveryIntegrationTest.java`  | #1.7-1.8 | ⏳  |

### 2.2 修改

| 文件                                                                                        | 计划段       | 状态 |
|-------------------------------------------------------------------------------------------|-----------|----|
| `src/main/java/ai/cc/chongming/review/infrastructure/agentscope/AgentRuntimeAdapter.java` | #1.1、#1.7 | ⏳  |
| `src/main/resources/application.yml`                                                      | #1.1、#1.7 | ⏳  |

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
