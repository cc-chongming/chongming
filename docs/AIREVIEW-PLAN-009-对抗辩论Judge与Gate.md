# 对抗辩论、Judge 与 Gate 计划

> **状态**: ⏳ 待实施
> **创建日期**: 2026-07-14
> **目标**: 把多 Agent 辩论实现为强类型、证据驱动、两轮受限且可回放的领域协议，并产出可人工确认的 Gate 草案。
> **前置计划**: PLAN-003、PLAN-004、PLAN-006、PLAN-007、PLAN-008

## 0. 背景与边界

辩论不能只是多个角色分别写报告。每次质询必须指向真实 Claim，每次回应必须指向对应 Turn，并可观察保持、让步或改变立场。
主持人负责选择辩题和发言顺序，ReviewProtocolGuard 决定动作是否合法。

## 1. 分段方案

### 1.1 Claim 提交与发布 ⏳

- `submitClaim` 接收 subjectKey、position、severity、statement、reasonSummary、evidenceIds。
- 校验角色、阶段、证据归属和幂等；P0/P1 无证据标为 UNVERIFIED。
- 首轮独立提交完成后再公开给其他角色。

### 1.2 ConflictDetector ⏳

- 规则发现相同 subjectKey 的相反立场、严重度冲突、互斥方案、依赖矛盾。
- 规则先召回候选，模型只做排序/摘要；最终 topic 仍引用真实 claimIds。
- 输出候选分数、规则命中和无冲突原因，支持人工补开辩题。

### 1.3 DebateTools 强类型接口 ⏳

- `openDebateTopic`、`submitChallenge`、`submitRebuttal`、`changePosition`。
- `requestAdditionalEvidence`、`submitJudgement`。
- 每个命令统一 actorRole、targetRole、topicId、round、expectedVersion、idempotencyKey。

### 1.4 第一轮定向质询 ⏳

- 主持人选择冲突最大的 Claim，向具体角色 `agent_send`。
- Challenge 必须包含 targetClaimId 和 evidenceIds/明确证据缺口。
- 目标角色同步回应，确保 UI 展示有顺序的真实交锋。

### 1.5 反驳、补证与立场变化 ⏳

- Rebuttal 指向 targetTurnId，可维持原立场、部分让步、完全改变或请求补证。
- `changePosition` 记录 before/after、reason、evidence，不覆盖旧 Claim。
- 补证后创建新 Evidence/Claim version，并保留辩论引用链。

### 1.6 第二轮与收敛 ⏳

- 已收敛提前结束；未收敛且未超限进入第二轮。
- 第二轮不得重复第一轮同内容；无新证据/新论点可由 Guard 拒绝无效争论。
- 两轮后仍无共识、超时或证据不足则 ESCALATED。

### 1.7 Judge 裁决 ⏳

- Judge 输入只含不可变 Claim、Evidence、Turn 和冲突规则，不新增事实。
- 输出采信 Claim、拒绝理由、剩余不确定性、是否需人工、对 Gate 的建议。
- `submitJudgement` 经 EvidenceValidator 和 Guard 后入库。

### 1.8 GatePolicy 与草案 ⏳

- 聚合 P0/P1、UNVERIFIED、Judge 结论、核心角色失败和规则命中。
- 输出 AI_PASS/CONDITIONAL/BLOCK/RETURN/HUMAN_REQUIRED 草案，不形成最终状态。
- P1 默认 Gate 与 override 权限做配置策略；未确认前默认 HUMAN_REQUIRED。

## 2. DebateTools 接口草案

```text
submitClaim(command) -> ClaimResult
openDebateTopic(command) -> DebateTopicResult
submitChallenge(command) -> DebateTurnResult
submitRebuttal(command) -> DebateTurnResult
changePosition(command) -> ClaimVersionResult
requestAdditionalEvidence(command) -> EvidenceRequestResult
submitJudgement(command) -> JudgeDecisionResult
```

## 3. 文件清单

### 3.1 新建

| 文件                                                                                            | 计划段      | 状态 |
|-----------------------------------------------------------------------------------------------|----------|----|
| `src/main/java/ai/cc/chongming/review/application/ClaimService.java`                          | #1.1     | ⏳  |
| `src/main/java/ai/cc/chongming/review/domain/debate/ConflictDetector.java`                    | #1.2     | ⏳  |
| `src/main/java/ai/cc/chongming/review/application/DebateService.java`                         | #1.3-1.6 | ⏳  |
| `src/main/java/ai/cc/chongming/review/application/JudgeService.java`                          | #1.7     | ⏳  |
| `src/main/java/ai/cc/chongming/review/domain/gate/GatePolicy.java`                            | #1.8     | ⏳  |
| `src/main/java/ai/cc/chongming/review/infrastructure/agentscope/tool/DebateTools.java`        | #1.3     | ⏳  |
| `src/main/java/ai/cc/chongming/review/infrastructure/agentscope/tool/DebateToolCommands.java` | #1.3     | ⏳  |
| `src/test/java/ai/cc/chongming/review/debate/ClaimServiceTest.java`                           | #1.1     | ⏳  |
| `src/test/java/ai/cc/chongming/review/debate/ConflictDetectorTest.java`                       | #1.2     | ⏳  |
| `src/test/java/ai/cc/chongming/review/debate/DebateToolsContractTest.java`                    | #1.3-1.6 | ⏳  |
| `src/test/java/ai/cc/chongming/review/debate/JudgeServiceTest.java`                           | #1.7     | ⏳  |
| `src/test/java/ai/cc/chongming/review/debate/GatePolicyTest.java`                             | #1.8     | ⏳  |
| `src/test/java/ai/cc/chongming/review/debate/DebateGoldenPathIntegrationTest.java`            | #1.1-1.8 | ⏳  |

### 3.2 修改

| 文件                                                                                 | 计划段      | 状态 |
|------------------------------------------------------------------------------------|----------|----|
| `src/main/java/ai/cc/chongming/review/application/ReviewOrchestrationService.java` | #1.4-1.7 | ⏳  |
| `src/main/resources/application.yml`                                               | #1.8     | ⏳  |

## 4. 实施顺序

1. **步骤 1**：先写 Claim 与 DebateTools 全部非法动作测试。
2. **步骤 2**：实现 Claim、冲突候选和开题。
3. **步骤 3**：实现第一轮 Challenge/Rebuttal 和立场变化。
4. **步骤 4**：实现第二轮限制、补证和收敛。
5. **步骤 5**：实现 Judge 与 GatePolicy。
6. **步骤 6**：使用 MockModel 跑完整黄金链路并归档事件。

## 5. 验证与退出标准

- Demo 样本至少有两个相反/互斥 Claim、一次定向质询、一次证据反驳、一次立场结果、一次 Judge。
- 从 Gate 草案可反向追溯到 Judge → Turn → Claim → Evidence。
- 伪造 ID、跨 review 引用、越权角色、越轮和重复幂等提交全部拒绝。
- 两轮上限由 Guard 而非 Prompt 保证。
- 报告装配批量加载，无 Claim/Evidence/Turn N+1。

## 6. 风险与应对

| 风险         | 应对                                                |
|------------|---------------------------------------------------|
| 模型制造虚假冲突   | ConflictDetector 只对已落库 Claim 建 topic，Evidence 再校验 |
| 辩论变成重复文本   | 第二轮要求新证据/新论点，Guard 可拒绝无增量 Turn                    |
| Judge 权力过大 | Judge 无仓库工具、不能新增 Claim，AI 只输出 Gate 草案             |

## 7. 变更记录

| 日期         | 变更                                    |
|------------|---------------------------------------|
| 2026-07-14 | 创建 Claim、冲突检测、两轮辩论、Judge 和 Gate 草案计划。 |
