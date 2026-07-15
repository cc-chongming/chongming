# 领域契约、状态机与协议守卫计划

> **状态**: ⏳ 待实施
> **创建日期**: 2026-07-14
> **目标**: 在不依赖模型和数据库的情况下冻结评审领域契约、生命周期和不可绕过的业务不变量。
> **前置计划**: PLAN-002 的 Java/测试基线

## 0. 背景与边界

Harness 可以自主计划，但不能修改强制角色、Agent 上限、辩论轮次、证据规则和人工最终决定。本计划先用纯 Java 领域层表达这些规则，后续
持久化、AgentScope 工具和 API 只能调用领域服务，不得复制规则。

## 1. 分段方案

### 1.1 冻结 ID、枚举和错误码 ⏳

- 定义 `ReviewId`、`ClaimId`、`EvidenceId`、`TopicId`、`TurnId` 等强类型 ID。
- 定义 ReviewStage、RoleType、ClaimSeverity、ClaimPosition、DebateTurnType、GateResult、DecisionStatus。
- 定义稳定错误码：非法状态、重复提交、证据无效、越轮、越权角色、版本冲突。

### 1.2 定义聚合与不可变值对象 ⏳

- 聚合：Review、ReviewPlan、Claim、DebateTopic、GateDecision。
- 值对象：EvidenceReference、RoleActivation、DebateTurn、JudgeDecision、HumanReviewItem。
- 所有公开输出禁止包含隐藏思维链，只保存公开论点、理由摘要和证据引用。

### 1.3 评审主状态机 ⏳

- 状态固定为：PENDING → SNAPSHOTTING → PLANNING → INITIAL_REVIEW → CONFLICT_DETECTION → DEBATE_ROUND_1 → DEBATE_ROUND_2 → JUDGING → WAITING_HUMAN → NOTIFYING → COMPLETED。
- 任一执行态可进入 FAILED；取消经过 CANCELLING → CANCELLED；重试创建新 attempt，不回写旧 attempt。
- 每个迁移声明触发者、前置条件、事件和可恢复点。

### 1.4 辩题状态机 ⏳

- 状态：OPEN → CHALLENGED → REBUTTED → RESOLVED/ESCALATED。
- 每个辩题最多两轮；Challenge 必须引用 `targetClaimId`，回应必须引用 `targetTurnId`。
- 已收敛可提前结束；无共识、证据不足或超时转人工。

### 1.5 ReviewProtocolGuard ⏳

- 强制四核心角色完成首轮；按需角色最多 3 个，Judge 1 个，总数最多 8。
- P0/P1 无有效证据只能 `UNVERIFIED`，不能自动阻断。
- AI 只能生成 Gate 草案；最终状态只能由人工决定命令产生。
- Guard 提供无副作用 `validate` 和带事件的领域命令入口。

### 1.6 幂等、版本与批量读取契约 ⏳

- 统一幂等键：`reviewId:topicId:round:actorRole:turnType`。
- 聚合使用 version 乐观锁；所有命令包含 expectedVersion。
- Repository 定义批量查询 Claim/Evidence/Turn 接口，禁止循环单查。

## 2. 核心测试矩阵

| 场景                        | 预期                       |
|---------------------------|--------------------------|
| 四核心角色缺一                   | 不允许进入 DEBATING           |
| 第 9 个 Agent 激活            | 返回 AGENT_LIMIT_EXCEEDED  |
| 第 3 轮质询                   | 返回 DEBATE_ROUND_EXCEEDED |
| Challenge 无 targetClaimId | 拒绝且不产生事件                 |
| P1 无 Evidence             | 降为 UNVERIFIED，不自动 BLOCK  |
| Agent 提交最终 Gate           | 拒绝，仅允许 DRAFT             |
| 相同幂等键重复命令                 | 返回首次结果，不重复产生 Turn        |
| expectedVersion 过期        | 返回 VERSION_CONFLICT      |

## 3. 文件清单

### 3.1 新建

| 文件                                                                                 | 计划段       | 状态 |
|------------------------------------------------------------------------------------|-----------|----|
| `src/main/java/ai/cc/chongming/review/domain/model/Review.java`                    | #1.2-1.3  | ⏳  |
| `src/main/java/ai/cc/chongming/review/domain/model/Claim.java`                     | #1.2      | ⏳  |
| `src/main/java/ai/cc/chongming/review/domain/model/DebateTopic.java`               | #1.2、#1.4 | ⏳  |
| `src/main/java/ai/cc/chongming/review/domain/model/GateDecision.java`              | #1.2      | ⏳  |
| `src/main/java/ai/cc/chongming/review/domain/model/ReviewTypes.java`               | #1.1      | ⏳  |
| `src/main/java/ai/cc/chongming/review/domain/protocol/ReviewProtocolGuard.java`    | #1.5      | ⏳  |
| `src/main/java/ai/cc/chongming/review/domain/protocol/ReviewStateMachine.java`     | #1.3      | ⏳  |
| `src/main/java/ai/cc/chongming/review/domain/protocol/DebateStateMachine.java`     | #1.4      | ⏳  |
| `src/main/java/ai/cc/chongming/review/domain/exception/ReviewDomainException.java` | #1.1      | ⏳  |
| `src/main/java/ai/cc/chongming/review/domain/repository/ReviewRepositories.java`   | #1.6      | ⏳  |
| `src/test/java/ai/cc/chongming/review/domain/ReviewStateMachineTests.java`          | #1.3      | ⏳  |
| `src/test/java/ai/cc/chongming/review/domain/DebateStateMachineTests.java`          | #1.4      | ⏳  |
| `src/test/java/ai/cc/chongming/review/domain/ReviewProtocolGuardTests.java`         | #1.5-1.6  | ⏳  |

### 3.2 修改

本计划不修改既有业务文件。

## 4. 实施顺序

1. **步骤 1**：先写错误码、强类型 ID 和状态机失败测试。
2. **步骤 2**：实现最小聚合与合法迁移。
3. **步骤 3**：补齐 Guard 的角色、轮次、证据和 Gate 规则。
4. **步骤 4**：补幂等和版本冲突测试。
5. **步骤 5**：冻结 Repository 与命令接口，发布给并行计划使用。

## 5. 验证与退出标准

- 领域测试无需 Spring Context、MySQL 或模型即可运行。
- 合法路径和全部非法迁移均有参数化测试。
- Guard 规则只存在一份，API、Agent Tool 和 Service 不复制判断。
- 输出一份状态迁移表，供 PLAN-009、010、011 引用。

## 6. 风险与应对

| 风险                   | 应对                                  |
|----------------------|-------------------------------------|
| 单文件 `ReviewTypes` 过大 | 实施时按枚举拆文件，文件表同步更新                   |
| 领域对象被 MyBatis DO 污染  | 领域模型不添加数据库注解，转换留在 infrastructure 层  |
| 未决 P1 策略阻塞           | 把 P1 默认策略做成 `GatePolicy` 配置，保留待决默认值 |

## 7. 变更记录

| 日期         | 变更                       |
|------------|--------------------------|
| 2026-07-14 | 创建领域契约、双状态机、协议守卫和幂等规则计划。 |
| 2026-07-15 | 评审主状态机对齐技术方案：补齐初始评审、冲突检测、分轮辩论、通知与取消状态。 |
