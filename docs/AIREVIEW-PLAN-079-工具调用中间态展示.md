# AIREVIEW-PLAN-079 工具调用中间态展示：运行中不渲染“暂无数据”

状态：✅ 完成

## 背景
抽屉里运行中的工具调用（dispatch_debate_action 等）输出区显示“暂无数据”，
误像“调用没返回”。实测事件链完整（started output:null/RUNNING + completed 有输出/SUCCESS），
纯 UI 缺陷：输出块无条件渲染且 null→“暂无数据”。对标 DSH：中间态只显示状态/等待，完成后才渲染输出。

## 方案
- [#1] AgUiToolCallMessage.vue：输出区仅在 phase=completed（或 status 终态）时渲染；
  运行中保留“等待工具返回…”与状态徽章，不渲染输出块；
- [#2] 完成且 output==null 时文案改“（无输出）”，与“暂无数据”（数据缺失）语义分离；
- [#3] 输入区同样：运行中可保留（截图已有），不变。

## 文件清单
- frontend/src/components/AgUiToolCallMessage.vue

## 变更记录
- 2026-08-31 立计划；派发后台子代理实施（与 078 并行零冲突）。
- 2026-08-31 子代理 60a17dbb 交付；vitest 164 全绿；产物同步；提交。
