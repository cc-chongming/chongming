# AIREVIEW-PLAN-066 对话条目悬浮时间戳：名称/头像 hover 浮现发言时间

状态：✅ 完成

## 背景
用户批注（截图红框：条目名称旁）：给对话后面加发言时间，但不直接显示，
鼠标放到名称和头像处时浮现。

## 方案
- [AIREVIEW-PLAN-066#1] LiveAgentConversation.vue：移除直接显示的 `<time>` 元素；
  在 `.agent-avatar` 与 `.agent-dialogue-header strong` 上绑定
  `:data-tip="row.createdAt ? '发言于 ' + displayTime(row.createdAt) : undefined"`。
- [AIREVIEW-PLAN-066#2] review.css 增 CSS tooltip：上述两选择器 position: relative；
  `[data-tip]:hover::after { content: attr(data-tip); ... }` 深色小气泡、绝对定位、nowrap、z-index；
  compact（右侧抽屉）同生效。

## 文件清单
- frontend/src/components/LiveAgentConversation.vue
- frontend/src/styles/review.css

## 变更记录
- 2026-08-28 立计划；派发后台子代理实施。
- 2026-08-28 子代理 78958c85 交付；父代理审查无夹带；vitest 164 全绿；产物同步 target/classes（40/40 一致）；提交。
