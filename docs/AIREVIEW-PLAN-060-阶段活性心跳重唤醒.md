# AIREVIEW-PLAN-060 阶段活性心跳：空窗重唤醒与确定性收口

状态：✅ 已实施（全量回归通过：807 run / 0 fail / 0 err / 30 skip）

## 背景
用户反馈“感觉像卡住”。盘点：推进是事件驱动毫秒级，无轮询周期；感知卡顿来自模型 turn 时延
（单次上限 3 分钟）与串行投递队列；真正危险是唤醒丢失/模型空回后**无重试**——辩论段有 60s 扫描
（6 分钟无进展窗）救援，独立审查/冲突检测/裁决三段无限等待。

## 方案
- [AIREVIEW-PLAN-060#1] 新增 ReviewLivenessGuard（@Service，实现 ReviewEventListener，@Scheduled PT60S）：
  onCommitted 记录每 attempt（reviewId:attemptNo）的 lastActivityAt 与 stage；终态事件清除。
- [AIREVIEW-PLAN-060#2] scan：idle > livenessRewakeIdle（默认 PT90S）且 stage 属于
  {INITIAL_REVIEW, CONFLICT_DETECTION, JUDGING} 时按段重唤醒（adapter.send，标签口径同 dispatcher）：
  - INITIAL_REVIEW：每个未 completed 的已激活角色收提醒；
  - CONFLICT_DETECTION：协调者收 register/skip 指令（同 dispatcher 唤醒文案）；
  - JUDGING：Judge 收裁决指令；
  - DEBATE/PLANNING 不覆盖（分别已有收敛看门狗与 scout 自身预算）。
- [AIREVIEW-PLAN-060#3] 重唤醒计数 per attempt+stage，stage 变化重置；超过 livenessMaxRewakes（默认 3）确定性收口：
  - CONFLICT_DETECTION：ConflictDetectionService.detect 召回候选→镜像 ListConflictCandidatesTool 映射批量
    DebateService.registerTopics（服务端元数据幂等键），无候选则 skipDebateWhenNoConflicts；
  - JUDGING：JudgeService.draftGate（GatePolicy 确定性草稿→WAITING_HUMAN）；
  - INITIAL_REVIEW：走 ReviewCommandService 既有 FAILED 路径，reason 注明活性超时与未完成角色。
- [AIREVIEW-PLAN-060#4] AgentScopeProperties 增 livenessRewakeIdle(PT90S)/livenessMaxRewakes(3)。
- [AIREVIEW-PLAN-060#5] 测试：idle 触发重唤醒标签断言；计数上限触发三段收口；活动重置；终态清除。

## 文件清单
- src/main/java/ai/cc/chongming/review/application/ReviewLivenessGuard.java（新增）
- src/main/java/ai/cc/chongming/review/config/AgentScopeProperties.java
- src/test/java/ai/cc/chongming/review/application/ReviewLivenessGuardTests.java（新增）

## 风险
- 重唤醒与正常唤醒叠加=噪音，上限 3 次可控；
- 串行队列头阻塞（concatMap）不在本计划改动（协议顺序安全优先），心跳把“卡死”封顶为≈90s+模型时延；
- 进程重启后监听状态丢失：恢复服务会发事件重建跟踪，可接受（同收敛看门狗）。

## 变更记录
- 2026-08-28 立计划；派发后台子代理实施。
- 2026-08-28 实施完成：ReviewLivenessGuard + 配置 + 8 项测试；全量回归通过（`JAVA_HOME=D:/Tool/Java21 ./mvnw.cmd -q test`）。
