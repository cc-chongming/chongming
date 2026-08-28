# AIREVIEW-PLAN-052 上下文收集结论面板标题固定

> **状态**: ✅ 全部完成
> **创建日期**: 2026-08-28
> **目标**: 上下文侦察下半部分（上下文收集结论）滚动时标题固定，与上半部分运行流面板一致。

## 背景

- 用户批注：上下文侦察页上半部分（运行流面板）顶部标题固定滚动，下半部分“上下文收集结论”标题没有固定，滚动时被带走。
- 现状：`.flow-content > .scout-conclusion-panel { flex: 1 1 auto; min-height: 0; overflow-y: auto }`（PLAN-043#3）——整个面板含 `header`（结构化评审事实/上下文收集结论/查阅依据计数）一起滚；对照运行流面板是 header 在外、滚动区在内。
- 组件结构（`ScoutConclusionPanel.vue`）：`section.scout-conclusion-panel` > `header` + `div.scout-conclusion-body`（摘要/四宫格/角色关注范围/完整上下文）；无结论时为空态文案。

## 分段方案

### 段 1：面板改弹性列，标题固定、正文内滚

**涉及文件**：修改 `frontend/src/styles/review.css`（纯样式，无需动组件模板）。

**关键实现细节**：
- `.flow-content > .scout-conclusion-panel`：保留 flex 填充与 `min-height: 0`，`overflow-y: auto` 改为 `display: flex; flex-direction: column; overflow: hidden`；
- `.scout-conclusion-panel > header { flex: 0 0 auto; }`（标题区固定）；
- `.scout-conclusion-body` 成为唯一滚动容器：`flex: 1 1 auto; min-height: 0; overflow-y: auto;`；
- 空态（无结论时）内容短小，不受影响；既有面板底色/圆角/边框样式不动。

## 文件清单

### 修改

| 文件 | 计划段 | 状态 |
|------|--------|------|
| `frontend/src/styles/review.css` | #1 | ✅ |

## 实施顺序

1. **步骤 1** → 前端子代理实施段 1 + vitest 回归；不构建、不提交。
2. **步骤 2** → 依赖步骤 1：父级独立审查（标题固定、正文内滚、空态正常），构建同步运行服务，提交推送。

## 风险与应对

- **风险**：面板内层既有 margin/padding 与弹性列叠加产生空隙 → 子代理按实际样式微调，保持视觉一致。
- **风险**：完整上下文 details 展开后的内滚体验 → 正文区统一内滚，details 在正文区内自然展开。

## 变更记录

- 2026-08-28：创建计划，派发前端实施子代理。
- 2026-08-28：段 1 交付并通过独立审查（新属性并入既有声明块、无重复选择器；标题固定、正文唯一滚动容器）；159 全绿；构建部署完成。全部完成。
