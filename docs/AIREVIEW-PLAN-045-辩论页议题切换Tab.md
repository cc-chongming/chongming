# AIREVIEW-PLAN-045 辩论页议题切换Tab

> **状态**: ✅ 全部完成
> **创建日期**: 2026-08-27
> **目标**: 多轮辩论页新增议题切换 Tab，法庭/共识度/中立方/对话流按所选议题聚焦，不再混合展示全部议题。

## 背景

- 用户批注：多轮辩论已有多个议题，页面只显示一个议题的标题，其余议题内容混在同一法庭，应加议题切换 Tab。
- 现状：`proClaims/conClaims/neutralClaims/consensusPercent` 由 `allClaims`（全部议题 flatMap）分区，法庭混合所有议题；标题 `debateSubject` 取第一个议题；`roundTurns` 亦跨议题；只有回合（R1/R2）选项卡。

## 分段方案

### 段 1：议题 Tab 与按议题聚焦

**涉及文件**：修改 `frontend/src/views/ReviewLiveView.vue`、`frontend/src/styles/review.css`

**关键实现细节**：
- 新增 `selectedTopicId` ref（空→解析为第一个议题）与 `selectedTopic` computed（找不到回退第一个，保证议题列表变化时稳定）。
- 议题 Tab 栏（置于回合选项卡上方）：每议题一个按钮，文案 `议题 {序号}` + 短标题（`topic.title ?? topic.subjectKey`，title 字段来自 PLAN-044，未落地时自然回退）；选中态高亮；超宽横向滚动。
- 法庭聚焦：支持方/质疑方/中立方与共识度改用 `selectedTopic.claims` 分区计算（`allClaims`/`roleClaims` 等其余用途不动）。
- 对话流聚焦：回合选项卡按所选议题的回合集合（1..max(该议题 currentRound, turns 回合)）；`roundTurns` 只取所选议题的 turns 与合成答辩条目（按 subject 匹配）。
- 页头标题沿用 `debateSubject`，改为所选议题标题回退。
- 议题/回合切换互不干扰：切议题保留当前 selectedRound，若超出该议题最大回合则回落到其最大回合。

## 文件清单

### 修改

| 文件 | 计划段 | 状态 |
|------|--------|------|
| `frontend/src/views/ReviewLiveView.vue` | #1 | ✅ |
| `frontend/src/styles/review.css` | #1 | ✅ |

## 实施顺序

1. **步骤 1** ✅ → 前端子代理实施段 1 + vitest 回归（158 绿）。
2. **步骤 2** ✅ → 独立审查**发现并修复子代理遗漏**：`selectedTopicId` ref 未声明（编译可过、运行必炸），补齐声明并在 `load()` 切换评审时重置；CSS/联动逻辑复查通过；构建（真实 SFC 编译校验）通过并同步运行服务。
3. **步骤 3** ✅ → 提交推送。

## 风险与应对

- **风险**：切换议题后 selectedRound 超出该议题回合数 → 回落该议题最大回合。
- **风险**：共识度语义由全量改为单议题 → 与“当前议题支持占比”表述一致（文案已是“支持 Claim 占比”），更准确。
- **风险**：title 字段未落地（PLAN-044 后端在途）→ `topic.title ?? topic.subjectKey` 回退，落地后自动生效。

## 变更记录

- 2026-08-27：创建计划，派发前端实施子代理。
- 2026-08-27：段 1 交付；独立审查捕获子代理遗漏的 `selectedTopicId` 声明（注释写了、声明丢了，compile-only 校验测不出），父级补齐并加 `load()` 重置；全部完成。
- 2026-08-27：段 2（用户批注“横向滚动条丑”）：议题 Tab 栏改为 `flex-wrap` 换行排布，横向滚动条彻底移除，全部议题直接可见。
