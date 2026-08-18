# 评审入口与公开对话体验收口计划

> **状态**: ⏳ 实施中（步骤 0 契约冻结已完成，流式 Spike 与步骤 1～5 并行实施）
> **创建日期**: 2026-08-10
> **目标**: 补齐仓库选择与草稿发起评审能力，并将 /live 收口为与主界面一致、以公开 AI 输出为主、可稳定回放 Scout
> 结论和多角色对话的可阅读评审体验。
> **关联计划**:
>
AIREVIEW-PLAN-012、AIREVIEW-PLAN-017、AIREVIEW-PLAN-018、AIREVIEW-PLAN-019、AIREVIEW-PLAN-020、AIREVIEW-PLAN-021、AIREVIEW-PLAN-022
> **需求来源**: 2026-08-10 本地真实运行验收与用户 UI 反馈

## 0. 能力定义与边界

### 0.1 能力声明

评审提交者能够从服务端当前激活的仓库白名单选择目标仓库，并能从草稿详情补齐 Markdown 与启动参数后发起评审；观察者能够在浅色
/live 工作区按阶段查看 Context Scout、Director、独立审查角色与 Judge 的公开 AI
对话，在右侧抽屉查看跨角色全局对话，并按需展开工具调用查看入参和出参；Scout、Claim、Judge、AI Gate 与人工 Gate
均以可阅读且可重启回放的正式结果展示。

本计划所称“对话”是只读的多 Agent 公开运行转录，不包含人类在运行中继续追问 Agent。双向交互应另行规划 HITL 命令、暂停和恢复状态机。

### 0.2 六项问题映射

| # | 当前问题                          | 交付能力                                | 计划段   |
|---|-------------------------------|-------------------------------------|-------|
| 1 | 仓库是自由文本                       | 从当前 Spring 配置安全读取选项，仓库字段改为下拉        | #2    |
| 2 | 草稿没有发起评审                      | 草稿补齐文档和参数后幂等发起 Review               | #3    |
| 3 | /live 是独立黑色主题                 | 保留全屏布局，视觉统一为平台浅色                    | #4    |
| 4 | Scout 没有稳定结论                  | 持久化结构化结论，在红框区域常驻渲染                  | #5    |
| 5 | Claim subjectKey 拼接不可读，结论层级不清 | Claim 正文优先，Judge、AI Gate、人工 Gate 分层 | #6    |
| 6 | 工具块主导页面，各 Agent 没有 AI 对话感     | 所有 Agent 共用对话组件，右抽屉提供全局对话，真实增量输出    | #7、#8 |

### 0.3 已确认决策

1. 主区域展示当前阶段或当前角色的专属 AI 对话。
2. 右侧抽屉提供“全部对话 / 评审事实 / 运行调试”三个选项卡。
3. Context Scout、Director、产品、项目、前端、后端和动态激活角色使用同一套对话体验。
4. 工具调用穿插在对应消息间，默认折叠；点击单个工具查看入参、出参、状态和耗时。
5. Scout 对话下方常驻展示“上下文收集结论”。
6. Claim 的 statement 和 reasonSummary 是主要阅读内容，subjectKey 只是次要技术标识。
7. 模型隐藏 reasoning、系统指令和内部工具策略不得进入正式界面。
8. 流式必须来自模型与后端增量事件，禁止用前端打字机伪造。
9. 供应商不支持 SSE 时明确使用非流式 fallback。
10. 浏览器不得获得仓库物理 root 或服务端凭据。
11. 新建或修改 Java 文件统一使用 @author zyj，并标注 AIREVIEW-PLAN-023 段号。

### 0.4 既有计划修订

本计划覆盖 AIREVIEW-PLAN-020 的用户可见行为：

- 新 Trace 不再发布或持久化 reasoning；前端忽略历史 REASONING_MESSAGE。
- 工具入参和出参仍可查看，但默认折叠并视觉降级；展示层对敏感键掩码。
- 继续复用 AIREVIEW-PLAN-022 的 Trace 持久化和游标回放，但减少无展示价值的 CUSTOM 噪声。
- 正式阶段、Claim、辩论、Judge 与 Gate 仍以领域事实为权威。
- AIREVIEW-PLAN-018/019 的 Scout 工作区文件保留为诊断副本，不作为正式查询事实源。

## 1. 总体架构

### 1.1 页面结构

    浅色 Header：评审标识 / 连接状态 / 返回工作台
    ├── 左侧阶段轨道
    ├── 中间当前阶段
    │   ├── Agent 专属对话
    │   ├── 默认折叠工具
    │   └── Claim / Plan / Scout 结论等结构化附件
    └── 可折叠右侧抽屉
        ├── 全部对话
        ├── 评审事实
        └── 运行调试

主区域按 phase 和 role 过滤；全局对话按 Trace sequence 聚合；评审事实来自领域事件；运行调试显示连接、生命周期、错误与游标，不显示
reasoning。

### 1.2 对话视图模型

    ConversationItem
    - id / sequence / runId
    - messageId 或 toolCallId
    - role / phase
    - kind: message | tool | claim | plan | notice
    - content / status
    - startedAt / completedAt / elapsedMs
    - input / output

工具最终状态综合 phase、status 与 ToolResultState；phase=failed 不得显示成功。同一 messageId 的 delta 必须幂等合并。

### 1.3 依赖

1. #2 是 #3 的前置。
2. #5 先完成正式读模型，再做红框面板。
3. #7 统一组件依赖 #4、#5、#6 的展示契约。
4. #8 真实流式依赖 #7 的稳定 reducer，并复用 PLAN-022 回放。
5. #9 在全部功能段完成后执行。

## 2. 仓库选项按当前配置下发

### 2.1 API

新增 GET /api/repositories，返回 id 与 displayName。

1. 数据来自当前 Spring Environment 绑定后的 RepositoryAccessProperties.allowed；local profile 自动读取
   application-local.yml。
2. 前端不得直接读取配置文件。
3. 保持配置顺序，拒绝重复 ID。
4. 禁止返回 root。
5. displayName 未配置时回退为 id。
6. 空配置返回空数组，前端禁用提交。

### 2.2 下拉框

新需求创建、独立评审创建、草稿详情编辑、草稿发起表单统一复用仓库选项：

1. 加载中显示读取状态。
2. 加载失败不回退为自由文本。
3. 单一选项自动选中。
4. 历史仓库已失效时只读展示，保存或发起前强制重新选择。
5. API 客户端、单元测试和 E2E fixture 同步更新。

## 3. 草稿详情发起评审

### 3.1 入口

仅 DRAFT 显示“发起评审”。表单收集：

- Markdown 文件，必填并重新上传，不用 description 生成文件。
- 仓库下拉、分支、可选 Commit。
- 提交人。
- 每行一项的公开计划。
- 计划原因和启动说明。

### 3.2 幂等编排

新增 RequirementReviewLaunchService 和单一启动端点，由服务端依次 intake、绑定、启动；命令携带 expectedVersion 和
Idempotency-Key。现有 createReview、submitRequirement、startReview 保持兼容。

1. 重复点击或网络重试不得创建第二个 Review。
2. intake 去重必须以 Requirement 为归属范围：同一 Requirement 的同一输入继续复用，两个不同 Requirement 即使 Markdown、提交人和仓库完全相同，也必须创建不同 Review root；不得先复用另一需求的 reviewId 再返回伪业务冲突。
3. intake 或绑定成功而启动失败时保留可恢复状态；重试同一命令继续启动。
4. 乐观锁冲突刷新 Requirement 后要求重新确认。
5. 成功后进入待评审或评审中并跳转 /reviews/{reviewId}/live。
6. 服务层和 Controller 覆盖成功、冲突、半失败恢复与幂等测试。

若事务和异步启动无法由单端点表达，允许采用“事务内创建绑定、事务后可重试启动”，但必须返回明确 phase 和 recoverable
状态，并先更新计划偏差。

## 4. /live 浅色视觉

### 4.1 主题

保留全屏观察台，不强制恢复平台侧栏；review-flow 样式迁移到平台浅色变量：

- 灰白页面、白色卡片、石色边框和轻阴影。
- 主次文字、按钮、tag、badge 与平台一致。
- Agent 头像保留角色色并满足浅色对比度。
- focus、hover、选中、禁用使用平台统一语义。

### 4.2 抽屉与响应式

1. 桌面端阶段轨道和主区域常驻，右抽屉可开关。
2. 抽屉宽 420 到 520px，不再固定 19rem。
3. 窄屏抽屉覆盖显示，支持 Escape、遮罩关闭和焦点恢复。
4. 关键状态不能只依赖颜色或 hover。
5. 对 1440×900、1024×768 和移动窄屏做截图回归。

## 5. Context Scout 对话与持久化结论

### 5.1 正式结论模型

新增 ContextScoutConclusion：

- reviewId、attemptNo、schemaVersion。
- summary、moduleRoots、entryPoints、constraints、risks。
- evidencePaths、roleScopes、rawPublicResult、createdAt。

新增 ContextScoutConclusionStore、MyBatis 实现和 MySQL 迁移，按 reviewId 与 attemptNo 唯一持久化。MySQL 5.6 使用 LONGTEXT
JSON，由应用层 ObjectMapper 校验。

新增 CONTEXT_SCOUT_COMPLETED 领域事件，只携带 status、schemaVersion、publicSummary 和 conclusionRef，不复制完整大对象。

1. Store 成功后再发布完成事件。
2. Scout 输出采用可验证 schema；解析失败时仍保存 summary 与 rawPublicResult，集合为空。
3. ReviewQueryService 和 ReviewContextAssembler 从 Store 读取，保证重启后一致。
4. 当前 attempt 优先返回成功结论，否则返回降级事件。
5. 不保存 reasoning、系统提示或未经授权的文件全文。
6. 历史 attempt 无结构化记录时标记 legacy 并兼容公开摘要。

### 5.2 红框面板

Scout 主区域依次展示对话、折叠工具、“上下文收集结论”。结论包括：

- 收集摘要。
- 关键模块和入口。
- 约束与风险。
- 查阅依据数量和证据路径。
- 各角色关注范围。
- 展开完整上下文。

需求详情和最终报告复用同一读模型，不再出现成功后仍显示“未开始”。

## 6. Claim 可读化与结论链

### 6.1 角色概览

收起卡不拼接 subjectKey，改为“发现 N 项：X 项阻断、Y 项高风险、Z 项改进建议”。P0/P1/P2/P3 分别映射为阻断、高风险、改进建议、提示。角色来自
activatedRoles 和 runtime identity，不硬编码四角色。

### 6.2 Claim 条目

1. 严重度和立场。
2. statement 作为主要正文。
3. reasonSummary 作为解释。
4. Evidence 数量和查看入口。
5. subjectKey 仅显示为技术标识。
6. 默认前三条，可展开全部。
7. 不用二次模型改写 Claim。
8. 运行对话是补充过程，不能淹没正式 Claim。

### 6.3 Judge、AI Gate、人工 Gate

页面明确展示：

    Judge 议题裁决
        ↓
    确定性 AI Gate 草案
        ↓
    人工最终 Gate（如存在）
        ↓
    正式报告版本（如存在）

1. WAITING_HUMAN 只能称 AI Gate 草案。
2. Judge 理由、采信和拒绝 Claim 可下钻。
3. 人工结果与 AI 草案不一致时突出差异和人工理由。
4. 枚举中文化。
5. /live 不直接写 Gate；移除五个伪快捷按钮，只保留“进入人工决策”。
6. 工作台人工表单默认“请选择”，避免默认 PASS。

## 7. 所有 Agent 的统一 AI 对话

### 7.1 统一组件

收口 LiveAgentConversation、AgUiToolCallMessage 与 runtime-conversation-adapter：

- Scout：对话加结论附件。
- Director：对话加计划附件。
- 独立角色：角色概览切换到完整对话，加 Claim 附件。
- Judge：对话加裁决附件。
- 全部对话：按 sequence 聚合所有公开消息。

要求：

1. 展示头像、角色、阶段、时间和流式状态。
2. Markdown 支持标题、列表、表格、代码块和链接；禁用 raw HTML 和危险 URL。
3. 同一 messageId 增量写入同一气泡。
4. 用户在底部时自动跟随；上滚后暂停并显示“回到最新消息”。
5. notice、失败和断线不冒充 AI 回答。
6. 服务端阻断新 reasoning，前端忽略历史 reasoning。

### 7.2 工具调用

默认显示紧凑行：

    🔧 search_text             已完成 · 342ms  >

点击展开入参和出参。

1. 每个工具默认折叠，包括运行中。
2. 单个工具独立展开。
3. phase=failed 或失败 ToolResultState 优先于 SUCCESS 字段。
4. 超长内容内部滚动并支持复制。
5. token、apiKey、authorization、password、secret 等敏感键在浏览器展示层掩码。
6. 运行调试保留完整事件顺序，但不显示 reasoning。

### 7.3 右侧抽屉

- 全部对话：跨 Agent 公开消息和折叠工具，可按角色过滤。
- 评审事实：计划、Claim、辩论、Gate 领域事件。
- 运行调试：连接、生命周期、错误、游标和诊断元数据。

关闭抽屉不停止订阅；重新打开保持 tab 和滚动位置。

## 8. 真实流式输出

### 8.1 技术 Spike

实现前用 IDEA MCP 与聚焦测试验证：

1. Provider delta 如何进入 AgentScope AgentEvent。
2. 工具名称和参数 delta 如何聚合。
3. AgentResultEvent 最终 Msg 如何与增量消息共用 messageId。
4. 取消、超时和工具循环如何关闭消息。
5. 若框架没有公开扩展点，记录最小 Bridge 扩展方案，禁止前端伪流式。

### 8.2 Provider

OpenAiCompatibleModelClient 当前固定 stream=false，需要：

1. 请求 stream=true。
2. 解析 data chunk 中的文本 delta、工具参数 delta、finish reason 和 usage。
3. Provider 接口增加流式回调或 Publisher，同时保留同步聚合兼容路径。
4. 明确定义超时、取消、429、4xx、5xx、畸形 chunk 与 DONE。
5. profile 不支持流式时使用明确 fallback。

### 8.3 AgentScope 与 AG-UI

1. 公开文本映射为稳定 messageId 的 START、多个 CONTENT、END。
2. 工具参数完整后才交给 ToolExecutor，不执行半截 JSON。
3. 最终聚合结果继续供 Msg、工具循环和领域命令使用。
4. ThinkingBlock 不再映射 reasoning。
5. 不把普通 delta 全部变成 CUSTOM，只保留身份、生命周期、公开文本、工具和错误。
6. runId、messageId/toolCallId、sequence 稳定，断线回放可幂等合并。

### 8.4 Trace

1. 减少 CUSTOM 和 reasoning 噪声后验证当前上限能保留完整公开对话。
2. 若仍触顶，先按事件价值调整保留，再评估配置。
3. 重启后从 PLAN-022 Trace 回放公开对话。
4. 历史 reasoning 由前端过滤，不迁移历史数据。

## 9. 测试与验收

### 9.1 TDD

每段先写失败测试：

1. 仓库 API 不暴露 root、顺序稳定、空配置安全。
2. 所有下拉框的加载、自动选择和历史失效值。
3. 草稿发起成功、重复幂等、既有 Review 冲突、启动失败和版本冲突。
4. Scout schema、Store、重启读取、解析 fallback 和降级兼容。
5. Claim 统计、正文优先和动态角色。
6. reasoning 被阻断/忽略、工具失败优先、delta 合并和断线去重。
7. Provider SSE、工具 delta、畸形 chunk、超时、取消和 fallback。
8. 各阶段对话、抽屉三 tab、工具默认折叠和展开。
9. 浅色主题和移动抽屉 E2E。

### 9.2 IDEA MCP 与构建

1. get_file_problems 检查所有 Java 修改。
2. run_tests 运行聚焦测试。
3. build_project 使用 E:/aicode/chongming，直到 isSuccess=true。
4. 再执行完整 Java 测试；环境阻断单独记录。
5. 检查无数据库循环查询。

### 9.3 前端

1. npm test。
2. npx playwright test。
3. npm run build。
4. 同步 src/main/resources/static/review 的 index.html 和匹配 assets，删除旧 hash。
5. E2E 使用真实形态 AG-UI 增量 fixture，不能用空 SSE 规避。
6. Markdown 测试覆盖脚本、危险链接和超长代码块。

### 9.4 真实浏览器

新建一个 Review/attempt 验收：

1. local 仓库下拉与配置一致，API/页面无 root。
2. 草稿发起成功且重复操作不产生第二个 Review。
3. /live 为浅色。
4. Scout 对话增长，工具折叠，完成后红框有结论。
5. Director 和全部激活角色都有 AI 对话。
6. 角色卡不显示 subjectKey 拼接串。
7. 全部对话按时序展示跨角色消息。
8. 工具可展开入参出参，失败状态正确。
9. 页面和新 Trace 无 reasoning。
10. 重启后消息、工具和 Scout 结论仍可回放。

## 10. 文件清单

### 10.1 新建

| 文件                                                                                                                  | 计划段 | 状态 |
|---------------------------------------------------------------------------------------------------------------------|-----|----|
| docs/AIREVIEW-PLAN-023-评审入口与公开对话体验收口.md                                                                             | #0  | ✅  |
| src/main/java/ai/cc/chongming/review/api/RepositoryOptionController.java                                            | #2  | ⏳  |
| src/main/java/ai/cc/chongming/review/application/RequirementReviewLaunchService.java                                | #3  | ⏳  |
| src/main/java/ai/cc/chongming/review/application/ReviewIntakeRequest.java                                           | #3  | ✅  |
| src/main/java/ai/cc/chongming/review/application/ReviewIntakeService.java                                           | #3  | ✅  |
| src/main/java/ai/cc/chongming/review/domain/model/ContextScoutConclusion.java                                       | #5  | ⏳  |
| src/main/java/ai/cc/chongming/review/domain/repository/ContextScoutConclusionStore.java                             | #5  | ⏳  |
| src/main/java/ai/cc/chongming/review/infrastructure/persistence/repository/MyBatisContextScoutConclusionStore.java  | #5  | ⏳  |
| src/main/java/ai/cc/chongming/review/infrastructure/persistence/mapper/ContextScoutConclusionPersistenceMapper.java | #5  | ⏳  |
| src/main/resources/db/migration/V17__create_context_scout_conclusion_table.sql                                      | #5  | ⏳  |
| src/test/java/ai/cc/chongming/review/api/RepositoryOptionControllerTests.java                                       | #2  | ⏳  |
| src/test/java/ai/cc/chongming/review/application/RequirementReviewLaunchServiceTests.java                           | #3  | ⏳  |
| src/test/java/ai/cc/chongming/review/application/ReviewIntakeServiceTests.java                                      | #3  | ✅  |
| frontend/src/components/ScoutConclusionPanel.vue                                                                    | #5  | ⏳  |
| frontend/src/components/ReviewClaimList.vue                                                                         | #6  | ⏳  |
| frontend/src/components/ReviewConversationDrawer.vue                                                                | #7  | ⏳  |

### 10.2 修改

| 文件                                                                                                 | 计划段            | 状态 |
|----------------------------------------------------------------------------------------------------|----------------|----|
| docs/AIREVIEW-PLAN-020-Live连续Agent对话流收口与验收.md                                                      | #0             | ✅  |
| docs/AIREVIEW-PLAN-018-共享项目上下文与定向检索.md                                                             | #5             | ⏳  |
| docs/AIREVIEW-PLAN-019-ContextScout对话式工具流.md                                                       | #5、#7          | ⏳  |
| docs/AIREVIEW-PLAN-022-运行时Trace持久化与重启回放.md                                                         | #7、#8          | ⏳  |
| src/main/java/ai/cc/chongming/review/config/RepositoryAccessProperties.java                        | #2             | ⏳  |
| src/main/java/ai/cc/chongming/review/api/RequirementCommandController.java                         | #3             | ⏳  |
| src/main/java/ai/cc/chongming/review/domain/event/ReviewEventType.java                             | #5             | ⏳  |
| src/main/java/ai/cc/chongming/review/infrastructure/agentscope/ContextScoutHarnessFactory.java     | #5             | ⏳  |
| src/main/java/ai/cc/chongming/review/infrastructure/agentscope/AgentScopeReviewRuntimeAdapter.java | #5、#8          | ⏳  |
| src/main/java/ai/cc/chongming/review/application/ReviewQueryService.java                           | #5、#6          | ⏳  |
| src/main/java/ai/cc/chongming/review/application/ReviewContextAssembler.java                       | #5             | ⏳  |
| src/main/java/ai/cc/chongming/review/infrastructure/model/ModelProviderClient.java                 | #8             | ⏳  |
| src/main/java/ai/cc/chongming/review/infrastructure/model/OpenAiCompatibleModelClient.java         | #8             | ⏳  |
| src/main/java/ai/cc/chongming/review/infrastructure/model/CommercialModelGateway.java              | #8             | ⏳  |
| src/main/java/ai/cc/chongming/review/infrastructure/agentscope/AgentScopeModelBridge.java          | #8             | ⏳  |
| src/main/java/ai/cc/chongming/review/infrastructure/agentscope/ReviewAgUiEventMapper.java          | #7、#8          | ⏳  |
| src/main/java/ai/cc/chongming/review/application/ReviewRuntimeTraceRegistry.java                   | #8             | ⏳  |
| frontend/src/api/review-api.js 与 review-api.test.js                                                | #2、#3          | ⏳  |
| frontend/src/views/RequirementCreateView.vue                                                       | #2             | ⏳  |
| frontend/src/views/ReviewCreateView.vue                                                            | #2             | ⏳  |
| frontend/src/views/RequirementDetailView.vue                                                       | #2、#3          | ⏳  |
| frontend/src/views/ReviewLiveView.vue                                                              | #4、#5、#6、#7    | ⏳  |
| frontend/src/views/ReviewReportView.vue                                                            | #5、#6          | ⏳  |
| frontend/src/components/LiveAgentConversation.vue                                                  | #7             | ⏳  |
| frontend/src/components/AgUiToolCallMessage.vue                                                    | #7             | ⏳  |
| frontend/src/components/HumanReviewPanel.vue                                                       | #6             | ⏳  |
| frontend/src/services/runtime-conversation-adapter.js 与测试                                          | #7、#8          | ⏳  |
| frontend/src/stores/runtime-trace-store.js                                                         | #7、#8          | ⏳  |
| frontend/src/styles/review.css                                                                     | #4、#5、#6、#7    | ⏳  |
| frontend/tests/review-workbench.e2e.js                                                             | #2、#3、#5、#6、#7 | ⏳  |
| frontend/tests/platform-shell.e2e.js                                                               | #4、#7          | ⏳  |
| 相关 Java 聚焦测试                                                                                       | #3、#5、#7、#8    | ⏳  |
| src/main/resources/static/review/index.html 与匹配 assets                                             | #9             | ⏳  |

## 11. 实施顺序

1. **步骤 0：契约冻结与流式 Spike**  
   固化 PLAN-023，更新 PLAN-020 冲突说明；验证 AgentScope delta 扩展点；先补失败测试。

2. **步骤 1：仓库选项（#2）**  
   安全 API、客户端和所有下拉框。

3. **步骤 2：草稿发起（#3）**  
   服务端幂等编排、表单和失败恢复。

4. **步骤 3：Scout 正式结论（#5）**  
   schema、Store、迁移、完成事件、查询投影和红框面板。

5. **步骤 4：公开对话协议（#7、#8）**  
   阻断 reasoning，收敛文本/工具事件，打通真实 delta 与回放 reducer。

6. **步骤 5：浅色 UI 与可读结论（#4、#6、#7）**  
   主题、阶段对话、Claim、结论链和右抽屉。

7. **步骤 6：验证与生产构建（#9）**  
   Java、前端单测、E2E、IDEA 构建、浏览器和重启回放。

8. **步骤 7：收尾**  
   更新段状态、文件清单、偏差、关联计划、技术契约和 learnings。

## 12. 风险与应对

| 风险                       | 应对                                       |
|--------------------------|------------------------------------------|
| Provider SSE 方言不同        | 能力开关加非流式 fallback                        |
| AgentScope 无公开 delta 扩展点 | 先 Spike，再做最小 Bridge 扩展，禁止伪流式             |
| 工具参数是半截 JSON             | 完整后才交给 ToolExecutor                      |
| Trace 事件增多               | 删除无价值 CUSTOM/reasoning 后再评估上限            |
| Scout schema 解析失败        | summary 和 rawPublicResult 强制 fallback    |
| 草稿部分成功                   | 幂等键、阶段记录和可恢复启动                           |
| Markdown XSS             | 禁用 raw HTML、危险 URL 测试                    |
| 历史仓库失效                   | 只读显示并强制重新选择                              |
| 工具含敏感字段                  | 展示层掩码且默认折叠                               |
| 动态角色丢失                   | 从 activatedRoles 和 runtime identity 动态生成 |
| 旧 PLAN-020 冲突            | PLAN-023 覆盖用户可见行为并同步旧计划                  |

## 13. 非目标

1. 不开放服务器物理路径。
2. 不持久化草稿 Markdown；发起时重新上传。
3. 不重构 Claim、Evidence、Debate、Judge、Gate 的领域算法。
4. 不在 /live 直接提交人工 Gate。
5. 不展示、推断或持久化隐藏 reasoning。
6. 不用前端动画伪造流式。
7. 不删除历史 Trace 或计划。
8. 不实现运行中的人类双向聊天。
9. 不擅自启动、停止或重启用户服务。

## 14. 完成定义

- 六项问题逐项通过自动化和真实浏览器验收。
- 所有代码带正确 AIREVIEW-PLAN-023 段号。
- Java 作者为 @author zyj。
- IDEA MCP 构建成功且修改文件无 ERROR。
- Java 和前端测试通过，生产 bundle 与源码一致。
- /live 无 reasoning，AI 正文为主，工具默认折叠且可查看入参出参。
- Scout 结论与公开对话重启后可回放。
- 计划状态、文件清单、变更记录和偏差已同步。

## 15. 变更记录

| 日期         | 变更                                                                                               |
|------------|--------------------------------------------------------------------------------------------------|
| 2026-08-10 | 创建 PLAN-023，统一规划仓库下拉、草稿发起、浅色 /live、Scout 持久化结论、Claim 与结论链可读化、全 Agent AI 对话、右侧全局对话抽屉、工具默认折叠与真实流式。 |
| 2026-08-10 | 开始实施；通过 IDEA MCP 确认现有迁移最高为 V16，将 Scout 结论迁移由计划中的 V15 调整为 V17，并并行推进入口、Scout、流式与 /live 实现。 |
| 2026-08-12 | 修复草稿提交同一 Markdown 时误复用其他需求 Review 的 409：受理幂等键增加 Requirement 归属范围；同需求重放仍复用、跨需求创建独立 Review root，且未带范围的旧 `/api/reviews` 去重键保持兼容。对旧逻辑已写入且指向缺失、非 `PENDING` 或已归属其他需求 Review 的错误完成态启动预约增加精确条件失效与自动重建，同一 `Idempotency-Key` 重试可恢复，无需人工清库；补充内存、MyBatis、持久化键与启动编排回归。 |
| 2026-08-18 | 需求文档双通道受理：`/api/reviews` 与 `/{requirementId}/reviews` 的 `requirementFile` 改为可选，新增 `requirementText` 参数，二者严格二选一（同传 `INVALID_INTAKE_DOCUMENT`、均缺 `MISSING_REQUIREMENT_DOCUMENT`、空文件保持 `EMPTY_DOCUMENT` 422）；应用层引入 `IntakeDocument` 统一承载文件名与字节，校验器只见单一来源。新建需求、独立创建评审与草稿发起三处页面共用 `RequirementDocInput` 双模式组件（上传 .md / 手动输入）；OpenAPI 受理契约与前端单测、e2e 同步更新。 |
