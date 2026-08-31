# AIREVIEW-PLAN-091 ag-ui 运行时流游标续接：刷新不再全量重放

状态：✅ 完成

## 背景
ag-ui 订阅不传游标，每次刷新从 0 全量重放（单 attempt 上限 2 万事件）；
live 事件排在 backlog 后，观感“刷新后不流式、不刷新不更新”。
domain /events 已有 afterSequence；ag-ui 端点只认 Last-Event-ID 头（新建 EventSource 不发送）。

## 方案
- [#1] ReviewRuntimeTraceController.stream 增 @RequestParam afterSequence（可选），
  cursor = max(Last-Event-ID 头, afterSequence)。
- [#2] ag-ui-runtime-sse.js：选项增 afterSequence；URL 带 afterSequence=<cursor>；
  onmessage 记录 lastReceived；onerror 重连时用 lastReceived（续接不重放）。
- [#3] runtime-trace-store：localStorage 持久化游标（key 含 reviewId:attempt），
  start() 传入 afterSequence；merge() 内更新游标（event.id 为数值时）。
- [#4] 测试：ag-ui-runtime-sse.test.js URL 断言更新+重连续接用例；store 游标持久化用例。

## 变更记录
- 2026-08-31 立计划；派发后台子代理实施（与 090 并行零冲突）。
- 2026-08-31 子代理 b9e3b850 交付；vitest 168 全绿；产物同步；提交。
