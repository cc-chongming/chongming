# AIREVIEW-PLAN-103 决策依据展示各方原始主张

## 背景
自冲突议题（反对者与支持者同一角色，如产品经理自己反对自己支持的点）不会发生辩论，
人工决策「决策依据」里只有裁决者理由与采信/拒绝计数——反对方原文不可见，决策者看不到
产品经理自己提出的反对点。

## 变更
- [AIREVIEW-PLAN-103#1] presentDebateJudgement 增加 positions：议题全部 Claim 的
  角色/立场/严重度/原文，质疑置前（OPPOSE → SUPPORT → NEUTRAL）。
- [AIREVIEW-PLAN-103#2] HumanReviewPanel.vue 裁决卡新增「各方主张」列表：立场徽标 +
  角色中文 + 严重度 + 原文。
- [AIREVIEW-PLAN-103#3] review.css 立场色条样式（质疑红 / 支持绿 / 中立灰）。

## 验收
- 自冲突议题的反对原文在决策依据可见；vitest 全绿；build 同步 static/review 与 target/classes。
