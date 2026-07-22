# Harness 上下文隔离与迭代收敛计划

> **状态**: 🚧 核心修复已实施；运行指标与更细粒度超迭代事件仍待补齐
> **创建日期**: 2026-07-22
> **目标**: 消除 AgentScope 通用 coding-agent 工作区上下文对评审角色的干扰，提供足以完成代码检索与领域提交的迭代预算，并把未完成角色显式转为可观测的领域结果。
> **前置计划**: PLAN-007、PLAN-008、PLAN-009、PLAN-010、PLAN-015

## 0. 失败证据与根因

本地 AgentScope 2.0 源码确认：`WorkspaceContextMiddleware` 会把 `AgentStateStore Context`、`Domain Knowledge`、`Memory Recall`、`Workspace` 和 `AGENTS.md` 拼接进 system prompt；其文本指示模型使用 `read_file`、`grep`、`glob` 以及 memory 工具。Chongming 虽已关闭这些工具，却没有关闭该 middleware，造成“提示允许、工具拒绝”的冲突。

`HarnessAgent.Builder.disableWorkspaceContext()` 已存在；构建阶段仅在未设置该开关时注册 `WorkspaceContextMiddleware`。因此本计划只改 Chongming 的 Harness 装配，不修改或 fork `E:\aicode\agentscope-java`。

当前核心角色 `maxIterations=4`。一次迭代是“单次模型推理/返回 + 该次返回中的所有工具执行”；工具执行后进入下一次推理时计数加一。达到 `iter >= maxIters` 后，AgentScope 进入 summary 路径并允许返回最终文本，不保证调用 `complete_initial_review`。对“列目录、检索、读取、提交多个 Claim、完成”的评审任务，4 轮不足。

## 1. 不变量与边界

- 角色只接收 Chongming 角色 prompt、需求快照公共摘要、服务端工具 schema 与工具结果；不接收通用 workspace、memory、knowledge 或 AGENTS.md 注入文本。
- `disableWorkspaceContext()` 不改变角色的实际工作目录、Session 持久化或服务端快照工具；仅停止 prompt 注入。
- 不因模型最终文本自动伪造 `complete_initial_review`；未完成必须成为明确的 `ROLE_INCOMPLETE`/失败事件或可重试状态。
- 不放开 shell、filesystem、memory、动态 skill/subagent，不把 AgentScope `BYPASS` 扩展到领域协议。

## 2. 实施步骤

### 2.1 关闭通用工作区上下文 ✅ 已实施

1. 在 `ReviewDirectorHarnessFactory` 和 `RoleSubagentFactory` 的 `HarnessAgent.Builder` 显式调用 `disableWorkspaceContext()`。
2. 保留已有 `disableFilesystemTools`、`disableShellTool`、`disableMemoryTools`、`disableMemoryHooks`、`disableDefaultWorkspaceSkills`、`skillsEnabled(false)` 等拒绝边界。
3. 补充兼容性测试：捕获模型请求，断言不含 `AgentStateStore Context`、`Domain Knowledge`、`Memory Recall`、`Workspace Files (Injected)`、`AGENTS.md` 和 `read_file/grep/glob` 指令，同时仍包含角色 prompt 与受限工具 schema。

### 2.2 调整角色迭代预算 ✅ 已实施

将 `roles/*.yml` 中的静态预算改为以下基线，并通过配置集中管理、记录在启动日志与领域运行事件中：

| 类别 | 当前 | 目标 | 依据 |
|---|---:|---:|---|
| 四核心角色 Product/Project/Frontend/Backend | 4 | 10 | 至少覆盖目录发现、3-4 次检索/读取、Claim、完成工具，并预留两次修正 |
| 按需角色 Architecture/Security/Test | 4 | 8 | 调查范围较窄，但仍需证据与完成步骤 |
| Judge | 3 | 4 | 仅消费已持久化公共事实并提交裁决/Gate 草案 |
| Director | 12 | 12 | 保持不变，另由业务事件串行唤醒控制 |

后续新增每角色模型调用数、工具调用数、耗时和已用/上限迭代日志，避免只依赖 UI 静默等待判断；这不阻塞本次“上下文冲突 + 角色静默结束”的修复。

### 2.3 未完成角色的领域兜底 ✅ 已实施（stream 结束核对）

1. `AgentScopeReviewRuntimeAdapter` 在 role stream 正常结束时核对：该角色是否已成功调用 `complete_initial_review`。
2. 未完成时 `InitialReviewProgressService` 发布 `ROLE_FAILED`（`failureCode=ROLE_INCOMPLETE`）与 `REVIEW_FAILED` 领域事件，评审进入明确 `FAILED` 状态，不无限等待；失败转换会在同一聚合锁内复核 attempt 和取消标记，旧 attempt 或已取消的 stream 不会污染当前评审。
3. 当前事件含 role 和失败原因；已用迭代、最后工具和 traceId 的运行指标属于后续可观测性补齐项。
4. 已提交 Claim 但未完成也不自动补完成；保留事实，交由重试 attempt 重新评审，避免把不完整结论伪装为完成。

### 2.4 Prompt 与工具调用收敛

1. 角色 prompt 固化最短路径：`listFiles` 定位 → 有限 `searchText/readLines/findSymbol` → `submit_claim`（如有）→ 必须 `complete_initial_review`。
2. 明确禁止对目录使用 `readLines`；每个工具错误返回可执行的下一步提示。
3. 在首次角色消息中附带需求摘要与不超过指定数量的推荐入口文件，减少盲目检索。
4. `searchText` 保持字面量默认；正则 ReDoS 加固继续遵循 PLAN-015。

## 3. 文件清单

| 文件 | 改动 | 状态 |
|---|---|---|
| `review/infrastructure/agentscope/ReviewDirectorHarnessFactory.java` | 关闭 WorkspaceContextMiddleware | ✅ |
| `review/infrastructure/agentscope/RoleSubagentFactory.java` | 关闭 WorkspaceContextMiddleware、收敛角色 prompt | ✅ |
| `src/main/resources/roles/*.yml` | 调整角色迭代预算 | ✅ |
| `review/application/InitialReviewProgressService.java` | 未完成角色的领域失败与事实保留 | ✅ |
| `review/infrastructure/agentscope/AgentScopeReviewRuntimeAdapter.java` | stream 结束核对完成工具并输出未完成结果 | ✅ |
| `review/infrastructure/agentscope/AgentEventAdapter.java` | 适配 `ExceedMaxItersEvent` 并附加迭代统计 | ⏳ 后续可观测性 |
| `src/test/java/.../agentscope/*Tests.java` | 上下文隔离、工具保留和完成工具测试 | ✅ 部分；多轮脚本回归待补 |
| `docs/AIREVIEW-PLAN-008-Harness主持人与角色编排.md` | 同步真实运行边界与状态 | ✅ |

## 4. 验收测试

1. Role/Director 模型请求中不再出现通用 workspace/memory/knowledge/AGENTS 上下文。
2. 四核心角色收到的工具集合仅包含 RolePack 允许的领域与快照工具。
3. 脚本模型使用 6 次“推理 + 工具”循环后仍可调用 `complete_initial_review`；第 11 次推理触发上限处理。
4. 达到上限且未完成时，评审不再停在 `INITIAL_REVIEW`；UI/SSE 收到 `ROLE_INCOMPLETE`，并进入明确失败或可重试状态。
5. 角色自然完成时不产生未完成事件；已提交 Claim 与完成事件顺序不变。
6. 回归验证 shell、filesystem、memory、动态 skill/subagent 仍不能被角色使用。

## 5. 风险与对策

| 风险 | 对策 |
|---|---|
| 关闭上下文误删必要事实 | 只关闭通用 middleware；需求摘要、RolePack prompt 和服务端快照工具保留，并用请求捕获测试验证 |
| 提高迭代造成模型成本上涨 | 按角色预算、工具/耗时观测、完成即停止；不按固定轮数强制消耗 |
| 最终文本误判为完成 | 仅以服务端 `complete_initial_review` 成功事件为完成依据 |
| AgentScope 版本升级改变 middleware 行为 | 以本地 AS2 源码兼容测试锁定 `disableWorkspaceContext()` 的效果 |

## 6. 变更记录

| 日期 | 变更 |
|---|---|
| 2026-07-22 | 基于本地 AgentScope 2.0 `WorkspaceContextMiddleware` 和 ReAct 迭代源码创建修复计划。 |
| 2026-07-22 | 实施上下文隔离、角色预算和 `ROLE_INCOMPLETE` 失败兜底；覆盖 Adapter stream 结束、旧 attempt 隔离和取消协调，11 个相关单元测试通过。 |
