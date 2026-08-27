# AIREVIEW-PLAN-042 答辩Claim汇入辩论对话流

> **状态**: ✅ 全部完成
> **创建日期**: 2026-08-27
> **目标**: 多轮辩论页“辩论对话流”除质询/答辩回合外，同步展示答辩人以 SUPPORT Claim 形式提交的答辩，避免异议答辩型议题对话流恒空。

## 背景

- 对话流现状：`roundTurns` 只来自 `topic.turns`（CHALLENGE/REBUTTAL/EVIDENCE/POSITION_SHIFT）。异议答辩型议题（仅反对方）中，答辩人以 SUPPORT Claim 回应（不走 turn），对话流恒空——用户批注“也展示出来”。
- 数据基础（已勘察）：每次提交都发 `CLAIM_SUBMITTED` 领域事件，EventView 携带 `claimId` 与 `stage`；答辩 Claim 提交阶段必为 `DEBATE_ROUND_1/2`（ClaimService 闸门保证），初审提交为 `INITIAL_REVIEW`。`store.events` 已含全部事件；议题成员经 PLAN-040 已含挂载的答辩 Claim。
- 结论：无需后端改动，前端以“事件定来源（辩论轮提交=答辩）+ 议题成员定归属”合成对话流条目。

## 分段方案

### 段 1：答辩 Claim 合成对话流条目

**涉及文件**：
- 新建：`frontend/src/services/review-debate-presenter.js` + 单测
- 修改：`frontend/src/views/ReviewLiveView.vue`

**关键实现细节**：
- 纯函数 `buildDefenseTurns(debates, events)`：
  - 由事件构建 `答辩Claim集合`：type==='CLAIM_SUBMITTED' 且 stage 以 'DEBATE_ROUND' 开头，claimId → round（DEBATE_ROUND_1→1，DEBATE_ROUND_2→2）；
  - 遍历各议题 `topic.claims`，claimId 命中集合者输出合成条目：`{ turnId: claim.claimId, actorRole: claim.role, type: 'REBUTTAL', round, subject: topic.subjectKey, content: claim.statement, severity: claim.severity }`（无 targetRole/stance 字段）；
  - 输出按 round 稳定排序；对缺失字段健壮。
- SFC：`roundTurns` 追加合成条目（与既有 turn 合并后按 selectedRound 过滤）；渲染沿用现有 `flow-dialogue-list` 行结构，`turnTypeLabel('REBUTTAL')` 天然显示“🛡️ 答辩”；合成条目无立场变化行。
- 不改后端、不改 turn 读模型。

## 文件清单

### 新建

| 文件 | 计划段 | 状态 |
|------|--------|------|
| `frontend/src/services/review-debate-presenter.js` | #1 | ✅ |
| `frontend/src/services/review-debate-presenter.test.js` | #1 | ✅ |

### 修改

| 文件 | 计划段 | 状态 |
|------|--------|------|
| `frontend/src/views/ReviewLiveView.vue` | #1 | ✅ |

## 实施顺序

1. **步骤 1** ✅ → 前端子代理实施段 1 + vitest 回归（158 绿）；不构建、不提交。
2. **步骤 2** ✅ → 独立审查通过（事件来源甄别、议题成员归属、claimId 去重、round 稳定排序、无 stance 字段自然降级）；构建产物 index-CScAsMTS.js 同步 `target/classes/static/review` 并验证 200。
3. **步骤 3** ✅ → 提交推送。

## 风险与应对

- **风险**：合成条目与真实 REBUTTAL turn 混淆 → 两者语义同为“答辩”，展示一致可接受；合成条目无立场变化信息，不渲染该行。
- **风险**：跨议题混排 → 沿用现有对话流“全议题按轮展开”口径（subject 已随行显示），保持一致。
- **风险**：回放事件缺失导致答辩条目缺失 → 与事实流同源，不额外补偿（与既有展示口径一致）。

## 变更记录

- 2026-08-27：创建计划，派发前端实施子代理。
- 2026-08-27：段 1 交付并通过独立审查，全部完成，无契约偏差。评审 #feea9306 的 4 条答辩（stage=DEBATE_ROUND_1）将在“辩论对话流 · R1”显示为 🛡️ 答辩。
