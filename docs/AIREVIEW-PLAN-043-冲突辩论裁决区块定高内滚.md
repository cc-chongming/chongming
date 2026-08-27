# AIREVIEW-PLAN-043 冲突辩论裁决区块定高内滚

> **状态**: ✅ 全部完成
> **创建日期**: 2026-08-27
> **目标**: 冲突检测、多轮辩论、裁决者裁决三个阶段的主体区块像运行流面板一样固定视口高度、内部滚动，阶段标题/回合切换保持可见。

## 背景

- PLAN-037#2 已把页面锁定视口高度并让运行流面板（`.flow-stream-live`）内部滚动；其余区块仍是自然高度，内容过长时由 `.flow-content` 整列滚动——用户截图显示冲突检测议题卡、辩论法庭被截断且标题滚没。
- 用户批注：冲突检测、多轮辩论、裁决者裁决的主体区块也要固定高度内部滚动。
- 现状结构：`.flow-content` 为定高 flex 列；`> *` 默认 `flex: 0 0 auto`，仅 `.flow-stream-live` 拉伸内滚。

## 分段方案

### 段 1：三个阶段的主体区块定高内滚

**涉及文件**：
- 修改：`frontend/src/styles/review.css`
- 修改：`frontend/src/views/ReviewLiveView.vue`（仅辩论区块包一层滚动容器）

**关键实现细节**：
- 冲突检测：`.flow-content > .flow-conflict-section { flex: 1 1 auto; min-height: 0; overflow-y: auto; }`——议题卡列表在卡片内部滚动，下方协调者处置卡保持自然高度。
- 多轮辩论：SFC 在回合选项卡之后把法庭（`.flow-debate-court`）+ 中立方（`.flow-neutral-claims`）+ 对话流（`.flow-debate-dialogue`）包进新容器 `<div class="flow-debate-scroll">`；CSS `.flow-content > .flow-debate-scroll { flex: 1 1 auto; min-height: 0; overflow-y: auto; }`，容器内各区块保持原样式与间距；回合选项卡固定在滚动区之上。
- 裁决者裁决：`.flow-content > .flow-judgement-section { flex: 1 1 auto; min-height: 0; overflow-y: auto; }`——议题裁决卡区块与运行流面板分享剩余高度并内部滚动（无裁决卡时该区块不渲染，不影响）。
- 独立审查不在本次范围（用户未批注；如有需要另立）。

## 文件清单

### 修改

| 文件 | 计划段 | 状态 |
|------|--------|------|
| `frontend/src/styles/review.css` | #1 | ✅ |
| `frontend/src/views/ReviewLiveView.vue` | #1 | ✅ |

## 实施顺序

1. **步骤 1** ✅ → 前端子代理实施段 1 + vitest 回归（158 绿）。
2. **步骤 2** ✅ → 独立审查通过（三条规则落位 037#2 规则块后、辩论包裹最小改动面、回合选项卡固定）；构建产物 index-CzCEhMZ1.js / index-2a4Gi6dE.css 同步运行服务并验证。
3. **步骤 3** ✅ → 提交推送。

## 风险与应对

- **风险**：裁决阶段面板与裁决卡两个 flex-fill 区块分割剩余高度，面板被压缩 → 面板已有自身内滚与最小可用性（18rem 级内容），可接受；如观感差可后续给裁决卡改 max-height 口径。
- **风险**：辩论滚动容器改变原有区块外边距表现 → 容器内保持原有 margin 体系，仅增加滚动与填充。
- **风险**：≤760px 移动端 `.review-flow-layout` 为 `height: auto`（页面自然高度）→ 滚动容器在移动端退化为自然高度不产生内滚（`overflow-y: auto` 无高度约束不生效），无需额外处理。

## 变更记录

- 2026-08-27：创建计划，派发前端实施子代理。
- 2026-08-27：段 1 交付并通过独立审查，全部完成，无实质偏差（子代理勘察时执行过一次只读 git status，无写入）。
