# AIREVIEW-PLAN-040 答辩支持方Claim挂载议题

> **状态**: ✅ 全部完成
> **创建日期**: 2026-08-27
> **目标**: 答辩人提交的 SUPPORT 防御 Claim 挂载到所属议题，辩论法庭支持方不再恒为空；存量进行中的评审无需迁移即可看到支持方。

## 背景

- 用户批注（多轮辩论页，议题 review.human_required_handoff）：“有支持的 claim 但是左边没有”——法庭支持方显示“暂无支持方 Claim”，质疑方有 8 条。
- 现状勘察（根因）：
  - 法庭数据链：`partitionClaimsByPosition(allClaims)`，`allClaims = debateTopics.flatMap(topic.claims)`；`DebateView.claims` 仅取 `topic.claimIds()`（`ReviewQueryService.toDebateView`，第 388 行）。
  - `topic.claimIds()` 只在开题时由冲突检测提案写入；`ClaimService.submitSynchronized`（第 78-114 行）在辩论轮接受 DEFENSE Claim：校验派发命令与 subjectKey 一致后 `debateStore.saveClaim` + `consumeDefenseCommand`，**从不把新 Claim 附加到议题**；`DebateTopic` 也没有相应 mutator。
  - 结论：答辩人的 SUPPORT 答辩 Claim 落库了，但法庭永远看不到 → 支持方恒空。
- 已有基础：`ReviewDebateStore.saveTopic/findTopic`（InMemory 与 MyBatis 双实现）齐备。

## 分段方案

### 段 1：写路径挂载 + 读路径自愈

**涉及文件**：
- 修改：`src/main/java/ai/cc/chongming/review/domain/model/DebateTopic.java`（新增幂等 `attachClaim(ClaimId)`）
- 修改：`src/main/java/ai/cc/chongming/review/application/ClaimService.java`（DEFENSE 落库后挂载议题并保存）
- 修改：`src/main/java/ai/cc/chongming/review/application/ReviewQueryService.java`（`toDebateView` 同 subjectKey 补全，治愈存量）
- 新增/修改：对应单测

**关键实现细节**：
- `DebateTopic.attachClaim(ClaimId)`：已存在则直接返回（幂等）；否则追加；不触碰状态机。
- `ClaimService.submitSynchronized`：`consumeDefenseCommand` 之后，若 `defenseCommand != null`：`debateStore.findTopic(review.id(), defenseCommand.topicId())` → `attachClaim(claim.claimId())` → `debateStore.saveTopic(topic)`；议题缺失时记警告不阻断（命令校验阶段已验过议题存在）。
- `toDebateView` 读路径补全：将 `claimsById` 中 subjectKey 与议题 subjectKey `equalsIgnoreCase` 相等、且不在 `topic.claimIds()` 中、状态非 WITHDRAWN 的 Claim 追加到视图 claims 尾部（保持议题原始成员在前）。该补全同时治愈本计划上线前已提交但未挂载的存量答辩 Claim，无需数据迁移。
- 前端无需改动：法庭/冲突视图均消费 `topic.claims`。

## 文件清单

### 修改

| 文件 | 计划段 | 状态 |
|------|--------|------|
| `src/main/java/ai/cc/chongming/review/domain/model/DebateTopic.java` | #1 | ✅ |
| `src/main/java/ai/cc/chongming/review/application/ClaimService.java` | #1 | ✅ |
| `src/main/java/ai/cc/chongming/review/application/ReviewQueryService.java` | #1 | ✅ |
| 对应单测（领域模型/ClaimService/查询投影） | #1 | ✅ |

## 实施顺序

1. **步骤 1** ✅ → 后端子代理实现 + 写单测并自跑全量回归（前序子代理已结束，构建目录空闲）：750/0/30 绿（净新增 4 个测试方法 + 1 处既有用例增强断言）。
2. **步骤 2** ✅ → 父级独立审查通过；用实时 API/日志取证确认根因（评审 #feea9306：4 条 DEFENSE 派发全部消费、PRODUCT 4 条 SUPPORT 答辩落库但从未挂载议题，与读路径补全条件完全吻合）。
3. **步骤 3** ✅ → 与 PLAN-038/039 合并提交推送；需 IDEA 重启生效，读路径补全使存量评审无需重跑。

## 风险与应对

- **风险**：读路径补全把无关同主题 Claim 拉进议题视图 → 补全限定 subjectKey 忽略大小写相等 + 排除 WITHDRAWN，且冲突检测本就按 subject 归组，口径一致。
- **风险**：重复挂载（重试/幂等命令）→ `attachClaim` 幂等；Claim 提交本身有 idempotencyKey 幂等。
- **风险**：并发构建互斥 → 本子代理不跑 Maven，统一在集成时回归。

## 变更记录

- 2026-08-27：创建计划，派发后端实施子代理。
- 2026-08-27：段 1 交付并通过独立审查，全部完成。取证记录：日志 17:25:38 director `dispatch_debate_action`×4 SUCCESS → 17:27:04–17:28:19 `DISPATCH_COMMAND_CONSUMED recipient=PRODUCT action=DEFENSE reason=WRITE_ACTION_COMMITTED`×4；PRODUCT 的 4 条 SUPPORT 答辩 subjectKey 与 4 个议题一一对应，读路径补全即时可见。
