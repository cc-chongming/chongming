# 前端辩论工作台计划

> **状态**: ⏳ 待实施
> **创建日期**: 2026-07-14
> **目标**: 用一个可演示、可回放的工作台呈现计划、角色、真实辩论、证据、Gate 和人工审核流程。
> **前置计划**: PLAN-005、PLAN-010 接口冻结；PLAN-009/011 可用 Mock 数据并行

## 0. 背景与边界

核心界面不是“多个角色报告页”，而是辩论时间线。MVP 采用技术方案确定的 Vue 3 工作台：前端源码位于 `frontend/`，通过 Vite 开发和构建；生产构建产物由 Spring Boot 的静态资源目录托管。页面契约与状态模型保持独立，后续如调整构建方式不得改变 API、SSE 或事件语义。

## 1. 分段方案

### 1.1 信息架构与路由 ⏳

- 页面：创建评审、评审工作台、最终报告。
- 工作台区域：阶段进度、Plan、活跃角色、辩论时间线、证据抽屉、人工审核、系统状态。
- reviewId 放路由参数；刷新后从 API 重建全部状态。

### 1.2 创建评审 ⏳

- 上传 `.md`、输入仓库路径、可选 branch/commit；前端做友好校验但不替代后端。
- 展示上传、快照和启动阶段；错误显示 errorCode、可恢复操作和 traceId。
- 防重复点击，收到 202 后跳转工作台。

### 1.3 API Client 与本地 Store ⏳

- 封装 reviews、plans、debates、report、human-review-items API。
- Store 以 reviewId+sequence 幂等合并；attempt 用于展示与筛选，不作为事件去重键；不直接用 DOM 作为状态源。
- 类型/字段契约保存在 JSON 样本，后端变更需同步更新。

### 1.4 SSE 连接与恢复 ⏳

- 保存 lastSequence；断线指数退避并携带 Last-Event-ID。
- 重连期间显示状态，回放事件和实时事件按 sequence 去重排序。
- 页面隐藏/关闭时释放连接，避免重复订阅。

### 1.5 Plan 与角色面板 ⏳

- 展示总计划、版本历史、修订原因、任务进度和剩余预算。
- 展示核心/按需/Judge 角色、激活原因、状态、耗时和失败原因。
- 不展示角色隐藏思维链。

### 1.6 辩论时间线 ⏳

- 卡片类型：Claim、Challenge、Rebuttal、PositionChanged、Judgement、Escalated。
- 卡片显示 actor/target、round、targetClaim/Turn、严重度、立场、Evidence 和 Gate 影响。
- 支持按 topic、role、severity 过滤；自动定位最新事件但不抢用户滚动位置。

### 1.7 Evidence 查看与代码回跳 ⏳

- 抽屉展示快照绝对路径、单行号、excerpt、hash 和漂移状态。
- 代码内容 HTML 转义；敏感文件只显示拒绝原因。
- 支持从 Claim/Turn/Gate 反向打开证据，不允许客户端构造任意服务器路径读取。

### 1.8 人工审核工作台 ⏳

- 查询、新增、编辑、删除未提交审核条目，显示 version 冲突。
- 提交 PASS/CONDITIONAL/BLOCK/RETURN/OVERRIDE，overrideReason 必填。
- 已提交版本只读；调整时创建新版本并显示历史差异。

### 1.9 报告与通知状态 ⏳

- 展示结构化报告和安全渲染的 Markdown 报告。
- Gate 结果可下钻 Judge/Turn/Claim/Evidence。
- 展示通知 PENDING/SENT/FAILED/DEAD，不把通知失败显示成评审失败。

### 1.10 可用性和 Demo 收口 ⏳

- 中文专业界面、空状态、加载骨架、错误边界、键盘可操作和基础响应式。
- 颜色不是唯一状态信号；P0/P1、立场变化和人工介入有文本/图标。
- 固定 Demo 模式关闭随机动画，保证现场 6～8 分钟讲解节奏；五分钟录制视频遵循需求文档的独立脚本。

## 2. 文件清单

### 2.1 新建

| 文件                                                                    | 计划段           | 状态 |
|-----------------------------------------------------------------------|---------------|----|
| `frontend/package.json`                                               | #1.1、#1.10    | ⏳  |
| `frontend/vite.config.js`                                             | #1.1          | ⏳  |
| `frontend/index.html`                                                 | #1.1          | ⏳  |
| `frontend/src/main.js`                                                | #1.1          | ⏳  |
| `frontend/src/App.vue`                                                | #1.1          | ⏳  |
| `frontend/src/router/index.js`                                        | #1.1          | ⏳  |
| `frontend/src/styles/review.css`                                      | #1.1、#1.10    | ⏳  |
| `frontend/src/api/review-api.js`                                      | #1.3          | ⏳  |
| `frontend/src/stores/review-store.js`                                 | #1.3-1.4      | ⏳  |
| `frontend/src/views/ReviewCreateView.vue`                             | #1.2          | ⏳  |
| `frontend/src/views/ReviewWorkbenchView.vue`                          | #1.1、#1.5-1.9 | ⏳  |
| `frontend/src/views/ReviewReportView.vue`                             | #1.1、#1.9     | ⏳  |
| `frontend/src/components/PlanPanel.vue`                               | #1.5          | ⏳  |
| `frontend/src/components/DebateTimeline.vue`                          | #1.6          | ⏳  |
| `frontend/src/components/EvidenceDrawer.vue`                          | #1.7          | ⏳  |
| `frontend/src/components/HumanReviewPanel.vue`                        | #1.8          | ⏳  |
| `frontend/src/services/review-sse.js`                                 | #1.4          | ⏳  |
| `frontend/src/test/events-golden.json`                                | #1.3-1.6      | ⏳  |
| `frontend/tests/review-workbench.e2e.js`                              | #1.2-1.10     | ⏳  |

### 2.2 修改

| 文件                                   | 计划段   | 状态 |
|--------------------------------------|-------|----|
| `src/main/resources/application.yml` | #1.1  | ⏳  |
| `src/main/resources/static/review/`  | #1.10 | ⏳  |

## 3. 实施顺序

1. **步骤 1**：初始化 Vue 3/Vite 工程，冻结页面线框、API/SSE 黄金样本和 Store 测试场景。
2. **步骤 2**：实现 Vue 创建页、API Client、Store 和 SSE 恢复。
3. **步骤 3**：实现 Plan/角色面板、辩论时间线和 Evidence 抽屉。
4. **步骤 4**：实现人工审核、报告和通知状态。
5. **步骤 5**：执行浏览器 E2E、断线、刷新、安全渲染和可用性检查。

## 4. 验证与退出标准

- 使用固定事件样本可独立运行 UI，不等待真实模型。
- SSE 断线/刷新后无卡片重复、缺失或乱序。
- 从 Gate 到 Evidence 的反向导航在三次点击内完成。
- 人工草稿 CRUD、版本冲突、提交后只读符合后端契约。
- Markdown/代码/错误文本无 XSS；不从客户端请求任意文件路径。
- 主场景浏览器 E2E 连续通过三次。

## 5. 风险与应对

| 风险         | 应对                                    |
|------------|---------------------------------------|
| 事件类型持续变化   | 以 versioned fixture 驱动 Store，未知事件降级显示 |
| Vue 组件状态分散 | 按页面与组件边界拆分，状态集中在 Store，不在组件间隐式传递 |
| 展示“思维链”风险  | 只渲染公开字段，后端 DTO 也不返回隐藏推理               |

## 6. 变更记录

| 日期         | 变更                                   |
|------------|--------------------------------------|
| 2026-07-14 | 创建前端工作台、SSE Store、辩论时间线、人工审核和 E2E 计划。 |
| 2026-07-15 | 前端实现方案对齐技术方案：采用 Vue 3/Vite，并更新源码、构建与 E2E 文件清单。 |
| 2026-07-15 | SSE 去重键对齐技术方案：使用 reviewId+sequence，重试 attempt 不重置 sequence。 |
