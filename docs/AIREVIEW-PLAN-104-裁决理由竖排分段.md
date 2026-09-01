# AIREVIEW-PLAN-104 人工决策裁决理由竖排分段

状态：✅ 完成

## 背景

人工决策面板「决策依据 → Judge 议题裁决」卡片中，裁决理由的「核心争议 / 采信依据 / 未决点」
三个分段被并排挤成三列，阅读体验差。根因：组件样式 `.judge-summary-list li > div`
（本意服务标题行：结论标签 + 议题名 + subjectKey）使用了 `display:flex; justify-content:space-between`，
而理由容器 `.judge-reason` 同为 `li` 的直接子 div，被该规则命中，内部各 `<p>` 分段变成横向 flex 项。

## 方案

- [#1] 标题行 div 增加类名 `judge-card-head`，flex 规则与窄屏媒体查询改以该类名限定；
  `.judge-reason` 显式 `display:block` 恢复竖排；`.judge-reason p strong` 标签独立成行（块级），
  形成「核心争议 / 采信依据 / 未决点」各自成段的竖排结构。
- 实时观测页裁决卡（`.flow-judgement-reason`）本就竖排，不受影响；报告页 `.judge-reason`
  为 scoped 样式且非 li 直接子级，不受影响。

## 文件清单

- frontend/src/components/HumanReviewPanel.vue（模板类名 + 组件样式）

## 验证

- `npx vite build` 通过，产物同步至 `src/main/resources/static/review` 与 `target/classes/static/review`；
- 前端 vitest 回归全绿；刷新人工决策页可见裁决理由按「核心争议 / 采信依据 / 未决点」竖排分段。

## 变更记录

- 2026-09-01 立计划；父会话直接实施（纯 CSS 热修复）；构建同步并提交。
