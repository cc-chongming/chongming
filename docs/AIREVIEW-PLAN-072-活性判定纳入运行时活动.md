# AIREVIEW-PLAN-072 活性判定纳入运行时活动：只读探索不再被误判停摆

状态：✅ 完成

## 背景
评审 b0959b6d 被 LIVENESS_FORCE_FAIL 误杀：guard 只计领域事件，角色长段只读工具调用
（searchText/readLines 数分钟）不产领域事件 → idle≥90s 判停摆 → 3 次重唤醒后强杀健康评审。

## 方案
- [#1] ReviewRuntimeTraceRegistry 增 `Optional<Instant> lastObservedAt(String runtimeId)`：
  取该 trace 最后一条 StampedEvent 的 observedAt（068 已注入），无 trace 为空。
- [#2] ReviewLivenessGuard.scan：idle = now - max(lastDomainActivity, lastObservedAt(runtimeId))；
  重唤醒与强杀判定都用新 idle。注入 ObjectProvider<ReviewRuntimeTraceRegistry> 防循环。
- [#3] 强杀保留但仅真停摆触发（领域+运行时双静默）；日志 LIVENESS_FORCE_FAIL 增 lastTraceActivity 字段。
- [#4] 测试：有运行时活动无领域事件→scan 不重唤醒不強杀；双静默超阈→照旧重唤醒/強杀。

## 变更记录
- 2026-08-31 立计划；等 071 交付后派发。
- 2026-08-31 子代理 4fdc2c63 交付；父代理核验 lastObservedAt 与双静默判定；回归 830 全绿；提交。
