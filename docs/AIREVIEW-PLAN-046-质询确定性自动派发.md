# AIREVIEW-PLAN-046 质询确定性自动派发

> **状态**: ✅ 全部完成
> **创建日期**: 2026-08-27
> **目标**: 质询（CHALLENGE）由服务端确定性派发，与答辩信封（REBUTTAL）自动签发机制对称，对辩链条不再依赖协调者模型自觉。

## 背景

- 用户批注：立场对立议题（支持+反对）只发生了产品经理答辩，没有对辩。取证（评审 48277fac 日志）：协调者 5 次派发全是 DEFENSE→PRODUCT，0 次 CHALLENGE；此前评审（feea9306）同样 0 次。
- 机制现状：质询依赖协调者模型派发（提示词仅对异议答辩议题给了三步指令，立场对立议题无专门指令）；质询落库后服务端已自动签发答辩信封（ReviewWorkflowDispatcher.issueRebuttalDispatch，幂等、TTL、失败容忍）。
- 关键勘察：`registerTopicsLocked` 开题即把阶段从 CONFLICT_DETECTION 推进到 DEBATE_ROUND_1（DebateService 约 165 行），因此 `DEBATE_TOPIC_OPENED` 处理时评审已处于辩论轮——派发时机成立。

## 分段方案

### 段 1：服务端质询派发（ReviewWorkflowDispatcher）

**统一规则（两个触发点，同一派发函数）**：对某议题，当其**至少含一条 SUPPORT 与一条 OPPOSE**、且该议题**尚无任何 CHALLENGE 回合**、评审处于辩论轮阶段时，向每个持有 OPPOSE Claim 的角色（去重、跳过 SUPPORT 目标角色自身）派发 `CHALLENGE` 命令，targetClaim = 该议题严重度最高（P0 优先）的一条 SUPPORT。
- 触发点 A：`DEBATE_TOPIC_OPENED`（立场对立议题开题即派发）；
- 触发点 B：`CLAIM_SUBMITTED`（stage=DEBATE_ROUND_*；落库 Claim 为 SUPPORT 且所属议题首次集齐支持/反对双方——异议答辩议题的答辩支持落库后触发；议题经 PLAN-040 挂载）。
- 幂等：idempotencyKey = `dispatch:challenge:{topicId}:{recipient}`（与议题+收件人绑定，不含 claimId，避免多条 SUPPORT 重复派发；重复触发由 ReviewDispatchService 幂等/去重兜底）。
- 失败容忍：单条派发异常仅记日志（沿用 REBUTTAL_DISPATCH_ISSUE_FAILED 模式），不阻断事件分发。
- 跳过条件：议题已有 CHALLENGE turn（含协调者手动派发的兼容）、评审非辩论轮阶段、收件人与目标 SUPPORT 角色相同。

**提示词同步（ReviewDirectorHarnessFactory.directorPrompt）**：
- 改写三步指令：异议答辩议题仍由协调者派发 DEFENSE；**质询改由服务端在开题/答辩支持落库时自动派发，协调者不得再派发 CHALLENGE**；答辩信封仍由服务端签发；
- 立场对立议题说明：开题后服务端自动向质疑方派发质询，协调者负责后续推进与收敛；
- `DEBATE_TOPIC_OPENED` 唤醒语同步微调（提及服务端自动质询）。

**涉及文件**：
- 修改：`src/main/java/ai/cc/chongming/review/infrastructure/agentscope/ReviewWorkflowDispatcher.java`
- 修改：`src/main/java/ai/cc/chongming/review/infrastructure/agentscope/ReviewDirectorHarnessFactory.java`（仅提示词文案）
- 新增/修改：对应单测

## 文件清单

### 修改

| 文件 | 计划段 | 状态 |
|------|--------|------|
| `ReviewWorkflowDispatcher.java` | #1 | ⏳ |
| `ReviewDirectorHarnessFactory.java` | #1 | ⏳ |
| 对应单测（调度器质询派发） | #1 | ⏳ |

## 实施顺序

1. **步骤 1** ✅ → 后端子代理实施段 1 + 全量回归 762/0/30（+6 新用例）。
2. **步骤 2** ✅ → 独立审查通过（统一派发规则、首支持闸门、幂等键、TTL 20 分钟、ClaimSeverity 序确认、提示词两拓扑口径一致）；提交推送；需 IDEA 重启生效。

## 风险与应对

- **风险**：开题事件处理与阶段推进的时序 → 已勘察：注册在同一聚合同步块内完成转移，派发在事件回调中执行，命令消费时校验的是届时的评审阶段（DEBATE_ROUND_1）。
- **风险**：协调者仍手动派发 CHALLENGE 造成双份质询 → 派发前检查议题既有 CHALLENGE turn；提示词明令禁止；即便发生，质询本身不破坏数据。
- **风险**：TTL 内无人消费 → 沿用既有派发 TTL/到期机制，到期事件会唤醒协调者善后（与现状一致）。
- **风险**：多 SUPPORT 议题目标选择 → 取严重度最高 SUPPORT（P0>P1>P2>P3，同级取先出现），质询针对需求立场主论点。

## 变更记录

- 2026-08-27：创建计划，派发后端实施子代理（用户选择方案 1：服务端确定性）。
- 2026-08-27：段 1 交付并通过独立审查。实现要点：触发 A（DEBATE_TOPIC_OPENED）+ 触发 B（CLAIM_SUBMITTED 首支持）统一走 issueChallengeDispatches；幂等键 `dispatch:challenge:{topicId}:{recipient}`；target=最高严重度非撤回 SUPPORT；跳过条件齐备（终态议题/已有 CHALLENGE turn/非辩论轮/无 OPPOSE 角色）；CLAIM_SUBMITTED 分支不额外唤醒（CHALLENGE_SUBMITTED 自然唤醒）。领域 API 实际命名：Claim.roleType()/position()、DebateTurn.turnType()。
