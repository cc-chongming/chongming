# AIREVIEW-PLAN-070 初审收尾噪音治理：完成后静音+judge 待命+唤醒语免疫

状态：✅ 完成（ReviewLivenessGuard 合并提交见 PLAN-069 提交说明）

## 背景（评审 0e9cc9d7 对话梳理）
1) 角色被 finalizer 代为收尾后仍被唤醒重做工作、复读长文结论；
2) 裁决者 spawn 即行动（查空议题、试 draft_gate 被拒）；
3) 重复唤醒放大抽屉噪音。

## 方案
- [#1] AgentScopeReviewRuntimeAdapter.send：目标为 review 角色且 review.stage()==INITIAL_REVIEW
  且该角色 activation.initialReviewCompleted 为真时，跳过投递并日志 ROLE_WAKE_SKIPPED_COMPLETED
  （信封投递 deliverDispatchCommand 不受影响——辩论期信封合法）。
- [#2] 裁决者 spawn/首转提示词增待命约束：“在收到服务端进入 JUDGING 的唤醒前保持待命：
  不主动查询议题、不起草门禁、不提交裁决。”（ContextScout/RoleSubagent/ Judge 提示词所在工厂，以读到的为准）
- [#3] ReviewLivenessGuard 初审重唤醒文案追加：“若你的初审已完成（initialReviewCompleted），
  不要重提交任何评估/主张，不要重贴结论，仅一行确认。”
- [#4] 测试：adapter 跳过用例；judge 提示词含待命句断言；guard 文案断言。

## 文件清单
- src/main/java/ai/cc/chongming/review/infrastructure/agentscope/AgentScopeReviewRuntimeAdapter.java
- judge 提示词工厂、ReviewLivenessGuard.java、对应测试

## 变更记录
- 2026-08-28 立计划；派发后台子代理实施（与 068 并行零冲突）。
- 2026-08-28 子代理 0891f356 交付；父代理审查无夹带；回归 825 全绿；独占文件先行提交，LivenessGuard 与 069 合并提交。
