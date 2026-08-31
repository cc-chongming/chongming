# AIREVIEW-PLAN-071 活性重唤醒排除 JUDGE/DIRECTOR

状态：✅ 完成

## 背景
评审 b0959b6d 日志实证：PLAN-060 初审重唤醒遍历全部 roleActivations 仅跳过 initialReviewCompleted，
JUDGE 在激活名单且永不 completed → 每轮 idle 扫描给裁决者发“初审仍未完成”提醒；
裁决者虽按 070 待命句正确待命，但误唤醒浪费模型调用且产生噪音。

## 方案
- [#1] ReviewLivenessGuard.rewakeIncompleteRoles：循环内跳过 roleType==DIRECTOR||JUDGE
  （与 InitialReviewProgressService.requireActiveInitialReviewer 同口径），注释 [AIREVIEW-PLAN-071#1]。
- [#2] 测试：INITIAL_REVIEW idle 且 judge/director 未 completed 时 rewake 不投递二者（断言 adapter.send 标签不含 -judge/-director）。

## 变更记录
- 2026-08-31 立计划；派发后台子代理实施。
- 2026-08-31 子代理 760d2046 交付；定向+全量回归绿；提交。
