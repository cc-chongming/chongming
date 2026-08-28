# AIREVIEW-PLAN-067 上下文侦察活动可见性：探索期工具组自动展开+scout 进度旁白

状态：✅ 完成

## 背景
用户两轮截图：scout 探索期面板只剩一行折叠“N 个工具调用”+大片空白（“给搞没有了”），
结论文本到达后又“突然弹出来”。根因：模型尾重型输出（中途无旁白）× 054 折叠 × basis-0 高面板。
scout 实际一直在工作，UI 未把活动可视化。

## 方案
- [AIREVIEW-PLAN-067#1] LiveAgentConversation.vue：tool-group 的 <details> 绑定 :open——
  组内仍有非终态工具（!groupDone(row)）或 rows 中尚无任何 message 行（纯探索期）时自动展开，
  让 read/grep/glob 活动实时可见；结论文本到达后自然回落折叠观感。
- [AIREVIEW-PLAN-067#2] ContextScoutHarnessFactory 的 scout 系统提示增一句：
  “每完成一批工具调用后，用一行简体中文简述本批发现与下一步（不要长篇）。”（promptVersion 递增）

## 文件清单
- frontend/src/components/LiveAgentConversation.vue
- src/main/java/ai/cc/chongming/review/infrastructure/agentscope/ContextScoutHarnessFactory.java

## 风险
- :open 绑定会在重渲染时覆盖用户手动折叠；探索期默认可见优先于手动偏好，可接受；
- 旁白为提示词级，模型不保证遵守，#1 的确定性兜底为主。

## 变更记录
- 2026-08-28 立计划；派发后台子代理实施。
- 2026-08-28 子代理 ab79c545 交付；父代理审查无夹带；vitest 164 全绿、ContextScout* 定向全绿；产物同步；提交。
