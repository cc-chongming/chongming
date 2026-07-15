# AgentScope 2.0.0 兼容性验证记录

> **日期**：2026-07-15  
> **运行环境**：Java 21.0.10、Spring Boot 4.0.7、AgentScope 2.0.0  
> **结论**：工程基线、Adapter 契约和 Harness 子代理协议可用；Plan/权限传播须由 Adapter 显式补偿。MySQL 实测依赖 Docker，已列为后续环境门禁。

## 验证矩阵

| 能力 | 验证方式 | 结论 | 说明 |
|---|---|---|---|
| 正式制品解析 | Maven 依赖解析 | PASS | `agentscope-harness` 与 `agentscope-extensions-mysql` 均固定为 `2.0.0`。 |
| 构建约束 | Maven Enforcer | PASS | Java 21、禁止 SNAPSHOT 依赖、依赖收敛均通过。 |
| 配置契约 | `ReviewPropertiesTests` | PASS | 非敏感运行时配置可绑定；启用模型网关但缺失环境变量密钥会启动失败。 |
| Plan Mode | `HarnessPlanModeCompatibilityTests` | PASS | 可进入、写入和退出；正式计划路径为 `plans/PLAN.md`。 |
| Harness 子 Agent | `HarnessSubagentCompatibilityTests` | PASS | `subagentFactory` 注册后可由 `DefaultAgentManager` 创建指定角色实例。 |
| 同步子 Agent 事件与会话 | `HarnessSubagentEventCompatibilityTests` | PASS | 覆盖同步 `agent_spawn`、稳定 label 的 `agent_send`、child source 事件透传与持久子 Agent 会话复用。 |
| 后台子 Agent | `HarnessBackgroundSubagentCompatibilityTests` | PASS | `timeout_seconds=0` 会在子任务未结束时让父 Agent 返回，子任务在后台完成。 |
| 父 Plan 状态传播 | `HarnessPlanModeSubagentCompatibilityTests` | ADAPTER_REQUIRED | 正式版可在 Plan Mode spawn，但子 Harness 不继承父 Plan 状态。 |
| 父 DENY 规则传播 | `HarnessSubagentPropagationCompatibilityTests` | ADAPTER_REQUIRED | 自定义 `subagentFactory` 创建的子 Harness 不会复制父侧 DENY Permission 规则。 |
| 运行时 Adapter 契约 | `AgentRuntimeAdapterContractTests` | PASS | Fake 覆盖 `start`、事件流、`send`、`cancel`、`resume` 及顺序事件。 |
| MySQL AgentState | `MysqlAgentStateCompatibilityTests` | SKIPPED | 测试已按 Testcontainers 编写；当前环境没有可用 Docker daemon。 |

## 已确认的实现约束

- 业务运行时继续固定使用 `AgentScope 2.0.0`；本地 `2.0.1-SNAPSHOT` 源码只用于理解 API，不可替代正式依赖。
- AgentScope 会按 `userId + sessionId` 从 `agentscope.state.home` 关联运行时状态；兼容性测试必须为每个测试隔离状态目录，避免跨测试恢复旧上下文。
- 协议类脚本测试应仅关闭 `memoryHooks`，避免辅助模型调用消耗固定响应；该设置不代表生产运行时的默认策略。
- 正式版不会自动传播父 Plan 状态或自定义工厂场景下的 DENY 规则；正式 `AgentRuntimeAdapter` 必须在创建子 Harness 时显式应用两项策略，并以业务规则为最终安全边界。
- `MysqlAgentStateStore` 提供 `close()`，但不实现 `AutoCloseable`；Adapter 必须在 `finally` 中显式关闭。
- 评审业务状态和审计表由 MyBatis 管理；AgentScope MySQL 扩展仅经其公开 API 保存运行时状态，业务代码不得直接依赖其内部表结构。

## PLAN-002 收口与后续门禁

- PLAN-002 的工程基线、公开 API 兼容性、Adapter/Fake 契约和文档收口已完成，可进入 PLAN-003。
- 在具备 Docker 的 CI 环境必须执行 MySQL AgentState round-trip，并补充 workspace KV、snapshot、锁和数据库重启后的恢复测试。
- 正式 `AgentRuntimeAdapter` 接入业务编排前，必须实现并测试 Plan 与 DENY 策略的显式子代理传播、跨进程/不同角色会话恢复和后台任务取消。
