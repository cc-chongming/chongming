# AIREVIEW-PLAN-100 人工决策徽章图标资产化

## 背景
人工决策提示条徽章使用 🧑 emoji，与页面统一的阶段图标资产体系（phase-icons）风格不符。

## 变更
- [AIREVIEW-PLAN-100#1] `ReviewLiveView.vue`：徽章内 emoji 替换为
  `phaseIconUrl('human', 'running')` 图标资产（与左侧流程导航同源）；
  `review.css`：徽章 inline-flex 对齐，图标 1.05rem。

## 验收
- 徽章显示人工决策阶段图标 + 文案，无 emoji；vite build 同步 static/review 与 target/classes。
