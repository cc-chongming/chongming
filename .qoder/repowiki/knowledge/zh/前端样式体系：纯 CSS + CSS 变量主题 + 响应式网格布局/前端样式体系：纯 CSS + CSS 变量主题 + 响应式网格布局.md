---
kind: frontend_style
name: 前端样式体系：纯 CSS + CSS 变量主题 + 响应式网格布局
slug: frontend_style
category: frontend_style
scope:
    - '**'
---

## 1. 采用的系统/方法

- **技术栈**：Vue 3 + Vite，无 UI 组件库（如 Element Plus、Ant Design Vue），所有视觉样式通过手写 CSS 实现。
- **CSS 方案**：单一全局样式文件 `frontend/src/styles/review.css`（约 700 行），使用原生 CSS 变量（`:root` 与局部 `--flow-*`）组织主题色板；无 SCSS/Less/Tailwind 等预处理或原子化框架。
- **构建集成**：Vite 将前端产物直接输出到 Spring Boot 的 `src/main/resources/static/review`，作为静态资源由后端托管（见 `vite.config.js` 中 `build.outDir` 与注释 `[AIREVIEW-PLAN-012#1.1]`）。
- **设计参考**：大量样式区块以注释标注对齐 `docs/ui-patterns-demo/platform.html` 中的原型页面（如 dashboard、requirement create/list/detail、report detail、review workbench pipeline），说明样式是围绕文档驱动的原型逐步落地的。

## 2. 关键文件

- `frontend/src/styles/review.css`：唯一的全局样式源，涵盖平台外壳、表单、表格、时间线、辩论工作台、SSE 实时对话流、报告视图、暗/亮双主题等全部视觉表现。
- `frontend/vite.config.js`：定义 Vite 构建目标为 Spring Boot static 目录，并配置 `/api` 代理到后端。
- `frontend/package.json`：仅依赖 `vue`、`vue-router`、`@ag-ui/core`，无任何 CSS 相关依赖，进一步确认样式完全自管。
- `docs/ui-patterns-demo/*.html`：UI 原型 HTML，作为样式实现的对照基准（多处 CSS 注释引用）。

## 3. 架构与约定

### 3.1 主题系统（CSS 变量 + 双主题覆盖）
- 基础主题通过 `:root` 声明：`color-scheme: light`、字体族（`Microsoft YaHei` / `PingFang SC` / system-ui）、主色 `#172033`、背景 `#f4f7fb`。
- 工作流全屏模式使用独立命名空间 `--flow-*` 变量（`--flow-bg`、`--flow-surface`、`--flow-accent`、`--flow-green`、`--flow-red`、`--flow-yellow`），在 `.review-flow-page` 上集中声明，便于切换暗/亮两套配色。
- 暗色主题默认生效（`.review-flow-shell` 背景 `#0b0d13`），并通过后续选择器块（约第 586 行起）用同名类名覆盖变量值切换到“浅色全屏评审流”，形成同一套类名的双主题机制。

### 3.2 布局策略
- 采用 **CSS Grid + Flexbox** 组合：平台外壳 `.platform-shell` 使用 `grid-template-columns: var(--sidebar-w) minmax(0, 1fr)` 实现可折叠侧边栏；工作区 `.workbench-grid`、`.roundtable-layout`、`.review-flow-layout` 等均为多列 grid。
- 侧边栏宽度通过 CSS 变量 `--sidebar-w` 控制，配合 `.platform-shell.sidebar-collapsed` 切换为窄态（64px）。
- 无固定栅格系统，但通过 `minmax(0, 1fr)`、`clamp()`、`min(1440px, 100%)` 等现代 CSS 实现自适应。

### 3.3 组件级样式约定
- 每个功能区域使用语义化 BEM 风格类名：`.panel`、`.metric-card`、`.timeline-card`、`.flow-phase-button`、`.flow-review-card`、`.agent-avatar`、`.tool-call-details` 等，类名即组件边界。
- 状态通过 data 属性 + CSS 选择器表达：如 `[data-status="connected"]`、`[data-role="DIRECTOR"]`、`[data-active="true"]`、`.done/.running/.failed/.pending` 等修饰类，避免 JS 注入样式。
- 角色/严重级别通过专用类映射颜色：`.severity.P0~P3`、`.flow-severity.P0~P3`、`.tag-draft/pending/approved/done/blocked/dev`、`.kpi.k-ac/k-yl/k-gn/k-rd` 等，形成统一的视觉编码表。

### 3.4 响应式策略
- 使用 `@media (max-width: ...)` 断点：960px、760px、640px、620px、1200px 等多处断点，从桌面三栏 → 两栏 → 单栏渐进降级。
- 移动端侧边栏自动展开为顶部导航条（`.platform-nav { grid-template-columns: repeat(4, ...) }` 横向滚动）。
- 启用 `prefers-reduced-motion: reduce` 时禁用动画（第 187 行）。

### 3.5 与 AG-UI 集成
- 通过 `.ag-ui-workbench-panel`、`.ag-ui-messages`、`.ag-ui-message`、`.ag-ui-tool-call` 等类对 `@ag-ui/core` 输出的 DOM 进行主题覆盖，使第三方 Agent 对话 UI 融入统一风格。

## 4. 约定与约束

- **无 CSS 框架/预处理器**：仓库未引入任何 CSS 库，所有样式手写于单一文件中，新增样式需遵循现有命名约定（功能前缀 + 状态后缀）。
- **样式与原型对齐**：多处注释明确标注 `aligned with docs/ui-patterns-demo/platform.html`，新增页面样式应优先参照对应原型 HTML 的结构与视觉。
- **主题扩展方式**：新增颜色必须走 CSS 变量（推荐复用 `--flow-*` 或在 `:root` 中声明），禁止硬编码新色值破坏一致性。
- **响应式优先**：所有新增组件需考虑 `max-width: 760px` / `960px` 下的降级布局，避免破坏现有 grid 行为。
- **可访问性**：已内置 `skip-link`、`:focus-visible` 高亮、`prefers-reduced-motion` 支持，新增交互元素应保持同等标准。
- **构建约束**：样式随 Vite 构建打包进 Spring Boot static 目录，修改后需重新 `npm run build` 才能生效于后端服务。

## 5. 置信度评估

该仓库的前端样式体系清晰且完整：单一 CSS 文件承载全部视觉，基于 CSS 变量的主题系统、Grid/Flex 布局、响应式断点、AG-UI 主题覆盖均已在代码中稳定实现，并有文档原型作为设计依据。因此置信度为 high。
