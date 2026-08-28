# AIREVIEW-PLAN-062 Scout 契约污染容错：relevance 无可用路径时回退无约束

状态：✅ 完成

## 背景
真实评审 399d2994：前端/后端角色均报“未授予任何仓库文件”，代码证据检查点全 UNKNOWN。
取证：Scout 结论 evidencePaths 全为自然语言描述句、roleScopes 为空、moduleRoots 亦散文。
relevance 谓词 `evidence.contains(相对路径) || under roleScopes` 对散文恒假 → 快照文件全被过滤 →
所有角色授权集为空（含前缀 "" 的角色）。机制按设计工作，但被污染契约饿死。

## 方案
- [AIREVIEW-PLAN-062#1] ReviewContextAssembler 增 pathLike 判定（归一化后无空白、无非 ASCII（≥0x2E80）字符，
  且含 "/" 或 "."）；evidencePaths/roleScopes 仅 pathLike 条目参与匹配。
- [AIREVIEW-PLAN-062#2] reviewRelevance：pathLike 证据与 pathLike 角色 scope 均为空时返回 null（无约束），
  授权回退 snapshot ∩ RolePack 静态路径策略；部分可用时仅用可用条目约束。
- [AIREVIEW-PLAN-062#3] scopedEvidencePaths 同样先过滤 pathLike，散文不再渲染进角色上下文。
- [AIREVIEW-PLAN-062#4] 单测：纯散文结论→谓词 null；混合结论→散文忽略、真路径生效；空结论不变；
  散文不渲染进 publicText。

## 文件清单
- src/main/java/ai/cc/chongming/review/application/ReviewContextAssembler.java
- 对应测试（ReviewContextAssemblerTests 存在则扩展，否则新增）

## 风险
- 回退无约束=角色可见静态策略全量文件，隔离性略降，但远好于零授权饿死；
- 正常（路径形态）结论行为不变。

## 变更记录
- 2026-08-28 立计划；派发后台子代理实施。
- 2026-08-28 子代理 5ac07ed6 交付；父代理逐行审查 diff 无夹带；回归 802 全绿；提交。
