# Context Scout 对话式工具流计划

> **状态**: 🟡 运行态验收进行中：工具流页面、脱敏输入/输出与耗时已在真实 Scout 预览中验证；为让模型在下一轮接收原生工具结果而补充的 Bridge 修复需要重启服务后复验最终中文概览。
> **创建日期**: 2026-07-28
> **目标**: 将 Context Scout 的实时执行展示从“事件状态卡片”升级为类似 Codex 的连续对话流，使每次原生只读工具调用可按需查看脱敏后的真实入参、出参、耗时和失败信息。
> **关联计划**: AIREVIEW-PLAN-017、AIREVIEW-PLAN-018

## 0. 背景与当前事实

当前独立入口 `/#/reviews/{reviewId}/scout` 已能够创建 Scout 预览、订阅 SSE，并实际收到原生 AS2 文件工具的 `TOOL_CALL_START` / `TOOL_CALL_RESULT` 事件。浏览器验证表明事件能够持续到达，工具执行结果为成功；但页面将事件逐条渲染为“TOOL CALL START”“工具状态：SUCCESS”的状态块，无法解释模型到底调用了哪个工具、传入了什么检索条件、拿到了什么结果。

根因不是单纯的 Vue 渲染：AgentScope 2 的 `ToolCallStartEvent` 当前只包含 `toolCallId` 和 `toolCallName`，`ToolResultEndEvent` 只包含工具名和状态。现有 `ReviewAgUiEventMapper` 因而无法从原始事件取得调用参数或 `ToolResultBlock` 内容，只能构造状态摘要。

本计划只改 Context Scout 预览的运行流体验。正式评审领域事实、Director/角色/Judge 展示、隐藏推理、宿主文件访问、工具权限和 Gate 流程均不在本计划范围内。

## 1. 不变量与安全边界

### 1.1 可见内容边界

- 只显示 Scout 的公开可见文本，以及其三个 AS2 原生定向检索工具 `glob_files`、`grep_files`、`read_file` 的观测数据；根目录清单来自服务端 `context-scout-init`，不作为模型工具调用展示。
- 不展示或推断 `REASONING_*`、系统提示词、模型隐藏思维、其他 Agent 会话、宿主绝对路径、凭证、工具实现异常栈或受控快照外的数据。
- 入参和出参进入浏览器前必须经过 `RuntimeTraceRedactor`；输出保留快照相对路径、行号、命中数等审计所需事实。
- 单次工具输入、输出和事件载荷都有独立字节/字符上限；超过上限必须保留摘要与 `truncated=true`，不得静默截断或发送完整大文件内容。

### 1.2 协议与生命周期边界

- 保持 Scout 使用 AS2 原生受限文件系统；观测包装不得重新实现文件读取、不得改变 `ToolsConfig` 白名单、不得开放 `write_file` / `edit_file`。
- 所有工具流事件必须绑定 `reviewId + attemptNo + previewId + runtimeId + toolCallId`；前端只可合并同一运行的同一 `toolCallId`。
- Scout 预览结果继续不能写入正式 `scout-overview`；工具对话流只保存在当前预览的有界运行 trace 中。
- 继续使用 AG-UI 标准 `RUN_*`、`TEXT_MESSAGE_*` 事件；参数与结果采用具名 `CUSTOM` 扩展，不篡改标准 `TOOL_CALL_*` schema。

## 2. 目标体验与事件契约

### 2.1 单列对话体验

页面主体按时间顺序渲染连续执行项：

```text
Context Scout
我先识别工程入口与构建方式。

› glob_files                                      进行中
  输入  { "pattern": "{pom.xml,package.json,README.md}" }

  返回  找到 3 个文件 · 42ms                     展开输出

Context Scout
已确认 Spring Boot + Vue 结构，接下来定向检索需求相关模块。
```

- 工具启动后立即出现一条调用项，显示中文化工具名称、原始工具名、结构化入参和“进行中”。
- 同一 `toolCallId` 的完成或失败事件必须原地更新该调用项，显示脱敏出参摘要、耗时、状态和截断标记，不新增第二张状态卡。
- 工具出参默认收起；点击后展示格式化 JSON 或文本。结果为空、拒绝或失败时仍显示对应的安全摘要。
- Assistant 可见文本与工具项在同一时间线中交错排列。最终 JSON 概览渲染为正常 Scout 消息，而不是独立日志区域。

### 2.2 `CUSTOM` 工具观测事件

新增事件名 `chongming.tool-call.v1`，`value` 必须满足以下最小结构：

```json
{
  "schemaVersion": 1,
  "phase": "started",
  "toolCallId": "call-123",
  "toolName": "grep_files",
  "input": { "pattern": "memory" },
  "output": null,
  "status": "RUNNING",
  "elapsedMs": null,
  "truncated": false
}
```

完成事件的 `phase` 为 `completed` 或 `failed`，同时提供 `output`、`status`、`elapsedMs`、`truncated`。`output` 使用字段白名单：`text`、`summary`、`matchedCount`、`relativePaths`、`errorCode`；禁止直接转发任意 Java 对象或原始异常。

## 3. 分段方案

### 3.1 Scout 原生工具观测边界 ✅

**目标**：在不替换 AS2 原生文件系统能力的前提下，采集真实 `ToolCallParam` 输入和 `ToolResultBlock` 输出。

**涉及文件**：

- 修改 `src/main/java/ai/cc/chongming/review/infrastructure/agentscope/ContextScoutHarnessFactory.java`
- 新建 `src/main/java/ai/cc/chongming/review/infrastructure/agentscope/ScoutToolTraceCollector.java`
- 新建或修改 AS2 适配层中的原生工具观测包装文件（以实际 AgentScope 官方扩展点为准）

**关键实现**：

1. 先在 `E:\aicode\agentscope-java` 确认 `Toolkit` / `AgentTool` 的官方装饰或 middleware 扩展点；优先使用框架扩展点，不修改三个定向检索工具的读取语义。
2. 为每个 Scout 预览运行创建独立 collector，收到 `ToolCallParam` 时记录开始时间、工具名、调用 ID 和参数副本；收到 `ToolResultBlock` 时提取受控文本或结构化摘要。
3. collector 只接受 `glob_files`、`grep_files`、`read_file`；任何其它工具名视为运行配置错误并不向浏览器发送内容。
4. `ContextScoutHarnessFactory` 在创建预览 Harness 时注入 collector；预览完成、失败、取消或 SSE 关闭后释放其内存引用。

**退出条件**：一条真实 `grep_files` 调用可在服务端获得调用 ID、脱敏参数、结果摘要和耗时，且 Scout 原生只读权限保持不变。

### 3.2 安全事件映射与运行 trace ✅

**目标**：将观测数据安全映射为 AG-UI `CUSTOM` 事件，并与标准 run/text/tool 生命周期同流发送。

**涉及文件**：

- 修改 `src/main/java/ai/cc/chongming/review/infrastructure/agentscope/ReviewAgUiEventMapper.java`
- 修改 `src/main/java/ai/cc/chongming/review/infrastructure/agentscope/RuntimeTraceRedactor.java`
- 修改 `src/main/java/ai/cc/chongming/review/application/ContextScoutPreviewService.java`
- 视需要修改 `src/main/java/ai/cc/chongming/review/application/ReviewRuntimeTraceRegistry.java`

**关键实现**：

1. 扩展 redactor，分别处理工具参数、工具输出和错误摘要；保留现有凭证与绝对路径替换规则。
2. 增加每字段与每事件大小上限、结构化文本截断和二进制结果拒绝策略；输出显式携带 `truncated`，让前端不把摘要误认为完整结果。
3. `ReviewAgUiEventMapper` 保留标准 `TOOL_CALL_START/END/RESULT` 以兼容既有运行 trace，同时发送 `chongming.tool-call.v1` 供对话 UI 合并。
4. 通过 `toolCallId` 关联标准事件和扩展事件；缺失 collector 数据时页面降级显示工具名与安全状态，不制造空参数或伪造结果。

**退出条件**：SSE 回放与实时事件都能按同一 `toolCallId` 恢复工具对话；密钥、绝对路径和超长内容不能出现在载荷中。

### 3.3 连续对话 reducer 与工具消息组件 ✅

**目标**：将现有分离的 `AgUiConversationPanel` 和 `AgentTraceDrawer` 事件列表收敛为可更新的对话时间线。

**涉及文件**：

- 修改 `frontend/src/services/ag-ui-review-adapter.js`
- 修改 `frontend/src/components/AgUiConversationPanel.vue`
- 新建 `frontend/src/components/AgUiToolCallMessage.vue`
- 修改 `frontend/src/components/AgentTraceDrawer.vue`（仅保留为后续角色的兼容视图，不再作为 Scout 主视图）
- 新建或修改对应 Vitest 文件

**关键实现**：

1. 在 conversation 状态中新增统一的 `items` 时间线，元素类型为 `message` 或 `toolCall`；保留现有 `messages` 兼容正式工作台，避免本计划意外影响公开领域对话。
2. reducer 接收 `chongming.tool-call.v1` 后以 `toolCallId` 创建或更新单一 `toolCall` item；标准文本事件继续形成 `message` item。
3. `AgUiToolCallMessage` 默认显示工具名称、输入摘要、状态、耗时和输出摘要；使用原生 `<details>` 或等价可访问控件展开完整脱敏输入/输出。
4. 组件只能把预处理后的字符串作为文本渲染，禁止 `v-html`；JSON 格式化失败时退化为纯文本。

**退出条件**：一次工具调用无论收到多少 start/end/result 事件，页面只显示一项并原地完成；展开后可见与该调用关联的输入和输出。

### 3.4 Context Scout 页面收敛 ✅

**目标**：将 Scout 预览页改为单列、对话优先的 Codex 式执行体验。

**涉及文件**：

- 修改 `frontend/src/views/ContextScoutPreviewView.vue`
- 修改 `frontend/src/styles/review.css`
- 修改 `frontend/src/services/scout-preview-sse.js`（如需增加事件过滤或断线状态）

**关键实现**：

1. 保留精简头部、运行按钮和“受限 AS2 工作区”说明；移除 Scout 页面中与对话重复的 `AgentTraceDrawer` 大块日志。
2. 主体只渲染 Scout 连续对话；运行中自动滚动到最新项，但用户手动上滚后不强制抢占视图。
3. 最终概览、错误和截断提示都显示在同一时间线；运行状态只作为紧凑徽标，不再占用单独状态卡。
4. 保持键盘可访问性：工具展开控件有可读名称，代码块可选中复制，色彩不作为成功/失败的唯一信息来源。

**退出条件**：用户在一个页面即可按顺序读到 Scout 的公开说明、每次工具调用的入参与出参和最终概览，无需查看运行状态卡片。

### 3.5 验证、构建与文档同步 🟡

**目标**：证明工具详情真实、可关联、可脱敏，且不破坏 Scout 的快照与权限边界。

**涉及文件**：

- 新建 `src/test/java/ai/cc/chongming/review/agentscope/ScoutToolTraceCollectorTests.java`
- 修改 `src/test/java/ai/cc/chongming/review/agentscope/*Tests.java`
- 修改 `frontend/src/services/ag-ui-review-adapter.test.js`
- 新建或修改 `frontend/src/components/*.test.js`
- 修改 `frontend/e2e/*`（存在可复用 Scout 路径时）
- 修改 `docs/AIREVIEW-PLAN-018-共享项目上下文与定向检索.md`
- 修改本计划文档的状态、文件清单和变更记录

**关键实现**：

1. 后端单测覆盖：参数与结果的同一调用关联、并发调用不串线、脱敏、绝对路径替换、截断、失败摘要、非白名单工具拒绝。
2. 前端单测覆盖：started → completed 原地更新、重复事件幂等、展开/收起、文本消息与工具消息时序、未知扩展事件降级。
3. 浏览器验收使用真实 Scout 预览：看到工具名、入参、出参、耗时与最终中文概览；确认无 `write_file`、宿主路径或密钥。
4. 运行 `npm test`、`npm run build` 和最小相关 Maven 测试；提交匹配的 `src/main/resources/static/review/` 构建产物与 hash 更新。

**退出条件**：真实浏览器预览不再显示“工具状态 SUCCESS”列表，而是可展开的连续工具对话，且安全回归全部通过。

## 4. 文件清单

### 新建

| 文件 | 计划段 | 状态 |
|---|---|---|
| `docs/AIREVIEW-PLAN-019-ContextScout对话式工具流.md` | #0 | ✅ |
| `src/main/java/ai/cc/chongming/review/infrastructure/agentscope/ScoutToolTraceCollector.java` | #3.1 | ✅ |
| `frontend/src/components/AgUiToolCallMessage.vue` | #3.3 | ✅ |
| `src/test/java/ai/cc/chongming/review/infrastructure/agentscope/ScoutToolTraceCollectorTests.java` | #3.5 | ✅ |
| `src/test/java/ai/cc/chongming/review/infrastructure/agentscope/ReviewAgUiEventMapperTests.java` | #3.5 | ✅ |

### 修改

| 文件 | 计划段 | 状态 |
|---|---|---|
| `src/main/java/ai/cc/chongming/review/infrastructure/agentscope/ContextScoutHarnessFactory.java` | #3.1 | ✅ |
| `src/main/java/ai/cc/chongming/review/infrastructure/agentscope/ReviewAgUiEventMapper.java` | #3.2 | ✅ |
| `src/main/java/ai/cc/chongming/review/infrastructure/agentscope/RuntimeTraceRedactor.java` | #3.2 | ✅ |
| `src/main/java/ai/cc/chongming/review/application/ContextScoutPreviewService.java` | #3.2 | ✅ |
| `src/main/java/ai/cc/chongming/review/application/ReviewRuntimeTraceRegistry.java` | #3.2 | ✅ |
| `frontend/src/services/ag-ui-review-adapter.js` | #3.3 | ✅ |
| `frontend/src/components/AgUiConversationPanel.vue` | #3.3 | ✅ |
| `frontend/src/components/AgentTraceDrawer.vue` | #3.3 | 未改动（Scout 页面已移除其主视图） |
| `frontend/src/views/ContextScoutPreviewView.vue` | #3.4 | ✅ |
| `frontend/src/styles/review.css` | #3.4 | ✅ |
| `frontend/src/services/scout-preview-sse.js` | #3.4 | 未改动（现有 SSE 解析已可透传 CUSTOM） |
| `frontend/src/services/ag-ui-review-adapter.test.js` | #3.5 | ✅ |
| `docs/AIREVIEW-PLAN-018-共享项目上下文与定向检索.md` | #3.5 | ✅ |

## 5. 实施顺序与依赖

1. **步骤 0** ✅：冻结当前事实、边界、事件契约和验收标准。
2. **步骤 1** ✅：实现 #3.1 原生工具观测边界；依赖步骤 0。
3. **步骤 2** ✅：实现 #3.2 安全映射和运行 trace；依赖步骤 1。
4. **步骤 3** ✅：实现 #3.3 对话 reducer 与工具消息组件；依赖步骤 2 的事件样本。
5. **步骤 4** ✅：实现 #3.4 Scout 页面；依赖步骤 3。
6. **步骤 5** 🟡：前端单测 7/7、相关 Java 测试已扩展为 8/8、生产前端构建和 IDEA 编译均已通过。真实浏览器已验证工具调用原地完成、输入/输出/耗时与路径脱敏；当前进程无法热替换新增的 Bridge 私有方法，待重启后确认 Scout 收到工具结果并收束为最终中文概览。

## 6. 风险与应对

| 风险 | 应对 |
|---|---|
| AgentScope 原生生命周期事件不含参数和结果 | 先确认官方拦截点；仅在 Scout 的三个已授权原生定向检索工具外层增加透明观测，不修改读取逻辑或权限。 |
| 大文件或搜索结果使 SSE/UI 失控 | 输入、输出、事件分别限长；展示摘要、命中计数和 `truncated`，完整结果不进入运行 trace。 |
| 参数或结果泄露密钥、宿主路径 | 专用 redactor + 字段白名单 + 回归测试；浏览器只接收脱敏后数据。 |
| 多个并发工具调用显示错位 | 以 `toolCallId` 作为唯一合并键，结果到达前保持 RUNNING，缺失调用 ID 只渲染安全降级项。 |
| 为展示详情而改变 Scout 工具行为 | 观测与执行解耦；对 AS2 原生工具的白名单、ROOTED 模式、共享快照下层和 Shell 禁用建立回归测试。 |
| 对话 UI 影响现有正式工作台 | 在 reducer 中新增 timeline 并保持 `messages` 兼容；本计划只替换 Scout 页面主视图。 |

## 7. 非目标

- 不展示或存储模型隐藏推理。
- 不将 Scout 工具详情写入正式领域事件、Claim、Gate 或 `scout-overview`。
- 不开放 Scout 写文件、Shell、宿主仓库或其它 Agent 的会话。
- 不在本计划中重做 Director、角色或 Judge 的页面；其后续复用本计划组件必须另行计划。

## 8. 验收标准

1. Scout 调用 `glob_files`、`grep_files`、`read_file` 时，页面能显示工具名和对应的真实、脱敏入参。
2. 每个调用完成后，原地显示真实、脱敏出参摘要、状态和耗时；同一调用不产生多张状态卡。
3. 长结果显示截断标记且可展开查看安全范围内的完整摘要；页面不出现绝对宿主路径、密钥或隐藏推理。
4. 工具失败显示安全错误摘要，SSE 重连或重复事件不会重复渲染工具项。
5. Scout 仅拥有三个 AS2 原生定向检索工具，且服务端 `context-scout-init` 已提供根目录与结构清单；真实浏览器预览可完成中文项目概览。
6. `npm test`、`npm run build` 和相关 Maven 测试通过，静态资源 hash 与 `index.html` 同步更新。

## 9. 变更记录

| 日期 | 变更 |
|---|---|
| 2026-07-28 | 根据“工具调用须展示入参、出参，体验类似 Codex 连续对话”的要求创建计划。 |
| 2026-07-28 | 已确认 AS2 `MiddlewareBase#onActing` 可在不替换原生工具的前提下取得 `ToolUseBlock` 入参，并从同一执行流的 `ToolResultTextDeltaEvent` / `ToolResultEndEvent` 取得输出与终态；实施改用该非弃用扩展点。 |
| 2026-07-28 | 完成 Collector、AG-UI `chongming.tool-call.v1` 映射、连续对话组件和生产构建；前端单测 7/7、相关 Java 测试 5/5 通过。 |
| 2026-07-28 | 代码审查发现工具开始事件早于 acting 中间件、预览文本/异常未统一脱敏、预览 trace 未释放；已改为 ModelBridge 在 ToolCallStart 前通知 Collector、统一对结果/异常脱敏，并在完成后保留 10 分钟可重连窗口再释放 run 与 trace。相关 Java 测试扩展为 13/13 通过，复核无 P0/P1；浏览器验收仍因本地服务拒绝连接待完成。 |
| 2026-07-28 | 对 `127.0.0.1:8080` 的第三次 TCP 验证仍为 connection refused；根据计划验收标准，未将真实浏览器 E2E 误记为完成，等待用户恢复服务后继续。 |
| 2026-07-28 | 用户恢复服务后，以需求文档和 `cx-ai` 白名单仓库创建真实评审 `4a99c8f4-171c-4d15-b17c-f64608ffda2c`，并在预览 `98ca5c28-5305-4a59-8f5c-1046a58a7a59` 观察到 `list_files`、`glob_files` 的连续工具项。展开 `list_files` 可见 `{\"path\":\".\"}`、真实结果摘要、`4ms` 耗时，宿主路径均显示为 `[HOST_PATH_REDACTED]`。 |
| 2026-07-28 | 真实验收发现 AS2 的 `ToolExecutor` 从 `ToolUseBlock.content` 而非 `input` 做 JSON Schema 校验，导致 Bridge 仅填 `input` 时必填参数被误判缺失。已由 Bridge 同时写入 canonical JSON `content`，并以回归测试覆盖；IDEA 调试热替换后同一浏览器预览已返回真实文件列表。 |
| 2026-07-28 | 后续真实预览仍重复根目录 `list_files`。根因是 Bridge 原先只用 `Msg#getTextContent()` 组装下一次模型请求，嵌套在 `ToolResultBlock` 的原生工具结果会丢失；已补充受限文本结果和 12,000 字符上限的上下文序列化，并新增回归测试。该新增私有方法不能由 JVM HotSwap 注入运行中类，需重启后复验。 |
| 2026-07-28 | 审查发现工具结果进入模型上下文时缺少提示注入边界和整次请求预算。已增加不可信证据系统指令、`BEGIN/END_UNTRUSTED_TOOL_RESULT` 边界、工具名校验、48,000 字符总上下文上限，并改为按完整消息倒序保留，绝不从不可信工具结果中间截断；Bridge 回归测试 8/8 通过。 |
| 2026-07-30 | Scout 改为受限 `context-scout-init` 流程：服务端生成根目录与结构清单，模型不再获得 `list_files`；三个定向检索工具分别受 2/3/4 次配额及总预算约束，违反契约即降级并继续 Director。 |
| 2026-07-30 | 真实网关日志显示预览错误调用 `streamEvents(String)`，该 AS2 重载会传入空运行上下文，导致旧 Scout 会话混入新预览。现改为按 `reviewId + attemptNo + previewId` 显式创建会话，并将 Scout/fallback 输出预算从 1,024 提升为 2,048 token；不把本地模型的隐藏 `reasoning` 当成公开结果。 |
