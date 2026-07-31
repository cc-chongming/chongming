# 共享项目上下文与角色定向检索计划

> **状态**：🟡 已实施核心链路与 Scout 运行边界，保留 Scout 预览与真实模型 E2E 验收
> **创建日期**：2026-07-23
> **目标**：在初审前一次性建立可审计的项目概览，使角色按职责接收共享上下文并执行最小必要的快照检索。
> **关联计划**：AIREVIEW-PLAN-007、AIREVIEW-PLAN-008、AIREVIEW-PLAN-016、AIREVIEW-PLAN-017

## 1. 问题与约束

- 角色包与通用提示词不一致时，角色可能尝试调用未授权工具；例如 PRODUCT 不拥有 `listFiles`。
- 仓库工具不能把所有异常折叠为同一错误文本，否则模型和实时页面均无法判断快照、参数或访问范围问题。
- `ReviewContextAssembler` 的隔离语义已经存在，但角色启动链路尚未注入需求摘要、仓库概览与模块信息。
- 所有仓库读取必须继续经过共享快照；不向 Agent 暴露宿主物理路径，也不允许 Junction、软链接或越界读取。
- 公开上下文只能包含已验证的需求快照、快照元数据、受控文件清单和证据；不得传播隐藏推理、角色草稿或凭证。

## 2. 目标流程

```text
需求快照 + 共享仓库快照
          ↓
Context Scout（AS2 原生受限工作区、无 Claim/Gate 权限）
          ↓
requirement-summary / repository-overview / module-map / role-scopes
          ↓
Director 分派角色
          ↓
RoleContext（按 contextSelectors 与角色范围筛选）
          ↓
定向 searchText / readLines / findSymbol
          ↓
公开事实、辩论、Judge
```

## 3. 实施项

### 3.1 工具可观测性与契约一致性 ✅

- 依据 RolePack 实际白名单生成仓库工具提示；未授权 `listFiles` 的角色不会再被要求调用它。
- 仓库工具将失败转换为安全错误码：`SNAPSHOT_FAILED`、`REPOSITORY_PATH_UNSAFE`、`INVALID_ARGUMENT`、`SNAPSHOT_UNAVAILABLE`。
- 服务端日志记录工具名、角色和错误码，不记录物理路径、凭证或原始异常内容。

### 3.2 共享项目概览 ✅（基础实现）

- 以 `reviewId + attemptNo` 缓存一次性共享概览。
- 概览由不可变需求章节、快照 Git 元数据、可评审文件数、模块根目录与有限示例文件构成。
- 快照不含可评审文本文件时，角色启动直接失败，不再产生“无发现”的空评审。
- PRODUCT 仅接收需求摘要和高层仓库元数据；其他角色额外接收模块根目录和示例文件。
- 取消评审时清理该 attempt 的进程内快照与上下文缓存，避免单例工厂持续持有已结束评审的路径和摘要。

### 3.3 模型化 Context Scout ✅

- 服务端在 Director 之前显式创建独立的 `CONTEXT_SCOUT` Harness；它不计入角色数，不拥有 Claim、辩论或 Gate 工具。
- Scout 不再使用项目自定义的 `listFiles`、`searchText`、`readLines` 等工具；改用 AS2 原生 `glob_files`、`grep_files`、`read_file`。根目录和受控文件清单改由服务端初始化步骤提供，不再暴露会导致重复全仓枚举的 `list_files`。
- AS2 文件系统使用私有 Scout 上层工作区与冻结共享快照下层工作区的 copy-on-write 叠加；Shell 仍禁用，宿主仓库从不进入工作区。通过 AS2 `ToolsConfig` 仅保留 `glob_files`、`grep_files`、`read_file` 三个原生定向读工具，`list_files`、`write_file`、`edit_file` 不会被暴露或调用。
- Director 与 Scout 的 AS2 原生文件系统均显式根植于 `<review>/attempts/<attemptNo>`；需求快照位于 `input/requirement.md`，不再回退到 Spring 进程当前目录或宿主重明工程。
- Scout 工作流参考 ECC 的 `codebase-onboarding`、`project-init` 与 `iterative-retrieval`：服务端先完成根目录、构建指纹、文件清单和模块根的确定性 `context-scout-init` 清单；模型随后只做需求定向 `glob`、关键词 `grep` 和高相关 `read`，并立即生成结构性结论。`glob_files`、`grep_files`、`read_file` 的上限分别为 2、3、4 次，违反工具契约、超过总预算或超时都会持久化为 `CONTEXT_SCOUT_DEGRADED` 后继续 Director。该约束由运行时代码强制执行，不只依赖提示词。
- Scout 的最终可见 JSON 结论会写入 `context-scout-result.json`，同步成为 `scout-overview` ContextFact；隐藏推理不会被读取、存储或传播。除取消外，Scout 运行失败会作为非终态 `CONTEXT_SCOUT_DEGRADED` 事实持久化，包含安全原因码与公开摘要；Director 继续启动，但页面会明确展示该次降级，不把失败伪装成已完成上下文准备。

### 3.4 RoleContext 注入与路径范围 ✅

- 需求、仓库、Scout 概览和角色范围均转为 `ReviewContextAssembler.ContextFact`；RolePack 通过 `contextSelectors` 选择并在 8,000 字符预算内注入模型请求。
- PRODUCT 仅收到需求和高层元数据；其他角色收到本职责的模块、示例证据路径及服务器强制的可检索范围。范围外读取、搜索和符号结果均会被服务端过滤，路径穿越在范围判断前拒绝。
- 初审和辩论工具严格按 RolePack 白名单注册；未获 `complete_initial_review` 权限的按需角色不会被运行时错误地要求完成初审。

### 3.5 实时展示与测试 🟡

- 实时观察台和评审工作台单独展示 Context Scout 的准备、完成和降级状态，并可从角色席位进入其 AG-UI 流式过程；降级信息从评审事件读模型恢复，刷新页面后仍可见。
- 新增独立 URL `/#/reviews/{reviewId}/scout`。它启动一个与正式评审隔离的 Scout 预览运行，并通过 `POST /api/reviews/{reviewId}/attempts/{attemptNo}/scout-previews` 与其 SSE 子资源返回 AS2 原生工具调用和公开中文结果；预览结果不会写入 `scout-overview`。该入口仅在 `review.diagnostics.context-scout-preview-enabled=true` 的受控本机诊断环境下可用，默认返回 404，避免在共享环境被任意请求触发模型调用或订阅运行流。预览优先复用该 attempt 已冻结的快照；仅在 `PENDING` 状态由受控启动流程初始化一次，进入评审流程后绝不重绑为新的工作树版本；随后正式启动也先复用该引用，保证两者读取同一冻结版本。
- AIREVIEW-PLAN-019 已将 Scout 预览的工具状态列表升级为连续对话流：通过 AS2 `MiddlewareBase#onActing` 观测四个原生只读工具的真实入参、文本出参与终态，使用脱敏、限长的 `chongming.tool-call.v1` CUSTOM 事件原地更新同一调用；不替换文件读取实现，也不开放写工具。
- 角色提示词参考 ECC 已验证角色/技能流程：PRODUCT 使用产品诊断，PROJECT 与 ARCHITECTURE 使用代码架构映射，BACKEND、FRONTEND、TESTING、SECURITY 使用各自工程与验证清单，JUDGE 只比较持久化证据。
- 已覆盖单次概览复用、PRODUCT 不接收示例代码文件、范围外读取拒绝、路径穿越拒绝、运行时取消释放与启动失败/通知完成释放链。
- 待补：在已启动的真实模型网关下，通过上述独立 URL 完成一次端到端 Scout 预览验收；当前工具流的 Collector、AG-UI 映射、脱敏与前端 reducer 已有最小自动化覆盖。2026-07-30 已补齐预览专属 `RuntimeContext`，防止历史 Scout 会话被错误恢复到新的浏览器预览；本地 Scout 与 fallback 输出预算均提升为 2,048 token。仍需重启后用真实网关复验。

## 4. 非目标

- 不开放宿主文件系统，不实现远程 Clone、对象存储或跨机器缓存。
- 不将 AgentScope 的 BYPASS 扩展至领域协议、数据库或快照边界。
- 不展示或持久化模型隐藏推理。

## 5. 验收标准

- PRODUCT 首轮可获得需求内容而不调用 `listFiles`。
- 相同评审尝试只构建一次共享概览，且快照为空时角色无法以空 findings 完成。
- 工具错误在实时流中可按安全错误码定位。
- Context Scout 的模型调用失败不会把评审转为 `FAILED`；取消仍优先终止整个尝试，且不会被降级吞掉。
- Scout 触及工具调用预算或超时后必须写入 `CONTEXT_SCOUT_DEGRADED` 并继续 Director；不可把非收敛的 Scout 调用无限延长为整条评审链路阻塞。
- Context Scout、角色上下文和服务端范围过滤均已接入；部署验收仍需在真实模型网关下验证一次完整评审运行。

## 6. 当前运行验收与剩余项

### 6.1 已确认

- 在真实评审 `68c022e4-95fe-4831-aa21-6befddc9ef81` 中，Scout 超时按非终态 `CONTEXT_SCOUT_DEGRADED` 处理，Director 仍继续运行；这符合 Scout 可降级、不阻断 Director 的边界。
- 同一 attempt 的 AG-UI 运行流已经能分别识别 Scout 与 Director，说明运行观察、attempt 隔离与基础角色标识未因 Scout 降级失效。

### 6.2 尚未通过的端到端验收

- 该 review 的领域状态仍为 `PLANNING`，只观察到 `PLAN_CREATED` 与 `CONTEXT_SCOUT_DEGRADED`；尚未出现 `ROLE_ACTIVATED`、`ROLE_STARTED`、`ROLE_COMPLETED` 或 PRODUCT 的 AG-UI 运行事件。
- 因此，不能把 Director 的计划文本中提及“产品经理”或后台日志中的角色名称视为 PRODUCT 已收到 RoleContext、实际执行了检索或完成初审。
- 后续真实模型验收必须在一个 attempt 内顺序确认：Director 完成计划后发生角色激活 → PRODUCT 收到需求摘要与高层仓库元数据 → PRODUCT 出现独立 `RUN_STARTED`/文本/工具或完成事件 → `complete_initial_review` 形成正式领域事件。达到该条件前，本计划维持“核心链路已实施、完整 E2E 待验收”。

## 7. 变更记录

| 日期 | 变更 |
|---|---|
| 2026-07-31 | 记录真实模型运行的阶段性结果：Scout 降级后 Director 可继续，但尚无 PRODUCT 角色运行或领域激活。将 Scout 可降级的已确认事实与正常角色初审的未完成 E2E 验收分离，避免将日志文本误记为角色已执行。 |
