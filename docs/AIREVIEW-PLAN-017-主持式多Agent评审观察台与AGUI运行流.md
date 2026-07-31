# 主持式多 Agent 评审观察台与 AG-UI 运行流计划

> **状态**: 🟡 已实现运行流订阅与角色执行抽屉；主持叙事和真实角色全链路验收仍进行中
> **创建日期**: 2026-07-22
> **目标**: 将现有“领域事件时间线 + 公开对话”升级为以 Director 主持过程为中心的实时评审观察台；用户可看见初审立论、主持人归纳与追问、角色间辩论、Judge 收束，并按需展开每个 Agent 的 AG-UI 流式执行详情。
> **前置计划**: PLAN-008、PLAN-009、PLAN-010、PLAN-012、PLAN-016

## CAPABILITY

评审发起者打开同一 review 的工作台后，默认看到的不是任务进度或混杂的日志，而是一场由 Director 主持的多 Agent 评审：角色完成初审后将公开结论交给 Director；Director 汇总冲突、发起针对性追问并推进议题辩论；Judge 在争议收束后给出裁决与 Gate 草案。用户可点击任一角色查看其同一运行中的 AG-UI 文本、推理（模型实际返回时）、工具调用和工具结果。

## 0. 当前事实与问题

- `ReviewEvent` 与 `/api/reviews/{id}/events` 已承载可恢复、已提交的领域事实；它不能替代运行调试流，也不得混入未提交的模型文本。
- `AgentScopeReviewRuntimeAdapter` 已在同一 `HarnessAgent.streamEvents` 链路接收原始 `AgentEvent`，但当前只转换为脱敏 `RuntimeObservation`，没有浏览器订阅面与消息内容。
- 前端 `AgUiConversationPanel` 当前只将公开领域事实转换为 `CUSTOM + TEXT_MESSAGE_*`，且明确不渲染 `REASONING_*`；这不满足主持过程与角色执行详情的需求。
- AgentScope Java 的 AG-UI 扩展提供 `AguiEvent`、`AguiAgentAdapter` 与文本、推理、工具、运行生命周期事件类型。当前模型桥只携带 `publicText`，要显示推理必须让网关明确提供并安全映射 `ThinkingBlock`；不得伪造推理内容。
- PLAN-016 已在 Director、普通角色和 Judge prompt 中要求所有可见输出使用简体中文；本计划的 UI 文案、主持指令摘要和工具状态同样统一中文。
- 本次真实运行验证中，评审 `68c022e4-95fe-4831-aa21-6befddc9ef81` 仍停留在 `PLANNING`：领域流仅有 `PLAN_CREATED` 与 `CONTEXT_SCOUT_DEGRADED`，运行 AG-UI 流只出现 `CONTEXT_SCOUT`、`DIRECTOR` 与工具事件，尚未产生 `PRODUCT` 的角色运行、领域激活或完成事件。因此“后台日志提及产品经理”不能作为 PRODUCT 已实际启动或前端漏渲染的验收依据。
- 当前主视图的“主持人叙事”只投影已提交的领域事件；角色的模型文本、工具入参与出参仅在对应角色的运行流中显示。页面默认选中 `DIRECTOR`，即使后续 PRODUCT 已正常启动，用户也需要从角色席位切换到 PRODUCT 才能查看其细节。该交互与“角色启动后自动可见”的目标仍有差距。

## 1. 体验与信息架构

### 1.1 默认主视图：评审圆桌

工作台把现有顶部摘要替换为阶段轨迹，再采用“角色席位 + 主持人叙事 + 议题辩论”三栏布局：

```text
阶段：初审立论 → 冲突识别 → 多 Agent 辩论 → Judge 收束 → 人工 Gate

角色席位                 主持人叙事                                  当前议题
Product  已提交结论       Director：已收到 Product、Project 立论       议题 #1：MySQL 兼容性
Project  已提交结论       Director → Backend：请核对 JSON 列兼容方案    Product 质询 Backend
Backend  正在调查         Backend → Director：发现 JSON 类型不兼容      Backend 答辩 / 补充证据
Frontend 等待调度         Director：形成争议，进入第一轮辩论            状态：ROUND_1
Judge     待命
```

- 阶段轨迹按状态机高亮：`INITIAL_REVIEW` 映射“初审立论”，`CONFLICT_DETECTION` 映射“冲突识别”，两个 debate stage 映射“多 Agent 辩论”，`JUDGING` 映射“Judge 收束”，`WAITING_HUMAN` 映射“人工 Gate”。已完成阶段可回看，不提供跳过阶段的操作。
- 角色席位只显示对用户有意义的状态：等待、调查中、已提交立论、收到追问、质询/答辩中、已完成、失败/取消；显示最新公开摘要、风险数、证据数与“查看执行过程”。不把 token 数或原始日志作为主视觉。
- 主持人叙事按发生顺序展示 Director 的计划、任务分派、角色结论送达、冲突归纳、追问、议题创建/关闭、Judge 移交；每项可跳转至相关角色、Claim 或议题。
- 议题区按 `topicId` 聚合，而不是按全局事件平铺。每个议题卡显示提出 Claim、冲突双方、轮次、Challenge、Rebuttal、立场变化、证据与关闭原因。

### 1.2 按需详情：角色执行抽屉

点击角色席位的“查看执行过程”后打开右侧抽屉；不替换主舞台。

- 按一次 Agent invocation/step 分段显示 AG-UI `RUN_*`、`TEXT_MESSAGE_*`、`REASONING_*`、`TOOL_CALL_*` 事件。
- 工具名称与执行状态默认可见；参数和结果折叠。允许显示已脱敏的快照相对路径、行号、命中数量，不显示宿主绝对路径、密钥、受控目录外内容或其它角色私有 session。
- 推理只在模型真实返回 `ThinkingBlock` 时显示；没有该块时显示“模型未返回可展示的推理流”，绝不由文本、工具调用或业务事实推断/补写。
- 抽屉支持“定位到当前步骤”“仅工具”“仅消息”“仅推理”本地筛选；不提供对模型提示词、工具权限或领域状态的修改入口。

## 2. 不变量、权限与边界

1. 正式领域事实仍只由服务端业务工具提交，仍经 `ReviewProtocolGuard`、状态机和领域事件写入；AG-UI 观察流只能读取运行态，不能写 Claim、Turn、Gate 或 review 阶段。
2. 领域 SSE 与 AG-UI 运行 SSE 是两个端点、两个 cursor 和两个语义：前者可持久化回放，后者是当前 attempt 的有限运行观察回放；两者不得互相伪装。
3. 每个 AG-UI event 必须绑定 `reviewId + attemptNo + runtimeId + agentId + role`；订阅者只能订阅本 review 当前或明确指定的 attempt，拒绝跨 review、路径、session 猜测。
4. 不输出原始系统提示、隐藏 memory、宿主文件路径、凭据、未允许的工具结果或模型供应商错误正文；错误按现有诊断脱敏规则处理。
5. 取消、失败、retry 必须关闭旧 attempt 的 live run，并把旧 attempt 只读留给回放；旧 runtime event 不得投递到新 attempt。
6. 页面必须继续在无 AG-UI live stream 时展示持久化领域事件与正式辩论结果，不能因观察功能不可用阻塞评审。

## 3. 实现契约

### 3.1 后端运行流

新增独立端点：

`GET /api/reviews/{reviewId}/attempts/{attemptNo}/runtime/ag-ui`

- `Accept: text/event-stream`；每个 SSE `data` 是一个完整 JSON AG-UI event，事件类型使用 AG-UI 的 `type` 字段，不依赖领域 event name。
- 建连后先发送有限的当前 attempt 内存回放，再发送实时事件；客户端使用 runtime cursor 去重。有限缓存默认 500 条或可配置上限，满后丢弃最早的非终态细节并发送一个 `CUSTOM` 截断提示。
- 首条为 review-level `RUN_STARTED` / `STATE_SNAPSHOT`，包含阶段、attempt、角色状态和不敏感运行摘要；每个 Agent invocation 使用独立 `runId`，通过 `parentRunId` 关联 review run。
- 终态发送对应 `RUN_FINISHED` 或 `RUN_ERROR`；取消发送明确的 `CUSTOM` cancellation 状态后关闭。SSE 断开不会取消评审或 Agent。

新增 `ReviewRuntimeTraceRegistry`：

- 由 `AgentScopeReviewRuntimeAdapter` 在 Agent 创建、raw event、正常结束、错误、取消时写入。
- 按 runtime/attempt 建立有界事件环形缓存与订阅者列表；订阅者注册、历史回放、缓冲、激活的顺序复用 `ReviewSseRegistry` 的无丢失模式，但不共享领域 sequence。
- 事件写入只做内存操作和脱敏转换，禁止在 Reactor callback 中做数据库查询或网络调用。

### 3.2 AgentScope → AG-UI 转换

引入 `agentscope-extensions-agui`，使用其 `AguiEvent` 协议对象与 `AguiAdapterConfig.enableReasoning(true)` 的事件语义；转换必须发生在现有真实运行 stream 上，不能为了观察再启动一个相同 Agent。

| AgentScope 事实 | AG-UI 事件 | 页面含义 |
|---|---|---|
| Agent 创建/开始 | `RUN_STARTED`、`STEP_STARTED` | 角色开始执行 |
| 可见模型文本 | `TEXT_MESSAGE_START/CONTENT/END` | 角色或主持人的可见发言 |
| 模型真实 ThinkingBlock | `REASONING_MESSAGE_*` | 可展开的推理流 |
| 工具开始/结束/结果 | `TOOL_CALL_START/END/RESULT` | 执行过程 |
| 原始 lifecycle/调度元数据 | `CUSTOM` | 角色状态、阶段、任务分派、轮次 |
| 正常结束/异常/超迭代/取消 | `RUN_FINISHED` / `RUN_ERROR` / `CUSTOM` | 可见收束或失败原因 |

- `AgentScopeModelBridge` 需要扩展模型网关响应契约以携带可选 thinking；响应没有 thinking 时不创建 reasoning event。
- 文本、工具参数、工具结果均先经过 `RuntimeTraceRedactor`；限制单字段与单事件大小，避免大文件内容或异常栈进入浏览器。
- Tool result 只显示概要（成功/拒绝/失败、工具名、耗时、命中/证据 ID）；完整工具结果保留在既有受控存储而非 AG-UI payload。

### 3.3 主持人叙事读模型

新增前端专用的 `ReviewNarrativeProjector`，从已提交的 `ReviewEvent` 构造展示项，绝不从模型私有文本构造领域结论。

| 叙事项 | 领域依据 |
|---|---|
| Director 创建/修订计划 | `PLAN_CREATED`、`PLAN_REVISED` |
| 角色受邀、启动、完成、失败 | `ROLE_ACTIVATED`、`ROLE_STARTED`、`ROLE_COMPLETED`、`ROLE_FAILED` |
| 角色结论送达主持人 | `CLAIM_SUBMITTED`、`ROLE_COMPLETED` 的公开摘要 |
| 主持人识别冲突/创建议题 | `INITIAL_REVIEW_COMPLETED`、`DEBATE_TOPIC_OPENED` |
| 主持人追问/角色回复 | `CHALLENGE_SUBMITTED`、`REBUTTAL_SUBMITTED`、`EVIDENCE_REQUESTED` |
| 议题收束/移交 Judge | `DEBATE_TOPIC_CLOSED`、进入 `JUDGING` 的阶段状态 |
| Judge 结论/Gate 草案 | `JUDGEMENT_SUBMITTED`、`GATE_DRAFTED`、`HUMAN_GATE_FINALIZED` |

如果现有领域事件缺少“Director 向角色发出追问”的公开事实，不从运行日志猜测；新增版本化领域事件或在现有事件 payload 中添加受控 `narrativeAction`，并同步状态机、存储、SSE 和前端样本。

### 3.4 前端状态与组件

保留 `review-store` 的领域事件状态，新增 `runtimeTraceStore`，二者只在 `reviewId + attemptNo` 处关联。

| 文件/组件 | 职责 |
|---|---|
| `ReviewStageRail.vue` | 五段阶段轨迹与当前阶段解释 |
| `ReviewRoundtable.vue` | 角色席位、主持人叙事、当前议题组合布局 |
| `DirectorNarrativePanel.vue` | Director 主线及跨角色结果送达 |
| `RoleSeatList.vue` | 角色状态、结论摘要、风险/证据计数、打开抽屉 |
| `DebateTopicBoard.vue` | 按 topic 聚合的质询、答辩、立场和收束 |
| `AgentTraceDrawer.vue` | 单角色 AG-UI 文本/推理/工具流与本地筛选 |
| `services/ag-ui-runtime-sse.js` | AG-UI SSE 解析、重连、cursor 与停止 |
| `stores/runtime-trace-store.js` | event 去重、按 agent/run 聚合、角色运行状态 |

现有 `AgUiConversationPanel` 更名或降级为“公开事实流”，不再承担 Agent 运行观察职责；`ReviewWorkbenchView` 默认渲染圆桌，抽屉按需挂载。

## 4. 分段实施顺序

### 4.1 冻结契约与事件样本 ✅

1. 为 runtime AG-UI endpoint、cursor、error、截断和脱敏编写 OpenAPI/JSON 样本。
2. 定义 review-level run 与 agent-level run ID 规则，测试 attempt 隔离、重连和 terminal close。
3. 将 Director/角色中文可见输出约束列为角色包契约回归项。

**退出条件**：后端/前端共享的 AG-UI 样本包含初审、辩论、Judge、失败、取消和截断六类场景。

**实际状态**：运行 SSE、事件身份与脱敏边界已实现；完整的六类端到端样本尚未齐备。

### 4.2 运行期 AG-UI trace 后端 ✅

1. 增加 AgentScope AG-UI 扩展依赖与 `ReviewRuntimeTraceRegistry`。
2. 在真实 Adapter stream 旁路写 trace，完成 run/text/tool/lifecycle 映射与脱敏。
3. 新增 SSE controller、有限回放、心跳、连接清理与 attempt 校验。

**退出条件**：Mock Harness 能使浏览器订阅收到不同角色交错的标准 AG-UI 事件；未新增第二次模型调用。

**实际状态**：真实 Harness 旁路发布、有限回放和浏览器订阅均已接入；本次真实运行已经收到 Scout 与 Director 的运行事件，证明订阅链路可用。尚待由真实角色运行补齐跨角色交错验证。

### 4.3 主持叙事与阶段读模型 🟡

1. 从领域事件投影角色席位、主持人叙事和按 topic 的辩论板。
2. 如缺少正式“追问”事件，先补版本化领域命令/事件再渲染，不以 raw trace 代替业务事实。
3. 补齐进入 Judge、Gate 和失败/取消的叙事收束。

**退出条件**：给定完整事件样本，页面可稳定重建“初审立论 → 辩论 → Judge 收束”的同一顺序。

**实际状态**：计划创建与 Scout 降级可投影到主持叙事；角色激活、初审完成、议题、Judge 与 Gate 的真实链路尚未通过同一 review 验收。本计划不将未提交的 Director 文本或日志替代为领域叙事。

### 4.4 圆桌 UI 与角色抽屉 🟡

1. 先替换工作台主区域为阶段轨迹、角色席位、Director 叙事和议题板。
2. 再接入单角色 AG-UI 抽屉、自动滚动、筛选与无 live stream 的降级状态。
3. 保持移动端单列顺序：阶段 → 主持人 → 当前议题 → 角色席位 → 执行抽屉。

**退出条件**：用户在不展开日志时能解释当前阶段、谁已立论、Director 等待谁、当前争议是什么、何时移交 Judge。

**实际状态**：圆桌、角色席位和单角色 trace 抽屉已存在；默认固定在 Director，且主持区不消费角色运行文本。待补“角色启动/有新文本时的可见提醒或自动聚焦”与完整议题板，避免真实角色开始后用户仍误以为页面没有更新。

### 4.5 验证、构建与文档 🟡

1. Java 单测：AG-UI 映射、脱敏、attempt 隔离、取消、缓存截断、订阅回放与终态。
2. Vue/Vitest：叙事投影、角色状态、AG-UI reducer、抽屉筛选、重连和降级。
3. Playwright：模拟四个角色立论、创建议题、质询答辩、Judge 收束，并断言主视图与执行抽屉。
4. 运行 `mvn test`、`npm test`、`npm run build`；将构建的静态资源与对应 hash 一并更新。

**退出条件**：全链路 mock 场景通过；不启动本地服务作为本计划的代码验证前提。

**实际状态**：已有映射、脱敏、SSE 与前端状态的针对性验证；真实模型验收目前只覆盖至 Scout 降级和 Director 计划阶段，尚未覆盖 PRODUCT 初审、辩论、Judge、Gate 与失败/取消的可视化闭环。

## 5. 非目标

- 不让用户在观察台直接向 Agent 发送任意消息、修改 prompt 或绕过 `ReviewProtocolGuard`。
- 不实现跨进程、跨机器的运行 trace 持久化；本计划只提供当前 attempt 的有界内存回放，正式审计仍使用领域事件。
- 不展示模型未返回的思维链，不推断或生成推理内容。
- 不改变远程 Git、快照访问、Agent 权限、Judge/Gate 业务决策或现有人工审核流程。

## 6. 风险与对策

| 风险 | 对策 |
|---|---|
| 运行流泄露 prompt/密钥/宿主路径 | 单独 Redactor、字段白名单、大小限制、敏感回归测试；默认只展示工具概要 |
| 原始 trace 与领域事实冲突 | 两条流分端点、分 cursor；圆桌结论只由领域事件投影 |
| 多角色并发造成 UI 时序混乱 | agent/run 独立 ID、事件单调 trace sequence、按 topic 与 Director 叙事分别排序 |
| 观察功能拖慢 Agent | 旁路有界内存写入；订阅者慢时丢弃非终态细节并报告截断，不反压评审执行 |
| 模型没有 reasoning | 显式空状态，不伪造；文本、工具与状态仍可完整解释执行过程 |
| 页面信息过载 | 默认圆桌只显示主持叙事和公开结论，角色 trace 使用抽屉按需展开 |

## 7. 文件清单

| 文件 | 变更 |
|---|---|
| `pom.xml` | 引入 AgentScope AG-UI extension |
| `review/infrastructure/agentscope/AgentScopeReviewRuntimeAdapter.java` | 在真实运行 stream 旁路发布 AG-UI trace |
| `review/infrastructure/agentscope/AgentScopeModelBridge.java` | 安全映射可选 thinking，不伪造 reasoning |
| `review/infrastructure/agentscope/RuntimeTraceRedactor.java` | 新建运行流脱敏与大小限制 |
| `review/application/ReviewRuntimeTraceRegistry.java` | 新建有界回放与实时订阅注册表 |
| `review/api/ReviewRuntimeTraceController.java` | 新建 AG-UI SSE 端点 |
| `frontend/src/stores/runtime-trace-store.js` | 新建运行流状态与 AG-UI reducer |
| `frontend/src/services/ag-ui-runtime-sse.js` | 新建 AG-UI SSE 客户端 |
| `frontend/src/components/*Roundtable*.vue` | 新建阶段、角色席位、主持人叙事、议题板、trace 抽屉 |
| `frontend/src/views/ReviewWorkbenchView.vue` | 接入圆桌主视图与抽屉 |
| `frontend/src/styles/review.css` | 圆桌与抽屉响应式样式 |
| `src/test/java/.../runtime/*Tests.java` | 后端转换、隔离、脱敏、SSE 测试 |
| `frontend/src/**/*.test.js` | store、AG-UI parser、组件测试 |
| `frontend/e2e/review-roundtable.spec.js` | 端到端视觉与交互测试 |
| `docs/AIREVIEW-PLAN-008-*.md`、`009`、`010`、`012` | 实施后同步职责边界与真实状态 |

## 8. 验收标准

1. 用户无需打开任何日志即可从主视图辨识当前处于初审立论、辩论还是结论收束。
2. 每个已完成角色都能在 Director 主线中体现“结论已送达”，并能跳转到公开 Claim/证据。
3. 所有正式 Challenge/Rebuttal/PositionChanged 都按 topic 出现在辩论板，Judge 只在允许阶段出现。
4. 角色抽屉能实时显示该角色的 AG-UI 文本、工具和真实 reasoning（如有），不显示其它角色的私有运行内容。
5. 刷新或短暂断线后，领域圆桌完整恢复；当前 attempt 的 trace 在有界缓存范围内恢复并明确提示截断。
6. 失败、取消和 retry 不会把旧 attempt trace 投递给新 attempt；不影响领域 SSE 和正式业务状态。
7. 所有可见 Agent 文本均为中文，前端没有通过翻译或拼接伪造模型内容。

## 9. Handoff

计划已具备直接实施条件。建议按 **4.1 → 4.2 → 4.3 → 4.4 → 4.5** 顺序执行，避免先做页面后倒推运行协议；其中 4.2 与 4.3 的契约冻结完成后，前后端可以并行。

## 10. 变更记录

| 日期 | 变更 |
|---|---|
| 2026-07-22 | 根据“主持人主持流程、角色结论回传、可展开执行流”的要求创建计划。 |
| 2026-07-22 | 已落地首个观察闭环：真实 Harness stream 旁路映射 AG-UI 的 run、文本、工具与 lifecycle 事件；新增按 review/attempt 隔离的有界 SSE 回放、`Last-Event-ID` 续传、浏览器去重、运行流脱敏、圆桌主视图与角色执行抽屉。尚未实现可选 ThinkingBlock 网关映射、缓存截断提示、完整的阶段和议题读模型与端到端覆盖。 |
| 2026-07-23 | Director 改为可在自身 attempt 工作区中读写和编辑文件；服务端将不可变需求快照复制为 `input/requirement.md` 工作副本。共享仓库快照与宿主目录仍不暴露给 Harness filesystem。 |
| 2026-07-31 | 基于真实评审 `68c022e4-95fe-4831-aa21-6befddc9ef81` 更新运行验收状态：AG-UI SSE 已收到 Scout/Director 事件，但评审仍在 PLANNING，未出现 PRODUCT 的角色运行或领域激活事件。将“运行流已订阅”与“多角色展示已验收”明确拆开，后者继续保持待验收。 |
