# AIREVIEW-PLAN-041 评审流程节点图标替换

> **状态**: ✅ 全部完成（含段 2 大标题图标）
> **创建日期**: 2026-08-27
> **目标**: 评审 Live 页左侧流程节点改用用户提供的“重明 需求评审流程图标（三状态）”设计稿，未开始/进行中/已完成随阶段状态切换。

## 背景

- 用户提供图标包 `D:\Users\Downloads\重明_需求评审流程图标_三状态.zip`（已解压至 `output/phase-icons-src/重明_需求评审流程图标_三状态/`）：7 节点 × 3 状态目录（未开始=灰蓝低饱和、进行中=蓝色高亮、已完成=绿色），1254×1254 RGBA，单张约 1.3MB，直接入包会膨胀 22MB。
- README 节点序：01 上传收案 / 02 评审规划 / 03 独立审查 / 04 冲突检测 / 05 多轮辩论 / 06 裁决者裁决 / 07 人工决策。
- 现状：`ReviewLiveView.vue` 左侧 `nav.flow-pipeline` 每个阶段按钮渲染 `<span class="flow-phase-icon">{{ phase.icon }}</span>`（汉字字符 + CSS 圆形底色），状态由 `phaseState(index)` 给出 done/running/pending/failed。

## 分段方案

### 段 1：资产制备 + 状态化图标渲染

**资产制备**：
- PIL 缩放至 192×192（Lanczos），输出到 `frontend/src/assets/phase-icons/`，英文命名 `{phase}.{state}.png`：
  - phase：01→intake（对应 scout/上下文侦察）、02→planning、03→review、04→conflict、05→debate、06→judging、07→human；
  - state：未开始→pending、进行中→running、已完成→done。
- 21 张小图经 `import.meta.glob('../assets/phase-icons/*.png', { eager: true, import: 'default' })` 引入（Vite 哈希命名、代码分割）。

**视图渲染**：
- `phaseIcon(phaseId, state)`：scout→intake、director→planning、review→review、conflict→conflict、debate→debate、judge→judging、human→human；状态映射 done/running/pending，failed 复用 running 图。
- 按钮内改为 `<img :src="phaseIcon(phase.id, phaseState(index))" :alt="phase.name">`，替换汉字字符。

**样式**：
- `.flow-phase-icon` 改为透明底图片容器（去掉圆形底色与字符色规则对 img 的干扰），`img { width/height 100%; object-fit: contain; display: block }`，尺寸保持 1.8rem（≤760px 为 1.35rem）。
- failed 状态在图标上加红色描边/外圈（box-shadow 或 outline），区分“进行中”。
- 状态色以 PNG 自带为准（灰蓝/蓝/绿），CSS 不再叠加状态底色。

## 文件清单

### 新建

| 文件 | 计划段 | 状态 |
|------|--------|------|
| `frontend/src/assets/phase-icons/*.png`（21 张缩放图，共 840KB） | #1 | ✅ |

### 修改

| 文件 | 计划段 | 状态 |
|------|--------|------|
| `frontend/src/views/ReviewLiveView.vue` | #1 | ✅ |
| `frontend/src/styles/review.css` | #1 | ✅ |

## 实施顺序

1. **步骤 1** ✅ → 前端子代理实施段 1（资产制备 + 渲染 + 样式），vitest 148 全绿；不构建、不提交。
2. **步骤 2** ✅ → 独立审查通过（glob 映射含缺失回退、failed 红圈双主题、移动端 1.35rem、资产 21 张 192×192 共 840KB）；构建产物 index-Dq3aiBhJ.js / index-C06oGCOl.css，同步 `target/classes/static/review`，运行服务已验证提供图标与新 bundle。
3. **步骤 3** ✅ → 提交推送。

## 风险与应对

- **风险**：1254px 原图入包膨胀 → 缩放 192×192（显示尺寸 1.8rem 的 6 倍以上，Retina 无压力）。
- **风险**：PNG 自带视觉与浅色面板冲突 → 状态色以设计稿为准，CSS 去底色；透明背景直接置于面板色上。
- **风险**：failed 态无对应设计稿 → 复用进行中图 + 红色描边区分。

## 变更记录

- 2026-08-27：创建计划，派发前端实施子代理。
- 2026-08-27：段 1 交付并通过独立审查。偏差均在契约允许范围：容器圆角 50%→.45rem（PNG 自带视觉）；移除对 img 无效的字符上色/圆底/脉冲动画；failed 复用 running 图 + 红圈描边。
- 2026-08-27：段 2（用户批注“换一下”）：阶段大标题 `flow-phase-header h1` 的字符改为同源三状态图标（`phaseIconUrl(阶段, 当前 phaseState)`），新增 `.flow-phase-title-icon`（1.55rem）。vitest 复绿、构建部署完成。
