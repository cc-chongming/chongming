# Learnings

Record project-specific corrections, knowledge gaps, and reusable practices here.

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
