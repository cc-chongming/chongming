# `/live` 连续 Agent 对话流收口与验收计划

> **状态**: 🟡 实施中（自动化门槛已完成，待用户重启服务后的真实浏览器验收）
> **创建日期**: 2026-07-31
> **目标**: 将 `/live` 收口为可靠的、按真实 AG-UI 事件顺序展示 Agent 思考、回答、工具入参和工具结果的连续对话页，并在不阻塞评审领域流程的前提下完成自动化与真实运行验收。
> **关联计划**: AIREVIEW-PLAN-017、AIREVIEW-PLAN-018、AIREVIEW-PLAN-019

## 0. 交接基线与边界

### 0.1 用户体验目标

`/#/reviews/{reviewId}/live` 只保留一条连续的运行对话时间线：

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

1. `/live` 只渲染对话时间线与紧凑的 review/阶段头部；删除 `ReviewRoundtable`、`AgentTraceDrawer` 等状态块式主布局。
2. 思考流式时展开，完成后折叠但可查看全文；回答用普通 Agent 消息气泡；工具默认显示名称、状态、耗时，点击展开原始参数/结果。
3. 失败、取消、缓存截断等运行事实以 notice 展示，不冒充模型回答。
4. 响应式布局保持单列，不依赖鼠标悬浮才能获取关键状态。

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

### 4.2 浏览器验收（用户重启后）

1. 创建一个新的 review/attempt，进入 `http://127.0.0.1:8080/review/#/reviews/{reviewId}/live`。
2. 首先确认 Context Scout 或 Director 出现一条真实运行消息；若模型返回 thinking，显示可展开 thinking，若没有则不显示伪造区域。
3. 至少观察两个不同参数的工具调用：确认名称、入参、出参和耗时属于各自调用，结果到达后原地更新，不重复生成条目。
4. 等待 Director 激活至少一个普通角色（优先 PRODUCT）；确认无需选择角色，时间线自动出现该角色的文字、工具与完成/失败事件。
5. 刷新一次页面或短暂断网重连，确认 runtime 有界回放去重；验证失败/Scout 降级显示为 notice，评审阶段与领域工作台仍可正常访问。
6. 将 reviewId、attemptNo、观察到的角色、工具调用 ID、失败（如有）记录进本计划变更记录；未达到 PRODUCT 及后续角色，不得宣称“多 Agent 全流程验收完成”。

## 5. 文件清单

### 新建

| 文件 | 计划段 | 状态 |
|---|---|---|
| `docs/AIREVIEW-PLAN-020-Live连续Agent对话流收口与验收.md` | #0 | ✅ |
| `frontend/src/components/LiveAgentConversation.vue` | #2.2 | ✅（待浏览器验收） |
| `frontend/src/services/runtime-conversation-adapter.js` | #2.1 | ✅（待补边界测试） |
| `frontend/src/services/runtime-conversation-adapter.test.js` | #2.1 | ✅ |
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
| `frontend/src/views/ReviewLiveView.vue` | #2.2 | ✅（待浏览器验收） |
| `frontend/src/styles/review.css` | #2.2 | ✅（待浏览器验收） |
| `src/main/resources/static/review/index.html` | #2.2 | ✅（构建产物） |
| `src/test/java/ai/cc/chongming/review/agentscope/AgentScopeModelBridgeTests.java` | #1.1 | ✅ |
| `src/test/java/ai/cc/chongming/review/agentscope/AgentScopeReviewRuntimeAdapterTests.java` | #3.1 | ✅ |
| `src/test/java/ai/cc/chongming/review/agentscope/RoleSubagentIsolationTests.java` | #3.2 | ✅ |
| `src/test/java/ai/cc/chongming/review/infrastructure/agentscope/ReviewAgUiEventMapperTests.java` | #1.3/#3.2 | ✅ |
| `src/test/java/ai/cc/chongming/review/infrastructure/agentscope/RuntimeTraceRedactorTests.java` | #1.3 | ✅ |
| `src/test/java/ai/cc/chongming/review/infrastructure/agentscope/ScoutToolTraceCollectorTests.java` | #3.2 | ✅ |
| `src/test/java/ai/cc/chongming/review/infrastructure/model/OpenAiCompatibleModelClientTests.java` | #1.1 | ✅ |

## 6. 实施顺序

1. **步骤 0** ✅：冻结目标、原始工具载荷例外、当前工作区与验收边界。
2. **步骤 1** ✅：完成模型 thinking、跨 Harness collector、AG-UI 映射和连续对话 UI 主体。
3. **步骤 2** ✅：修复 Scout 降级原因码回归；精确原因码未降级为通用码。
4. **步骤 3** ✅：补齐 mapper/collector/审计的防御性测试，并定位、修复 RolePack API 过时期望。
5. **步骤 4** ✅：完成定向 Java、前端单测、生产构建、IDEA 构建、差异检查与代码审查；Maven Wrapper 因 shell JDK 8 环境未运行，已记录为环境限制。
6. **步骤 5** ⏳：用户重启后的新 attempt 浏览器验收；依赖步骤 4。
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

下一位执行者从**步骤 5**开始。自动化门槛已完成；先请用户按其服务管理方式重启服务并创建新的 review/attempt，再执行本文件 #4.2 的浏览器验收。

运行服务由用户控制。完成自动化门槛后，请用户重启服务并创建新的 review，然后按 #4.2 执行浏览器验收。若模型只返回文本没有 thinking，这属于正常兼容路径；若工具输入/输出为空，应先核对该新 attempt 是否真正产生了 `chongming.tool-call.v1` 事件，而不是回退到旧状态块 UI。

## 10. 变更记录

| 日期 | 变更 |
|---|---|
| 2026-07-31 | 根据“`/live` 必须像主流 Agent 对话软件、展示思考/回答/工具入参与结果”的要求创建收口计划。 |
| 2026-07-31 | 记录主体实现已在未提交工作区，以及近期后端定向测试中 Scout 三个精确降级原因码被通用码替代的回归；将修复、测试、浏览器新 attempt 验收顺序固化为可交接步骤。 |
| 2026-07-31 | 用户明确当前阶段不需要工具调用结果脱敏；将其限定为本机调试 runtime trace 的临时边界，并保留后续生产化需单独设计的约束。 |
| 2026-07-31 | 完成 Scout 三个精确降级原因码回归：测试改为模拟 `createRuntime(...)` 返回的 `ScoutRuntime`，不再因旧 `create(...)` mock 产生空 runtime 并吞没根因。 |
| 2026-07-31 | 完成 mapper/collector 防御性覆盖、PRODUCT RolePack 旧 API 期望修复和前端跨角色同 toolCallId 去重样本；IDEA 全量构建、7 个后端定向类（40 用例）、前端 Vitest（6 文件、12 用例）、Playwright E2E（2 用例）与 Vite 构建通过。Maven Wrapper 被当前 shell JDK 8 基线限制阻断，已保留为环境记录。 |
