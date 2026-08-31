# AIREVIEW-PLAN-083 议题三态状态与页面跟随焦点

状态：✅ 完成

## 背景
串行辩论下未开始议题仍显示“进行中”（截图 8 议题 6 个进行中），语义错误；
且焦点前进时页面不跟随，用户需手动点 Tab。

## 方案
- [#1] 三态状态：已裁决（terminal）/ 进行中（=当前焦点，列表序第一个非终态）/ 排队中（其余非终态）；
  Tab 徽章增 .queued 样式（中性灰蓝，浅/深主题各一条）。
- [#2] 页面跟随：focusTopicId computed = debateTopics 列表序首个非终态；
  watch(focusTopicId)：变化且未 pin → selectedTopicId = focus；switchTopic 置 topicPinned=true；
  pinned 且 selected≠focus 时 Tab 栏右侧显示“回到焦点”小按钮，点击解除 pin 并跳焦点。
- [#3] 回合选项卡逻辑不变；左侧流程副标题不变。

## 文件清单
- frontend/src/views/ReviewLiveView.vue、frontend/src/styles/review.css

## 变更记录
- 2026-08-31 立计划；派发后台子代理实施（与 082 并行零冲突）。
- 2026-08-31 子代理 0a45a8d4 交付；vitest 164 全绿；产物同步；提交。
