# AIREVIEW-PLAN-107 人工决策阶段终态徽章显示完成

状态：✅ 完成

## 背景

人工提交最终结论后（门禁定稿、阶段机进入 NOTIFYING/COMPLETED），实时观察台「人工决策」
阶段徽章仍显示「● 进行中」。根因：phaseState() 以 index === activePhaseIndex 判定 running，
而 COMPLETED 阶段落位恰在最后一阶人工决策，缺少终态→done 的规则。

## 方案

- [#1] phaseState() 增加规则：当前阶段为 human 且 stage 为 NOTIFYING/COMPLETED 时返回 done，
  徽章显示「✓ 完成」，左侧连接线同步变绿；WAITING_HUMAN 仍保持进行中。

## 文件清单

- frontend/src/views/ReviewLiveView.vue

## 验证

- vitest 全绿；vite build + 同步 target/classes；提交决策后人工决策徽章显示完成。

## 变更记录

- 2026-09-01 立计划；父会话直接实施（纯逻辑热修复）；构建同步并提交。
