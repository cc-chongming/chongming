# AIREVIEW-PLAN-049 角色头像资产接入

> **状态**: ✅ 全部完成
> **创建日期**: 2026-08-27
> **目标**: 全部角色展示位由汉字字符头像换成设计稿 PNG 头像，字符保留为回退。

## 背景

- 用户提供“需求评审角色头像资产包”：11 个角色（CONTEXT_SCOUT→scout.png … JUDGE→judge.png），1254×1254 RGBA，圆形头像 + 柔和渐变 + 职责徽章视觉，自带映射表与回退字符文档。
- 现状：角色头像均为汉字字符（侦/协/产/项/前/后/架/安/测/能/裁），渲染点集中在两个组件：`ReviewLiveView.vue`（8 处 `.flow-agent-avatar`：运行流属主、启用决策行、审查卡、冲突协调者卡、议题 Claim 卡 ×3、对话流行）与 `LiveAgentConversation.vue`（2 处 `.agent-avatar`：消息行/省略占位）。
- 接入先例：PLAN-041 流程图标（import.meta.glob + 192 缩放 + 回退）。

## 分段方案

### 段 1：资产制备 + RoleAvatar 组件接线

**资产**：11 张 PNG 缩放 192×192 入 `frontend/src/assets/role-avatars/`（文件名不变）。
**新组件** `frontend/src/components/RoleAvatar.vue`：props `role`（大写代码）+ 可选 `fallback` 字符；内部经 `import.meta.glob('../assets/role-avatars/*.png', eager)` 取图，角色代码→文件名映射（CONTEXT_SCOUT→scout，其余小写直映）；有图渲染 `<img>`（圆形、object-fit: cover），无图回退字符。
**替换点**：ReviewLiveView.vue 8 处 `.flow-agent-avatar`（冲突协调者卡的硬编码“协”也换）+ LiveAgentConversation.vue 2 处 `.agent-avatar`；保留容器类与 data-role（样式钩子不变），文字内容改由组件承担。
**样式**：容器尺寸不变（1.8~2rem 系），img 圆形 `border-radius: 50%`、`object-fit: cover`；容器含 img 时去掉字符底色/边框干扰（以组件根类区分）；深色基础段与浅色覆盖段同步。

## 文件清单

### 新建

| 文件 | 计划段 | 状态 |
|------|--------|------|
| `frontend/src/assets/role-avatars/*.png`（11 张） | #1 | ✅ |
| `frontend/src/components/RoleAvatar.vue` | #1 | ✅ |

### 修改

| 文件 | 计划段 | 状态 |
|------|--------|------|
| `frontend/src/views/ReviewLiveView.vue` | #1 | ✅ |
| `frontend/src/components/LiveAgentConversation.vue` | #1 | ✅ |
| `frontend/src/styles/review.css` | #1 | ✅ |

## 实施顺序

1. **步骤 1** ✅ → 前端子代理实施段 1 + vitest 回归（158 绿）。
2. **步骤 2** ✅ → 独立审查**发现并修复子代理选择器失配**：`has-image` 加在内层 slot 而去干扰规则写成 `.flow-agent-avatar.has-image`（永不命中，头像会带灰底边框），改为 `.flow-agent-avatar:has(img)` / `.agent-avatar:has(img)`；构建产物头像以独立哈希文件输出（未内联），同步运行服务并验证 200；提交推送。

## 风险与应对

- **风险**：字符底色/边框残留到图片头像 → 组件根类区分，含 img 时容器背景透明。
- **风险**：遗漏渲染点 → 以 `flow-agent-avatar|agent-avatar` 全量 grep 核对。
- **风险**：资产体积 → 192 缩放后单张约 25-50KB，11 张 <600KB。

## 变更记录

- 2026-08-27：创建计划，派发前端实施子代理。
- 2026-08-27：段 1 交付（替换点 8+1 齐全、回退链正确）；审查修复容器去干扰选择器（:has(img)）；全部完成。
