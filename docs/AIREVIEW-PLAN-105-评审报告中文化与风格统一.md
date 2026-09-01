# AIREVIEW-PLAN-105 评审报告中文化与风格统一

状态：✅ 完成

## 背景

评审报告页（ReviewReportView.vue）存在三类体验问题（用户截图标注）：
1. 中英夹杂：概览 KPI「Claim」、章节「Claim 清单」、空态文案「Claim / Scout」、辩论状态 ESCALATED 直显英文等；
2. 角色标识为 emoji/文本符号（💡🎨 等），与实时观察台/人工决策使用的角色头像资产不一致；
3. 「辩论与 Judge 裁决」区的裁决理由是一整段「裁决理由：核心争议：… 采信依据：… 未决点：…」，
   与人工决策面板（PLAN-102/104）的「中文标题 + 核心争议 / 采信依据 / 未决点 竖排分段」风格不一致。

## 方案

- [#1] 中文化：概览 KPI「Claim」→「主张」；「Claim 清单」→「主张清单」；表格空态与采信/拒绝空态文案中的 Claim→主张；
  roleMeta 的 CONTEXT_SCOUT 标签「Scout」→「侦察」；debateStatusMap 增加 ESCALATED→「升级裁决」；
  「Judge 结论」徽章→「裁决结论」；章节标题「辩论与 Judge 裁决」→「辩论与议题裁决」。
- [#2] 角色头像：检查点结论条目头与主张清单角色列改用 RoleAvatar 组件（assets/role-avatars 资产）+ 中文角色名，
  替换 roleLabel() 的 emoji 拼接；为报告页头像槽位提供尺寸样式（约 1.15rem 圆角）。
- [#3] 裁决区风格统一：辩论卡片头部显示中文议题标题（judgement.title ?? subjectKey）+ subjectKey 小字；
  裁决理由改用 judgementReasonBlocks(entry.judgement.reason) 竖排分段（标签独立成行加粗），
  卡片视觉对齐人工决策面板议题卡（白底、蓝色左边条、圆角），采信/拒绝两列保留但样式向人工面板主张列表靠拢。
- [#4] 整体微调：统一卡片圆角/边框/间距，KPI 数字与标签排版微调，保持与平台浅色风格一致；不做大改版。

## 文件清单

- frontend/src/views/ReviewReportView.vue（模板 + 组件样式 + 引入 RoleAvatar / judgementReasonBlocks）

## 验证

- npx vitest run --exclude 'tests/**' 全绿；npx vite build 通过并同步 static/review 与 target/classes/static/review；
- 报告页概览/清单/裁决区无英文协议词直显，角色为头像资产，裁决理由分段竖排。

## 变更记录

- 2026-09-01 立计划；派发后台子代理实施；回归 172 全绿，构建同步，随 PLAN-106 一并提交。
