# AIREVIEW-PLAN-068 运行时事件注入观测时间：hover 时间戳生效

状态：✅ 完成

## 背景
PLAN-066 悬浮时间戳不生效：AG-UI 运行时事件 JSON 无任何时间字段，adapter 的 createdAt 恒 null。
持久化表 runtime_trace_event 有 created_at DATETIME(3)，观测时间已在库，只是没进 SSE payload。

## 方案
- [AIREVIEW-PLAN-068#1] ReviewRuntimeTraceRegistry：内部 StampedEvent 增 observedAt（live 记录时取 now；
  replay 映射 row.created_at）；SSE 发送（历史重放与 live 两路）用 ObjectNode 注入
  `createdAt`（ISO-8601 毫秒）后再写 data，不改 AguiEvent record 与事件类型结构。
- [AIREVIEW-PLAN-068#2] 若 trace store 的 row record/mapper 未暴露 created_at，补齐映射。
- [AIREVIEW-PLAN-068#3] 前端零改动（adapter 已读 event.createdAt）；回归既有 SSE/adapter 单测不破坏。
- [AIREVIEW-PLAN-068#4] 测试：registry 单测断言 live 与 replay 的 SSE payload 含 createdAt 且可解析。

## 文件清单
- src/main/java/ai/cc/chongming/review/application/ReviewRuntimeTraceRegistry.java
- 对应 trace store（如需要）与测试

## 风险
- payload 多一个字段为向前兼容增量，adapter/旧客户端忽略未知字段；
- created_at 为服务器墙钟（中国时区列），前端 displayTime 按 ISO 解析展示，口径与列表时间一致即可。

## 变更记录
- 2026-08-28 立计划；派发后台子代理实施。
- 2026-08-28 子代理 f7813849 交付；父代理审查无夹带；回归 814 全绿；提交。
