# AIREVIEW-PLAN-096 页头阶段徽章中文化

## 背景
实时视图页头两处直接渲染原始英文 stage 枚举：连接态文案「运行流已连接 · INITIAL_REVIEW」
与阶段徽章「INITIAL_REVIEW」。左侧流程导航与列表页均已有中文阶段名，页头是唯一的英文裸枚举出口。

## 变更
- [AIREVIEW-PLAN-096#1] `frontend/src/views/ReviewLiveView.vue` 页头：
  - 连接态（review-flow-header-status）不再拼接 `· {{ stage }}`，仅保留「运行流已连接 / 正在连接运行流 / 运行已失败 …」；
  - 阶段徽章（flow-stage-chip）改用既有 `stageLabel` 映射（INITIAL_REVIEW→初审中、JUDGING→裁决中、WAITING_HUMAN→待人工决策 等），未知枚举回退原值。

## 验收
- 页头不再出现裸英文 stage；徽章与列表页中文标签一致；
- vitest 全绿；vite build 同步至 static/review 与 target/classes。
