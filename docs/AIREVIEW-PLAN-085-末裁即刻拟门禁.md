# AIREVIEW-PLAN-085 末裁即刻拟门禁：JUDGEMENT_SUBMITTED 全裁且无门禁即确定性 draftGate

状态：✅ 完成

## 背景
评审 1e0759c7：裁决者 14:54 过早 draft_gate 被拒后，门禁拟制无确定性触发者，
空窗约 1 小时直到裁决者再被唤醒。GATE_DRAFTED 在白名单内，客户端无责。

## 方案
- [#1] ReviewWorkflowDispatcher.onCommitted 的 JUDGEMENT_SUBMITTED 分支：
  若 review.stage()==JUDGING 且 debateStore.findTopics 全有 judgement 且 debateStore.findGateDraft 为空
  → judgeService.draftGate(review)（GatePolicy 确定性草案）；ObjectProvider<JudgeService> 注入；
  try/catch 仅 WARN；日志 GATE_DRAFT_ON_LAST_JUDGEMENT。
- [#2] 裁决者后续自身 draft_gate 走既有幂等/拒绝路径，不重复拟制。
- [#3] 测试：末裁事件+无门禁→draftGate 调用；已有门禁→never；非末裁→never。

## 变更记录
- 2026-08-31 立计划；等 084 交付后派发。
- 2026-08-31 子代理 e10dbdfb 交付；3 新用例；回归 852 全绿；提交。
