# AIREVIEW-PLAN-039 支持类Claim展示口径修正

> **状态**: ✅ 全部完成
> **创建日期**: 2026-08-27
> **目标**: SUPPORT 立场的 Claim 不再显示为“P2 改进/改进建议”，徽章与角色摘要按立场口径显示“支持”。

## 背景

- 用户批注（独立审查页，产品经理卡片）：两条 SUPPORT·P2 的 Claim 徽章显示“P2 · 改进”，摘要显示“发现 4 项：0 项阻断、2 项高风险、2 项改进建议”。支持类论点被标成“改进建议”语义错误；用户期望摘要为“发现 4 项：0 项阻断、2 项高风险、2 项支持”。
- 现状勘察：`ReviewClaimList.vue` 徽章纯按 severity 映射（P0 阻断/P1 高风险/P2 改进/P3 提示，忽略 position）；`review-live-presenter.js` 的 `claimOverview` 也纯按 severity 计数。

## 分段方案

### 段 1：立场感知的类别标签（徽章 + 摘要同口径）

**规则（用户字面口径）**：
- SUPPORT 且 severity ∈ {P2, P3} → 类别“支持”；
- 其余按 severity：P0 阻断、P1 高风险、P2 改进（建议）、P3 提示（即 P0/P1 的 SUPPORT 仍计入阻断/高风险，OPPOSE 全部沿用严重度口径）。

**涉及文件**：
- 修改：`frontend/src/services/review-live-presenter.js`（新增 `claimCategoryLabel(claim)`；`claimOverview` 改为四段式“发现 N 项：a 项阻断、b 项高风险、c 项改进建议、d 项支持”，P3·OPPOSE 非零时追加“、e 项提示”）
- 修改：`frontend/src/components/ReviewClaimList.vue`（徽章改用 `claimCategoryLabel`，文案 {severity} · {类别}）
- 修改：`frontend/src/services/review-live-presenter.test.js`（更新既有用例 + 支持类计数用例）

**关键实现细节**：
- `claimOverview` 分桶：阻断 = P0（全部）、高风险 = P1（全部）、改进建议 = P2 且非 SUPPORT、支持 = SUPPORT 且 P2/P3、提示 = P3 且非 SUPPORT。
- 徽章保留原 `flow-severity` 严重度色类不变，仅文案变化。
- 不改后端、不改辩论法庭视图的 severity 徽章（那边展示的是严重度本身）。

## 文件清单

### 修改

| 文件 | 计划段 | 状态 |
|------|--------|------|
| `frontend/src/services/review-live-presenter.js` | #1 | ✅ |
| `frontend/src/components/ReviewClaimList.vue` | #1 | ✅ |
| `frontend/src/services/review-live-presenter.test.js` | #1 | ✅ |

## 实施顺序

1. **步骤 1** ✅ → 前端子代理实施段 1（与 PLAN-038 后端段并行，零文件冲突）。
2. **步骤 2** ✅ → 独立审查通过；vitest 148/0（145 基线 + 3 新增，含 1 个既有用例更新为四段式）。
3. **步骤 3** ✅ → 与 PLAN-038/040 一起构建、同步、提交推送。

## 风险与应对

- **风险**：SUPPORT·P0/P1 仍显示阻断/高风险，用户可能期望一切支持都显示“支持” → 按用户给出的字面目标串实现（其示例中 P1 支持仍计高风险）；如需全立场口径，改 `claimCategoryLabel` 一处即可。
- **风险**：既有测试断言旧文案 → 同步更新旧用例为四段式（该例无 position，视为非 SUPPORT，计入改进建议、支持为 0）。

## 变更记录

- 2026-08-27：创建计划，派发前端实施子代理。
- 2026-08-27：段 1 交付并通过独立审查，无契约偏差；产品经理实例输出与用户期望一致。
