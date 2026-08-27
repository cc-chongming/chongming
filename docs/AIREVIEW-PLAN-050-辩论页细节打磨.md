# AIREVIEW-PLAN-050 辩论页细节打磨：标题固定/看板压短/Claim卡弹框

> **状态**: ✅ 全部完成
> **创建日期**: 2026-08-27
> **目标**: 对话流标题固定不随列表滚动；对抗看板占比压低；Claim 卡固定行高截断，悬浮看全文（title），点击弹框看全文。

## 背景

- 用户批注（辩论页）：1) “📜 辩论对话流 · R1”标题不应随内容滚动，应滚动的是其下的回合列表；2) 上方对抗看板（法庭块）太高，应压短；3) 每张角色 Claim 卡应固定行高，文字多则截断，悬浮看全文、点击打开弹框看全文。
- 现状（043#4 之后）：`.flow-debate-dialogue` 整个 section 滚动（标题跟着滚）；`.flow-debate-board` 与对话流块 flex 1:1 平分；Claim 卡 `.flow-debate-claim p` 无截断，长文本撑高卡片。

## 分段方案

### 段 1：三处打磨

**A 对话流标题固定**：`.flow-debate-dialogue` 改为 flex 列（overflow: hidden），标题 `.flow-conflict-heading` 不收缩固定在顶部，`.flow-dialogue-list` flex 填充 + `overflow-y: auto` 成为唯一滚动容器；空态提示行为不变。
**B 对抗看板压短**：两块弹性权重改为看板 2 : 对话流 3（约 4:6），法庭 `min-height` 20rem 降到 16rem 保证压短生效；看板仍保留自身内滚兜底。
**C Claim 卡定高 + 悬浮 + 弹框**：
- 卡片正文 3 行截断（`-webkit-line-clamp: 3`），卡片高度趋同，`cursor: pointer`；
- 悬浮：正文 `:title="claim.statement"` 原生提示全文；
- 点击：打开弹框显示全文（角色头像+角色名、严重度、完整陈述、技术标识），支持关闭按钮与遮罩点击关闭；弹框适用于支持方/质疑方/中立方全部 Claim 卡；
- 弹框样式：居中白卡、max-width 40rem、正文区超高内滚、DSH 浅色、z-index 高于页面内容。

**涉及文件**：修改 `frontend/src/views/ReviewLiveView.vue`（弹框状态与模板）、`frontend/src/styles/review.css`。

## 文件清单

### 修改

| 文件 | 计划段 | 状态 |
|------|--------|------|
| `frontend/src/views/ReviewLiveView.vue` | #1 | ✅ |
| `frontend/src/styles/review.css` | #1 | ✅ |

## 实施顺序

1. **步骤 1** ✅ → 前端子代理实施段 1 + vitest 回归（158 绿）。
2. **步骤 2** ✅ → 独立审查通过（标题固定选择器限定、看板/对话流 4:6 权重、Claim 卡三处统一、弹框层级 70 与内滚结构正确）；构建同步运行服务并验证 200；提交推送。

## 风险与应对

- **风险**：标题类共用 `.flow-conflict-heading`，滚动改造误伤冲突页标题 → 选择器限定在 `.flow-debate-dialogue >` 之下。
- **风险**：看板压短后法庭内容被裁 → 看板保留 `overflow-y: auto` 兜底内滚。
- **风险**：原生 title 与点击弹框重复 → title 提供快速悬浮预览，弹框提供完整阅读，两者并存不冲突。

## 变更记录

- 2026-08-27：创建计划，派发前端实施子代理。
- 2026-08-27：段 1 交付并通过独立审查，全部完成。说明项：`.flow-debate-split > .flow-debate-dialogue` 移除 `overflow-y:auto`（由容器 `overflow:hidden` 接管，列表独占滚动）；弹框头部固定、`.flow-claim-modal-body` 自身内滚；Esc 关闭未在本期范围。
- 2026-08-27：追加段 2（用户批注“两个滚动条长度没有计算好”）：固定 4:6 权重改为内容自适应——看板 `flex: 0 1 auto; max-height: 60%`（自然高度、封顶、超出内滚），对话流 `flex: 1 1 auto` 占据剩余空间；消除“一侧挤压出滚动条、另一侧大量留白”的高度错配。
