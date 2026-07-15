# 工程基线与 AgentScope 正式版验证计划

> **状态**: ⏳ 待实施
> **创建日期**: 2026-07-14
> **目标**: 锁定可重复构建的工程基线，并用自动化测试确认 AgentScope 2.0.0 正式制品满足核心 Harness 能力。
> **前置计划**: 无

## 0. 背景与边界

项目当前固定 `agentscope.version=2.0.0`，但现有 `FirstAgent` 仅验证简单 Harness 调用。本计划必须在业务开发前确认正式制品，而不是依据本地
`2.0.1-SNAPSHOT` 源码推断行为。若核心能力不满足，只允许通过项目 Adapter 兼容，不切换内部制品。

本计划不实现评审业务流程、数据库表或真实角色 Prompt。

## 1. 分段方案

### 1.1 固化依赖和构建基线 ⏳

- 修改 `pom.xml`：集中管理 AgentScope 2.0.0、MySQL 扩展、MySQL Driver、Validation、Actuator、Testcontainers、JaCoCo。
- 保留 Spring MVC + MyBatis 阻塞模型，不引入 WebFlux/JPA。
- 增加 Maven Enforcer：Java 21、禁止 SNAPSHOT、依赖收敛。
- 验收：IDEA 完整构建成功；依赖树中无 AgentScope 非 2.0.0 版本。

### 1.2 建立配置契约 ⏳

- 新建 `ReviewProperties`、`AgentScopeProperties`、`ModelGatewayProperties`，凭证只接受环境变量。
- `application.yml` 只保留非敏感默认值；创建 `application-test.yml` 使用 MockModel 和 Testcontainers 动态配置。
- 验收：缺少必须配置时启动失败并给出明确字段；日志不打印密钥。

### 1.3 Harness/Plan Mode 合约测试 ⏳

- 使用可控 MockModel 构造最小 `HarnessAgent`。
- 验证 Plan Mode 进入/退出、计划文件写入、任务清单更新和 RuntimeContext 透传。
- 记录正式版实际事件类型、顺序和线程模型，形成 `AgentScopeCompatibilityReport.md`。

### 1.4 子 Agent 与持久会话合约测试 ⏳

- 验证 `agent_spawn` 同步/后台模式、稳定 label、`agent_send`、`persistSession`。
- 验证同 `userId + sessionId` 恢复上下文，不同角色会话隔离。
- 验证父 Plan 状态、权限和子事件是否传播；差异必须由 Adapter 测试固定。

### 1.5 MySQL 扩展可用性 Spike ⏳

- 确认正式版 `agentscope-extensions-mysql:2.0.0` 可解析并与项目 DataSource 集成。
- 验证 AgentState、workspace KV、snapshot 和锁在 MySQL 重启后恢复。
- 禁止业务代码直接查询 AgentScope 内部表；只通过扩展 API 使用。

### 1.6 Adapter 与示例收口 ⏳

- 定义 `AgentRuntimeAdapter` 最小接口：`start`、`streamEvents`、`send`、`cancel`、`resume`。
- 将 `FirstAgent` 降级为 Spike 示例或删除，避免生产代码包含硬编码模型地址和无计划注释示例。
- 为 Adapter 建立伪实现，供后续领域计划不依赖真实模型并行开发。

## 2. 文件清单

### 2.1 新建

主代码基准目录为 `src/main/java/ai/cc/chongming/review/`，测试基准目录为 `src/test/java/ai/cc/chongming/review/`。

| 范围            | 相对文件                                                  | 计划段      | 状态 |
|---------------|-------------------------------------------------------|----------|----|
| main          | `config/ReviewProperties.java`                        | #1.2     | ⏳  |
| main          | `config/AgentScopeProperties.java`                    | #1.2     | ⏳  |
| main          | `config/ModelGatewayProperties.java`                  | #1.2     | ⏳  |
| main          | `infrastructure/agentscope/AgentRuntimeAdapter.java`  | #1.6     | ⏳  |
| test          | `support/FakeAgentRuntimeAdapter.java`                | #1.6     | ⏳  |
| test          | `compatibility/HarnessPlanModeCompatibilityTests.java` | #1.3     | ⏳  |
| test          | `compatibility/SubagentCompatibilityTests.java`        | #1.4     | ⏳  |
| test          | `compatibility/MysqlAgentStateCompatibilityTests.java` | #1.5     | ⏳  |
| test-resource | `application-test.yml`                                | #1.2     | ⏳  |
| docs          | `验证记录/AgentScopeCompatibilityReport.md`               | #1.3-1.5 | ⏳  |

### 2.2 修改

| 文件                                              | 计划段       | 状态 |
|-------------------------------------------------|-----------|----|
| `pom.xml`                                       | #1.1、#1.5 | ⏳  |
| `src/main/resources/application.yml`            | #1.2      | ⏳  |
| `src/main/java/ai/cc/chongming/FirstAgent.java` | #1.6      | ⏳  |

## 3. 实施顺序

1. **步骤 1**：先写依赖锁定和配置绑定测试。
2. **步骤 2**：调整 `pom.xml`，IDEA 构建直至成功。
3. **步骤 3**：实现 Harness/Plan Mode 合约测试。
4. **步骤 4**：实现子 Agent、事件、权限和恢复合约测试。
5. **步骤 5**：接入 MySQL 扩展 Testcontainers 测试。
6. **步骤 6**：定义 Adapter，归档兼容矩阵和差异。

## 4. 验证与退出标准

- IDEA `build_project` 返回 `isSuccess=true`。
- 所有兼容测试可重复运行三次，无随机失败。
- 正式制品版本、API 行为、事件传播、Plan 继承、恢复能力均有 PASS/FAIL 结论。
- 任何 FAIL 都有 Adapter 规避方案和对应回归测试；无法规避则阻断后续 PLAN-008。
- JaCoCo 能生成报告；本阶段新增生产代码覆盖率不低于 80%。

## 5. 风险与应对

| 风险                          | 应对                                    |
|-----------------------------|---------------------------------------|
| 正式版未发布 MySQL 扩展             | 立即标为阻断，不静默使用 SNAPSHOT；核对正式 BOM/仓库后再决策 |
| MockModel 无法触发 Harness 工具路径 | 使用固定工具调用脚本和最小自定义 Model，不连接真实服务        |
| 兼容测试依赖内部实现                  | 只断言公开行为、事件和持久化结果，不反射私有字段              |

## 6. 变更记录

| 日期         | 变更                         |
|------------|----------------------------|
| 2026-07-14 | 创建工程基线、正式版兼容性 Spike 和退出门禁。 |
