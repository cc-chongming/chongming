# AgentScope 2.0.0 兼容性验证记录

> **日期**：2026-07-15  
> **运行环境**：Java 21.0.10、Spring Boot 4.0.7、AgentScope 2.0.0  
> **结论**：工程基线可用；MySQL 实际集成等待具备 Docker 的环境执行。

## 验证矩阵

| 能力 | 验证方式 | 结论 | 说明 |
|---|---|---|---|
| 正式制品解析 | Maven 依赖解析 | PASS | `agentscope-harness` 与 `agentscope-extensions-mysql` 均固定为 `2.0.0`。 |
| 构建约束 | Maven Enforcer | PASS | Java 21、禁止 SNAPSHOT 依赖、依赖收敛均通过。 |
| 配置契约 | `ReviewPropertiesTests` | PASS | 非敏感运行时配置可绑定；启用模型网关但缺失环境变量密钥会启动失败。 |
| Plan Mode | `HarnessPlanModeCompatibilityTests` | PASS | 可进入、写入和退出；正式计划路径为 `plans/PLAN.md`。 |
| Harness 子 Agent | `HarnessSubagentCompatibilityTests` | PASS | `subagentFactory` 注册后可由 `DefaultAgentManager` 创建指定角色实例。 |
| MySQL AgentState | `MysqlAgentStateCompatibilityTests` | SKIPPED | 测试已按 Testcontainers 编写，但本机没有可用 Docker daemon。 |

## 已确认的实现约束

- 业务运行时继续固定使用 `AgentScope 2.0.0`；本地 `2.0.1-SNAPSHOT` 源码只用于理解 API，不可替代正式依赖。
- `MysqlAgentStateStore` 提供 `close()`，但不实现 `AutoCloseable`；Adapter 必须在 `finally` 中显式关闭。
- 评审业务状态和审计表由 MyBatis 管理；AgentScope MySQL 扩展仅经其公开 API 保存运行时状态，业务代码不得直接依赖其内部表结构。

## 尚未完成的验证

- 使用固定脚本模型覆盖 `agent_spawn`、`agent_send`、父子事件传播、权限继承和持久会话恢复。
- 在 Docker 可用的 CI 环境执行 MySQL AgentState round-trip，并补充 workspace KV、snapshot、锁和数据库重启后的恢复测试。
- 定义并接入 `AgentRuntimeAdapter`，隔离上述正式版 API 差异后再进入 PLAN-008 编排开发。
