# `/live` 连续 Agent 对话流收口与验收计划

> **状态**: 🟡 实施中（已通过 `cx-ai` 的真实浏览器运行流、工具 trace 与断线回放验收；2026-08-04 开始将 `/live` 迁移为 full-flow 工作区；多角色全流程终态仍待验收）
> **创建日期**: 2026-07-31
> **目标**: 将 `/live` 收口为可靠的、按真实 AG-UI 事件顺序展示 Agent 思考、回答、工具入参和工具结果的连续对话页，并在不阻塞评审领域流程的前提下完成自动化与真实运行验收。
> **关联计划**: AIREVIEW-PLAN-017、AIREVIEW-PLAN-018、AIREVIEW-PLAN-019

## 0. 交接基线与边界

### 0.1 用户体验目标

`/#/reviews/{reviewId}/live` 采用 `docs/ui-patterns-demo/full-flow.html` 的流程工作区信息架构：左侧是阶段轨道，中间是当前阶段的公开运行流和角色席位，右侧切换领域事实与运行调试。运行事件仍按真实 AG-UI 到达顺序展示，完整工具入参/结果仅在展开的调试区呈现：

```text
CONTEXT_SCOUT
  思考（模型实际返回时）
  工具：glob_files
    输入：{...}
    输出：{...}
  回答：项目结构概览……

DIRECTOR
  思考（模型实际返回时）
  工具：plan_write
    输入：{...}
    输出：{...}
  回答：已创建评审计划……
```

它不是领域工作台的替代品：正式阶段、Claim、辩论、Judge 与 Gate 仍由工作台和持久化领域事件表达。`/live` 只观察同一 attempt 的实时运行，不启动第二次模型调用，也不写领域事实。

### 0.2 明确约束

1. 只显示供应商真实返回并经 `ThinkingBlock` 映射的 thinking；未返回时不生成、翻译或补写“思考过程”。
2. 当前为用户明确要求的本机调试模式：已授权工具的完整入参和文本结果原样进入 runtime trace 与 `/live`，**不脱敏、不截断**。模型可见文本和异常仍沿现有错误处理链路处理。
3. 该例外不等于放开宿主文件系统，不改变共享快照、工作区根目录、工具白名单、领域协议或数据库权限。
4. 旧运行事件没有新增工具原始载荷；前端改造完成后必须创建新的 review/attempt 验收，不能拿历史 attempt 的空载荷判定失败。
5. 不启动、停止或重启用户的本地服务；真实浏览器步骤仅在用户已重启并明确可用后执行。

### 0.3 当前工作区事实（2026-07-31）

- 已新增 `LiveAgentConversation.vue` 和 `runtime-conversation-adapter.js`，`ReviewLiveView.vue` 已不再渲染固定角色状态块。
- 网关已读取 OpenAI-compatible 的 `reasoning_content`，并兼容 `reasoning`；仅有真实值时才写入 `ThinkingBlock`。
- Director、Context Scout、普通角色与初审收尾 Agent 均已接入同一观察型工具 collector；collector 只观测，不改工具调用、权限或结果。
- `npm test`（6 文件、12 用例）与 `npm run build` 已通过；生产 `index.html` 引用的 JS/CSS hash 与本次构建产物一致，无遗留旧 hash。
- IDEA 全量构建及 7 个后端定向测试类（40 个用例）已通过。Scout 三种精确降级原因码均已恢复；`RoleSubagentIsolationTests` 已从过时的 `submitEvidence` 期望改为 RolePack 实际公开的 `submit_claim`/`complete_initial_review`，未放宽生产白名单。
- 当前 shell 的 `JAVA_HOME` 指向 JDK 8，`./mvnw.cmd test` 在 Maven Enforcer 的 Java 21 基线检查前退出；已使用项目 IDEA JDK 21 完成构建和定向测试，不能将该 Maven 环境问题记为全量 Maven 通过。
- 已修复 `ReviewDiagnosticsProperties` 的配置绑定构造器歧义：显式标记 record 的两参数主构造器，避免新增 Context Scout 诊断开关后在应用启动时回退到无参实例化。

## 1. 后端事件契约收口

### 1.1 模型真实 thinking 映射 ✅

**涉及文件（修改）**

- `src/main/java/ai/cc/chongming/review/domain/gateway/ModelGateway.java`
- `src/main/java/ai/cc/chongming/review/infrastructure/model/ModelProviderClient.java`
- `src/main/java/ai/cc/chongming/review/infrastructure/model/OpenAiCompatibleModelClient.java`
- `src/main/java/ai/cc/chongming/review/infrastructure/model/CommercialModelGateway.java`
- `src/main/java/ai/cc/chongming/review/infrastructure/agentscope/AgentScopeModelBridge.java`

**实现约束**

1. `ModelResponse` 与 `ProviderResponse` 的新增 `thinkingText` 必须保持旧构造器兼容，避免 test double 和备用模型配置被破坏。
2. 只从 `choices[0].message.reasoning_content` 读取；字段为空时才回退 `reasoning`。
3. 将非空 thinking 作为独立 `ThinkingBlock` 放在公开文本前；不要把它拼进文本回复，也不要以此替代最终回答。
4. 检查模型调用审计链路，确保新增字段不会意外写入原本只保存公开摘要的持久化记录；若现有审计完整持久化响应，必须显式排除 `thinkingText` 并增加回归测试。

**退出条件**：包含 thinking、仅文本、空 thinking、仅 reasoning 兼容字段四类响应均有测试；AG-UI 只对真实 block 发出 `REASONING_MESSAGE_*`。

### 1.2 跨 Harness 工具观察 ✅

**涉及文件（修改）**

- `src/main/java/ai/cc/chongming/review/infrastructure/agentscope/ContextScoutHarnessFactory.java`
- `src/main/java/ai/cc/chongming/review/infrastructure/agentscope/ReviewDirectorHarnessFactory.java`
- `src/main/java/ai/cc/chongming/review/infrastructure/agentscope/RoleSubagentFactory.java`
- `src/main/java/ai/cc/chongming/review/infrastructure/agentscope/ScoutToolTraceCollector.java`
- `src/main/java/ai/cc/chongming/review/infrastructure/agentscope/AgentScopeReviewRuntimeAdapter.java`

**实现约束**

1. collector 必须以 `runId + toolCallId` 关联开始、增量、结束与异常，允许工具先于 collector lifecycle 到达。
2. Director、角色与 finalizer 使用“保留 AgentScope 默认文件工具”的 `AgentScopeModelBridge` 重载；Scout 继续显式限制为只读原生工具。不得因接入观察器把 `plan_*`、`todo_write` 或正常工作区工具意外排除。
3. collector 只转发已注册并被运行时授权的工具，不自行执行、重试、重写参数或读取路径。

**退出条件**：四类 Harness 均可产生至少一个被映射的工具事件；权限白名单与原有工具测试无回归。

### 1.3 AG-UI 映射与原始工具载荷 ✅

**涉及文件（修改）**

- `src/main/java/ai/cc/chongming/review/infrastructure/agentscope/ReviewAgUiEventMapper.java`
- `src/main/java/ai/cc/chongming/review/infrastructure/agentscope/RuntimeTraceRedactor.java`

**实现约束**

1. 文本、thinking、工具调用和结果都必须保留同一个 `runId`、`agentId`、角色、工具调用 ID 与 trace sequence。
2. 页面可用标准 `TOOL_CALL_*` 和 `CUSTOM(chongming.tool-call.v1)` 合并同一工具条目；CUSTOM 输入/输出为完整原始调试载荷。
3. `RuntimeTraceRedactor` 在这个本地诊断分支只提供 `rawToolInput/rawToolOutput`；不得错误调用旧的脱敏/长度截断分支。
4. `AgentResultEvent` 可能没有结果对象；mapper 必须安全地只发终态或错误事件，不能因 `getResult()` 为 null 让 SSE 线程 NPE。

**退出条件**：单元测试覆盖 thinking、文本、工具输入/输出、工具失败、空 result 与重复事件；浏览器用例能将同一调用原地更新。

## 2. 前端连续对话收口

### 2.1 事件归约与身份解析 ✅

**涉及文件（新建）**

- `frontend/src/services/runtime-conversation-adapter.js`
- `frontend/src/services/runtime-conversation-adapter.test.js`

**实现约束**

1. 按服务器 trace sequence 归约为 `thinking`、`message`、`tool`、`notice` 四类条目，不能按固定角色筛选或默认 Director。
2. 角色名优先由运行 lifecycle/CUSTOM 身份事件解析；工具、文本和结束事件延用已有 run 身份。
3. 同一工具按 `tool:<runId>:<toolCallId>` 合并；输入、结果、状态、耗时应更新同一个条目，重复 SSE 事件不得生成多条卡片。
4. 输入/输出显示原始值；禁止前端对内容做脱敏或摘要截断。只有 CSS 折叠控制可见区域，展开后保持完整内容。

### 2.2 页面与样式 ✅

**涉及文件（新建/修改）**

- `frontend/src/components/LiveAgentConversation.vue`（新建）
- `frontend/src/views/ReviewLiveView.vue`（修改）
- `frontend/src/styles/review.css`（修改）

**实现约束**

1. `/live` 使用 full-flow 工作区：左侧阶段轨道、中间公开运行流与角色席位、右侧领域事实/运行调试侧栏；不再把原始 trace 时间线作为整页唯一主布局。
2. 思考流式时展开，完成后折叠但可查看全文；回答用普通 Agent 消息气泡；工具默认显示名称、状态、耗时，点击展开原始参数/结果。
3. 失败、取消、缓存截断等运行事实以 notice 展示，不冒充模型回答。
4. 响应式布局在窄屏收为单列，不依赖鼠标悬浮才能获取关键状态。

**退出条件**：前端单测证明 thinking → tool → answer 的顺序，且两次不同工具调用显示不同入参与出参；Vite 生产构建同步更新静态资源和 `index.html`。

## 3. 必须先修复的回归

### 3.1 Scout 降级原因码回归 ✅

**现象**：`AgentScopeReviewRuntimeAdapterTests` 的三种可识别 Scout 失败都被页面事件写成通用 `CONTEXT_SCOUT_UNAVAILABLE`。

**优先排查路径**

1. 在 `AgentScopeReviewRuntimeAdapter#runScout` 与 `#scoutFailureCode` 的边界记录/断言原始异常类型与 cause 链，不要只凭最终 summary 推断。
2. 复查本次将 Scout factory 从 `create(...)` 扩展为 `createRuntime(...)` 的改动。测试 mock 如果仍只 stub `create(...)`，将得到空 runtime/NPE，进而掩盖原始 `ModelGatewayException` 或 `ScoutLimitExceededException`。测试应模拟 `ScoutRuntime`，代码仍要保证真实 factory 抛出的异常可完整传到 `scoutFailureCode`。
3. 修复后分别断言 `MODEL_CALL_TIMEOUT`、`MODEL_NETWORK_ERROR`、`CONTEXT_SCOUT_INIT_CONTRACT_VIOLATED` 均被持久化到 `CONTEXT_SCOUT_DEGRADED.reasonCode`，同时 Director 仍被启动。
4. 不通过“把测试期望改为通用码”掩盖失败原因；PLAN-018 已承诺页面展示安全原因码。

**涉及文件（修改）**

- `src/main/java/ai/cc/chongming/review/infrastructure/agentscope/AgentScopeReviewRuntimeAdapter.java`
- `src/test/java/ai/cc/chongming/review/agentscope/AgentScopeReviewRuntimeAdapterTests.java`

**退出条件**：上述 3 个断言全部恢复，通过后再运行本计划第 5 段的完整定向集。

**完成记录（2026-07-31）**：`runScout(...)` 实际调用 `ContextScoutHarnessFactory#createRuntime(...)`，原测试仍只桩设 `create(...)`，导致空 runtime 异常覆盖原始 failure cause。测试现模拟完整 `ScoutRuntime`；`MODEL_CALL_TIMEOUT`、`MODEL_NETWORK_ERROR` 与 `CONTEXT_SCOUT_INIT_CONTRACT_VIOLATED` 均已验证被持久化到 `CONTEXT_SCOUT_DEGRADED.reasonCode`，Director 继续启动。

### 3.2 防御性映射与测试补齐 ✅

1. `ReviewAgUiEventMapper` 已覆盖 null Agent result、缺少 toolCallId 的稳定回退 ID 和工具 ERROR 原始结果；映射不会因异常输入使 SSE 线程 NPE。
2. `ScoutToolTraceCollector` 已覆盖 `plan_write` 与 `complete_initial_review`：只观察同一个 `ActingInput` 和运行时已授权工具，不重写入参或改变权限。
3. `RoleSubagentIsolationTests` 已确认并改为断言 PRODUCT RolePack 的真实 API：`submit_claim`、`complete_initial_review`、`searchText`，且明确不暴露旧 `submitEvidence`；生产白名单未改。
4. `ReviewReportServiceTests` 已通过，未复现空白行差异。

## 4. 真实运行验收

### 4.1 自动化门槛 ✅

按以下顺序执行，任一步失败先修复，再进入下一步：

1. `AgentScopeReviewRuntimeAdapterTests`、`AgentScopeModelBridgeTests`、`ReviewAgUiEventMapperTests`、`RuntimeTraceRedactorTests`、`ScoutToolTraceCollectorTests`、`OpenAiCompatibleModelClientTests`、`ModelGatewayContractTests`。
2. `npm test`。
3. `npm run build`，检查 `src/main/resources/static/review/index.html` 与本次生成的 JS/CSS hash 成对更新，没有遗留被 index 引用的旧 hash。
4. 使用 IDEA 构建项目；若 Maven 可用，再运行与改动相关的 Java 测试集。全量测试发现的既有失败必须记录责任和复现证据，不能静默忽略。
5. `git diff --check`，随后进行代码审查，确认没有把原始工具内容误写入领域事件、报告或持久化审计。

**完成记录（2026-07-31）**：IDEA 全量构建通过；`AgentScopeReviewRuntimeAdapterTests`、`AgentScopeModelBridgeTests`、`ReviewAgUiEventMapperTests`、`RuntimeTraceRedactorTests`、`ScoutToolTraceCollectorTests`、`OpenAiCompatibleModelClientTests`、`ModelGatewayContractTests` 共 40 个用例通过；前端 Vitest 6 文件 12 用例与 Playwright E2E 2 个用例通过；Vite 生产构建通过。`git diff --check` 通过。代码审查确认原始工具入参/结果仅进入进程内 `ReviewRuntimeTraceRegistry` 的 AG-UI runtime trace；领域事件、报告与 `ModelCallAuditService` 不持久化原始工具内容或 `thinkingText`（审计仅记录公开文本 hash）。`./mvnw.cmd test` 因当前 shell JDK 8 不满足 Java 21 基线而未执行，属于环境限制。

**启动回归记录（2026-07-31）**：IDEA 控制台复现到 `ReviewDiagnosticsProperties` 无默认构造器启动失败后，已显式标记其两参数主构造器为 `@ConstructorBinding`。IDEA 编译、`ReviewDiagnosticsPropertiesTests` 与完整 `ChongmingApplicationTests`（Java 21、test profile）均通过，确认 `@ConfigurationPropertiesScan` 的实际启动路径已恢复。

### 4.2 浏览器验收（用户重启后）

1. 创建一个新的 review/attempt，进入 `http://127.0.0.1:8080/review/#/reviews/{reviewId}/live`。
2. 首先确认 Context Scout 或 Director 出现一条真实运行消息；若模型返回 thinking，显示可展开 thinking，若没有则不显示伪造区域。
3. 至少观察两个不同参数的工具调用：确认名称、入参、出参和耗时属于各自调用，结果到达后原地更新，不重复生成条目。
4. 等待 Director 激活至少一个普通角色（优先 PRODUCT）；确认无需选择角色，时间线自动出现该角色的文字、工具与完成/失败事件。
5. 刷新一次页面或短暂断网重连，确认 runtime 有界回放去重；验证失败/Scout 降级显示为 notice，评审阶段与领域工作台仍可正常访问。
6. 将 reviewId、attemptNo、观察到的角色、工具调用 ID、失败（如有）记录进本计划变更记录；未达到 PRODUCT 及后续角色，不得宣称“多 Agent 全流程验收完成”。

**首次真实运行记录（2026-07-31）**：用户确认提交验收后，以 `cx-ai` 创建 review `ce02c9ab-06ef-4626-aa33-325369ef87d9`。attempt 1 的首次 `/start` 返回 `COMMAND_UNEXPECTED_FAILURE`，用新的幂等键重试后返回 `202 PLANNING`，但随即失败；`/retry` 创建 attempt 2 并再次 `/start` 后同样失败。通过临时 IDEA 调试断点确认异步启动异常为 `IllegalStateException: Requirement snapshot was not found for the active review attempt`。根因是 `/retry` 只创建领域 attempt，未把已接受的需求输入物化到新 attempt 的受控工作区，违反 PLAN-010 “输入快照可复用”的约束。

**修复与恢复条件**：`RequirementSnapshotStore` 现在将原始 Markdown、标准化 Markdown 与新 manifest 原子复制到新 attempt；`ReviewIntakeService` 将其作为幂等的 `copySnapshotForRetry(...)` 暴露，`ReviewCommandService#retry(...)` 已接线调用。IDEA 编译、`ReviewIntakeServiceTests`（5 用例）和 `ReviewCommandServiceTests`（11 用例）均通过。

**重启后复验记录（2026-07-31）**：用户重启后，创建 review `3ba2743d-966c-41e1-a68f-7797a8216b9d`，attempt 1 与 retry attempt 2 均返回 `202 PLANNING`。浏览器已打开 `/live` 并成功建立运行流连接；attempt 2 的临时 IDEA 调试断点显示失败为 `RepositoryAccessException: Configured repository root does not exist`，不再是缺少需求快照，证明 retry 快照物化已生效。当前 `application-local.yml` 仅允许 `cx-ai`，其 root `E:\aicode\cx-ai-bak` 在本机不存在；运行在 Agent 创建前失败，未观察到 Context Scout、Director、PRODUCT 或工具调用，故不得宣称 `/live` 验收通过。

**启动前失败呈现修复（2026-07-31）**：同次浏览器观察发现：`/live` 已读取 `FAILED` 阶段但在没有 AG-UI trace 时仍显示“等待 Agent 运行事件”。现将 FAILED、CANCELLED、COMPLETED 的空运行态映射为明确 notice，并保持它与模型回答、thinking 和工具条目分离。`npm test`（7 文件、14 用例）、Playwright（2 用例）及 `npm run build` 均通过；构建产物和 `index.html` 已同步更新。完整浏览器验收仍受仓库 root 阻断。

**配置恢复记录（2026-07-31）**：用户明确提供 `D:\GitCode\cx-ai` 作为 `cx-ai` 的真实项目仓库；已校验该目录存在、包含 `.git` 且 `git rev-parse --is-inside-work-tree` 返回 `true`。为避免把任一开发机的绝对路径固化到配置，受版本管理的默认配置与本地覆盖均使用 `${CX_AI_REPOSITORY_ROOT:../cx-ai}`：默认从评审系统工作目录查找同级 `cx-ai`，目录布局不同的机器以环境变量 `CX_AI_REPOSITORY_ROOT` 覆盖。不得做全盘扫描或接受调用方传入的任意本地路径，既有白名单、非链接及独立 Git 仓库校验仍然生效。运行中的服务尚未重新加载配置，需由用户重启后创建新的 review/attempt，继续本节验收。

**可移植仓库配置后的真实浏览器验收（2026-07-31）**：用户重启后，以 `cx-ai` 和本计划 Markdown 创建 review `18fdabb2-e6b9-4b40-be74-89e8504e170d`（attempt 1）。`/live` 成功连接，先后观察到 Context Scout、Director 和 PRODUCT（页面标题“产品经理”）三个 Agent；实时页面已展示 58 条运行条目，其中包含真实 thinking、文本回答和工具调用。已观察到 `glob_files`、`list_files`、`read_file`、`todo_write`、`plan_enter`、`plan_write`、`searchText`、`readLines` 等调用；例如 Scout `glob_files` 完成耗时 442/443ms，Director `list_files`、`read_file`、`plan_write` 和 `todo_write` 均有成功完成记录。刷新页面前后均为 31 条既有条目，连接恢复后保持去重；随后继续增长到 58 条，证明有界回放未重复已有 trace 且实时流继续追加。正式查询已到达 `INITIAL_REVIEW`、progress `40`、version `11`；同时存在 `CONTEXT_SCOUT_INIT_CONTRACT_VIOLATED` 降级事件，Director 按既有降级策略继续评审。此前“仓库 root 不存在”阻断已解除，真实 `/live` 核心验收通过；未到达后续全部角色与终态，不能宣称完整多角色流程验收完成。

## 5. 文件清单

### 新建

| 文件 | 计划段 | 状态 |
|---|---|---|
| `docs/AIREVIEW-PLAN-020-Live连续Agent对话流收口与验收.md` | #0 | ✅ |
| `frontend/src/components/LiveAgentConversation.vue` | #2.2 | ✅（待浏览器验收） |
| `frontend/src/services/runtime-conversation-adapter.js` | #2.1 | ✅（待补边界测试） |
| `frontend/src/services/runtime-conversation-adapter.test.js` | #2.1 | ✅ |
| `frontend/src/services/live-run-status.js` | #4.2 | ✅（无 trace 的终态 notice） |
| `frontend/src/services/live-run-status.test.js` | #4.2 | ✅ |
| `src/main/resources/static/review/assets/index-*.js` | #2.2 | ✅（构建产物，提交时以实际 hash 为准） |
| `src/main/resources/static/review/assets/index-*.css` | #2.2 | ✅（构建产物，提交时以实际 hash 为准） |

### 修改

| 文件 | 计划段 | 状态 |
|---|---|---|
| `docs/AIREVIEW-PLAN-017-主持式多Agent评审观察台与AGUI运行流.md` | #0 | ✅ |
| `src/main/java/ai/cc/chongming/review/domain/gateway/ModelGateway.java` | #1.1 | ✅ |
| `src/main/java/ai/cc/chongming/review/infrastructure/model/ModelProviderClient.java` | #1.1 | ✅ |
| `src/main/java/ai/cc/chongming/review/infrastructure/model/OpenAiCompatibleModelClient.java` | #1.1 | ✅ |
| `src/main/java/ai/cc/chongming/review/infrastructure/model/CommercialModelGateway.java` | #1.1 | ✅ |
| `src/main/java/ai/cc/chongming/review/infrastructure/agentscope/AgentScopeModelBridge.java` | #1.1/#1.2 | ✅（待审计检查） |
| `src/main/java/ai/cc/chongming/review/infrastructure/agentscope/ContextScoutHarnessFactory.java` | #1.2 | ✅ |
| `src/main/java/ai/cc/chongming/review/infrastructure/agentscope/ReviewDirectorHarnessFactory.java` | #1.2 | ✅ |
| `src/main/java/ai/cc/chongming/review/infrastructure/agentscope/RoleSubagentFactory.java` | #1.2 | ✅ |
| `src/main/java/ai/cc/chongming/review/infrastructure/agentscope/ScoutToolTraceCollector.java` | #1.2/#3.2 | ✅ |
| `src/main/java/ai/cc/chongming/review/infrastructure/agentscope/AgentScopeReviewRuntimeAdapter.java` | #1.2/#3.1 | ✅ |
| `src/main/java/ai/cc/chongming/review/infrastructure/agentscope/ReviewAgUiEventMapper.java` | #1.3/#3.2 | ✅ |
| `src/main/java/ai/cc/chongming/review/infrastructure/agentscope/RuntimeTraceRedactor.java` | #1.3 | ✅ |
| `frontend/src/views/ReviewLiveView.vue` | #2.2/#4.2 | ✅（full-flow 工作区迁移；全流程终态验收待执行） |
| `frontend/src/components/LiveAgentConversation.vue` | #2.2/#4.2 | ✅（终态 notice 呈现；全流程验收待仓库配置） |
| `frontend/src/styles/review.css` | #2.2 | ✅（待浏览器验收） |
| `src/main/resources/static/review/index.html` | #2.2 | ✅（构建产物） |
| `src/test/java/ai/cc/chongming/review/agentscope/AgentScopeModelBridgeTests.java` | #1.1 | ✅ |
| `src/test/java/ai/cc/chongming/review/agentscope/AgentScopeReviewRuntimeAdapterTests.java` | #3.1 | ✅ |
| `src/test/java/ai/cc/chongming/review/agentscope/RoleSubagentIsolationTests.java` | #3.2 | ✅ |
| `src/test/java/ai/cc/chongming/review/infrastructure/agentscope/ReviewAgUiEventMapperTests.java` | #1.3/#3.2 | ✅ |
| `src/test/java/ai/cc/chongming/review/infrastructure/agentscope/RuntimeTraceRedactorTests.java` | #1.3 | ✅ |
| `src/test/java/ai/cc/chongming/review/infrastructure/agentscope/ScoutToolTraceCollectorTests.java` | #3.2 | ✅ |
| `src/test/java/ai/cc/chongming/review/infrastructure/model/OpenAiCompatibleModelClientTests.java` | #1.1 | ✅ |
| `src/main/java/ai/cc/chongming/review/config/ReviewDiagnosticsProperties.java` | #4.1 | ✅（启动配置绑定修复） |
| `src/test/java/ai/cc/chongming/review/config/ReviewDiagnosticsPropertiesTests.java` | #4.1 | ✅（启动配置绑定回归） |
| `src/main/java/ai/cc/chongming/review/infrastructure/document/RequirementSnapshotStore.java` | #4.2/PLAN-010#1.7 | ✅（重试输入快照物化） |
| `src/main/java/ai/cc/chongming/review/application/ReviewIntakeService.java` | #4.2/PLAN-010#1.7 | ✅（重试快照复制接线） |
| `src/main/java/ai/cc/chongming/review/application/ReviewCommandService.java` | #4.2/PLAN-010#1.7 | ✅（retry 调用快照复制） |
| `src/test/java/ai/cc/chongming/review/application/ReviewIntakeServiceTests.java` | #4.2/PLAN-010#1.7 | ✅（重试工作区回归） |
| `src/test/java/ai/cc/chongming/review/application/ReviewCommandServiceTests.java` | #4.2/PLAN-010#1.7 | ✅（retry 接线回归） |

## 6. 实施顺序

1. **步骤 0** ✅：冻结目标、原始工具载荷例外、当前工作区与验收边界。
2. **步骤 1** ✅：完成模型 thinking、跨 Harness collector、AG-UI 映射和连续对话 UI 主体。
3. **步骤 2** ✅：修复 Scout 降级原因码回归；精确原因码未降级为通用码。
4. **步骤 3** ✅：补齐 mapper/collector/审计的防御性测试，并定位、修复 RolePack API 过时期望。
5. **步骤 4** ✅：完成定向 Java、前端单测、生产构建、IDEA 构建、差异检查与代码审查；Maven Wrapper 因 shell JDK 8 环境未运行，已记录为环境限制。
6. **步骤 5** 🟡：已完成重启后的新 attempt 受理、启动、`/live` 连接、retry 快照复验和启动前失败 notice 修复；`cx-ai` 已采用“同级目录默认值 + 本机环境变量覆盖”的可移植定位规则，并以新 review 观察到 Context Scout、Director、PRODUCT、真实工具 trace 与刷新回放去重。当前 attempt 仍在 `INITIAL_REVIEW`，待后续角色、辩论/Gate 和终态完成后补齐全流程记录。
7. **步骤 6** ⏳：更新 PLAN-017/018 实施状态、`.learnings/LEARNINGS.md` 与提交说明；依赖步骤 5。

## 7. 风险与应对

| 风险 | 应对 |
|---|---|
| 本地调试的原始工具结果含敏感值 | 仅限当前本机诊断；不得复用到共享或生产环境，后续上线必须另立脱敏与访问控制计划。 |
| thinking 供应商字段不一致 | 仅兼容已确认的 `reasoning_content`/`reasoning`，空值不伪造；新增厂商字段先补测试。 |
| collector 改动影响工具权限 | 保持观测中间件只读，覆盖 Director/role/finalizer/Scout 工具注册回归。 |
| 历史运行缺少原始载荷 | 明确用新 attempt 验收，不将历史空内容误判为映射失败。 |
| 前端收到标准事件与 CUSTOM 的顺序不同 | 以 toolCallId 合并，测试 start/结果先后与 SSE 重放幂等。 |
| Scout 降级原因被吞掉 | 使用精确原因码断言与 Director 继续运行双重验收，不接受通用错误码替代。 |
| 可视化正常但领域流程未推进 | 浏览器验收必须确认 `ROLE_ACTIVATED/STARTED/COMPLETED` 和至少一个普通角色的独立运行，不只看 Director 日志。 |

## 8. 非目标

- 不把 `/live` 改造成用户可自由对 Agent 输入的通用聊天页。
- 不展示不存在的模型推理，不从业务事件推测 thinking。
- 不修改共享快照、宿主仓库访问、AgentScope BYPASS 范围、RolePack 领域工具权限或数据库结构。
- 不在本计划将未验证的原始调试载荷能力宣传为生产安全能力。

## 9. 交接说明

下一位执行者从**步骤 5**继续。自动化门槛、retry 快照修复和 `cx-ai` 的真实 `/live` 核心浏览器验收已完成；`cx-ai` 默认定位为评审系统工作目录的同级 `../cx-ai`，可由 `CX_AI_REPOSITORY_ROOT` 覆盖。先观察 review `18fdabb2-e6b9-4b40-be74-89e8504e170d` 的后续角色与终态；只有其完成或失败后，才能记录全流程结果。

运行服务由用户控制。本地 `cx-ai` 默认查找同级 `../cx-ai`，目录不遵循此布局时设置本机环境变量 `CX_AI_REPOSITORY_ROOT`。当前真实 attempt 正在运行；若模型只返回文本没有 thinking，这属于正常兼容路径；若工具输入/输出为空，应先核对该 attempt 是否真正产生了 `chongming.tool-call.v1` 事件，而不是回退到旧状态块 UI。

## 10. 变更记录

| 日期 | 变更 |
|---|---|
| 2026-07-31 | 根据“`/live` 必须像主流 Agent 对话软件、展示思考/回答/工具入参与结果”的要求创建收口计划。 |
| 2026-07-31 | 记录主体实现已在未提交工作区，以及近期后端定向测试中 Scout 三个精确降级原因码被通用码替代的回归；将修复、测试、浏览器新 attempt 验收顺序固化为可交接步骤。 |
| 2026-07-31 | 用户明确当前阶段不需要工具调用结果脱敏；将其限定为本机调试 runtime trace 的临时边界，并保留后续生产化需单独设计的约束。 |
| 2026-07-31 | 完成 Scout 三个精确降级原因码回归：测试改为模拟 `createRuntime(...)` 返回的 `ScoutRuntime`，不再因旧 `create(...)` mock 产生空 runtime 并吞没根因。 |
| 2026-07-31 | 完成 mapper/collector 防御性覆盖、PRODUCT RolePack 旧 API 期望修复和前端跨角色同 toolCallId 去重样本；IDEA 全量构建、7 个后端定向类（40 用例）、前端 Vitest（6 文件、12 用例）、Playwright E2E（2 用例）与 Vite 构建通过。Maven Wrapper 被当前 shell JDK 8 基线限制阻断，已保留为环境记录。 |
| 2026-07-31 | 修复 IDEA 启动时 `ReviewDiagnosticsProperties` 的构造器绑定歧义：该 record 同时保留两参数主构造器与一参数兼容构造器，配置扫描无法自动推断绑定构造器并错误回退为无参实例化。现显式使用 `@ConstructorBinding` 绑定两参数主构造器；新增配置绑定回归测试，并以完整 `ChongmingApplicationTests` 验证实际应用上下文启动。 |
| 2026-07-31 | 已提交一次真实验收：review `ce02c9ab-06ef-4626-aa33-325369ef87d9` 的 attempt 1 和 retry attempt 2 均在启动后失败。IDEA 调试确认原因是新 retry attempt 没有需求快照，而不是 `/live` 前端映射问题。已补齐受控工作区的输入快照原子复制及 `ReviewCommandService#retry(...)` 接线；IDEA 编译、`ReviewIntakeServiceTests` 5 用例与 `ReviewCommandServiceTests` 11 用例通过。当前 JVM 尚未加载此修复，浏览器全流程验收仍待用户重启后使用新 review/attempt 执行。 |
| 2026-07-31 | 用户重启后的第二次真实验收创建 review `3ba2743d-966c-41e1-a68f-7797a8216b9d`；attempt 1 与 retry attempt 2 均成功受理并启动，浏览器 `/live` 已建立连接。attempt 2 的诊断从“缺少需求快照”变为 `Configured repository root does not exist`，验证 retry 快照修复已加载。当前唯一允许的 `cx-ai` 映射 `E:\aicode\cx-ai-bak` 不存在，Agent 尚未创建，未观察到角色或工具调用；浏览器全流程验收继续保持未完成。 |
| 2026-07-31 | 浏览器观察到无 AG-UI trace 的 FAILED attempt 被错误呈现为“等待 Agent 运行事件”。已新增终态状态归约，使 FAILED/CANCELLED/COMPLETED 在空时间线中呈现独立 notice，不伪装为模型回答；`npm test` 7 文件 14 用例、Playwright 2 用例与 Vite 构建通过，静态 bundle 已更新。 |
| 2026-07-31 | 用户提供真实仓库 `D:\GitCode\cx-ai`；已校验其存在且为独立 Git work tree。`cx-ai` root 在默认配置和本机覆盖中均改为可移植的 `${CX_AI_REPOSITORY_ROOT:../cx-ai}`：默认查找评审系统同级项目，非标准布局由本机环境变量覆盖，不固化开发机绝对路径且不放宽仓库边界。待用户重启服务后以新的 review/attempt 继续真实浏览器验收。 |
| 2026-07-31 | 用户重启后的第三次真实验收创建 review `18fdabb2-e6b9-4b40-be74-89e8504e170d`（attempt 1）。`/live` 已观察到 Context Scout、Director、PRODUCT，至少 58 条真实运行条目及 `glob_files`、`list_files`、`read_file`、`todo_write`、`plan_enter`、`plan_write`、`searchText`、`readLines` 调用；刷新前后 31 条已展示条目保持一致，连接恢复后继续追加。查询阶段为 `INITIAL_REVIEW`（40%），Scout 记录 `CONTEXT_SCOUT_INIT_CONTRACT_VIOLATED` 后由 Director 继续。`cx-ai` 仓库定位、运行流与回放验收通过；全角色/Gate/终态仍待当前 attempt 继续运行。 |
| 2026-07-31 | `cx-ai` 可移植定位规则及本次验收记录回写后，IDEA 项目构建通过（无问题）；`git diff --check` 通过。 |
| 2026-08-04 | 按用户确认，将 `/reviews/:reviewId/live` 从原始运行 trace 整页迁移为 `full-flow.html` 对应的流程工作区：阶段轨道、当前阶段公开运行流、角色席位和领域事实/调试侧栏复用同一 SSE 与持久化领域事件数据，不新增模型调用或后端协议。 |
