# AIREVIEW-PLAN-034 对话流 DSH 视觉重构

> **状态**: ✅ 全部完成（补齐文档，实施先行）
> **创建日期**: 2026-08-26
> **目标**: 将评审工作台的流式对话与工具调用从传统气泡样式重构为 DSH 式简约单色排版，并修复相关的渲染、滚动与布局问题。

## 背景

评审实时页的对话流采用彩色气泡 + 高饱和角色头像的传统聊天样式，与项目转向的 stone 单色设计语言割裂；
工具调用为常驻盒子卡片，连续调用噪声大；流式滚动存在自动跟随自我锁死缺陷；
SafeMarkdown 围栏解析脆弱，且部分对话面未渲染 Markdown。本计划为事后补齐：实施已完成并提交（3fce4e9）。

## 分段方案

### #1 消息流去气泡与排版

- 目标：消息正文去盒（透明背景、无边框、留白分隔），行高与字号贴近文档式阅读。
- 修改：`frontend/src/styles/review.css`（.agent-answer、.live-agent-timeline 间距、消息条目差异化间距）。

### #2 工具调用分组与图标

- 目标：连续工具调用合并为默认折叠的分组行（Codex 式），图标从彩色 🔧 emoji 换为 currentColor 内联 SVG；
  输入/输出 JSON 用浅色等宽块呈现；分组 key 稳定，流式累积不打断用户展开状态。
- 修改：`LiveAgentConversation.vue`（rows 归组、groupStatusLabel）、`AgUiToolCallMessage.vue`（SVG 图标）、
  `review.css`（.tool-group、.tool-call-details pre 浅色、.tool-call-symbol 单色）。

### #3 Markdown 围栏解析加固与全面接入

- 目标：围栏容错（缩进、语言名前空格、3+ 反引号、未闭合流式围栏）；行内代码与宽表格不再撑出横向滚动；
  AgUiConversationPanel 与 AgentTraceDrawer 从纯文本插值改为 SafeMarkdown 渲染。
- 修改：`safe-markdown.js`（+ `safe-markdown.test.js` 新增回归测试）、`AgUiConversationPanel.vue`、`AgentTraceDrawer.vue`。

### #4 流式滚动跟随修复

- 目标：消除"滚不动"缺陷——程序滚动不再重新武装跟随状态；滚轮向上即释放跟随；回底自动恢复跟随。
- 修改：`LiveAgentConversation.vue`（programmaticScroll 守卫、onWheel、scrollToEnd 收口）。

### #5 布局与滚动条治理

- 目标：对话容器消除横向滚动条（内容层面换行/自滚 + 容器 overflow-x: hidden）；
  垂直滚动条与文本留白（padding-right）；"回到最新消息"按钮避让（左上移位）。
- 修改：`review.css`（.live-agent-scroll、.safe-markdown table/code、.conversation-latest、summary flex-wrap）。

### #6 角色中文化与头像单色化

- 目标：角色头像英文首字母改为中文单字（侦/协/审/裁/前/后/产/项/架/安/测/能）；
  Context Scout→上下文侦察、Director 协调者→协调者、Judge 裁决者→裁决者；
  流程轨图标 emoji 换为中文单字；全部头像统一单色 stone 风格（浅石底 + 墨字 + 发丝边框），删除按角色染色。
- 修改：`LiveAgentConversation.vue`、`ReviewLiveView.vue`、`ReviewConversationDrawer.vue`、
  `AgUiConversationPanel.vue`、`ContextScoutPreviewView.vue`、`ScoutConclusionPanel.vue`、`PlanPanel.vue`、
  `review.css`（.agent-avatar、.flow-agent-avatar 单色）。

### #7 生产包重建

- 目标：`npm run build` 重建至 `src/main/resources/static/review/`，与源码同批提交。

## 文件清单

### 修改

| 文件 | 计划段 | 状态 |
|------|--------|------|
| `frontend/src/styles/review.css` | #1 #2 #5 #6 | ✅ |
| `frontend/src/components/LiveAgentConversation.vue` | #1 #2 #4 #6 | ✅ |
| `frontend/src/components/AgUiToolCallMessage.vue` | #2 | ✅ |
| `frontend/src/services/safe-markdown.js` | #3 | ✅ |
| `frontend/src/services/safe-markdown.test.js` | #3 | ✅ |
| `frontend/src/components/AgUiConversationPanel.vue` | #3 #6 | ✅ |
| `frontend/src/components/AgentTraceDrawer.vue` | #3 | ✅ |
| `frontend/src/views/ReviewLiveView.vue` | #1 #5 #6 | ✅ |
| `frontend/src/views/ContextScoutPreviewView.vue` | #6 | ✅ |
| `frontend/src/components/ScoutConclusionPanel.vue` | #6 | ✅ |
| `frontend/src/components/PlanPanel.vue` | #6 | ✅ |
| `frontend/src/components/ReviewConversationDrawer.vue` | #6 | ✅ |
| `src/main/resources/static/review/**` | #7 | ✅ |

## 实施顺序

1. **步骤 1** ✅ 消息流去气泡（#1）
2. **步骤 2** ✅ 工具调用去盒 → 分组折叠 + SVG 图标（#2）
3. **步骤 3** ✅ 围栏解析加固 + 全面接入（#3）
4. **步骤 4** ✅ 滚动跟随修复（#4）
5. **步骤 5** ✅ 布局与滚动条治理（#5）
6. **步骤 6** ✅ 中文化与头像单色化（#6）
7. **步骤 7** ✅ 生产包重建并同步运行服务（#7）

## 风险与应对

- 去气泡后消息边界依赖留白：通过差异化条目间距（消息 1.5rem / 工具 0.65rem）保持层次。
- 横向滚动治理可能裁切内容：先在内容层（行内代码换行、表格自滚、summary 换行）消化宽度，容器层 overflow-x: hidden 仅作兜底。
- 分组 key 若含数量会在流式中重建节点：改为以首个工具调用 id 为 key，保留用户展开状态。

## 变更记录

- 2026-08-26：实施完成（提交 3fce4e9）；按 .codex/rules/plan-driven-development.md 补齐本计划文档。
