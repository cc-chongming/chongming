# AIREVIEW-PLAN-075 辩论环节根治：收敛守卫活动感知+重投静默闸+协调者唤醒冷却

状态：✅ 完成

## 背景（评审 e77ee354，最新代码实锤）
1) DebateConvergenceGuard 的 no-progress 只统计协调者唤醒，角色辩论活动不可见 →
   进行中的辩论 6 分钟被强收敛（reason=no-progress elapsed=PT6M15S escalatedTopics=7）；
2) 063 重投不查收件人是否在跑 → 重复 turn 噪音（PRODUCT“再次收到答辩”）；
3) 协调者对同状态重复唤醒循环复读（抽屉 8 连重复分析）。

## 方案
- [#1] DebateConvergenceGuard 实现 ReviewEventListener：onCommitted 更新 per-attempt lastActivityAt；
  注入 ObjectProvider<ReviewRuntimeTraceRegistry>；scan 的 no-progress 判定改为
  now - max(lastWakeAt, lastActivityAt, lastObservedAt(runtimeId)) ≥ debateNoProgressTimeout。
  expired-dispatch 与 wall-clock 路径不变。日志增 lastActivity 字段。
- [#2] ReviewLivenessGuard.redeliverPendingEnvelopes：重投前取 traceRegistry.lastObservedAt；
  若 lastObservedAt 晚于 command.createdAt()（收件人自签发后有活动=可能在跑）则跳过该命令（日志 REDELIVERY_SKIPPED_RECIPIENT_ACTIVE）。
- [#3] ReviewWorkflowDispatcher.wakeDirector 冷却：per-attempt 记录 lastWakeAt+lastWakeMsgHash；
  60s 内同 hash 且期间无 CHALLENGE_SUBMITTED/REBUTTAL_SUBMITTED/CLAIM_SUBMITTED 新事件→跳过 send
  （日志 COORDINATOR_WAKE_COOLDOWN）。事件计数复用 liveness 式 onCommitted 监听或 dispatcher 自身 onCommitted 记录。
- [#4] 测试：收敛守卫在角色活动下不强收敛（mock trace/事件）；双静默仍收敛；重投静默闸跳过活跃收件人；
  协调者冷却抑制重复唤醒、新 turn 后解除。

## 文件清单
- DebateConvergenceGuard.java、ReviewLivenessGuard.java、ReviewWorkflowDispatcher.java、对应测试

## 变更记录
- 2026-08-31 立计划；派发后台子代理实施（与 074 并行零文件冲突）。
- 2026-08-31 子代理 1a7bd0ec 交付；复核发现 Bean 循环与 no-progress 旧语义两处回归，发回修复（ObjectProvider 懒解析+EPOCH 哨兵排除）；
  父代理最终回归 EXIT=0（841 全绿）；与 076 合并提交（共享文件不可拆分）。
