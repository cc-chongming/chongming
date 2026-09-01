# AIREVIEW-PLAN-113 进入人工决策解除手动阶段钉住

状态：✅ 完成

## 背景

评审跑完议题裁决、阶段机进入 WAITING_HUMAN 后，页头徽章已显示「待人工决策」，
但主视图仍停留在「议题裁决」，不跳转到最后一步「人工决策」。根因：左侧步骤的
手动点选（selectPhase 写入 selectedPhase）被设计为永久钉住——activePhase 优先取
selectedPhase，syncPaced 在钉住时直接 return，且 selectedPhase 仅在切换评审时清空。
于是运行期点选过「议题裁决」后，被动落位（含 WAITING_HUMAN → 人工决策 的最后一跳）
被永久压制，人工决策面板（v-else-if="activePhase === 'human'"）永远不渲染。

## 方案

- [#1] review-phase-presenter 新增 shouldReleasePhasePin：落位目标进入人工决策区间
  （target >= HUMAN_PHASE_INDEX，即 WAITING_HUMAN/NOTIFYING/COMPLETED）且钉住仍停在
  更早阶段时返回 true；ReviewLiveView 据此 watch 清空 selectedPhase，节奏层随即落位
  人工决策。到达后用户再手动回看早前步骤仍受尊重（stage 不再跃迁，不会二次解除）。

## 文件清单

- frontend/src/services/review-phase-presenter.js
- frontend/src/services/review-phase-presenter.test.js
- frontend/src/views/ReviewLiveView.vue

## 验证

- vitest 全绿（新增 shouldReleasePhasePin 用例：进入门禁解除、运行期回看保留、
  已选人工决策不解除、无钉住不解除）；npm run build 同步 static/review bundle。

## 变更记录

- 2026-09-01 立计划并实施（用户反馈待人工决策不跳转的修复）；构建同步并提交。
- 2026-09-01 编号更正：与先提交的邮件「查看需求详情」次按钮 PLAN-110 冲突，让位改编号 111。
