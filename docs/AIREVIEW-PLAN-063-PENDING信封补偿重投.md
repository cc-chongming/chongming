# AIREVIEW-PLAN-063 PENDING 信封补偿重投：被拒一次不再等于永久沉默

状态：✅ 完成

## 背景
日志取证（评审 399d2994）：PROJECT 的 CHALLENGE 信封送达后其 turn 在 15:53:53 调用
submit_challenge，因议题刚被 BACKEND 置为 CHALLENGED 被状态机拒绝；PROJECT 结束 turn 不重试；
15:54:24 议题变 REBUTTED（CHALLENGE 再度适用）但无人再唤醒 PROJECT；6 分钟静默后看门狗
no-progress 强制收敛。缺口：信封被拒后仍 PENDING，但投递只发生在 ISSUED 事件，无补偿重投。

## 方案
- [AIREVIEW-PLAN-063#1] ReviewLivenessGuard 注入 ObjectProvider<ReviewDispatchStore>；scan() 的 idle 分支
  （idle ≥ livenessRewakeIdle）在阶段覆盖判断之外（DEBATE 也生效）增加 redeliverPendingEnvelopes：
  遍历 findPending(reviewId, attemptNo)，跳过已过期；per-commandId 计数（上限复用 livenessMaxRewakes），
  未超限则 adapter.deliverDispatchCommand(runtimeId, roleLabel, envelopeText(command), command) 重投，
  日志 LIVENESS_ENVELOPE_REDELIVERED。roleLabel/runtimeId 口径同 ReviewWorkflowDispatcher。
- [AIREVIEW-PLAN-063#2] 重投不改变信封状态（仍 PENDING），消费/过期/拒绝语义不变；超限后交还既有
  收敛看门狗 expired-dispatch 路径兜底。
- [AIREVIEW-PLAN-063#3] 测试：idle DEBATE + PENDING 命令→deliverDispatchCommand 被调且携带该命令；
  超上限不再重投；非 idle 不重投。

## 文件清单
- src/main/java/ai/cc/chongming/review/application/ReviewLivenessGuard.java
- src/test/java/ai/cc/chongming/review/application/ReviewLivenessGuardTests.java

## 风险
- 重投给正在运行的角色会排在串行队列后，无协议副作用；
- 计数上限 3，最坏 3×90s 后交还看门狗，不引入新死锁。

## 变更记录
- 2026-08-28 立计划；派发后台子代理实施。
- 2026-08-28 子代理 71fc0422 交付；父代理审查 diff 无夹带；回归 812 全绿；提交。
