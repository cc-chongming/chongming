# AIREVIEW-PLAN-077 领域事件桥接 AG-UI SSE：阶段实时推进

状态：✅ 完成

## 背景
前端 reviewEventFromAgUiEvent 等待 CUSTOM `chongming.review.domain-event.v1`（value 含 sequence+reviewId），
服务端无发射器 → 领域事件（INITIAL_REVIEW_COMPLETED/DEBATE_SKIPPED/ROLE_COMPLETED…）从不到达客户端 →
applyStageFromEvent/各 refresh 永不触发 → 初审完成后不跳转，只能手动刷新。

## 方案
- [#1] ReviewRuntimeTraceRegistry 增 recordDomainEvent(runtimeId, ReviewEvent)：
  包装为 AguiEvent.Custom(threadId, runtimeId, "chongming.review.domain-event.v1", value)，
  value = {schemaVersion:1, sequence:<分配的trace sequence>, reviewId, attemptNo, type, stage, progress,
  actorRole, payload, occurredAt}；走既有 publish 路径（live 投递+trace 持久化，重连重放可补齐）。
- [#2] 新增 @Component DomainEventAgUiBridge implements ReviewEventListener：onCommitted 时
  以 ReviewRuntimeContext.runtimeIdFor(reviewId, attemptNo) 调 recordDomainEvent；
  ObjectProvider<ReviewRuntimeTraceRegistry> 防循环；异常仅 WARN 不影响领域提交。
- [#3] 客户端零改动（解析器已存在）；验证：SSE 流可见 domain-event CUSTOM 且 value.stage 正确。
- [#4] 测试：registry 单测（CUSTOM 名/sequence/stage 断言）；bridge 单测（onCommitted→record 调用）。

## 文件清单
- ReviewRuntimeTraceRegistry.java、新增 DomainEventAgUiBridge.java、对应测试

## 风险
- trace 容量既有上限（20000/attempt）不变，domain 事件增量可控；
- 客户端 lastSequence 去重已按 sequence，重放不重复应用。

## 变更记录
- 2026-08-31 立计划；派发后台子代理实施。
- 2026-08-31 子代理 10e018b7 交付；SSE 帧样例验证 name/value.stage/value.sequence 与客户端解析器匹配；回归 845 全绿；提交。
