# AIREVIEW-PLAN-090 写工具结果经 ToolEmitter 流式回灌——trace 不再“未返回文本”

状态：✅ 完成

## 背景
submit_claim/dispatch_debate_action 等写工具返回块状 ToolResultBlock，AS2 不为其发
ToolResultTextDeltaEvent → ScoutToolTraceCollector 输出为空 → 公开 trace 显示
“工具未返回文本内容/暂无数据”。读工具有 delta 流故正常。

## 方案
- [#1] 两个工具工厂（ReviewRoleToolFactory/ReviewDebateToolFactory）增静态助手
  streamResult(ToolCallParam, ToolResultBlock)：param.getEmitter() 非 null 时 emit 同一 block，
  try/catch 吞异常（发射失败不影响工具语义）。
- [#2] 所有写/调度工具成功路径返回前调用：submit_assessment/submit_claim/complete_initial_review/
  submit_challenge/submit_rebuttal/change_claim_position/request_additional_evidence/
  dispatch_debate_action/close_debate_topic/begin_second_debate_round/submit_judgement/draft_gate 等。
- [#3] 测试：ToolCallParam.builder() 配捕获型 ToolEmitter，断言 submit_claim 与 dispatch_debate_action
  成功时发射了含结果文本的 block；失败路径不发射或发射错误文本均可（断言不抛异常）。

## 变更记录
- 2026-08-31 立计划；派发后台子代理实施。
- 2026-08-31 子代理 bf6a7611 交付；2 新用例；全量绿（含 089 后续测试修正）；提交。
