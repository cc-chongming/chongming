# Learnings

## [LRN-20260720-001] Tool-only model responses require end-to-end protocol validation

**Status**: resolved

OpenAI-compatible providers may return a valid response with no text and only `tool_calls`. The provider response, domain response, and AgentScope bridge must all allow this shape while rejecting missing or duplicate call IDs, non-object arguments, and tool names outside the schemas exposed for the current call.

---

## [LRN-20260810-001] correction

**Logged**: 2026-08-10T00:00:00+08:00
**Priority**: high
**Status**: active
**Area**: diagnostics-security-boundary

### Summary

评估调试配置时，不能脱离实际部署边界把“本地完整模型会话日志”和“Git 忽略的本地明文模型密钥”直接判定为待修复缺陷；本项目当前明确保留两者以提高本地排障效率。

### Details

通用 `application.yml` 已默认关闭完整会话日志，`application-local.yml` 只用于受控本地调试且不纳入版本控制。后续分析应先区分通用/生产配置与本地开发配置，再判断风险是否已经越过边界。可接受边界是：本地可记录完整会话并保存调试密钥，但密钥、Authorization 和敏感正文不得进入 Git 提交、公共事件、评审报告或对外日志。

### Suggested Action

后续安全或运行报告使用“已确认缺陷 / 接受的本地调试约定 / 建议加固”三类结论；未发现越界证据时，不要求关闭本地日志，也不要求迁移或轮换本地调试密钥。

### Metadata

- Source: user correction during AIREVIEW-PLAN-024 planning
- Related Files: src/main/resources/application.yml, src/main/resources/application-local.yml, docs/AIREVIEW-PLAN-024-评审确定性覆盖与编排收敛.md
- Tags: correction, local-profile, conversation-log, api-key, threat-boundary

---

Record project-specific corrections, knowledge gaps, and reusable practices here.
## [LRN-20260716-001] compatibility

**Logged**: 2026-07-16T12:05:00+08:00
**Priority**: high
**Status**: resolved
**Area**: backend

### Summary
AgentScope Harness 的原始事件可不携带 metadata；启用 Plan Mode 后，模型桥接还必须显式允许 AgentScope 自带的计划工具。

### Details
真实 Harness 测试显示部分 `AgentEvent` 的 `metadata` 为 null，运行观测适配器若直接取值会中断整条 Agent 流。同时，Plan Mode 会注册 `plan_enter`、`plan_write`、`plan_exit`、`todo_write` 与 `wait_async_results`；若模型桥接只按业务 RolePack 白名单校验，会错误地拒绝这些框架内置、无业务副作用的工具。适配器现对 metadata 做 null-safe 提取，并只将这组内置计划工具加入有效白名单；shell、文件系统与未授权角色工具仍被拒绝。

### Suggested Action
对 AgentScope 原始事件按前向兼容原则处理，绝不假设可选 metadata 存在。角色白名单与 AgentScope 内置运行时工具应分层维护，并以真实 Harness 测试覆盖两者的交集。

### Metadata
- Source: compatibility_test
- Related Files: src/main/java/ai/cc/chongming/review/infrastructure/agentscope/AgentEventAdapter.java, src/main/java/ai/cc/chongming/review/infrastructure/agentscope/AgentScopeModelBridge.java
- Tags: agentscope, harness, plan-mode, events, permissions

---

## [LRN-20260714-001] architecture

**Logged**: 2026-07-14T14:30:05+08:00
**Priority**: high
**Status**: resolved
**Area**: backend

### Summary
AgentScope should execute bounded Agent calls while the Spring application owns the auditable review workflow.

### Details
The project pins AgentScope 2.0.0, while the local AgentScope source tree is already 2.0.1-SNAPSHOT. Core review roles need deterministic activation, evidence validation, debate limits, Gate rules, and recovery semantics that must not depend on mutable Agent memory or snapshot APIs.

### Suggested Action
Keep AgentScope behind an AgentRuntimeAdapter, pin the release version, persist domain state in MySQL, and cover structured output, events, permissions, and MCP registration with compatibility tests.

### Metadata
- Source: conversation
- Related Files: pom.xml, docs/技术方案/AI需求评审Agent_AgentScope2技术方案.md
- Tags: agentscope, architecture, versioning, workflow

---

## [LRN-20260714-003] best_practice

**Logged**: 2026-07-14T16:00:00+08:00
**Priority**: low
**Status**: resolved
**Area**: docs

### Summary
For multi-pose image generation, keep hard visual identity marks stable and express direction through the broader silhouette when intermediate detail becomes unreadable.

### Details
The 重明 bird's double pupils were clear in the canonical pose and cardinal anchors, but some dense 8-pose direction strips simplified them. Direction anchors, blind cardinal QA, deterministic atlas checks, and independent visual review still verified a cohesive usable pet. Intermediate ambiguity was documented as warnings rather than treated as a structural failure.

### Suggested Action
For future high-frame-count pets, prioritize cardinal readability, stable body registration, and no structural defects; use explicit warning records for subtle intermediate facial-detail deviations accepted by the user.

### Metadata
- Source: conversation
- Related Files: output/pets/chongming-run/qa/direction-semantics.json

---

## [LRN-20260714-002] correction

**Logged**: 2026-07-14T16:20:00+08:00
**Priority**: high
**Status**: resolved
**Area**: backend

### Summary
The review system should use a Harness main agent for adaptive orchestration, with a deterministic protocol guard enforcing business invariants.

### Details
The previous plan excluded HarnessAgent from the MVP core too early. This project inherently needs a moderator that plans repository exploration, dynamically activates role agents, coordinates debate, and manages shared workspace artifacts. AgentScope 2.0 Harness already provides Plan Mode, subagents, task tracking, workspace modes, event streaming, permissions, and persisted AgentState. Shared facts and evidence should live in the workspace and database, while each role keeps an independent reasoning context to avoid groupthink and context pollution.

The local source also enters spawned Harness subagents into Plan Mode when the parent is active. The current `plan-mode.md` still describes this as a gap, while `subagent.md` and `AgentSpawnTool` reflect the implemented behavior; source and tests must be treated as authoritative until the documentation is reconciled.

### Suggested Action
Revise the technical plan around `ReviewDirectorHarness + Role Subagents + ReviewProtocolGuard + MySQL/Audit`. Let Harness decide how to execute and revise the review plan, but prevent it from bypassing mandatory roles, Agent count, debate rounds, evidence validation, budget, Gate rules, or human approval.

### Metadata
- Source: user_feedback
- Related Files: docs/技术方案/AI需求评审Agent_AgentScope2技术方案.md, docs/需求文档/AI需求评审Agent_团队赛道项目方案V2.md
- Tags: agentscope, harness, plan-mode, subagent, architecture
- See Also: LRN-20260714-001

### Resolution
- **Resolved**: 2026-07-14T17:10:00+08:00
- **Notes**: Rewrote the technical plan around ReviewDirectorHarness, persistent role subagents, strong Plan Mode, DebateTools, ReviewProtocolGuard, replayable debate events, and AI-native delivery.

---

## [LRN-20260714-003] product_decision

**Logged**: 2026-07-14T18:10:00+08:00
**Priority**: high
**Status**: resolved
**Area**: docs

### Summary
The MVP now has fixed decisions for AgentScope versioning, persistence, input format, model access, notification integration, and human review.

### Details
Use AgentScope 2.0.0 formal artifacts only. Persist Agent runtime state, workspace, snapshots, and locks through `agentscope-extensions-mysql`, while MyBatis remains responsible for queryable review-domain and audit tables. Accept Markdown requirement documents only, retain data without automatic expiry, use company commercial models without a quota Gate, and reuse the previously verified learning-platform notification MCP. Human review is managed as editable UI drafts followed by immutable versioned decisions.

### Suggested Action
Treat these decisions as implementation constraints and keep only reviewer authentication, P1 default Gate policy, and on-site cache fallback as deferred policy work.

### Metadata
- Source: user_feedback
- Related Files: docs/技术方案/AI需求评审Agent_AgentScope2技术方案.md
- Tags: agentscope, mysql, persistence, markdown, human-review
- See Also: LRN-20260714-001, LRN-20260714-002

### Resolution
- **Resolved**: 2026-07-14T18:10:00+08:00
- **Notes**: Incorporated the confirmed decisions into the technical plan and converted the former open-question list into an implementation decision table.

---

## [LRN-20260714-004] best_practice

**Logged**: 2026-07-14T19:20:00+08:00
**Priority**: high
**Status**: resolved
**Area**: docs

### Summary
Large two-person Agent projects should use one dependency-DAG master plan plus independently verifiable numbered capability plans.

### Details
A single six-week roadmap is not detailed enough for parallel execution. The reusable structure is: freeze shared contracts first, assign a single owner to shared files, split runtime, persistence, evidence, orchestration, debate, events, human review, UI, security, evaluation, and delivery into separate plans, and require each segment to name files, tests, evidence, dependencies, and exit criteria.

### Suggested Action
Implement work from `AIREVIEW-PLAN-001` in dependency order. Update the active plan segment, file table, verification record, deviations, and change log in the same PR as the code.

### Metadata
- Source: conversation
- Related Files: docs/AIREVIEW-PLAN-001-总体实施路线图.md, .codex/rules/plan-driven-development.md
- Tags: planning, dependency-dag, parallel-development, verification
- See Also: LRN-20260714-002, LRN-20260714-003

### Resolution
- **Resolved**: 2026-07-14T19:20:00+08:00
- **Notes**: Created a master roadmap and fourteen implementation plans with dependencies, file lists, TDD checks, risks, and independent exit gates.

---

## [LRN-20260714-005] best_practice

**Logged**: 2026-07-14T20:10:00+08:00
**Priority**: medium
**Status**: resolved
**Area**: docs

### Summary
The project README should connect the Chongming mythology to concrete system mechanisms while clearly separating product vision from implemented capability.

### Details
The double-pupil metaphor maps naturally to role-specific contexts and multi-perspective review, while the guardian metaphor maps to evidence validation and the human-controlled Gate. Because the repository is still at the framework-spike stage, the README must label planned architecture and MVP boundaries explicitly instead of presenting roadmap items as released features.

### Suggested Action
Keep the README's current-status section synchronized with the numbered implementation plans. Promote capabilities from planned to available only after their plan exit criteria and verification evidence are complete.

### Metadata
- Source: conversation
- Related Files: README.md, docs/AIREVIEW-PLAN-001-总体实施路线图.md
- Tags: readme, brand-story, implementation-status, documentation

### Resolution
- **Resolved**: 2026-07-14T20:10:00+08:00
- **Notes**: Added a README that ties the Chongming story to evidence-driven debate, documents the planned architecture, and states the current implementation stage.

---

## [LRN-20260715-001] compatibility

**Logged**: 2026-07-15T12:16:00+08:00
**Priority**: medium
**Status**: resolved
**Area**: backend

### Summary

AgentScope 2.0.0 的 `MysqlAgentStateStore` 必须显式关闭，不能作为 try-with-resources 资源使用。

### Details

正式版制品可解析，且 MySQL 状态存储公开 `close()` 方法；但该类型不实现 `AutoCloseable`。兼容性测试最初使用 try-with-resources 时在编译期失败。将其改为 `try/finally` 显式调用 `close()` 后，完整基线测试通过。MySQL 的实际 round-trip 测试使用 Testcontainers，并在当前没有可用 Docker daemon 的环境中自动跳过。

### Suggested Action

在运行时 Adapter 中封装 AgentScope 状态存储的生命周期，不向业务层泄漏其资源类型；在 Docker 可用的 CI 环境强制执行 MySQL round-trip、重启恢复、snapshot 和锁测试。

### Metadata
- Source: compatibility_test
- Related Files: src/test/java/ai/cc/chongming/review/compatibility/MysqlAgentStateCompatibilityTests.java
- Tags: agentscope, mysql, lifecycle, testcontainers

---

## [LRN-20260715-002] compatibility

**Logged**: 2026-07-15T14:48:00+08:00
**Priority**: medium
**Status**: resolved
**Area**: backend

### Summary

AgentScope Harness 会按 `userId + sessionId` 恢复状态；固定脚本模型的协议测试必须隔离状态目录，并排除非协议的辅助模型调用。

### Details

在同一个 JVM 内复用相同 `userId + sessionId` 时，Harness 会从 `agentscope.state.home` 下恢复此前保存的 AgentState，导致测试获得旧上下文。为每个测试设置独立的临时 state home 后，`agent_spawn`、稳定 label 的 `agent_send`、`persistSession=true` 与 child source 事件透传均可重复验证。记忆钩子会额外调用模型；在固定响应序列的协议测试中应使用 `disableMemoryHooks()`，避免辅助调用抢占用于 `agent_send` 的脚本响应。

### Suggested Action

兼容性测试为每个用例隔离 state home，并明确 userId、sessionId 和脚本模型响应序列。仅在验证底层协议时关闭非协议钩子；生产运行时是否启用记忆能力必须由领域编排和成本策略单独决定。

### Metadata
- Source: compatibility_test
- Related Files: src/test/java/ai/cc/chongming/review/compatibility/HarnessSubagentEventCompatibilityTests.java, docs/验证记录/AgentScopeCompatibilityReport.md
- Tags: agentscope, harness, session, persistence, memory-hooks, testing

---

## [LRN-20260715-003] compatibility

**Logged**: 2026-07-15T15:25:00+08:00
**Priority**: high
**Status**: resolved
**Area**: backend

### Summary

AgentScope 2.0.0 不会自动将父 Harness 的 Plan 状态或自定义子代理工厂场景下的 DENY Permission 规则传播到子 Harness。

### Details

本地 `agentscope-java` 的 2.0.1-SNAPSHOT 源码已经包含父 Plan 状态和 DENY 规则传播逻辑，但项目锁定的正式制品是 2.0.0。固定脚本模型验证表明：正式版允许父 Agent 在 Plan Mode 中调用 `agent_spawn`，子 Harness 也会被创建和执行，却保持非 Plan 状态；同时，`subagentFactory` 创建的子 Harness 不含父侧 DENY 规则。同步/后台 spawn、child event、稳定 label 的 `agent_send` 和 `persistSession` 均可用。

### Suggested Action

正式 `AgentRuntimeAdapter` 创建子 Harness 时显式应用父 Plan/只读策略和 DENY 规则，并由 `ReviewProtocolGuard` 在业务层重复强制安全边界。不要依据本地 SNAPSHOT 源码推断正式版行为；升级 AgentScope 前先运行该兼容性测试组。

### Metadata
- Source: compatibility_test
- Related Files: src/test/java/ai/cc/chongming/review/compatibility/HarnessPlanModeSubagentCompatibilityTests.java, src/test/java/ai/cc/chongming/review/compatibility/HarnessSubagentPropagationCompatibilityTests.java, docs/技术方案/AI需求评审Agent_AgentScope2技术方案.md
- Tags: agentscope, harness, plan-mode, permission, adapter, compatibility

---
## [LRN-20260715-004] compatibility

**Logged**: 2026-07-15T17:20:00+08:00
**Priority**: high
**Status**: resolved
**Area**: backend

### Summary

AgentScope 2.0.0 `JdbcSnapshotSpec` always enables schema initialization; unlike `JdbcStore`, it exposes no public constructor or builder flag to suppress constructor-side DDL.

### Details

`MysqlAgentStateStore` and `JdbcStore` both support a production-safe `initializeSchema=false` mode. In contrast, both public `JdbcSnapshotSpec` constructors create `JdbcRemoteSnapshotClient(..., true)`. Building it against a shared production DataSource would therefore perform AgentScope snapshot auto-DDL even when the application's persistence bootstrap flag is false.

### Suggested Action

Use `NoopSnapshotSpec` whenever `REVIEW_AGENTSCOPE_INITIALIZE_SCHEMA=false`; enable `JdbcSnapshotSpec` only during a controlled bootstrap or after upgrading AgentScope to an API that separates snapshot construction from schema initialization. Keep business Flyway migrations independent from all AgentScope-owned tables.

### Metadata
- Source: local_agentscope_source
- Related Files: src/main/java/ai/cc/chongming/review/config/ReviewPersistenceConfiguration.java, D:/GitCode/agentscope-java/agentscope-extensions/agentscope-extensions-mysql/src/main/java/io/agentscope/extensions/mysql/snapshot/JdbcSnapshotSpec.java
- Tags: agentscope, mysql, snapshot, ddl, migration, production-safety

---

## [LRN-20260715-005] behavior

**Logged**: 2026-07-15T18:32:00+08:00
**Priority**: low
**Status**: resolved
**Area**: backend

### Summary

Markdown intake explicitly rejects a zero-byte upload with `EMPTY_MARKDOWN` (HTTP 422); empty-file coverage must verify rejection rather than an empty-content hash.

### Details

The validator checks `sourceByteCount == 0` before producing normalized staging files. A later PLAN-005 coverage test initially assumed that empty UTF-8 Markdown was accepted, and the full verification exposed the existing contract. The intended behavior is now covered directly and no workspace snapshot is created for empty input.

### Suggested Action

Keep `EMPTY_MARKDOWN` in the public intake error contract and include it whenever client validation or API documentation lists rejected Markdown content.

### Metadata
- Source: clean_verify
- Related Files: src/main/java/ai/cc/chongming/review/infrastructure/document/MarkdownRequirementValidator.java, src/test/java/ai/cc/chongming/review/document/MarkdownRequirementValidatorTests.java
- Tags: markdown, validation, empty-file, api-contract

---
## [LRN-20260716-001] security

**Logged**: 2026-07-16T10:40:00+08:00
**Priority**: high
**Status**: resolved
**Area**: backend

### Summary
Windows NTFS junctions are not reliably exposed as symbolic links and must be rejected through the DOS reparse-point attribute before canonicalizing a configured repository root.

### Details
`Files.isSymbolicLink` alone does not cover a `mklink /J` directory junction. `RepositoryBoundaryGuard` now checks `dos:attributes` with `NOFOLLOW_LINKS` for the reparse-point bit while walking the configured root and its ancestors. The regression test creates a real junction on Windows and confirms that an opaque configured repository ID is rejected with `REPOSITORY_PATH_UNSAFE`.

### Suggested Action
Reuse the same no-follow reparse-point check for every source path and snapshot root. Keep a real Windows junction test in the boundary suite; do not infer junction behavior from symbolic-link tests.

### Metadata
- Source: repository_boundary_test
- Related Files: src/main/java/ai/cc/chongming/review/infrastructure/repository/RepositoryBoundaryGuard.java, src/test/java/ai/cc/chongming/review/repository/RepositoryBoundaryGuardTests.java
- Tags: windows, junction, symlink, path-traversal, repository-security

---
## [LRN-20260716-002] correction

**Logged**: 2026-07-16T11:15:00+08:00
**Priority**: medium
**Status**: resolved
**Area**: backend

### Summary

Spring context tests exposed that model components with a test-only second constructor require an explicit injection constructor, and this application does not automatically supply an `ObjectMapper`.

### Details

`OpenAiCompatibleModelClient` has a public production constructor and a package-private HTTP-client constructor for deterministic adapter tests. Spring 7 did not select the intended constructor until it was marked `@Autowired`. The next startup failure showed that this application does not activate Jackson's usual auto-configuration, although PLAN-007 components require JSON decoding. `ModelGatewayJacksonConfiguration` now supplies a plain `ObjectMapper` only under `@ConditionalOnMissingBean`, so any future application-wide mapper remains authoritative.

### Suggested Action

When adding framework-managed components with test-specific constructors, mark the production constructor explicitly and retain a context-startup test. Treat JSON mapper availability as an explicit integration dependency rather than assuming a web application always enables Jackson auto-configuration.

### Metadata

- Source: plan_007_context_test
- Related Files: src/main/java/ai/cc/chongming/review/infrastructure/model/OpenAiCompatibleModelClient.java, src/main/java/ai/cc/chongming/review/config/ModelGatewayJacksonConfiguration.java, src/test/java/ai/cc/chongming/ChongmingApplicationTests.java
- Tags: spring, constructor-injection, jackson, model-gateway, testing

---

## [LRN-20260722-001] architecture

**Logged**: 2026-07-22T17:12:00+08:00
**Priority**: medium
**Status**: active
**Area**: review-observability

### Summary

AG-UI execution observation must be published as a side branch of the existing `HarnessAgent.streamEvents` subscription. Starting an `AguiAgentAdapter` separately would invoke the model twice and make the page trace diverge from the real review.

### Suggested Action

Keep durable domain SSE and bounded runtime AG-UI SSE as separate endpoints. Keep trace writes in-memory and make slow browser delivery occur outside the trace-buffer lock.

### Metadata

- Source: AIREVIEW-PLAN-017 implementation
- Related Files: src/main/java/ai/cc/chongming/review/infrastructure/agentscope/AgentScopeReviewRuntimeAdapter.java, src/main/java/ai/cc/chongming/review/application/ReviewRuntimeTraceRegistry.java
- Tags: ag-ui, harness, sse, observability

---

## [LRN-20260801-001] architecture

**Logged**: 2026-08-01T14:50:00+08:00
**Priority**: medium
**Status**: active
**Area**: requirement-platform

### Summary

需求生命周期不能复用 `ReviewStage`：它需要独立聚合和版本锁，而跨聚合的推进只应消费已提交的评审事件。

### Details

`Requirement` 保存跨多次评审的业务状态；`Review` 仅描述一次评审 attempt。实现中由 `RequirementLifecycleService` 监听 `PLAN_CREATED` 与 `HUMAN_GATE_FINALIZED`，避免 Controller 直接写两个聚合。反向 `review_request.requirement_id` 通过端口写入，内存与 MyBatis 同时实现；RETURN 后允许新 review 覆盖旧链接。Dashboard 与列表只能使用跨评审的只读事件投影，不能在逐条循环中查询数据库。

### Suggested Action

新增需求关联能力时，优先扩展读模型或显式端口，保持评审到需求为单向事件流。运行 MySQL 5.6 迁移验证时，确认字符串化 JSON 继续使用 `LONGTEXT`/`MEDIUMTEXT`，禁止引入 JSON 列。

### Metadata

- Source: AIREVIEW-PLAN-021 implementation
- Related Files: src/main/java/ai/cc/chongming/review/domain/model/Requirement.java, src/main/java/ai/cc/chongming/review/application/RequirementLifecycleService.java, docs/AIREVIEW-PLAN-021-需求全生命周期管理平台.md
- Tags: requirement, lifecycle, event-driven, projection, mysql56

---

## [LRN-20260801-002] correction

**Logged**: 2026-08-01T15:30:00+08:00
**Priority**: high
**Status**: active
**Area**: requirement-platform

### Summary

输入受理幂等、需求—评审关联和平台分页必须作为同一个可验证契约设计；不能把“返回既有评审”误当成新需求可以复用的评审，也不能先截断固定窗口再对外宣称分页。

### Details

`ReviewIntakeService` 的 `forceNewAttempt` 语义是同一 review root 的新 attempt，而不是新 `reviewId`。因此需求创建页必须识别 `reused=true` 并停止绑定；写入 `review_request.requirement_id` 要原子限制为 PENDING 且未绑定/同绑定，避免覆盖既有业务关系。平台列表和报告列表则必须从持久化投影获得 COUNT、筛选和 LIMIT/OFFSET；进程内报告或“最近 500 条”只能作为短期显示窗口，不能冒充完整 API 契约。

### Suggested Action

在 REQLIFE-H1/H2/M1/M2 完成并有 MySQL、重启、501+ 数据样本证据之前，计划、README 和验证记录均使用“实现基线/待验收”，不得标记平台发布完成。

### Metadata

- Source: AIREVIEW-PLAN-021 code review
- Related Files: docs/AIREVIEW-PLAN-021-需求全生命周期管理平台.md, docs/验证记录/RequirementPlatformReport.md
- Tags: idempotency, aggregate-link, pagination, persistence, verification

---

## [LRN-20260801-003] testing

**Logged**: 2026-08-01T16:30:00+08:00
**Priority**: medium
**Status**: active
**Area**: requirement-platform

### Summary

跨评审平台能力必须把内存、MyBatis 和运行时证据分层记录：内存回归能证明领域契约，不能替代 MySQL 5.6 的事务、Flyway 与执行计划证据。

### Details

REQLIFE-H1 用“先验证需求、再原子保留评审绑定”的顺序，保证绑定失败不会把需求从草稿改为待评审；H2 的 501 条回归只证明读模型没有固定 500 条截断。报告持久化的 Mapper 替身测试可以验证重读映射，但无法替代真实进程重启和 MySQL SQL 执行。全量 Maven 225 项通过，Docker 不可用的 6 项 Testcontainers 测试必须保留为跳过，不得转换为数据库验收通过。

### Suggested Action

后续环境验收按 MySQL 迁移、H1 并发/复用、H2 分页和 M1 重启读取、M2 EXPLAIN、浏览器闭环的顺序执行；将命令、样本量和结果补入验证记录后，才更新 PLAN-021 为完成。

### Metadata

- Source: AIREVIEW-PLAN-021 closeout
- Related Files: src/main/java/ai/cc/chongming/review/application/RequirementCommandService.java, src/main/java/ai/cc/chongming/review/application/ReviewListQueryService.java, docs/验证记录/RequirementPlatformReport.md
- Tags: mysql56, testcontainers, paging, transaction, verification

---

## [LRN-20260801-004] handoff

**Logged**: 2026-08-01T18:00:00+08:00
**Priority**: high
**Status**: active
**Area**: requirement-platform

### Summary

验收计划不能只罗列外部环境待办：一旦代码审查发现未关闭的并发一致性、投影载荷或排序契约问题，必须先把它们升格为代码收口门禁，并使历史测试结果失效为“仅基线”。

### Details

REQLIFE-H3 要求需求状态变更与反向评审绑定同处于需求聚合临界区，否则内存双并发路径会留下孤儿关联。REQLIFE-H4 要求跨评审列表只读取报告元数据，避免在分页列表中搬运正文。REQLIFE-M3 要求内存排序显式对齐 SQL 的同时间次序。三项修复都必须在 MySQL、性能和浏览器验收之前完成，且不能把旧的全量测试数字描述成修改后的验收结果。

### Suggested Action

交接时提供有顺序、修改边界、完成判据和禁止事项的控制卡；后续先运行最小相关测试，再跑当前工作树全量回归，最后才接外部环境验证。

### Metadata

- Source: AIREVIEW-PLAN-021 handoff review
- Related Files: docs/AIREVIEW-PLAN-021-需求全生命周期管理平台.md, docs/验证记录/RequirementPlatformReport.md
- Tags: handoff, code-review, concurrency, projection, deterministic-order

---

## [LRN-20260804-001] verification

**Logged**: 2026-08-04T00:00:00+08:00
**Priority**: high
**Status**: active
**Area**: requirement-platform

### Summary

持久化受理幂等不能只依赖进程内 Map；历史评审事件的可空投影字段也必须在跨评审列表和 Dashboard 同时兼容。

### Details

测试库重启后，只有以输入身份派生的稳定键查询 `review_request.input_idempotency_key`，并从工作区清单恢复快照，才能继续返回同一 review root。仅用内存 `submissions` 会在重启后重复创建评审。另有历史 `review_event.progress=NULL`，直接拆箱会使 `/api/reviews` 和 `/api/dashboard` 失败；读模型应将缺失进度显式映射为 0，而不是假定新增列已回填。

### Suggested Action

涉及跨进程去重时，把唯一键和恢复路径一起测试，并用重启后的真实请求验证。为旧表新增可空字段时，同时检查所有投影与序列化边界。

### Metadata

- Source: AIREVIEW-PLAN-021 test-database verification
- Related Files: src/main/java/ai/cc/chongming/review/application/ReviewIntakeService.java, src/main/java/ai/cc/chongming/review/application/ReviewListQueryService.java, src/main/java/ai/cc/chongming/review/application/DashboardQueryService.java
- Tags: idempotency, restart, nullability, projection, mysql56

---

## [LRN-20260804-002] performance

**Logged**: 2026-08-04T00:00:00+08:00
**Priority**: medium
**Status**: active
**Area**: requirement-platform

### Summary

MySQL 5.6 的排序索引必须完整匹配 Dashboard 的排序键；只有 `occurred_at` 的单列索引仍会导致 filesort。

### Details

Dashboard 近期活动按 `occurred_at DESC, review_id DESC, event_sequence DESC` 读取。测试库中使用 1,000 条会话临时事件进行 `EXPLAIN` 后，V13 的 `(occurred_at, review_id, event_sequence)` 复合索引被选中，结果为 `Using index` 且无 filesort。临时样本不写入业务表，连接结束后自动清理。

### Suggested Action

为 `ORDER BY` 声明索引时，逐列比对筛选和排序顺序，并将迁移后的实际 `EXPLAIN` 作为性能验收证据。

### Metadata

- Source: AIREVIEW-PLAN-021 MySQL 5.6 verification
- Related Files: src/main/resources/db/migration/V13__optimize_recent_review_event_order.sql, src/main/java/ai/cc/chongming/review/infrastructure/persistence/mapper/ReviewPlatformQueryMapper.java
- Tags: mysql56, explain, composite-index, dashboard, performance

---

## [LRN-20260804-003] runtime

**Logged**: 2026-08-04T00:00:00+08:00
**Priority**: high
**Status**: active
**Area**: agentscope-workflow

### Summary

AgentScope Director 不能在核心角色注册之前同步耗尽首轮对话；否则它会在 `PLANNING` 读取不到 Claim 的情况下收到后续阶段提示，造成协议工具全部被拒绝。

### Details

运行时启动现在只创建 Director 与执行 Scout；核心角色完成并提交 `INITIAL_REVIEW_COMPLETED` 后，才由已提交事件唤醒 Director。Director、角色和 Judge 只能通过受限的持久化清单工具获得 Claim、topic 与 turn ID，不能假设这些业务事实会自动出现在工作区文件中。无冲突时必须经服务端校验的受限工具进入 JUDGING，不能构造虚假的辩题。

### Suggested Action

修改多 Agent 评审编排时，测试“运行时已注册”和“模型对话已运行”两个不同时间点；任何需要持久化 ID 的角色都应有最小只读查询工具。

### Metadata

- Source: AIREVIEW-PLAN-021 model-gateway validation
- Related Files: src/main/java/ai/cc/chongming/review/infrastructure/agentscope/AgentScopeReviewRuntimeAdapter.java, src/main/java/ai/cc/chongming/review/infrastructure/agentscope/ReviewDebateToolFactory.java
- Tags: agentscope, orchestration, director, persisted-facts

---

## [LRN-20260804-004] runtime

**Logged**: 2026-08-04T00:00:00+08:00
**Priority**: high
**Status**: active
**Area**: model-gateway

### Summary

模型以正常文本结束、或网关返回非 JSON 响应时，不能让评审静默停在中间阶段；应使用受限收尾器或显式失败事件保持协议可诊断。

### Details

角色收尾器仅保留 `complete_initial_review`，避免模型在收尾阶段反复提交已被拒绝的 Claim。Director 在冲突分析后未作转换时仅获得“无冲突跳过辩论”工具；服务端仍会校验真实 Claim 立场，失败则记录 `DIRECTOR_CONFLICT_INCOMPLETE`。网关的非 JSON 响应仍属于外部失败，应记录为 `ModelGatewayException`，不能被收尾器伪装成成功。

### Suggested Action

所有模型工具流应同时覆盖：正常工具完成、正常文本结束、工具拒绝、模型格式错误和取消。发布验收必须把外部网关错误与本地状态机错误分开记录。

### Metadata

- Source: AIREVIEW-PLAN-021 model-gateway validation
- Related Files: src/main/java/ai/cc/chongming/review/infrastructure/agentscope/RoleSubagentFactory.java, src/main/java/ai/cc/chongming/review/infrastructure/agentscope/ReviewDirectorHarnessFactory.java
- Tags: finalizer, model-gateway, failure-handling, protocol

---

## [LRN-20260812-001] Playwright route glob intercepts Vite module scripts in dev mode

**Logged**: 2026-08-12T00:00:00+08:00
**Priority**: high
**Status**: resolved
**Area**: frontend-e2e

### Summary

Playwright `page.route('**/api/**')` 等宽泛 glob 会同时命中 Vite 开发服务器的模块请求（如 `/src/api/review-api.js`），mock 返回 JSON 后浏览器因 MIME 类型拒绝加载模块，整个应用无法启动，表现为元素定位超时的假性 UI 故障。

### Details

E2E 的 webServer 是 `npm run dev`，源码模块均从 `/src/**` 加载，路径中含 `api` 字样。`**/api/**` 与正则 `\/api\/(repositories|requirements|reviews)` 这类模式若只按路径片段匹配，可能误伤模块脚本。定位手段：在 route handler 内打印命中的 URL，或用 `page.on('console')` 观察 `Failed to load module script ... MIME type "application/json"` 错误。修复方式是改用谓词 `page.route((url) => url.pathname.startsWith('/api/'), handler)`，只对真实 API 请求生效。

### Suggested Action

新增 E2E mock 时优先使用 pathname 谓词或以 `/api/` 开头的精确 glob（如 `**/api/requirements**` 仍需复核是否命中 `/src/api/`），并在提交前完整跑一次 `npx playwright test`（本机可通过 `npx playwright install chromium` 安装浏览器二进制）。

### Metadata

- Source: T3 前端登录模块测试与构建产物同步
- Related Files: frontend/tests/review-workbench.e2e.js, frontend/playwright.config.js
- Tags: playwright, vite, e2e, route-mock

---

## [LRN-20260811-001] persistence

**Logged**: 2026-08-11T12:10:00+08:00
**Priority**: high
**Status**: active
**Area**: review-convergence

### Summary

冲突检测的候选、已登记、已跳过和无冲突处置属于可恢复业务事实，不能只存在于服务内存 Map；否则报告计数虽能在单进程内正确，重启后仍会丢失一一对应审计链。

### Suggested Action

类似“检测结果 + 后续处置”的跨阶段状态应通过独立领域 Store 批量替换、批量更新和一次读取恢复，内存/MyBatis 双实现保持同一契约，并用重建服务实例的测试验证恢复，而不是只测试同实例缓存。查询和处置必须显式携带 attemptNo；用 `MAX(attempt_no)` 推断会在当前 attempt 结果为空时错误回落到旧数据。

多主题登记的“原子”不能只靠 Service 方法名或循环外校验：Store 必须提供真正的批量写契约，MyBatis 用单条批量 SQL，应用层再用外层事务把主题批次与审计终结纳入同一提交边界。领域阶段与事件必须在这两类持久化写成功后推进。

### Metadata

- Source: AIREVIEW-PLAN-024 phase 7 audit
- Related Files: src/main/java/ai/cc/chongming/review/domain/repository/ReviewConflictAuditStore.java, src/main/java/ai/cc/chongming/review/application/ConflictDetectionService.java
- Tags: conflict-audit, persistence, replay, batch-query

---

## [LRN-20260811-002] frontend-testing

**Logged**: 2026-08-11T12:10:00+08:00
**Priority**: high
**Status**: active
**Area**: playwright

### Summary

Vite 开发环境中的宽泛 `**/api/**` 路由 Mock 会误拦截 `/src/api/review-api.js` 模块请求，返回 JSON 后导致页面以 MIME 错误空白；E2E Mock 必须只匹配真实后端 API 根路径。

### Suggested Action

Playwright route 使用带主机/API 根边界的正则；版本冲突重试场景不要依赖会随 DOM 重建清空的原生 file `required` 状态，文件必填由应用提交逻辑校验，并用保留的 File 对象验证重试。

### Metadata

- Source: AIREVIEW-PLAN-024 phase 7 E2E recovery
- Related Files: frontend/tests/review-workbench.e2e.js, frontend/src/views/RequirementDetailView.vue
- Tags: playwright, vite, route-mock, file-input, retry

---

## [LRN-20260812-001] idempotency

**Logged**: 2026-08-12T10:45:00+08:00
**Priority**: high
**Status**: resolved
**Area**: requirement-platform

### Summary

评审受理被嵌入需求启动命令时，内容去重键必须包含需求归属范围；跨需求复用同一 review root 会把正常提交误报为 `REVIEW_ALREADY_BOUND`。

### Details

草稿启动入口已经按 Requirement 和 `Idempotency-Key` 管理命令幂等，但下层 `ReviewIntakeService` 仍只按提交人、仓库、分支/提交和 Markdown 哈希去重。两个不同草稿上传同一文件时，下层会返回第一条需求的 reviewId，原子绑定层随后正确拒绝跨需求覆盖并产生 409。修复方式是让需求启动传入稳定的 `requirement:{requirementId}` 归属范围，并把它加入进程内与持久化受理键；范围为空时不增加任何键组件，以保持旧 `/api/reviews` 的历史去重键兼容。旧逻辑还可能在绑定失败前把错误 reviewId 写成完成态启动预约，因此重放路径必须识别该 review 是否缺失、已非 `PENDING` 或已归属其他需求，并以 Requirement、幂等键、指纹和 reviewId 四项精确匹配的条件删除旧预约后重新受理，避免误删并发命令，也避免要求人工清理数据库。

### Suggested Action

组合多个幂等层时，逐层列出“重放主体”和“唯一键”。内容相同不代表业务主体相同；下层内容去重必须接受上层归属范围，且需分别覆盖同主体重放、跨主体同内容、进程重启、数据库唯一键，以及修复上线前已污染的完成态幂等记录。

### Metadata

- Source: live_api_failure
- Related Files: src/main/java/ai/cc/chongming/review/application/ReviewIntakeRequest.java, src/main/java/ai/cc/chongming/review/application/ReviewIntakeService.java, src/main/java/ai/cc/chongming/review/application/RequirementReviewLaunchService.java
- Tags: requirement, review, idempotency, ownership-scope, persistence
- See Also: LRN-20260801-002, LRN-20260804-001

### Resolution

- **Resolved**: 2026-08-12T10:45:00+08:00
- **Notes**: IDEA MCP 编译通过；定向测试覆盖跨需求隔离、同需求重放、持久化键隔离和启动命令范围传递。

---

## [LRN-20260820-001] persistence-timezone

**Logged**: 2026-08-20T21:00:00+08:00
**Priority**: high
**Status**: resolved
**Area**: persistence

### Summary

本项目并存两种 DATETIME 列约定：Java 写入的列存 UTC 墙钟（`atOffset(UTC).toLocalDateTime()` ↔ `toInstant(UTC)`），而 `DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP` 的 DB 自动生成列存的是 MySQL 服务器本地（中国）墙钟。两类列混用同一个 `toInstant(UTC)` 还原会把后者推快 8 小时。

### Details

评审列表/首页的 `review_request.updated_at` 是 DB 自动生成列，被按 UTC 还原后前端再转 Asia/Shanghai，显示为“次日凌晨”。事件表 `occurred_at` 等 Java 写入列则正常，造成“只有列表时间错”的迷惑现象。修复在 `MyBatisReviewPlatformProjectionStore` 对该列单独按 `Asia/Shanghai` 还原并加注释。新建表若需要 DB 默认时间，读取侧必须按服务器时区还原，或改为 Java 显式写入 UTC 墙钟。

### Suggested Action

新增 DATETIME 列时先标注“谁写入”：Java 写入走 UTC 墙钟约定；DB 自动生成列读取侧用 `ZoneId.of("Asia/Shanghai")` 还原。Code Review 时检查 `toInstant(ZoneOffset.UTC)` 的列来源。

### Metadata

- Source: user-reported wrong list times
- Related Files: src/main/java/ai/cc/chongming/review/infrastructure/persistence/repository/MyBatisReviewPlatformProjectionStore.java, src/main/resources/db/migration/V1__create_review_request_and_runtime_tables.sql
- Tags: persistence, timezone, datetime, utc-wall-clock, db-generated

---

## [LRN-20260821-001] mybatis-mapper-scan

**Logged**: 2026-08-21T09:00:00+08:00
**Priority**: high
**Status**: resolved
**Area**: persistence

### Summary

新增 MyBatis Mapper 接口必须放在 `@MapperScan` 声明的包（`ai.cc.chongming.review.infrastructure.persistence.mapper`）内；放在其他包在内存模式/单测里一切正常，但 `review.persistence.enabled=true` 启动时 Bean 缺失直接启动失败。

### Details

`ReviewPersistenceConfiguration` 的 `@MapperScan` 只扫描上述包，且显式 MapperScannerConfigurer 会抑制 mybatis-spring-boot 自动配置对 `@Mapper` 注解的全包扫描。PLAN-031 初版把 `TaskAttachmentMapper` 放在 `task.infrastructure`，定向测试（InMemory 仓储 + standalone MockMvc）全绿但 MySQL 模式无法启动。已移至扫描包，与 `DevTaskMapper`（task 域却住在 review 持久化包）的既有约定一致。

### Suggested Action

新增 Mapper 一律放 `review.infrastructure.persistence.mapper`；若确需新包，同步扩展 `@MapperScan` 数组。验证 MySQL 路径不能只靠 InMemory 单测，至少跑一次 persistence=true 的上下文或集成测试。

### Metadata

- Source: code review of PLAN-031 against ReviewPersistenceConfiguration
- Related Files: src/main/java/ai/cc/chongming/review/config/ReviewPersistenceConfiguration.java, src/main/java/ai/cc/chongming/review/infrastructure/persistence/mapper/TaskAttachmentMapper.java
- Tags: mybatis, mapper-scan, package-convention, persistence-enabled, startup-failure

---

## [LRN-20260821-002] mybatis-blob-scalar-return

**Logged**: 2026-08-21T11:00:00+08:00
**Priority**: high
**Status**: resolved
**Area**: persistence

### Summary

MyBatis `@Select` 方法直接返回裸 `byte[]` 时会被误路由到 `ByteTypeHandler`，对 BLOB/LONGBLOB 列调 `getByte` 抛 `NumberOutOfRange`（Spring 译为 `DataIntegrityViolationException`，HTTP 500）。读 BLOB 必须返回行记录（record/POJO）让 MyBatis 按列元数据选 `ByteArrayTypeHandler`，再取字段。

### Details

PLAN-031 附件下载初版 `byte[] findContent(...)` 在 MySQL LONGBLOB 上 500；列表/元数据查询（返回 record）正常，造成“能传能列、不能下载”的迷惑现象。独立 MyBatis 会话复现确认：裸 `byte[]` → `SQLDataException: Value '...' is outside of valid range for type java.lang.Byte`；改返回 record 后 `getBytes` 正常。单测用 InMemory 仓储覆盖不到该路径，BLOB 读写需对真实 MySQL 验证。

### Suggested Action

Mapper 读 BLOB 一律返回 record/行对象并在 store 层取字节字段；不要写裸 `byte[]`/`Byte` 标量返回。涉及 BLOB 的改动至少跑一次 persistence=true 的集成验证或独立 MyBatis 复现脚本。

### Metadata

- Source: live download 500 + standalone MyBatis repro
- Related Files: src/main/java/ai/cc/chongming/review/infrastructure/persistence/mapper/TaskAttachmentMapper.java, src/main/java/ai/cc/chongming/task/infrastructure/MyBatisTaskAttachmentStore.java
- Tags: mybatis, blob, longblob, type-handler, byte-array, data-integrity

---

## [LRN-20260821-003] debate-silent-stall-hybrid-convergence

**Logged**: 2026-08-21T15:00:00+08:00
**Priority**: high
**Status**: resolved
**Area**: orchestration

### Summary

事件驱动的 Director 唤醒计数无法捕捉“静默死锁”（末次唤醒后无任何事件产生），只能靠墙钟兜底，导致停顿卡满预算（实测 879s）。收敛看门狗应升级为“三信号最快优先”：已过期 PENDING 调度命令（精准、秒级）→ 无进展空闲（no-progress）→ 墙钟兜底。

### Details

真实评审 `116b0609` 在并发=4 后单调用已快（2~10s），但 14:18:49→14:33:31 静默 879s：Director 末次唤醒未发出再唤醒事件、角色空闲，`noteDirectorWake` 不再触发，事件计数停摆，仅 60s 巡检等满 20 分钟墙钟才强制收敛。混合版在 `scanForStalledDebates` 增加两条更快路径：①`hasExpiredPending`——attempt 仍有已过期的 PENDING 调度命令（指派了但目标角色永不消费）立即收敛；②`no-progress`——`lastWakeAt` 起超过 `debate-no-progress-timeout`（默认 PT6M，需大于单次 director 调用上限 PT300S 防误收敛）仍无活动即收敛；③原墙钟 PT20M 兜底。`forceConvergence` 日志带 `reason=` 区分路径。

### Suggested Action

多 Agent 事件驱动编排必须为“无事件产生”的死锁设计独立看门狗，且看门狗不能只依赖墙钟：优先用“已指派但过期未消费的工作”这类精准信号秒级救回，再用空闲窗口，墙钟仅作最后兜底。无进展窗口必须大于单次模型调用上限，避免把正常长调用误判为死锁。

### Metadata

- Source: live review 116b0609 log gap analysis (879s silent stall)
- Related Files: src/main/java/ai/cc/chongming/review/application/DebateConvergenceGuard.java, src/main/java/ai/cc/chongming/review/config/AgentScopeProperties.java
- Tags: orchestration, debate, convergence, silent-stall, watchdog, liveness, dispatch

---

## [LRN-20260821-004] multi-channel-notification-fanout

**Logged**: 2026-08-21T16:00:00+08:00
**Priority**: medium
**Status**: resolved
**Area**: notification

### Summary

多端通知不应改 Outbox 状态机，而应在入队侧做“每收件人 × 每通道”扇出：每个通道一条独立 Outbox 行（幂等键含 channel），各自重试/退避/DEAD，互不牵连；路由侧按 channel 分发到对应 `NotificationDeliveryPort` 适配器。新通道默认关闭、失败关闭，不冲击既有主通道。

### Details

学习通 uid 通道接入：`enqueueMatrix` 在 mail/local 之外，当 `chaoxing.enabled` 且 `users.company_uid` 为数字 uid 时并行追加 `chaoxing` 行；`NotificationDeliveryRouter` 增 `chaoxing` 分支；`ChaoxingNotificationAdapter` 用 `@ConditionalOnProperty` 条件装配。参考 `sendNotice` 适配为 RestTemplate+Jackson（项目无 hutool/fastjson）。第三方签名（`fillParams_encnew`）隔离为独立方法并标 TODO，配置缺失时抛不可重试异常，保证未配置时绝不发出错误签名请求且邮件通道不受影响。

### Suggested Action

接入新通知通道时：入队侧扇出（一条/通道）而非改状态机；适配器条件装配+失败关闭；第三方签名/密钥隔离为可替换缝并经环境变量注入；默认关闭新通道。验证至少跑 test-compile 与路由禁用用例。

### Metadata

- Source: user-provided Chaoxing sendNotice integration
- Related Files: src/main/java/ai/cc/chongming/review/application/NotificationOutboxService.java, src/main/java/ai/cc/chongming/review/infrastructure/notification/NotificationDeliveryRouter.java, src/main/java/ai/cc/chongming/review/infrastructure/notification/ChaoxingNoticeClient.java
- Tags: notification, outbox, multi-channel, fan-out, chaoxing, adapter, fail-closed

---

## [LRN-20260826-001] Role-prompt expression guidance lives in role-pack data, not scattered Java

**Logged**: 2026-08-26T00:00:00+08:00
**Priority**: medium
**Status**: resolved
**Area**: role-pack-prompts, output-quality

### Summary

角色评审输出的中英夹杂与角色串味根因是：所有角色的系统提示词只有一句共用的 “Use Simplified Chinese”，表达约束没有按角色配置。修复方式是数据驱动：把“身份/岗位词汇/禁用项/检查点视角”下沉到各 `roles/*.yml` 的 `voice:` 段，公共协议词中文化映射集中在 `RoleSubagentFactory.commonExpressionGuidance()`，避免再散落进 Java 拼接。

### Details

- RolePack 新增可选 `Voice` 记录（identity/focus/avoid/lens），缺省为 EMPTY 保持兼容；Registry 用 Spring `YamlMapFactoryBean` 解析。
- 8 个角色包全部配置 voice 并中文化 checklist instruction；checkpointKey 保持稳定；promptVersion 递增（product-v4、project/backend/frontend-v3、architecture/security/testing/judge-v2）。
- 协议词映射（OPPOSE→反对、SUPPORT→支持、CHALLENGE→质询、EVIDENCE_REQUEST→证据请求、Claim→主张、Gate→门禁 等）与“机器标识（claimId/subjectKey/checkpointKey）禁入正文”写进公共规范，对初审/Claim/辩论/裁决/finalizer 全部生效。
- 中文改写 instruction 时注意契约测试断言：核心角色 adversarial_scrutiny 断言 “OPPOSE”（大写），product.core_value_stance 断言 “SUPPORT”+“OPPOSE”，改写必须保留协议机制词。

### Suggested Action

后续改角色措辞：只改 `roles/*.yml` 的 voice/instruction 并递增对应 promptVersion；不要改动 `RoleSubagentFactory` 的公共规范。验证跑 `RolePackContractTests`、`RoleVoicePromptTests`。另注意本机 Maven enforcer 强约束 JDK 21：需 `JAVA_HOME=D:/Tool/Java21`（系统 PATH 的 java 为 1.8/17，直接 `./mvnw.cmd` 会被 RequireJavaVersion 拦截）。

### Metadata

- Source: AIREVIEW-PLAN-032 implementation
- Related Files: src/main/resources/roles/*.yml, src/main/java/ai/cc/chongming/review/infrastructure/agentscope/RoleSubagentFactory.java, src/main/java/ai/cc/chongming/review/domain/role/RolePack.java, docs/AIREVIEW-PLAN-032-角色化表达规范与角色提示词.md
- Tags: prompt-engineering, role-pack, expression-guidance, voice, 中文

---

## [LRN-20260826-002] 自评审仓库的运行时状态会污染快照与模块根

**Logged**: 2026-08-26T00:00:00+08:00
**Priority**: high
**Status**: resolved
**Area**: repository-snapshot, context-scout

### Summary

chongming 评审自身时，工作区快照把 .agentscope/workspace 等运行时状态当作评审内容冻入；模块根探测取清单前 40 文件的顶级目录，全部命中 .agentscope，INIT 清单告诉 Scout "唯一模块根是 .agentscope"。Scout 检索落空后循环重试，撞硬预算降级。另：违规调用在事件发布前被异常吞掉，日志无工具名，诊断靠考古。

### Details

修复（AIREVIEW-PLAN-035）：快照复制与指纹排除运行时目录（.agentscope/.qoder/.claude/.codex/.learnings 任意深度，output/logs 仅根级）；模块根改为全量清单按顶级目录计数取前 8；硬预算与提示词/基线统一为 2/3/4；违规异常携带工具名与次数并进入降级 WARN。

### Suggested Action

评审任何"仓库评审自身"的场景前，先检查快照顶层构成与 INIT 清单的模块根是否合理；Scout 降级时先看 context_scout_degraded 的 detail 字段。

### Metadata

- Source: AIREVIEW-PLAN-035 implementation
- Related Files: src/main/java/ai/cc/chongming/review/application/RepositorySnapshotService.java, src/main/java/ai/cc/chongming/review/infrastructure/agentscope/ReviewRepositoryToolFactory.java, src/main/java/ai/cc/chongming/review/infrastructure/agentscope/AgentScopeReviewRuntimeAdapter.java, docs/AIREVIEW-PLAN-035-快照治理与Scout契约可观测.md
- Tags: snapshot, module-roots, scout-contract, observability

---

## [LRN-20260826-003] 一边倒评审是结构问题：冲突需要显式的支持方

**Logged**: 2026-08-26T00:00:00+08:00
**Priority**: high
**Status**: resolved
**Area**: review-debate, conflict-detection

### Summary

独立审查全部产出 OPPOSE 且各挂不同主题时，冲突检测（要求同主题 SUPPORT+OPPOSE）报告"无冲突"，兜底开题也没有防守方。"检测不到冲突"不等于"没有冲突"：需求文档本身是隐含支持方，但无角色代表它；反对与反对之间也可能互不相容。

### Details

修复（AIREVIEW-PLAN-033）：A 议题无支持方时 Director 必须指派 PRODUCT 为需求答辩人逐条回应（辩护或承认）；B 全部初审角色新增平衡义务（认可点须以 SUPPORT 提交，required 检查点强制）；C ConflictDetector 新增 OPPOSE_DIVERGENCE（同主题多角色反对即候选，基础分 40）。服务端 skip 闸门自 2026-08-19 起已禁止带 OPPOSE 跳过，register_topics 本就接受无 SUPPORT 主题。

### Suggested Action

再出现"一边倒"时：先确认各角色是否提交了 SUPPORT 主张（平衡义务生效与否），再看 OPPOSE_DIVERGENCE 是否把相关异议归入候选；答辩质量差时调整 product voice 的答辩义务措辞而非改状态机。

### Metadata

- Source: AIREVIEW-PLAN-033 implementation
- Related Files: src/main/java/ai/cc/chongming/review/domain/debate/ConflictDetector.java, src/main/java/ai/cc/chongming/review/infrastructure/agentscope/ReviewDirectorHarnessFactory.java, src/main/resources/roles/*.yml, docs/AIREVIEW-PLAN-033-需求答辩人与平衡初审机制.md
- Tags: debate, conflict-detection, defender, balanced-review

---

## [LRN-20260826-004] 计划驱动开发规则需先读后动，事后补齐成本更高

**Logged**: 2026-08-26T00:00:00+08:00
**Priority**: medium
**Status**: resolved
**Area**: process, plan-driven-development

### Summary

.codex/rules/plan-driven-development.md 要求"先出计划，再写代码"：计划文档含头部元信息/分段方案/文件清单/实施顺序/风险/变更记录，代码注释标注 [PLAN-编号#段.子段]。本次多个功能先行实施后才读规则，补齐计划文档与段标注花了额外一轮。

### Suggested Action

接到新功能/重构任务时，先读 .codex/rules/ 与 AGENTS.md，在 docs/ 下建 AIREVIEW-PLAN-XXX 计划文档再动手；实施中段更新状态，收尾时更新 .learnings 与（如有）CLAUDE.md。

### Metadata

- Source: retroactive plan backfill session
- Related Files: .codex/rules/plan-driven-development.md, docs/AIREVIEW-PLAN-033-*.md, docs/AIREVIEW-PLAN-034-*.md, docs/AIREVIEW-PLAN-035-*.md
- Tags: process, plan-driven, documentation

---

## [LRN-20260827-001] PLANNING 窗口内初次进入应停在上下文侦察，阶段机索引不等于观看落位

**Logged**: 2026-08-27T00:00:00+08:00
**Priority**: medium
**Status**: resolved
**Area**: review-ui, phase-navigation

### Summary

Context Scout 在阶段机进入 PLANNING 后仍持续流式输出（Scout 运行横跨 SNAPSHOTTING→PLANNING 窗口）。此前 `activePhaseIndex` 直接按 `phaseIndexByStage` 落位，PLANNING 即停在“评审规划”，而侧边栏却显示“评审规划=未开始、上下文侦察=进行中”——页面停在未开始的阶段，体验割裂。

### Details

修复（AIREVIEW-PLAN-037）：落位决策抽为纯函数 `resolvePhaseLanding`（frontend/src/services/review-phase-presenter.js）：按阶段索引落位，仅当结果为 1（PLANNING）且 Scout 未结束时回落到 0。Scout 结束双信号 = 运行流 RUN_FINISHED 事件或 `summary.contextScout` 落库（COMPLETED 结论/COMPLETED 事件/DEGRADED 事件都使其非空，见 ReviewQueryService 的 ContextScoutView 构造）。回放缺 RUN_FINISHED 时由 summary 兜底。`scoutComplete` 同步重写为 `scoutConcluded || activePhaseIndex >= 2`，消除原先对 activePhaseIndex 的双向依赖。

### Suggested Action

改阶段导航时记住：阶段机索引 ≠ 观看落位；任何“窗口内并行流”（Scout 之于 PLANNING）都要用结束信号决定初次落位与徽章，新增类似窗口时在 resolvePhaseLanding 加分支并补单测。

### Metadata

- Source: AIREVIEW-PLAN-037 implementation
- Related Files: frontend/src/services/review-phase-presenter.js, frontend/src/views/ReviewLiveView.vue, src/main/java/ai/cc/chongming/review/application/ReviewQueryService.java, docs/AIREVIEW-PLAN-037-初次进入停留上下文侦察阶段.md
- Tags: review-ui, phase-landing, context-scout, live-view

---
