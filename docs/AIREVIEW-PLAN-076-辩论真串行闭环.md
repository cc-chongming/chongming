# AIREVIEW-PLAN-076 辩论真串行闭环：质询队列串行+服务端确定性关题

状态：✅ 完成

## 背景
用户拍板：对辩按“主→子→主”闭环串行。现状两个结构性错配：
A. CHALLENGE 并行扇出 × 状态机乒乓串行 → N-1 个信封到达即被拒；
B. 关题/进下一题依赖协调者手动工具调用 → 协调者停摆=整桌冻住。

## 方案
- [#1] 质询队列串行：issueChallengeDispatches 改为 advanceChallengeQueue(topic)：
  OPPOSE 角色按 claim 挂载序排队（排除 SUPPORT 角色）；只为队列中第一个
  “本轮无 CHALLENGE turn 且无 PENDING CHALLENGE 命令”的角色签发；
  触发点：DEBATE_TOPIC_OPENED、defense CLAIM_SUBMITTED、REBUTTAL_SUBMITTED、
  DISPATCH_COMMAND_CONSUMED/EXPIRED（事件驱动推进队列）。
- [#2] 服务端确定性关题：ReviewLivenessGuard 的 DEBATE 分支增静默关题：
  焦点议题无 PENDING 命令且 ≥90s 无新 turn 且（已有 turn 或队列耗尽）→
  DebateService.closeTopic（有 REBUTTAL→RESOLVED，否则 ESCALATED，
  服务端元数据幂等键 liveness-close:<topicId>，publicResolution 注明确定性收口）；
  焦点自动前进（focus resolver 取下一非终态），既有 DEBATE_TOPIC_CLOSED 唤醒协调者监督。
- [#3] 全议题终态自动 begin_judging（064 已幂等）放入同一 DEBATE 分支，协调者不再必经。
- [#4] 测试：双 OPPOSE 队列串行（第一个消费+答辩后第二个才收到）；过期跳过推进；
  静默关题 RESOLVED/ESCALATED 两路；全终态自动 begin_judging。

## 文件清单
- ReviewWorkflowDispatcher.java（队列串行）、ReviewLivenessGuard.java（静默关题+自动 judging）、对应测试

## 变更记录
- 2026-08-31 用户确认串行方向；立计划。
- 2026-08-31 子代理 737d6d75 交付（队列串行/静默关题/自动 judging，5 新用例）；父代理核验标记齐全+回归 EXIT=0；与 075 合并提交。
