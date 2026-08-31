# AIREVIEW-PLAN-073 被动落位节奏层：每阶段最少停留，杜绝闪渡

状态：✅ 完成

## 背景
用户反馈：4/4 初审完成后应即刻跳冲突检测；实际冲突检测被服务器快速推进挤压成“闪一下”才到多轮辩论。
服务器 INITIAL_REVIEW→CONFLICT_DETECTION→DEBATE 可能只隔一次协调者模型调用（数秒），
前端被动落位如实追赶 → 中间阶段几乎不可见。

## 方案
- [#1] ReviewLiveView 增节奏层：pacedIndex ref + 8s 最少停留（MIN_DWELL_MS=8000）：
  - 目标 landing（activePhaseIndex）上升时：立即前进一步（保证 4/4 完成即刻进冲突检测），
    其后每步间隔 ≥8s（setTimeout 链），确保冲突检测等中间阶段至少可见 8s；
  - 目标下降或相等时直接同步（回退不延迟）；
  - 手动点选阶段（selectedPhase）不受节奏层影响，paced 直接跟随；
  - 组件卸载清 timer。
- [#2] activePhase 改为基于 pacedIndex（selectedPhase 优先）；徽章/phaseState 仍用服务器 activePhaseIndex 保持真实。
- [#3] 手动验证清单写入计划（无组件单测基建）：4/4 完成即刻进冲突检测；服务器连跳时冲突检测停留≥8s。

## 文件清单
- frontend/src/views/ReviewLiveView.vue

## 变更记录
- 2026-08-31 立计划；派发后台子代理实施。
- 2026-08-31 子代理 f5196278 交付；父代理审查 diff 无夹带；vitest 164 全绿；产物同步；提交。
- 2026-08-31 hotfix be8b500：immediate watch 在 setup 期强制求值 activePhaseIndex，触碰后定义的 computed 触发 TDZ 白屏；
  改为非 immediate watch + onMounted 首帧直接落位（dwell 仅约束会话内后续推进）。
- 2026-08-31 hotfix2：Vue watch 的源函数在 watch 创建时即求值（与 immediate 无关），
  位于 computed 定义之前的 watch 仍 TDZ；将该 watch 移至全部 computed 之后。
