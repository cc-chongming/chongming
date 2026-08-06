# 对抗辩论、Judge 与 Gate 计划

> **状态**: 🟡 可提交的领域协议、工具门面、自动化验证及受限 AgentScope 工具注册已完成；生产 MySQL 命令写入、跨进程恢复和多辩题编排待后续计划。
> **创建日期**: 2026-07-14
> **目标**: 把多 Agent 辩论实现为强类型、证据驱动、两轮受限且可回放的领域协议，并产出可人工确认的 Gate 草案。
> **前置计划**: PLAN-003、PLAN-004、PLAN-006、PLAN-007、PLAN-008

## 0. 背景与边界

辩论不能只是多个角色分别写报告。每次质询必须指向真实 Claim，每次回应必须指向对应 Turn，并可观察保持、让步或改变立场。
主持人负责选择辩题和发言顺序，ReviewProtocolGuard 决定动作是否合法。

## 1. 分段方案

### 1.1 Claim 提交与发布 ✅

- `submitClaim` 接收 subjectKey、position、severity、statement、reasonSummary、evidenceIds。
- 校验角色、阶段、证据归属和幂等；P0/P1 无证据标为 UNVERIFIED。
- 首轮独立提交完成后再公开给其他角色。

### 1.2 ConflictDetector ✅

- 规则发现相同 subjectKey 的相反立场、严重度冲突、互斥方案、依赖矛盾。
- 规则先召回候选，模型只做排序/摘要；最终 topic 仍引用真实 claimIds。
- 输出候选分数、规则命中和无冲突原因，支持人工补开辩题。

### 1.3 DebateTools 强类型接口 ✅

- `openDebateTopic`、`submitChallenge`、`submitRebuttal`、`changePosition`。
- `requestAdditionalEvidence`、`submitJudgement`。
- 每个命令统一 actorRole、targetRole、topicId、round、expectedVersion、idempotencyKey。

### 1.4 第一轮定向质询 ✅

- 主持人选择冲突最大的 Claim，向具体角色 `agent_send`。
- Challenge 必须包含 targetClaimId 和 evidenceIds/明确证据缺口。
- 目标角色同步回应，确保 UI 展示有顺序的真实交锋。

### 1.5 反驳、补证与立场变化 ✅

- Rebuttal 指向 targetTurnId，可维持原立场、部分让步、完全改变或请求补证。
- `changePosition` 记录 before/after、reason、evidence，不覆盖旧 Claim。
- 补证后创建新 Evidence/Claim version，并保留辩论引用链。

### 1.6 第二轮与收敛 ✅

- 已收敛提前结束；未收敛且未超限进入第二轮。
- 第二轮不得重复第一轮同内容；无新证据/新论点可由 Guard 拒绝无效争论。
- 两轮后仍无共识、超时或证据不足则 ESCALATED。

### 1.7 Judge 裁决 ✅

- Judge 输入只含不可变 Claim、Evidence、Turn 和冲突规则，不新增事实。
- 输出采信 Claim、拒绝理由、剩余不确定性、是否需人工、对 Gate 的建议。
- `submitJudgement` 经 EvidenceValidator 和 Guard 后入库。

### 1.8 GatePolicy 与草案 ✅

- 聚合 P0/P1、UNVERIFIED、Judge 结论、核心角色失败和规则命中。
- 输出 AI_PASS/CONDITIONAL/BLOCK/RETURN/HUMAN_REQUIRED 草案，不形成最终状态；HUMAN_REQUIRED 触发 WAITING_HUMAN。
- 人工最终决定使用 PASS/CONDITIONAL/BLOCK/RETURN/OVERRIDE，与 AI 草案枚举分离。
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
| `src/main/java/ai/cc/chongming/review/application/ClaimService.java`                          | #1.1     | ✅  |
| src/main/java/ai/cc/chongming/review/domain/repository/ReviewDebateStore.java                   | #1.1-1.8 | ✅  |
| src/main/java/ai/cc/chongming/review/infrastructure/debate/InMemoryReviewDebateStore.java            | #1.1-1.8 | ✅  |
| src/main/java/ai/cc/chongming/review/config/ReviewGateProperties.java                                | #1.8     | ✅  |
| `src/main/java/ai/cc/chongming/review/domain/debate/ConflictDetector.java`                    | #1.2     | ✅  |
| `src/main/java/ai/cc/chongming/review/application/DebateService.java`                         | #1.3-1.6 | ✅  |
| `src/main/java/ai/cc/chongming/review/application/JudgeService.java`                          | #1.7     | ✅  |
| `src/main/java/ai/cc/chongming/review/domain/gate/GatePolicy.java`                            | #1.8     | ✅  |
| `src/main/java/ai/cc/chongming/review/infrastructure/agentscope/tool/DebateTools.java`        | #1.3     | ✅  |
| `src/main/java/ai/cc/chongming/review/infrastructure/agentscope/tool/DebateToolCommands.java` | #1.3     | ✅  |
| `src/test/java/ai/cc/chongming/review/debate/ClaimServiceTests.java`                           | #1.1     | ✅  |
| `src/test/java/ai/cc/chongming/review/debate/ConflictDetectorTests.java`                       | #1.2     | ✅  |
| `src/test/java/ai/cc/chongming/review/debate/DebateToolsContractTests.java`                    | #1.3-1.6 | ✅  |
| `src/test/java/ai/cc/chongming/review/debate/JudgeServiceTests.java`                           | #1.7     | ✅  |
| `src/test/java/ai/cc/chongming/review/debate/GatePolicyTests.java`                             | #1.8     | ✅  |
| `src/test/java/ai/cc/chongming/review/debate/DebateGoldenPathIntegrationTests.java`            | #1.1-1.8 | ✅  |

### 3.2 修改

| 文件                                                                                 | 计划段      | 状态 |
|------------------------------------------------------------------------------------|----------|----|
| `src/main/java/ai/cc/chongming/review/application/ReviewOrchestrationService.java` | #1.4-1.7 | ⏸  |
| `src/main/java/ai/cc/chongming/review/domain/model/Review.java`                       | #1.1     | ✅  |
| src/main/java/ai/cc/chongming/review/domain/model/ReviewTypes.java                  | #1.3-1.7 | ✅  |
| src/main/java/ai/cc/chongming/review/domain/gate/GatePolicy.java                      | #1.8     | ✅  |
| src/main/java/ai/cc/chongming/review/config/DebateProtocolConfiguration.java           | #1.3-1.8 | ✅  |
| src/main/resources/application.yml                                               | #1.8     | ✅  |

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

## 6.1 已实现范围与部署边界

- `ClaimService` 只接受已激活角色在 `INITIAL_REVIEW` 阶段提交的 Claim；P0/P1 缺证据降为 `UNVERIFIED`。Claim 提交会标记该角色首审完成，四个核心角色全部完成前不能发布首轮 Claim。
- `ConflictDetector` 对同一 `subjectKey` 的相反立场、相差至少两级的严重度，以及同一 Evidence 被相反立场引用时生成稳定候选及无冲突原因；主持人仍只能以真实 `claimIds` 开题。
- `DebateTools` 门面包含 Claim、开题、定向质询/反驳、立场变化、补证请求、Judge 和 Gate 草案。第二轮不得原样重复第一轮质询；补证请求只记录缺口，不会伪造 Evidence。
- `JudgeService` 只能采信或拒绝该终态辩题已有的 Claim；Gate 草案必须等待每个辩题都有 Judge 结论。`HUMAN_REQUIRED` 会使 Review 从 `JUDGING` 进入 `WAITING_HUMAN`，AI 永不写最终 Gate。
- `InMemoryReviewDebateStore` 是当前假数据库配置下的可测试默认实现。实际部署前必须以 MyBatis 事务实现同一接口，并将 Review 版本、幂等命令、Claim/Turn/Judge/Gate 写入同一事务；首轮以及后续 Debate/Judge/Gate 工具均已真实注册到 AgentScope Harness。`ReviewWorkflowDispatcher` 仅在正式业务事件提交后按运行时串行唤醒 Director、角色或 Judge，不能替代持久化恢复队列。
- 技术方案的状态机保持 `DEBATE_ROUND_1 → DEBATE_ROUND_2 → JUDGING`。辩题可在第一轮关闭；编排层仍需经过无新 Turn 的第二轮阶段标记后进入 Judge，避免绕过固定状态机。

## 6.2 验证证据

- 聚焦协议测试：9 个测试通过（Claim、冲突候选、工具契约、两轮链路、Judge、Gate）。
- 完整验证：`./mvnw.cmd test` 通过，98 个测试通过、0 失败、0 错误、3 个 Docker/Testcontainers MySQL 用例因本机无 Docker 自动跳过。
- 详见 `docs/验证记录/DebateJudgeGateReport.md`。
## 7. 变更记录

| 日期         | 变更                                    |
|------------|---------------------------------------|
| 2026-07-14 | 创建 Claim、冲突检测、两轮辩论、Judge 和 Gate 草案计划。 |
| 2026-07-16 | 完成强类型 Claim/辩论/Judge/Gate 协议、配置化 P1 Gate、工具契约和全量回归；标注生产持久化与运行时注册边界。 |

| 2026-08-06 | 修复"存在反对意见却总跳过辩论"的流程缺陷：`DebateService.hasConflictingClaimPositions` 原判定要求**同一 subjectKey 完全一致**下存在立场对立才算冲突，而真实评审中各角色反对意见通常挂在各自主题键下，导致守卫把有反对意见误判为"无冲突"，Director 走 `skip_debate_when_no_conflicts` 直接进入 JUDGING（事件时间线出现 DEBATE_SKIPPED / NO_CONFLICTING_CLAIM_POSITIONS）。现放宽为**存在任一未撤回的 OPPOSE Claim 即视为冲突**，同步调整 Director 系统提示与 `ReviewWorkflowDispatcher` 唤醒消息（存在 OPPOSE 即开议题，否则才 skip），并新增"有 OPPOSE 时 skip 被拒"测试、修正"无冲突跳过"测试用全 SUPPORT 输入。debate 包 13/13、application/agentscope 相关 25/25 通过。 |

| 2026-08-06 | 通过运行日志（REVIEW_WORKFLOW_DISPATCH_FAILED / DIRECTOR_INCOMPLETE）确认"存在反对却走不了冲突检测"的第二个根因：`open_debate_topic` 原强制要求所选 Claim 的 subjectKey 与传入值完全一致，而真实评审中 15 条 OPPOSE Claim 的主题键各不相同 → Director 反复穷举参数仍被拒（异常被吞成 "review workflow tool rejected"，模型无法诊断）→ 3 分钟兜底后 `REVIEW_FAILED`（failureType=DIRECTOR_CONFLICT_INCOMPLETE）。修复：①`DebateService.openTopic` 允许聚合跨 subjectKey 的反对 Claim（所选全部同主题时用 Claim 主题键，跨主题时用 Director 提供的主题键命名议题）；②`ReviewDebateToolFactory.BoundTool` 将具体拒绝原因（errorCode + message）返回给模型并记日志 `REVIEW_WORKFLOW_TOOL_REJECTED`，不再吞成通用提示。debate 包 14/14、相关回归通过；需重启后端并以新评审验证。 |

| 2026-08-06 | 修复辩论共识度恒为 0% 的引导问题：Director 开辩论议题时只纳入反对方（OPPOSE）Claim、未纳入支持方（SUPPORT）Claim，议题内支持方为空，而 live 共识度=支持 Claim 占比，故恒为 0%。修复：①Director 系统提示明确"每个议题必须同时包含支持方与反对方 Claim，禁止只放单方"；②`open_debate_topic` 工具描述同步要求双方向对阵；③live 辩论区在议题仅含反对方时给出"暂无支持方"提示，避免 0% 造成误解。debate 包 14/14、前端 21/21、build 通过，static/review 同步 index-Cx7yt8YI.js；需重启后端并以新评审验证。 |
