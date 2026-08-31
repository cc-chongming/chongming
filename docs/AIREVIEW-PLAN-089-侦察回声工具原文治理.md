# AIREVIEW-PLAN-089 治理模型回声工具原文：前端确定性删除+提示词禁止+后端上限收紧

状态：✅ 完成

## 背景
评审 12d161cd：scout 把 [BEGIN_UNTRUSTED_TOOL_RESULT tool=grep_files] 包裹的工具原文
整段抄进自己的 TEXT 流，公开对话页喷出一大段代码。标记来自 AgentScopeModelBridge 的不可信包装；
flash 模型复述行为；工具结果虽有截断但回声音量大。

## 方案
- [#1] runtime-conversation-adapter.js 导出 sanitizeEchoedToolDump(text)：
  首个 '[BEGIN_UNTRUSTED_TOOL_RESULT' 处截断，前段 trimEnd + '\n\n…（工具原文已省略，详见工具调用组）'；
  buildRuntimeConversation 的 TEXT_MESSAGE_END 分支对 item.content 应用（主面板与抽屉共用 runtimeItems）。
- [#2] ContextScoutHarnessFactory scout 提示词 avoid 增：“不得在正文中引用或复述
  [BEGIN_UNTRUSTED_TOOL_RESULT] 包裹的工具原文；工具发现一律用自己的话概述。”
- [#3] AgentScopeModelBridge：MAX_TOOL_RESULT_CONTEXT_CHARS 若 >4000 收紧为 4000。
- [#4] adapter 单测：含标记文本→截断+后缀；无标记→原样。

## 变更记录
- 2026-08-31 立计划；派发后台子代理实施。
- 2026-08-31 子代理 cc8c4d86 交付；MAX 12000→4000；vitest 166 全绿；产物同步；提交。
