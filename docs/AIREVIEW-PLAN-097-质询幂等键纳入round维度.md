# AIREVIEW-PLAN-097 质询幂等键纳入 round 维度

## 背景 / 现象
评审 40558999-435c-4bad-9902-ab1b4f14255d、议题 d292e6d0：R1 完成（BACKEND CHALLENGE →
PRODUCT REBUTTAL）后协调者调用 begin_second_debate_round 开启 R2，但 BACKEND / PRODUCT
均未收到第二轮信封，全程静默；协调者空等后自述「两轮交锋均已完成」并关闭 RESOLVED——
第二轮实际从未发生。用户侧表现：开启第二轮后看不到任何角色收到派发通知。

## 根因（日志 + 代码双重证据）
- 服务端质询队列已挂钩 DEBATE_ROUND_2_STARTED（ReviewWorkflowDispatcher 事件路由 →
  advanceChallengeQueue），队列核心也支持 round=2（challengedThisRound 按 round 过滤）。
- 但 CHALLENGE 幂等键为 `dispatch:challenge:{topicId}:{recipient}`，**缺 round 维度**；
  ReviewDispatchService.issue() 命中同键直接返回旧命令（replayed=true，无日志），
  R2 的 CHALLENGE 被当作 R1 命令的「重放」静默吞掉。
- REBUTTAL 键绑定 challengeTurnId（新一轮新 turn 自然新键），不受影响；问题仅在 CHALLENGE 键。
- 日志证据：R2 开启（约 10:23:37）后 d292e6d0 既无 DISPATCH_COMMAND_ISSUED 也无
  CHALLENGE_QUEUE_SKIPPED——pump 走到 issue() 后被幂等去重无声返回。

## 变更
- [AIREVIEW-PLAN-097#1] `ReviewWorkflowDispatcher.java` 质询签发幂等键纳入 round：
  `dispatch:challenge:{topicId}:r{round}:{recipient}`。同轮多次触发仍幂等，跨轮不再互吞。
- [AIREVIEW-PLAN-097#2] 回归测试（ReviewWorkflowDispatcherTests）：R1 完成（同一 OPPOSE 角色
  已有 round=1 CHALLENGE turn）后 beginTopicSecondRound 发出 DEBATE_ROUND_2_STARTED，
  队列必须向同一 OPPOSE 角色签发第二条 PENDING CHALLENGE（round=2），不被 R1 幂等键吞掉。

## 验收
- 新回归测试绿；后端全量套件回到基线（约 854 通过 / 0 失败 / 30 跳过）。
