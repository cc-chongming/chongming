# AIREVIEW-PLAN-082 二轮条件纳入实质分歧：REBUTTED 且有 P0/P1 未撤回 OPPOSE 允许二轮

状态：✅ 完成

## 背景
评审 1e0759c7：4 个已关闭议题全 1 轮。协调者 38 次 begin_second_round 均被
`requiresSecondRoundAction` 拒绝：REBUTTED 且无未答证据请求→false。状态机只看动作队列
是否空，不看分歧是否消解；串行队列单 OPPOSE 议题一轮 ping-pong 后即 REBUTTED → 必 1 轮。

## 方案
- [#1] DebateStateMachine.requiresSecondRoundAction 增重载 (topic, claims)：REBUTTED 分支返回
  hasUnansweredEvidenceRequest || claims 中存在未撤回 P0/P1 OPPOSE（实质分歧留存→允许二轮）；
  旧签名委托空 claims 保持其他调用点语义。
- [#2] DebateService.beginTopicSecondRound 调用点改传 topic claims（debateStore 取 claimIds 映射）。
- [#3] 硬上限不变（二轮封顶）；R2 内串行队列按“本轮无 CHALLENGE turn”重新武装（076 已按 round 判定）。
- [#4] 测试：REBUTTED+P1 OPPOSE→允许二轮；REBUTTED+仅 P2/P3→拒绝；OPEN/CHALLENGED 不变。

## 变更记录
- 2026-08-31 立计划；派发后台子代理实施。
- 2026-08-31 子代理 1348438f 交付；6 新用例覆盖 REBUTTED±P0/P1、terminal、OPEN/CHALLENGED；回归 847 全绿；提交。
