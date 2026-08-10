# AIREVIEW-PLAN-024：评审确定性覆盖与编排收敛

> **状态**: ⏳ 待实施
> **创建时间**: 2026-08-10
> **目标**: 在不新增 Agent 的前提下，修正核心角色单向质疑、仓库取证越权、辩论路由失真、冲突开题不完整、Gate 缺少正向覆盖依据和完整流程未闭环等问题，使一次评审能够产出“已确认、部分满足、存在缺口、无法确认、不适用”的完整结论，并由现有 Director 可靠编排到最终完成。

## 背景

2026-08-10 完整运行已走通“需求投递 → Context Scout → 四个核心角色初审 → 冲突检测 → 辩论 → Judge → Gate → WAITING_HUMAN”，证明协议守卫、阶段状态机和模型降级骨架可工作；但运行质量未达到可交付标准。原始分析见 `logs/评审运行分析报告-20260810.md`。

本计划合并报告问题与后续复盘结论，并修正两个分析前提：

1. `readLines` 不是所有角色全程不可用。实际现象是 PRODUCT、FRONTEND 持续读取了未获授权的路径，BACKEND 大部分读取成功、末尾才耗尽预算。主因是 Context Scout 向角色暴露了超出 RolePack 路径范围的候选文件，角色随后拿这些路径调用工具；不能仅归因于数值参数 schema。
2. 本地 `application-local.yml` 中开启完整模型会话日志、保留本地调试密钥，是当前明确接受的开发调试约定，不列为本计划缺陷。通用 `application.yml` 继续默认关闭完整会话日志；本地配置继续保持 Git 忽略，密钥和 Authorization 不进入提交、报告或公共事件。

### 统一问题清单

| 编号 | 优先级 | 问题 | 目标结果 |
|---|---|---|---|
| Q1 | P0 | 子 Agent 只提交质疑，正向结论只存在于自由文本，Gate 无法消费 | 每个角色对全部检查点提交结构化 Assessment，包含确定信息与不确定信息 |
| Q2 | P0 | Context Scout 暴露角色无权读取的路径，造成 `readLines` 连续拒绝 | 角色只看到服务端授予的 `fileRef`，无授权文件时不注册读取工具 |
| Q3 | P0 | Dispatcher 广播通用辩论提示，被挑战角色未被定向唤醒，第三方可以错误反驳 | Director 产生目标明确的服务端派发命令，Dispatcher 精确投递；领域层校验实际应答人 |
| Q4 | P1 | 任意 OPPOSE 都被视为冲突，生产流程未使用确定性 ConflictDetector | 单个 GAP 是风险而非冲突；只有同一主题存在相互矛盾的结论才进入辩论 |
| Q5 | P1 | 首次开题后立即离开冲突检测阶段，后续主题无法创建 | 冲突候选批量、幂等、原子登记，全部登记后只迁移一次阶段 |
| Q6 | P1 | 第二轮动作与目标回合错位，出现 `TARGET_TURN_REQUIRED`、重复状态迁移和空转 | 每次派发携带允许动作及目标 ID；已收敛时可提前结束，不强制空跑第二轮 |
| Q7 | P1 | Gate 的 `AI_PASS` 仅表示“没有阻断 Claim”，不代表检查点被正向验证 | Gate 同时校验覆盖率、阻断缺口、高风险 UNKNOWN 和 Judge 结论 |
| Q8 | P1 | 报告只展示 Claim，缺少“需求合理、研发无问题”等确定结论，数量口径不一致 | 报告按 Assessment 状态汇总并从持久化事实派生计数 |
| Q9 | P1 | `role-reviewer` 30 秒 × 2 次重试导致串联等待和 8 次超时降级 | 缩短失败链路、增加运行级熔断与阶段指标，避免同一故障反复等待 |
| Q10 | P1 | 完整运行止于 WAITING_HUMAN，未验证人工决策、通知和 COMPLETED | 增加确定性全链路测试并在真实验收中完成最终闭环 |
| Q11 | P2 | 原报告将 `readLines` 诊断为全程不可用，且 OPPOSE/剩余主题计数出现偏差 | 诊断按角色、错误码、成功/失败次数统计，禁止手写派生计数 |

### 范围与非目标

**本计划范围**：核心角色输出契约、角色级仓库授权、Director/Dispatcher 编排、冲突与辩论领域规则、Gate/报告/工作台、模型可靠性、持久化和测试。

**不新增 Agent**：继续使用现有 Director、Context Scout、PRODUCT、PROJECT、FRONTEND、BACKEND、按需角色和 Judge。问题在角色契约、服务端授权与编排层解决，不增加“正向评价 Agent”或新的总控 Agent。

**保留混合编排**：Director 是高层主编排者，负责选择下一动作和目标；Java 服务端负责权限、阶段、幂等和状态迁移；Dispatcher 只负责投递已校验的命令。Director 不绕过领域规则直接修改状态。

**接受的本地调试边界**：

- `application-local.yml` 可继续启用 `log-conversation: true`，用于本地完整会话排障；不要求本计划关闭或删除。
- 本地模型密钥可继续保存在被 Git 忽略的本地配置中，方便调试；本计划不要求迁移或轮换。
- 必须保持：生产/通用配置默认不记录完整会话、本地配置不纳入版本控制、日志和报告不输出密钥或 Authorization。

## 方案 0：冻结评审语义与验收基线

### 目标

先把“检查结论、风险主张、冲突、辩论、Gate”五类概念分开，避免继续用 Claim 同时表达正向结论和风险。

### 新增文件

- `src/main/java/ai/cc/chongming/review/domain/model/ReviewAssessment.java`
- `src/main/java/ai/cc/chongming/review/domain/repository/ReviewAssessmentStore.java`
- `src/main/java/ai/cc/chongming/review/infrastructure/assessment/InMemoryReviewAssessmentStore.java`
- `src/test/java/ai/cc/chongming/review/assessment/ReviewAssessmentContractTests.java`

### 修改文件

- `src/main/java/ai/cc/chongming/review/domain/model/ReviewTypes.java`
- `src/main/java/ai/cc/chongming/review/domain/role/RolePack.java`
- `src/main/java/ai/cc/chongming/review/domain/role/RolePackRegistry.java`
- `src/main/resources/roles/product.yml`
- `src/main/resources/roles/project.yml`
- `src/main/resources/roles/frontend.yml`
- `src/main/resources/roles/backend.yml`
- `src/test/java/ai/cc/chongming/review/role/RolePackContractTests.java`

### 关键细节

1. 新增 Assessment 状态：
   - `CONFIRMED`：有充分证据确认符合或无问题；
   - `PARTIAL`：部分满足，未满足部分必须说明；
   - `GAP`：确认存在缺口；
   - `UNKNOWN`：当前授权证据不足，不能把“未看到”写成“未实现”；
   - `NOT_APPLICABLE`：检查点对本次需求不适用。
2. 每条 Assessment 至少包含 `roleType`、`checkpointKey`、`status`、`summary`、`reasonSummary`、`evidenceIds`、`reviewId`、`attemptNo` 和服务端幂等键。
3. RolePack 的 checklist 从无标识文本升级为稳定 `checkpointKey + instruction + required` 契约；四个核心角色必须覆盖全部 required 检查点。
4. Claim 只表达可质疑、可裁决的风险命题。`GAP` 或确有争议的 `PARTIAL` 可以生成 Claim；`CONFIRMED`、`UNKNOWN`、`NOT_APPLICABLE` 不再伪装成带严重度的 SUPPORT Claim。
5. 保留现有 Claim position 以兼容辩论存量模型，但新流程不再要求每条正向 Assessment 映射为 SUPPORT Claim。
6. 验收基线：四个核心角色 required checkpoint 覆盖率 100%；报告中必须可见每个角色的正向、负向和未知结论。

## 方案 1：在现有子 Agent 层完成结构化结论闭环

### 目标

不增加新 Agent，直接修改现有角色提示词、工具和完成守卫，让角色既回答“哪里有问题”，也回答“哪些检查已确认无问题”。

### 新增文件

- `src/main/java/ai/cc/chongming/review/application/AssessmentService.java`
- `src/test/java/ai/cc/chongming/review/application/AssessmentServiceTests.java`

### 修改文件

- `src/main/java/ai/cc/chongming/review/infrastructure/agentscope/RoleSubagentFactory.java`
- `src/main/java/ai/cc/chongming/review/infrastructure/agentscope/ReviewRoleToolFactory.java`
- `src/main/java/ai/cc/chongming/review/application/InitialReviewProgressService.java`
- `src/main/java/ai/cc/chongming/review/infrastructure/agentscope/AgentScopeReviewRuntimeAdapter.java`
- `src/test/java/ai/cc/chongming/review/agentscope/RoleSubagentIsolationTests.java`
- `src/test/java/ai/cc/chongming/review/agentscope/AgentScopeReviewRuntimeAdapterTests.java`
- `src/test/java/ai/cc/chongming/review/application/InitialReviewProgressServiceTests.java`

### 关键细节

1. 新增 `submit_assessment` 工具，参数只接受稳定检查点和结构化状态；角色身份、review、attempt、版本和幂等键由服务端注入。
2. `submit_claim` 文案改为“仅在确认风险缺口或形成可争议命题时调用”，不再提示“每个 finding 都提交 Claim”。
3. `complete_initial_review` 在服务端检查 required checkpoint 是否全部覆盖；缺失时返回明确的 `ASSESSMENT_COVERAGE_INCOMPLETE` 及缺失 key，不允许用一段 publicSummary 绕过。
4. publicSummary 改为由已持久化 Assessment 和 Claim 派生，模型可提供补充摘要但不作为唯一事实来源。
5. 提示词明确要求：证据充分时主动提交 `CONFIRMED`；证据不在授权范围时提交 `UNKNOWN`；禁止把“没有读到文件”推断为“功能不存在”。
6. Finalizer 只开放尚缺的 Assessment 与完成工具，避免重复提交已持久化项。

## 方案 2：以角色授权 fileRef 替代模型自由拼接路径

### 目标

让 Agent 从源头拿不到无权访问的路径；在工具注册前完成授权过滤，而不是等调用后连续拒绝。

### 新增文件

- `src/main/java/ai/cc/chongming/review/infrastructure/agentscope/tool/RepositoryFileGrant.java`
- `src/main/java/ai/cc/chongming/review/infrastructure/agentscope/tool/RepositoryFileGrantSet.java`
- `src/test/java/ai/cc/chongming/review/agentscope/RepositoryFileGrantTests.java`

### 修改文件

- `src/main/java/ai/cc/chongming/review/application/ReviewContextAssembler.java`
- `src/main/java/ai/cc/chongming/review/infrastructure/agentscope/ReviewRepositoryToolFactory.java`
- `src/main/java/ai/cc/chongming/review/infrastructure/agentscope/tool/RepositoryToolContext.java`
- `src/main/java/ai/cc/chongming/review/infrastructure/agentscope/tool/ReadOnlyRepositoryTools.java`
- `src/main/java/ai/cc/chongming/review/infrastructure/repository/RepositorySearchIndex.java`
- `src/test/java/ai/cc/chongming/review/application/ReviewContextAssemblerTests.java`
- `src/test/java/ai/cc/chongming/review/agentscope/ReviewRepositoryToolFactoryTests.java`

### 关键细节

1. 计算 `effectiveReadableFiles = snapshotFiles ∩ rolePathPolicy ∩ reviewRelevantFiles`；Context Scout 给每个角色的摘要和候选文件都从该集合生成。
2. 对 Agent 只暴露不可猜测的 `fileRef`，其服务端绑定 `reviewId + attemptNo + roleType + snapshotCommit + normalizedPath`；`readLines`、`getFileMetadata` 只接受 `fileRef`。
3. `listFiles`、`searchText`、`findSymbol` 只能返回当前角色的 fileRef，绝不返回越权相对路径。
4. 若有效授权集合为空，动态移除 `readLines`/`getFileMetadata`，提示角色将相关检查点标记为 `UNKNOWN`，不允许循环试探路径。
5. 校验顺序调整为“参数形状 → fileRef 授权/快照归属 → 读取预算扣减 → 实际读取”；拒绝、越权、文件缺失不消耗有效读取预算。
6. 统一错误码：`FILE_REF_NOT_GRANTED`、`FILE_NOT_IN_SNAPSHOT`、`INVALID_LINE_RANGE`、`READ_BUDGET_EXHAUSTED`；前三者不可重试。
7. 对相同工具、相同参数、相同错误码增加单角色运行级短路，第二次不再访问底层仓库，并在提示中要求改为 `UNKNOWN`。
8. 保留服务端路径归一化作为纵深防护，但它不再是模型侧主要接口。

## 方案 3：Director 决策、服务端校验、Dispatcher 精确投递

### 目标

保留现有 Director 作为主编排 Agent，但把它的高层意图固化为可校验、可重放的派发命令，禁止 Dispatcher 向所有角色广播模糊许可。

### 新增文件

- `src/main/java/ai/cc/chongming/review/domain/model/ReviewDispatchCommand.java`
- `src/main/java/ai/cc/chongming/review/domain/repository/ReviewDispatchStore.java`
- `src/main/java/ai/cc/chongming/review/application/ReviewDispatchService.java`
- `src/main/java/ai/cc/chongming/review/infrastructure/dispatch/InMemoryReviewDispatchStore.java`
- `src/test/java/ai/cc/chongming/review/application/ReviewDispatchServiceTests.java`
- `src/test/java/ai/cc/chongming/review/agentscope/ReviewWorkflowDispatcherTests.java`

### 修改文件

- `src/main/java/ai/cc/chongming/review/infrastructure/agentscope/ReviewDirectorHarnessFactory.java`
- `src/main/java/ai/cc/chongming/review/infrastructure/agentscope/ReviewDebateToolFactory.java`
- `src/main/java/ai/cc/chongming/review/infrastructure/agentscope/ReviewWorkflowDispatcher.java`
- `src/main/java/ai/cc/chongming/review/infrastructure/agentscope/AgentScopeReviewRuntimeAdapter.java`
- `src/main/java/ai/cc/chongming/review/domain/event/ReviewEventType.java`
- `src/main/java/ai/cc/chongming/review/application/ReviewEventDrafts.java`

### 关键细节

1. Director 仍决定“开哪些主题、让谁挑战谁、是否继续第二轮、何时收敛”；不新增第二个主 Agent。
2. Director 工具产生 `ReviewDispatchCommand`，字段至少包含：`commandId`、`reviewId`、`attemptNo`、`stage`、`round`、`recipientRole`、`allowedAction`、`topicId`、`targetClaimId`、`targetTurnId`、`expiresAt`。
3. 服务端在持久化前校验角色已激活、主题和目标属于当前 review、动作适用于当前主题状态、round 与当前阶段一致；模型不能自行扩大权限。
4. Dispatcher 只消费已通过校验的命令，并把同一 envelope 注入目标角色上下文；移除“所有角色先列主题再自行选择 challenge/rebuttal”的广播提示。
5. 挑战提交成功后，服务端生成只发给 `challenge.targetRole` 的 `REBUTTAL` 派发；不依赖其他仍在运行的角色碰巧看见主题。
6. 每个角色只能看到此次派发允许的写工具和目标 ID；读工具可以列出公共上下文，但不能据此执行 envelope 之外的动作。
7. 命令幂等、过期和消费状态写入公开运行事件，便于重启恢复和分析“未派发、已派发、已消费、已拒绝”。

## 方案 4：重建冲突检测、批量开题与辩论收敛规则

### 目标

让风险、冲突、辩论主题和回合动作一一对应，修复任意 OPPOSE 即冲突、只能开一个主题、第三方反驳和第二轮空转。

### 新增文件

- `src/main/java/ai/cc/chongming/review/application/ConflictDetectionService.java`
- `src/test/java/ai/cc/chongming/review/application/ConflictDetectionServiceTests.java`

### 修改文件

- `src/main/java/ai/cc/chongming/review/domain/debate/ConflictDetector.java`
- `src/main/java/ai/cc/chongming/review/application/DebateService.java`
- `src/main/java/ai/cc/chongming/review/domain/protocol/DebateStateMachine.java`
- `src/main/java/ai/cc/chongming/review/infrastructure/agentscope/tool/DebateToolCommands.java`
- `src/main/java/ai/cc/chongming/review/infrastructure/agentscope/ReviewDebateToolFactory.java`
- `src/test/java/ai/cc/chongming/review/debate/ConflictDetectorTests.java`
- `src/test/java/ai/cc/chongming/review/debate/DebateGoldenPathIntegrationTests.java`
- `src/test/java/ai/cc/chongming/review/debate/DebateToolsContractTests.java`
- `src/test/java/ai/cc/chongming/review/debate/DebateStageTransitionEventTests.java`
- `src/test/java/ai/cc/chongming/review/agentscope/ReviewDebateToolFactoryTests.java`

### 关键细节

1. 生产流程必须调用 `ConflictDetector`，禁止 `DebateService.hasConflictingClaimPositions()` 以“存在任意 OPPOSE”代替冲突检测。
2. 冲突按稳定 `subjectKey` 聚合，并由相互矛盾的 Assessment/Claim 触发；单个 `GAP`、单个 `UNKNOWN` 进入 Gate 风险输入，但不自动形成辩题。
3. 提供批量 `register_topics`：Director 一次提交全部候选选择；服务端先完整校验、去重和幂等，再原子保存全部主题，最后从 `CONFLICT_DETECTION` 迁移到 `DEBATE_ROUND_1`。
4. `openTopic` 不再承担“保存第一条主题并立即迁移阶段”两项职责；主题登记与回合启动分离。
5. 修正反驳身份不变量：
   - `rebuttal.actorRole == challenge.targetRole`；
   - `rebuttal.targetRole == challenge.actorRole`；
   - `rebuttal.targetTurnId == challenge.turnId`；
   - topic、review、attempt 必须一致。
6. 禁止 PROJECT 代替 BACKEND 回应指向 BACKEND 的挑战；违规返回 `DISPATCH_ACTOR_MISMATCH`，且不改变主题状态。
7. 第二轮不再重复第一轮固定状态图。进入条件是至少存在“需要补证、立场未澄清或 Judge 前必须回答”的开放动作；否则 Director 可直接收敛到 JUDGING。
8. 第二轮动作 envelope 指向明确的 Claim 或 Turn。针对第一轮 Turn 的回应保留其真实 `targetTurnId`，不以当前 round 覆盖目标 Turn 的原始 round。
9. 支持提前收敛：所有主题已 `RESOLVED`、`WITHDRAWN` 或有明确 `ESCALATED` 理由时，可跳过空第二轮；禁止为了状态机形式完整而产生 0 动作回合。
10. 报告中的“冲突候选数、已登记主题数、剩余风险数、未闭环动作数”全部从 store 查询派生，不接受模型在文本中手写计数。

## 方案 5：让 Gate 与报告消费完整 Assessment 事实

### 目标

让“AI 通过”意味着关键检查已被正向覆盖，而不是仅仅没有 P0/P1 OPPOSE；让用户在最终报告中同时看到确定信息、风险和未知项。

### 新增文件

- `src/main/resources/db/migration/V19__create_review_assessment_and_dispatch_tables.sql`
- `src/main/java/ai/cc/chongming/review/infrastructure/persistence/repository/MyBatisReviewAssessmentStore.java`
- `src/main/java/ai/cc/chongming/review/infrastructure/persistence/mapper/ReviewAssessmentPersistenceMapper.java`
- `src/main/java/ai/cc/chongming/review/infrastructure/persistence/repository/MyBatisReviewDispatchStore.java`
- `src/main/java/ai/cc/chongming/review/infrastructure/persistence/mapper/ReviewDispatchPersistenceMapper.java`
- `src/test/java/ai/cc/chongming/review/infrastructure/persistence/repository/MyBatisReviewAssessmentStoreTests.java`
- `src/test/java/ai/cc/chongming/review/infrastructure/persistence/repository/MyBatisReviewDispatchStoreTests.java`

### 修改文件

- `src/main/java/ai/cc/chongming/review/domain/gate/GatePolicy.java`
- `src/main/java/ai/cc/chongming/review/application/JudgeService.java`
- `src/main/java/ai/cc/chongming/review/application/ReviewReportService.java`
- `src/main/java/ai/cc/chongming/review/application/ReviewQueryService.java`
- `src/main/java/ai/cc/chongming/review/infrastructure/persistence/mapper/ReviewPersistenceMapper.java`
- `src/main/java/ai/cc/chongming/review/infrastructure/persistence/repository/MyBatisReviewRepository.java`
- `src/test/java/ai/cc/chongming/review/debate/GatePolicyTests.java`
- `src/test/java/ai/cc/chongming/review/report/ReviewReportServiceTests.java`
- `src/test/java/ai/cc/chongming/review/application/ReviewQueryServiceTests.java`
- `src/test/java/ai/cc/chongming/review/infrastructure/persistence/ReviewPersistenceMigrationIntegrationTests.java`
- `frontend/src/api/review-api.js`
- `frontend/src/stores/review-store.js`
- `frontend/src/views/ReviewWorkbenchView.vue`
- `frontend/src/views/ReviewReportView.vue`
- `frontend/src/components/ReviewRoundtable.vue`

### 关键细节

1. Gate 输入扩展为 `assessments + claims + judgeDecisions + requiredCheckpointSet`。
2. Gate 确定性优先级：
   - required checkpoint 未覆盖 → `HUMAN_REQUIRED`；
   - P0/P1 `GAP` 缺少有效处置或证据 → `BLOCK`/`HUMAN_REQUIRED`；
   - 高风险 `UNKNOWN` → `HUMAN_REQUIRED`；
   - Judge 明确 RETURN/BLOCK/CONDITIONAL → 延续现有保守优先级；
   - 只有 required checkpoint 全覆盖且无阻断项，才允许 `AI_PASS`。
3. Gate reason 输出覆盖数据，例如 `required=24, confirmed=15, partial=4, gap=3, unknown=2, notApplicable=0`，计数由服务端计算。
4. 报告新增“确定结论”“部分满足”“风险缺口”“证据不足”“不适用”五个区块，并按角色、检查点稳定排序。
5. “研发无问题”必须细化为具体检查点的 `CONFIRMED`，禁止生成没有范围和证据的笼统表扬。
6. 对前端角色无授权仓库的场景显示 `UNKNOWN：当前评审快照未授予前端文件`，而不是 SUPPORT 或 OPPOSE。
7. 工作台展示角色覆盖进度和五态数量；用户可区分“未执行”“执行但未知”“确认无问题”“确认有缺口”。
8. 前端构建后同步 `src/main/resources/static/review/` 生产 bundle，并删除旧 hash 资源。

## 方案 6：缩短模型失败链路并补齐可观测性

### 目标

减少同一模型故障的重复等待，让一次完整评审能明确解释时间消耗和降级位置。

### 修改文件

- `src/main/resources/application.yml`
- `src/main/resources/application-local.yml`
- `src/main/java/ai/cc/chongming/review/infrastructure/model/CommercialModelGateway.java`
- `src/main/java/ai/cc/chongming/review/infrastructure/model/ModelProfileRegistry.java`
- `src/main/java/ai/cc/chongming/review/application/ReviewRuntimeTraceRegistry.java`
- `src/test/java/ai/cc/chongming/review/model/ModelGatewayContractTests.java`
- `src/test/java/ai/cc/chongming/review/infrastructure/model/OpenAiCompatibleModelClientTests.java`
- `src/test/java/ai/cc/chongming/review/application/ReviewRuntimeTraceRegistryTests.java`

### 关键细节

1. `role-reviewer` 默认从“30 秒、2 次重试”调整为“30 秒、最多 1 次重试”；具体值通过定向压测确认，避免单角色连续等待约 90 秒。
2. 增加以 provider/model/profile 为键的运行级熔断：连续达到阈值后，本 attempt 后续同 profile 直接走配置的 fallback，并记录熔断原因。
3. 将超时、非 JSON、工具参数拒绝、仓库授权拒绝、读取预算耗尽分开计数，禁止统一写成“模型降级”。
4. 记录阶段耗时、角色首 token、工具成功/失败次数、Assessment 覆盖时间、派发等待时间、每轮有效动作数。
5. 相同不可重试工具错误由运行级短路处理，避免模型反复消耗轮次和 token。
6. 下调角色输出上限时以“完整 Assessment 覆盖不被截断”为约束，不能只为提速牺牲结构化结论。
7. 保留本地 `log-conversation: true` 和本地调试密钥；本段只调整可靠性参数，不改变已接受的调试便利边界。

## 方案 7：全链路回放、人工闭环与发布验收

### 目标

先用确定性模型夹具证明所有协议分支，再经授权运行一次真实模型流程，最终完成到 `COMPLETED`，不把 WAITING_HUMAN 当成完整流程完成。

### 新增文件

- `src/test/java/ai/cc/chongming/review/lifecycle/ReviewQualityConvergenceIntegrationTests.java`
- `frontend/tests/review-assessment-coverage.e2e.js`

### 修改文件

- `src/test/java/ai/cc/chongming/review/lifecycle/ReviewLifecycleIntegrationTests.java`
- `src/test/java/ai/cc/chongming/review/agentscope/ReviewOrchestrationServiceTests.java`
- `frontend/src/api/review-api.test.js`
- `frontend/src/stores/review-store.test.js`
- `frontend/tests/review-workbench.e2e.js`
- `docs/AIREVIEW-PLAN-001-总体实施路线图.md`
- `README.md`
- `README.zh-CN.md`
- `.learnings/LEARNINGS.md`

### 关键细节

1. 确定性夹具至少覆盖三条路径：
   - 全部关键检查点 CONFIRMED，无冲突，直接 Judge/Gate；
   - 同主题确认与缺口相互冲突，批量开多个主题，定向挑战/反驳后收敛；
   - 高风险 UNKNOWN，Gate 转人工，人工决定后通知并完成。
2. 错误路径覆盖：越权 fileRef、错误反驳 actor、过期派发、重复派发、第二轮无动作、模型超时熔断、重启后幂等重放。
3. E2E 必须断言 `WAITING_HUMAN → 人工决定 → NOTIFYING → COMPLETED`，并校验最终报告版本和通知结果。
4. 真实模型验收需要用户再次明确授权启动项目/调用模型；实施阶段若无授权，只完成静态、单测、集成测试和前端 E2E，不自行启动服务。
5. 验收后回写本计划各方案状态、文件清单、测试证据、偏差和剩余工作；同步总路线图、README 与 `.learnings/LEARNINGS.md`。

## 核心接口契约

### Assessment 写入

```text
submit_assessment(
  checkpointKey,
  status: CONFIRMED | PARTIAL | GAP | UNKNOWN | NOT_APPLICABLE,
  summary,
  reasonSummary,
  evidenceIds[]
)
```

服务端注入 review、attempt、role、version 和 idempotency。`UNKNOWN` 必须说明缺少何种授权证据；`CONFIRMED` 必须说明确认范围。

### 定向派发

```text
ReviewDispatchCommand(
  commandId, reviewId, attemptNo, stage, round,
  recipientRole, allowedAction,
  topicId?, targetClaimId?, targetTurnId?, expiresAt
)
```

角色工具调用必须引用有效 `commandId`；工具工厂仅注册 envelope 中允许的写动作。

### 仓库读取

```text
readLines(fileRef, startLine, lineCount?)
```

`fileRef` 只在当前 review、attempt、role、snapshot 内有效。Agent 不再提交路径，服务端也不向该角色返回未授权路径。

## 不变量

1. 每个核心角色完成初审前，全部 required checkpoint 必须存在恰好一个当前有效 Assessment。
2. 未被授权的文件路径不会出现在角色上下文、搜索结果或工具参数中。
3. 单一风险不等于冲突；冲突候选必须由确定性规则从持久化事实产生。
4. 所有辩论写动作必须同时满足角色身份、派发命令、阶段、回合、主题和目标归属。
5. 只有被挑战者本人可以反驳该挑战。
6. 所有主题登记完成后才能离开冲突检测阶段；阶段迁移只发生一次。
7. 没有有效第二轮动作时不得空转第二轮。
8. `AI_PASS` 必须以 required checkpoint 完整覆盖为前提。
9. 报告计数全部从持久化 Assessment、Claim、Topic、Turn 和 Gate 数据派生。
10. 本地完整会话日志和本地明文调试密钥可以保留，但不得越过本地 Git 忽略与敏感信息不外泄边界。

## 文件清单

### 新增文件

| 文件 | 方案 | 状态 |
|---|---:|---|
| `src/main/java/ai/cc/chongming/review/domain/model/ReviewAssessment.java` | 0 | ✅ 已完成 |
| `src/main/java/ai/cc/chongming/review/domain/repository/ReviewAssessmentStore.java` | 0 | ✅ 已完成 |
| `src/main/java/ai/cc/chongming/review/infrastructure/assessment/InMemoryReviewAssessmentStore.java` | 0 | ✅ 已完成 |
| `src/main/java/ai/cc/chongming/review/application/AssessmentService.java` | 1 | ⏳ 待实施 |
| `src/main/java/ai/cc/chongming/review/infrastructure/agentscope/tool/RepositoryFileGrant.java` | 2 | ⏳ 待实施 |
| `src/main/java/ai/cc/chongming/review/infrastructure/agentscope/tool/RepositoryFileGrantSet.java` | 2 | ⏳ 待实施 |
| `src/main/java/ai/cc/chongming/review/domain/model/ReviewDispatchCommand.java` | 3 | ⏳ 待实施 |
| `src/main/java/ai/cc/chongming/review/domain/repository/ReviewDispatchStore.java` | 3 | ⏳ 待实施 |
| `src/main/java/ai/cc/chongming/review/application/ReviewDispatchService.java` | 3 | ⏳ 待实施 |
| `src/main/java/ai/cc/chongming/review/infrastructure/dispatch/InMemoryReviewDispatchStore.java` | 3 | ⏳ 待实施 |
| `src/main/java/ai/cc/chongming/review/application/ConflictDetectionService.java` | 4 | ⏳ 待实施 |
| `src/main/resources/db/migration/V19__create_review_assessment_and_dispatch_tables.sql` | 5 | ⏳ 待实施 |
| `src/main/java/ai/cc/chongming/review/infrastructure/persistence/repository/MyBatisReviewAssessmentStore.java` | 5 | ⏳ 待实施 |
| `src/main/java/ai/cc/chongming/review/infrastructure/persistence/mapper/ReviewAssessmentPersistenceMapper.java` | 5 | ⏳ 待实施 |
| `src/main/java/ai/cc/chongming/review/infrastructure/persistence/repository/MyBatisReviewDispatchStore.java` | 5 | ⏳ 待实施 |
| `src/main/java/ai/cc/chongming/review/infrastructure/persistence/mapper/ReviewDispatchPersistenceMapper.java` | 5 | ⏳ 待实施 |
| `src/test/java/ai/cc/chongming/review/assessment/ReviewAssessmentContractTests.java` | 0 | ✅ 已完成 |
| `src/test/java/ai/cc/chongming/review/application/AssessmentServiceTests.java` | 1 | ⏳ 待实施 |
| `src/test/java/ai/cc/chongming/review/agentscope/RepositoryFileGrantTests.java` | 2 | ⏳ 待实施 |
| `src/test/java/ai/cc/chongming/review/application/ReviewDispatchServiceTests.java` | 3 | ⏳ 待实施 |
| `src/test/java/ai/cc/chongming/review/agentscope/ReviewWorkflowDispatcherTests.java` | 3 | ⏳ 待实施 |
| `src/test/java/ai/cc/chongming/review/application/ConflictDetectionServiceTests.java` | 4 | ⏳ 待实施 |
| `src/test/java/ai/cc/chongming/review/infrastructure/persistence/repository/MyBatisReviewAssessmentStoreTests.java` | 5 | ⏳ 待实施 |
| `src/test/java/ai/cc/chongming/review/infrastructure/persistence/repository/MyBatisReviewDispatchStoreTests.java` | 5 | ⏳ 待实施 |
| `src/test/java/ai/cc/chongming/review/lifecycle/ReviewQualityConvergenceIntegrationTests.java` | 7 | ⏳ 待实施 |
| `frontend/tests/review-assessment-coverage.e2e.js` | 7 | ⏳ 待实施 |

### 修改文件

| 文件或文件组 | 方案 | 状态 |
|---|---:|---|
| `src/main/java/ai/cc/chongming/review/domain/model/ReviewTypes.java` | 0 | ✅ 已完成 |
| `src/main/java/ai/cc/chongming/review/domain/role/RolePack.java`、`RolePackRegistry.java` | 0 | ✅ 已完成 |
| `src/main/resources/roles/product.yml`、`project.yml`、`frontend.yml`、`backend.yml` | 0-1 | ✅ 方案0 已完成（方案1 待续） |
| `RoleSubagentFactory.java`、`ReviewRoleToolFactory.java`、`AgentScopeReviewRuntimeAdapter.java` | 1 | ⏳ 待实施 |
| `InitialReviewProgressService.java` | 1 | ⏳ 待实施 |
| `ReviewContextAssembler.java`、`ReviewRepositoryToolFactory.java` | 2 | ⏳ 待实施 |
| `RepositoryToolContext.java`、`ReadOnlyRepositoryTools.java`、`RepositorySearchIndex.java` | 2 | ⏳ 待实施 |
| `ReviewDirectorHarnessFactory.java`、`ReviewWorkflowDispatcher.java` | 3 | ⏳ 待实施 |
| `ReviewDebateToolFactory.java`、`DebateToolCommands.java` | 3-4 | ⏳ 待实施 |
| `ConflictDetector.java`、`DebateService.java`、`DebateStateMachine.java` | 4 | ⏳ 待实施 |
| `GatePolicy.java`、`JudgeService.java` | 5 | ⏳ 待实施 |
| `ReviewReportService.java`、`ReviewQueryService.java` | 5 | ⏳ 待实施 |
| `ReviewPersistenceMapper.java`、`MyBatisReviewRepository.java` | 5 | ⏳ 待实施 |
| `frontend/src/api/review-api.js`、`frontend/src/stores/review-store.js` | 5 | ⏳ 待实施 |
| `ReviewWorkbenchView.vue`、`ReviewReportView.vue`、`ReviewRoundtable.vue` | 5 | ⏳ 待实施 |
| `application.yml`、`application-local.yml` | 6 | ⏳ 待实施 |
| `CommercialModelGateway.java`、`ModelProfileRegistry.java`、`ReviewRuntimeTraceRegistry.java` | 6 | ⏳ 待实施 |
| 相关 Java 单元、集成和持久化测试 | 0-7 | ⏳ 待实施 |
| `review-api.test.js`、`review-store.test.js` 与 `frontend/tests/review-workbench.e2e.js` | 5、7 | ⏳ 待实施 |
| `src/main/resources/static/review/` | 5、7 | ⏳ 待实施 |
| `docs/AIREVIEW-PLAN-001-总体实施路线图.md`、README 双语文档、`.learnings/LEARNINGS.md` | 7 | ⏳ 待实施 |

> 文件名是实施前的目标设计。若现有持久化聚合更适合承载 Assessment 或 Dispatch，实施时可以在不改变接口与不变量的前提下合并类型，但必须在本计划“偏差记录”和文件清单中同步说明。

## 实施顺序与依赖

1. **方案 0**：先冻结 Assessment/Claim/Conflict 语义和 RolePack 稳定检查点。
2. **方案 1**：在现有子 Agent 完成 Assessment 提交与覆盖守卫。
3. **方案 2**：建立 fileRef 授权，消除越权路径暴露和无效预算消耗。
4. **方案 3**：建立持久化定向派发 envelope，替换广播指令。
5. **方案 4**：接入生产 ConflictDetector、批量开题和精确辩论动作。
6. **方案 5**：持久化 Assessment/Dispatch，升级 Gate、报告和工作台。
7. **方案 6**：在契约稳定后调整超时、重试、熔断和指标，避免提前优化掩盖协议错误。
8. **方案 7**：执行定向测试、全量构建、前端测试和完整流程闭环；最后更新路线图与学习记录。

依赖关系：`0 → 1 → 5`，`0 → 4 → 5`，`2 → 7`，`3 → 4 → 7`，`6 → 7`。方案 2 可与方案 1 并行实施，但合并后必须共同通过角色初审集成测试。

## 验证矩阵

| 场景 | 验证方式 | 通过标准 |
|---|---|---|
| 正向信息 | Role 工具与集成测试 | 每个核心角色至少能提交 CONFIRMED，且报告可查询 |
| 检查点完整性 | InitialReviewProgressService 测试 | 缺 required key 无法完成；补齐后幂等完成 |
| 无授权路径 | fileRef 单测/集成测试 | 上下文和搜索结果均不出现越权路径；读取被拒不扣预算 |
| 无可读文件 | 工具注册测试 | 不注册 readLines，角色用 UNKNOWN 完成相关检查点 |
| 单一风险 | ConflictDetectionService 测试 | 有 GAP 但无相反结论时不创建辩题 |
| 多主题 | DebateService 集成测试 | N 个候选原子登记 N 个主题，阶段只迁移一次 |
| 定向反驳 | DebateService/Dispatcher 测试 | 只有 challenge.targetRole 可回应，第三方被拒且状态不变 |
| 第二轮收敛 | 生命周期集成测试 | 无有效动作时跳过；有动作时目标 ID 和回合一致 |
| Gate 覆盖 | GatePolicy 参数化测试 | 覆盖不足或高风险 UNKNOWN 不得 AI_PASS |
| 报告计数 | Report/Query 测试 | 五态、主题和剩余风险计数均与 store 一致 |
| 模型超时 | Gateway/Trace 测试 | 达阈值后同 attempt 直接 fallback，指标区分超时和工具拒绝 |
| MySQL 兼容 | IDEA 构建 + 迁移集成测试 | V19 在目标 MySQL 版本可执行，读写和重放一致 |
| 前端展示 | Vitest + Playwright + build | 五态可见、覆盖率正确、静态 bundle 同步 |
| 完整闭环 | 生命周期 E2E | 最终到 COMPLETED，报告和通知均生成且版本一致 |

## 量化验收标准

- 核心角色 required checkpoint 覆盖率：100%。
- 报告确定性：CONFIRMED/PARTIAL/GAP/UNKNOWN/NOT_APPLICABLE 五态总数与持久化记录完全一致。
- 越权路径暴露：0；相同不可重试工具错误连续执行次数：不超过 1。
- 辩论错路由：0；第三方反驳成功数：0。
- 已检测冲突候选与已登记/明确跳过主题均有一一对应审计记录。
- 无必要第二轮的空动作次数：0。
- 覆盖不完整时 AI_PASS 次数：0。
- 确定性 E2E 最终阶段：COMPLETED。
- 真实模型验收总耗时以本次约 27 分钟为基线，目标不高于 15 分钟；若外部模型持续不可用，必须在报告中明确归因，不能把降级速度当成质量通过。

## 风险与缓解

| 风险 | 影响 | 缓解措施 |
|---|---|---|
| Assessment 与 Claim 双轨后模型调用次数增加 | token 和时延上升 | checklist 稳定化、批量提交 Assessment、Finalizer 只补缺项 |
| fileRef 改造破坏现有工具兼容 | 角色取证失败 | 工具内部兼容期只允许服务端把旧路径转换为 grant；Agent 公共 schema 直接切 fileRef |
| 批量开题跨内存/MySQL 实现不一致 | 重启后主题丢失或阶段错乱 | 同一应用事务、唯一键和幂等键；增加重启回放测试 |
| Director 仍生成非法意图 | 流程停滞 | 服务端返回具体可恢复错误和合法动作列表，不放宽领域校验 |
| Gate 变严格导致更多人工审核 | 自动通过率下降 | 先确保检查点证据充足；不以降低标准换取 AI_PASS |
| 熔断误伤偶发超时 | 过早降级 | 以 attempt 为边界、配置阈值、记录半开探测，不跨评审永久熔断 |
| 本地完整日志和明文调试密钥被误提交 | 敏感信息泄露 | 保持 ignore 校验、提交前差异检查、报告脱敏；不取消本地调试便利 |
| 计划涉及文件较多 | 合并与回归成本高 | 按方案独立提交，每段完成即更新计划状态并跑最小测试集 |

## 变更日志

| 日期 | 变更 |
|---|---|
| 2026-08-10 | 创建 PLAN-024，合并完整运行报告和后续复盘问题；确定不新增 Agent，采用“现有 Director 主编排 + 服务端守卫 + Dispatcher 精确投递”。 |
| 2026-08-10 | 将角色输出从单一 Claim 扩展为五态 Assessment；Gate 与报告以检查点覆盖为基础。 |
| 2026-08-10 | 修正 `readLines` 根因假设，采用角色授权 fileRef、动态工具注册和失败不扣预算。 |
| 2026-08-10 | 明确本地完整会话日志与本地明文调试密钥是接受的调试约定，不作为缺陷移除。 |
| 2026-08-10 | 方案0 完成：冻结五态 `AssessmentStatus` 与 `ReviewAssessment`、批量幂等 `ReviewAssessmentStore`、内存实现；`RolePack.checklist` 升级为稳定 `Checkpoint` 契约，四个核心角色各 6 检查点（5 required）。 |

## 偏差记录

- 2026-08-10：迁移文件由 `V17__create_review_assessment_and_dispatch_tables.sql` 改为 `V19__create_review_assessment_and_dispatch_tables.sql`。原因：V17（`context_scout_conclusion`）与 V18（`requirement_review_launch_command`）已被 PLAN-023 占用且 Flyway 迁移历史不可变；影响：Flyway 版本序列顺延至 V19；替代方案：重命名 PLAN-023 迁移——拒绝（迁移历史不可变）。
- 2026-08-10（方案0）：`RolePack.checklist` 由 `List<String>` 升级为 `List<Checkpoint>` 后，两处消费方做了必要的编译期适配——`RoleSubagentFactory.rolePrompt` 改为按 `checkpointKey (instruction)` 渲染检查点文案、`ReviewContextAssemblerTests` 的 RolePack 夹具改用 `Checkpoint`。原因：检查点类型升级为稳定契约是方案0 既定目标；影响：仅适配读取方式，未改变方案1 对这两处的语义职责；替代方案：保留 `List<String>` 另加并行字段——拒绝（会造成双源不一致）。

实施过程中任何接口、文件、状态机或验收标准调整，都必须先记录原因、影响与替代方案，再修改对应方案状态。
