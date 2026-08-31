# AIREVIEW-PLAN-087 人工决策页：议题中文名优先 + 裁决文本“问题→态度→依据”顺序

状态：✅ 完成

## 背景
用户批注：人工决策页议题名称仍为英文 subjectKey；裁决文本先写“采信依据”不合阅读逻辑，
应先说明问题点，再说明各角色最后态度，最后才是采信依据。

## 方案
- [#1] review-conclusion-presenter.js presentDebateJudgement 增 `title: debate.title ?? null`；
  HumanReviewPanel 裁决卡：标题位显示 `judge.title ?? judge.subjectKey`，
  有 title 时 subjectKey 降为 <code> 技术标识；补最小样式。
- [#2] roles/judge.yml：voice.focus 增顺序约束“裁决说明按‘问题点→各方最终态度→采信/拒绝依据’
  顺序书写：先一句话说明议题核心争议，再说明支持/反对方最终态度（含是否撤回），最后说明采信依据与未决点”；
  promptVersion judge-v2→judge-v3。

## 变更记录
- 2026-08-31 立计划；派发后台子代理实施（与 084 并行零冲突）。
- 2026-08-31 子代理 b3a341dc 交付；vitest 164 全绿；产物同步；提交。
