# AIREVIEW-PLAN-064 收敛尾端幂等：begin_judging 容忍先行收敛+唤醒语阶段感知

状态：✅ 完成

## 背景
评审 399d2994 尾端：看门狗 forceConvergence 先关题并 beginJudging；随后 DEBATE_TOPIC_CLOSED 事件
唤醒协调者，协调者按协议调 begin_judging 被 ILLEGAL_STATE_TRANSITION 拒绝（stage 已 JUDGING）。
“一根筋变两头堵”：等不到事件时沉默，等到时动作又因服务端已先行而非法。功能无碍（Judge 已派发）
但拒绝是噪音且浪费协调者 turn。

## 方案
- [AIREVIEW-PLAN-064#1] DebateService.beginJudging 幂等容忍：stage 已为 JUDGING/WAITING_HUMAN/NOTIFYING/
  COMPLETED 时 LOGGER.info(BEGIN_JUDGING_REPLAYED) 直接返回，不抛异常、不重复发布 JUDGING_STARTED；
  validateBeginJudging 保持严格（状态机审计不变）。
- [AIREVIEW-PLAN-064#2] ReviewWorkflowDispatcher 的 DEBATE_TOPIC_CLOSED 分支阶段感知：解析 review，
  若 stage 已离开辩论阶段（JUDGING 及之后）改发“评审已进入裁决（服务端收敛先行完成），本轮无需任何动作，
  不要再次调用 begin_judging。”；否则维持现有文案。
- [AIREVIEW-PLAN-064#3] 测试：DebateService 已 JUDGING 再 beginJudging→无异常且无第二个 JUDGING_STARTED；
  dispatcher 在 JUDGING 下收到 DEBATE_TOPIC_CLOSED→director 唤醒语含“无需任何动作”。

## 文件清单
- src/main/java/ai/cc/chongming/review/application/DebateService.java
- src/main/java/ai/cc/chongming/review/infrastructure/agentscope/ReviewWorkflowDispatcher.java
- 对应测试

## 风险
- 幂等早返不发布事件，重复收敛不再产生重复 JUDGING_STARTED（去重收益）；
- 真非法路径（辩论中议题未终态）仍由 validateBeginJudging 严格拒绝。

## 变更记录
- 2026-08-28 立计划；派发后台子代理实施（与 PLAN-063 并行，文件零冲突）。
- 2026-08-28 子代理 8a93f9a9 交付；父代理审查 diff 无夹带；回归 812 全绿；提交。
