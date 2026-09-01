# AIREVIEW-PLAN-102 裁决理由结构化分段渲染

## 背景
人工决策「决策依据」与裁决阶段卡片里，Judge 的 reasonSummary 是一大段不分行的文本，
可读性差。文本本身带结构标签（核心争议 / 各方立场（…） / 采信依据 / 裁决 / 未决点）。

## 变更
- [AIREVIEW-PLAN-102#1] `review-conclusion-presenter.js` 新增 `judgementReasonBlocks`：
  按标签（全角冒号）切分为 [{label, text}]；标签加粗、每段独立成行；
  未识别标签时单段原样返回（自由文本不损坏）。
- [AIREVIEW-PLAN-102#2] `HumanReviewPanel.vue` 决策依据裁决卡与 `ReviewLiveView.vue`
  裁决阶段卡片改用分段渲染。
- [AIREVIEW-PLAN-102#3] `review.css` 段落间距与标签加粗样式。

## 验收
- 截图同款文本切分为 5 个标签段（node 验证）；vitest 全绿；build 同步 static/review 与 target/classes。
