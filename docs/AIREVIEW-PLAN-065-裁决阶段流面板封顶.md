# AIREVIEW-PLAN-065 裁决阶段运行流面板封顶：裁决卡免挤压

状态：✅ 完成

## 背景
用户批注（截图）：裁决者裁决阶段，裁决者对话框把下方裁决卡压迫截断；要求把固定高度调低。
JUDGING 窗口内流面板（judge 对话）与 .flow-judgement-section 共享主区，流面板过高时裁决卡出视口。

## 方案
- [AIREVIEW-PLAN-065#1] ReviewLiveView 流面板 section 增修饰类：activePhase==='judge' 时加
  `flow-stream-capped`；review.css 增 `.flow-content > .flow-stream-capped { max-height: 55%; }`，
  裁决卡（.flow-judgement-section，flex 1 1 auto 内滚）至少拿 45% 主区。
- 仅裁决阶段生效，scout/director 既有布局不动；≤760px block 布局百分比 against auto=none，天然回退。

## 文件清单
- frontend/src/views/ReviewLiveView.vue
- frontend/src/styles/review.css

## 顺序
模板类 → CSS → vitest → vite build 同步 → 父代理审查提交。

## 变更记录
- 2026-08-28 立计划；派发后台子代理实施。
- 2026-08-28 子代理 594bb43b 交付；父代理审查 diff 无夹带；vitest 164 全绿；产物同步 target/classes；提交。
