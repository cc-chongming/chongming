# AIREVIEW-PLAN-059 议题串行辩论：服务端焦点闸门

状态：✅ 完成

## 背景
真实评审截图：7 个议题并行开题后角色被 7 路信封淹没，模型调用排队；收敛看门狗预算耗尽，
forceConvergence 把全部议题 ESCALATED“超时裁决”；产品经理的答辩 submit_claim 在轮次失活后被拒，
辩论对话流全程为空。用户拍板：先做议题串行。

## 根因
DEBATE 阶段所有非终态议题同时接收调度信封（服务端 CHALLENGE/REBUTTAL 自动签发 + 协调者 DEFENSE 等），
并发度=议题数×角色数；单模型网关串行处理能力远低于此，信封过期/无进展触发强制收敛。

## 方案（服务端确定性焦点闸门，不依赖模型自觉）
- [AIREVIEW-PLAN-059#1] AgentScopeProperties 增 `debateSerialTopics`（@DefaultValue("true")）。
- [AIREVIEW-PLAN-059#2] 新增 DebateFocusResolver：focus(review)=store 列表序第一个非终态议题；全终态为空。
- [AIREVIEW-PLAN-059#3] DispatchDebateActionTool 串行闸：proposal.topicId != focus 时返回工具错误
  （中文说明当前焦点与排队规则），不签发；焦点议题放行。
- [AIREVIEW-PLAN-059#4] ReviewWorkflowDispatcher：
  - issueChallengeDispatches / issueRebuttalDispatch 串行跳过非焦点议题（日志 SERIAL_SKIP）；
  - DEBATE_TOPIC_OPENED 唤醒附焦点指令（仅为焦点议题签发 DEFENSE 等；其余排队）；
  - DEBATE_TOPIC_CLOSED 唤醒附“下一焦点议题=<id>”或“全部终态 begin_judging”；
  - 焦点前进到“双方齐备但从未被质询”的议题时补发 SERVER_CHALLENGE（复用幂等键不叠加）。
- [AIREVIEW-PLAN-059#5] BeginSecondRoundTool 串行闸：仅焦点议题可开第二轮。
- [AIREVIEW-PLAN-059#6] DebateConvergenceGuard 预算按议题数缩放：wakes 与墙钟 ×max(1, n)；
  no-progress（PT6M）与 expired-dispatch 两路快速救援不变，死评审仍分钟级收敛。
- [AIREVIEW-PLAN-059#7] 测试：focus 解析、工具串行拒绝、dispatcher 非焦点跳过、guard 缩放；全量回归。

## 文件清单
- src/main/java/ai/cc/chongming/review/config/AgentScopeProperties.java（#1）
- src/main/java/ai/cc/chongming/review/application/DebateFocusResolver.java（#2 新增）
- src/main/java/ai/cc/chongming/review/infrastructure/agentscope/ReviewDebateToolFactory.java（#3、#5）
- src/main/java/ai/cc/chongming/review/infrastructure/agentscope/ReviewWorkflowDispatcher.java（#4）
- src/main/java/ai/cc/chongming/review/application/DebateConvergenceGuard.java（#6）
- 对应测试文件（#7）

## 顺序
1→2→3→4→5→6→7→全量后端回归（JAVA_HOME=D:/Tool/Java21 ./mvnw.cmd test）→父代理审查→提交。

## 风险
- 串行拉长总时长：guard 预算按 n 缩放兜底；快速救援信号不变；
- 存量在途评审：开关默认 true 对并行在途评审立即生效，焦点=列表序第一个非终态，已发信封可继续消费（resolveForWrite 不校验焦点），仅新签发受闸；
- 前端无需改动（议题 Tab 天然展示焦点推进）。

## 变更记录
- 2026-08-28 立计划；派发后台子代理实施。
- 2026-08-28 子代理失败（根因 LRN-20260828-002：会话默认 qorder 已删除）；父代理按本计划直接实施 #1-#7：
  全量后端回归 789 通过/0 失败/30 skip（783 基线+6 新用例）；父代理独立审查后提交。
