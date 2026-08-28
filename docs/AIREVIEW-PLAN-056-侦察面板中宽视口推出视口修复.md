# AIREVIEW-PLAN-056 上下文侦察：中宽视口下运行流把结论卡推出视口修复

状态：✅ 完成

## 背景
用户反馈（截图，视口约 984×883）：上下文侦察阶段上方运行流输出越来越多，
把下方「结构化评审事实」结论卡一直往下压，最后看不到，整页出现外层滚动条。

## 根因
1. `.review-flow-layout` 固定视口高度，但行轨道约束 `grid-template-rows: minmax(0, 1fr)`
   只在 `@media (min-width: 1121px)` 内启用（PLAN-037#3）。761–1120px 行轨道为 auto：
   行高被内容撑开 → 固定高度 grid 容器整体溢出 → 页面外层滚动 → 结论卡被推出视口。
2. `.flow-content > .scout-conclusion-panel` 与运行流面板同为 `flex: 1 1 auto`，
   上方输出极长时 flex 收缩按内容基线比例分配，结论卡份额不稳定。

## 方案
- [AIREVIEW-PLAN-056#1] `grid-template-rows: minmax(0, 1fr)` 提升到 `.review-flow-layout`
  基础规则（全宽度生效；≤760px 为 block 布局不受影响）。≤1120px 时侧栏底部块占 auto 行，
  主行 1fr 吸收剩余高度，`.flow-content` 恢复内部滚动。
- [AIREVIEW-PLAN-056#2] 结论卡改 `flex: 0 1 auto; max-height: 45%`：按内容自适应但封顶
  主区高度 45% 并内部滚动（`.scout-conclusion-body` 已内滚），剩余高度让给运行流面板；
  上方输出再多结论卡也始终可见。

## 文件清单
- frontend/src/styles/review.css（#1、#2）

## 顺序
1. CSS #1 → 2. CSS #2 → 3. vitest 回归 → 4. vite build 并同步 static/review 与 target/classes → 5. 父代理审查提交。

## 风险
- 行轨道全宽生效同时影响 761–1120px 的 Director/Judge 阶段：原本同样被撑出外层滚动，
  修复后转为内滚，属预期收益；
- 结论卡内容不足 45% 时按内容自适应，不空占。

## 变更记录
- 2026-08-28 立计划；派发后台子代理实施。
- 2026-08-28 子代理再次中途失败（无输出、零写入，同 PLAN-055 现象）；父代理按本计划直接实施：
  #1 行轨道提升基础规则、#2 结论卡 45% 封顶；vitest 159 全绿；vite build 同步
  src/main/resources/static/review 与 target/classes/static/review；父代理独立审查 diff 无夹带后提交。
