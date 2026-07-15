# PLAN-003 领域协议验证记录

> 日期：2026-07-15  
> 运行环境：Java 21.0.10、JUnit 5  
> 结论：领域状态机、ProtocolGuard、幂等和乐观锁契约已冻结，不依赖 Spring、MySQL 或模型调用。

## 验证矩阵

| 能力 | 测试 | 结论 |
|---|---|---|
| 主评审状态机 | ReviewStateMachineTests | PASS |
| 取消与失败边界 | ReviewStateMachineTests | PASS |
| 两轮辩题状态机 | DebateStateMachineTests | PASS |
| Claim 和 Turn 引用完整性 | DebateStateMachineTests | PASS |
| 四核心角色和八 Agent 上限 | ReviewProtocolGuardTests | PASS |
| P0/P1 无证据降级 | ReviewProtocolGuardTests | PASS |
| AI 草案与人工最终 Gate 边界 | ReviewProtocolGuardTests | PASS |
| 幂等重放与 expectedVersion | ReviewProtocolGuardTests | PASS |

## 状态迁移表

| 当前状态 | 后继状态 |
|---|---|
| PENDING | SNAPSHOTTING、CANCELLING |
| SNAPSHOTTING 至 NOTIFYING | 固定主流程下一状态，或 FAILED、CANCELLING |
| CANCELLING | CANCELLED |
| COMPLETED、CANCELLED、FAILED | 无 |

辩题状态为 OPEN 到 CHALLENGED 到 REBUTTED；REBUTTED 可进入下一轮 CHALLENGED，或进入 RESOLVED、ESCALATED。回合范围固定为 1 至 2。

## 构建证据

完整 clean verify 通过：31 个测试执行，30 个通过，1 个 MySQL Testcontainers 测试因本机无 Docker 自动跳过；应用 JAR 已成功打包。