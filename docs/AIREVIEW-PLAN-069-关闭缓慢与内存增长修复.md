# AIREVIEW-PLAN-069 关闭缓慢与内存增长修复：SSE 关闭钩子+统一终态清理

状态：✅ 完成

## 背景（诊断报告 docs/诊断-重启关闭缓慢与内存增长排查.md）
2 条 SSE 下优雅关闭 33s（等满 30s 默认超时）；根因 SSE emitter 无关闭钩子；
COMPLETED 成功路径从不清理 per-review 结构，长期运行内存增长。

## 方案
- [#1] ReviewSseRegistry 增 @PreDestroy：complete 全部 emitter 并清 map。
- [#2] ReviewRuntimeTraceRegistry.close() 追加：遍历 traces → trace close/complete emitter + clear（与 068 合并后代码）。
- [#3] application.yml：spring.lifecycle.timeout-per-shutdown-phase=10s 兜底；management.health.mail.enabled=false。
- [#4] 统一终态清理：dispatcher 在终态事件（REVIEW_CANCELLED/REVIEW_FAILED/或 stage 进入 COMPLETED 的事实）
  除现有 reject 外，清理 queues(complete+remove)、liveness.clear、promoter.clear、orchestration forget、traceRegistry remove；
  具体钩子以读到的真实事件类型为准（可借 liveness guard 观测到 stage=COMPLETED 时触发一次清理）。
- [#5] ReviewLivenessGuard.redeliveries 终态清除+容量上限双保险。
- [#6] 验收：8081 实例挂 2 条 SSE 后 shutdown <2s（可复现则做，否则以钩子代码+单测为准）。

## 变更记录
- 2026-08-28 立计划；等 068 交付后派发。
- 2026-08-28 子代理 541a1535 交付；验收实测 2 条 SSE 下 shutdown 33s→<1s（ContextClosedEvent 提前 complete 是关键）；
  父代理核验 LivenessGuard 同时含 069 清理与 070 文案；最终回归 827 全绿；提交（含 070 遗留的 LivenessGuard 合并改动）。
