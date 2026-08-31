# AIREVIEW-PLAN-084 末题关题即刻进裁决：DEBATE_TOPIC_CLOSED 全终态即 beginJudging

状态：✅ 完成

## 背景
8/8 议题裁决后页面不跳裁决者裁决。JUDGING_STARTED 在白名单内，客户端无责；
服务端缺口：末题由协调者 close 后无确定性 beginJudging 调用者（协调者裁量可被冷却/停摆抑制，
liveness 自动 judging 走静默扫描滞后数分钟）。

## 方案
- [#1] ReviewWorkflowDispatcher.onCommitted 的 DEBATE_TOPIC_CLOSED 分支：wake 之后检查
  debateStore.findTopics(review.id()) 全终态 → debateService.beginJudging(review)（064 幂等）；
  DebateService 经 ObjectProvider 注入防循环；异常仅 WARN。
- [#2] 日志 BEGIN_JUDGING_ON_LAST_CLOSE reviewId=...；既有 liveness 路径保留兜底。
- [#3] 测试：dispatcher 收到末题 CLOSED 事件→beginJudging 被调用（mock DebateService 验证）；
  非末题 CLOSED 不调用。

## 变更记录
- 2026-08-31 立计划；派发后台子代理实施。
- 2026-08-31 子代理 ccf2b330 交付；2 新用例；回归 849 全绿；提交。
