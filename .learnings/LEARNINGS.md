# Learnings

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