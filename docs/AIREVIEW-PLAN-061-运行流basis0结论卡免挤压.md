# AIREVIEW-PLAN-061 运行流面板 basis 0：结论卡不再被长输出挤压

状态：✅ 完成

## 背景
用户反馈（截图）：上下文侦察窗口仍压缩「上下文收集结论」窗口，要求把侦察窗口放短。
PLAN-056 只给结论卡加了 45% 封顶，未阻止运行流面板以巨大内容基线参与 flex 溢出收缩：
输出越长基线越大，按基线比例收缩时小基线的结论卡先被压扁（截图仅剩一行）。

## 方案
- [AIREVIEW-PLAN-061#1] `.flow-content > .flow-stream-live` 由 `flex: 1 1 auto` 改 `flex: 1 1 0`：
  基线归零后运行流只领“容器剩余高度”，结论卡按内容高度完整渲染（仍受 45% 封顶与内滚约束），
  二者不再按内容基线互抢；侦察窗口自然变短。Director/Judge 同受益（流面板领其余区块之后的高度）。
- [AIREVIEW-PLAN-061#2] ≤760px block 布局回退：该媒体查询内 `.flow-content > .flow-stream-live { flex: 0 0 auto; }`，
  避免 auto 高容器里 basis 0 把流面板压成 0 高。

## 文件清单
- frontend/src/styles/review.css（#1、#2）

## 顺序
CSS #1 → #2 → vitest → vite build 同步 static/review 与 target/classes → 父代理审查提交。

## 风险
- 结论卡空态（等待结论）时运行流领剩余高度，与现状观感一致；
- 手动点选/其它阶段布局不受影响（仅 .flow-stream-live 参与）。

## 变更记录
- 2026-08-28 立计划；派发后台子代理实施。

- 2026-08-28 子代理 c0ecbc3f 交付（通道恢复后首次成功）；父代理独立审查 diff 无夹带，vitest 164 全绿，产物同步 target/classes；提交。
