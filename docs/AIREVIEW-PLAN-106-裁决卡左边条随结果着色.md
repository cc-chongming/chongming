# AIREVIEW-PLAN-106 人工决策裁决卡左边条随结果着色

状态：✅ 完成

## 背景

人工决策面板「Judge 议题裁决」列表每张议题卡的左边条固定蓝色，无法一眼区分裁决结论。
用户要求左边条颜色随结果变化：有条件通过 / AI 通过 / 驳回 等各自对应颜色。

## 方案

- [#1] HumanReviewPanel 新增 RESULT_TONE 映射：AI_PASS/PASS→绿(#16a34a)、CONDITIONAL/RETURN/HUMAN_REQUIRED→琥珀(#d97706)、
  BLOCK→红(#dc2626)、OVERRIDE→紫(#7c3aed)、缺省→灰(#a8a29e)；议题卡 li 绑定 tone-* 类，覆盖默认蓝色左边条。

## 文件清单

- frontend/src/components/HumanReviewPanel.vue

## 验证

- vitest 全绿；vite build + 同步 target/classes；刷新人工决策页左边条随结论着色。

## 变更记录

- 2026-09-01 立计划；父会话直接实施（纯 CSS 热修复），与 PLAN-105 一并构建提交。
