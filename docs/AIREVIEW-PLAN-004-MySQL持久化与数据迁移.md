# MySQL 持久化与数据迁移计划

> **Status**: BASELINE IMPLEMENTED; runtime integration and real-MySQL recovery verification are pending.
> **创建日期**: 2026-07-14
> **目标**: 建立 AgentScope 运行态与评审领域数据相互隔离、可恢复、可审计的 MySQL 持久化层。
> **前置计划**: PLAN-002、PLAN-003

## 0. 背景与边界

AgentScope 运行态使用 `agentscope-extensions-mysql` 保存 AgentState、workspace KV、snapshot 和锁；评审业务表由 MyBatis 管理。
两层可共用受管 DataSource，但必须使用独立命名空间，业务查询不得依赖扩展内部表。MVP 数据长期保存，不实现 TTL 或物理删除。

## 1. 分段方案

### 1.1 DataSource 与迁移基线 — DONE (baseline)

- 接入 MySQL Driver、连接池和 Flyway；凭证来自环境变量。
- 生产关闭 AgentScope 自动建表时需有明确初始化方案；测试可按正式 API选择自动初始化。
- 业务表名称以技术方案的数据模型表清单为准，不额外施加统一 `review_` 前缀；AgentScope 表使用扩展默认或配置前缀。

### 1.2 核心评审表迁移 — DONE (baseline)

- 创建 review_request、review_plan、requirement_snapshot、repository_snapshot、role_activation、agent_run。
- 所有表含 created_at、updated_at、version；只追加表不提供 update/delete Mapper。
- attempt 范围数据使用 `review_id + attempt`；`review_event` 使用 `review_id + sequence` 唯一索引；session/label 建唯一约束。

### 1.3 论点、辩论与 Gate 表迁移 — DONE (baseline)

- 创建 evidence_block、claim、claim_evidence、debate_topic、debate_turn、judge_decision、gate_decision。
- 幂等键唯一；外键或应用层完整性策略必须统一，避免半落库。
- `excerpt`、Prompt 摘要、公开发言及 JSON 序列化载荷使用 TEXT/LONGTEXT，明确字符集 utf8mb4；不依赖 MySQL 原生 JSON 类型。

### 1.4 审计、事件、报告和通知表 — DONE (baseline)

- 创建 review_event、audit_event、model_call_log、notification_outbox、review_report、human_review_item。
- 事件 sequence 在同一 review 内全局单调递增，重试 attempt 不重置；Outbox 使用状态、next_retry_at、attempt_count。
- 不保存隐藏思维链；模型日志只存元数据、公开输出哈希和失败摘要。

### 1.5 MyBatis DO、Mapper 与 Repository — DONE (baseline)

- DO 只表示表结构，Repository 完成领域对象转换。
- 写命令使用短事务；不要在持有事务时等待模型或执行文件扫描。
- Claim/Evidence/Turn 提供批量 `IN` 查询与关联批量装配，禁止 N+1。

### 1.6 AgentScope MySQL Store 配置 — PARTIAL

- 通过同一 DataSource 创建 `MysqlDistributedStore`/`MysqlAgentStateStore`。
- 配置独立 keyPrefix，验证 MySQL named lock 不与其他应用冲突。
- 做进程重启、并发锁和状态恢复测试；业务表与运行态表分别断言。

### 1.7 数据一致性与恢复 — PENDING

- 每次领域命令同事务写业务表和 `review_event`；模型/通知使用状态推进和 Outbox。
- 崩溃恢复从数据库业务阶段重新驱动 Harness，不以 workspace 文件推断最终业务状态。
- 提供数据库备份/恢复和测试数据清理脚本；生产不开放物理删除 API。

## 2. Current implementation status

| Deliverable | Status | Evidence |
|---|---|---|
| Flyway and placeholder configuration | DONE | `pom.xml`, `ReviewPersistenceProperties`, `application.yml` |
| Business schema migrations | DONE | `V1` to `V5` under `src/main/resources/db/migration/` |
| Batch-oriented MyBatis repository | DONE | `ReviewPersistenceMapper`, `MyBatisReviewRepository` |
| Aggregate persistence restore | DONE | `Review.restore(...)` and repository unit tests |
| AgentScope shared datasource store | DONE (wiring) | `ReviewPersistenceConfiguration` |
| Real MySQL migration/state tests | PENDING EXECUTION | Testcontainers tests exist; Docker is unavailable locally |
## 3. Explicit pending work

1. **Docker/CI verification** — execute the existing Flyway migration, shared-datasource AgentScope state, and MySQL state compatibility tests against MySQL 5.6 and 8.4; add real unique-constraint and optimistic-lock failure assertions.
2. **Runtime adapter integration** — have the production `AgentRuntimeAdapter` consume `reviewDistributedStore` when persistence is enabled, and verify session/workspace/review restoration after process restart.
3. **Transactional workflow** — implement command handlers that atomically write domain changes plus `review_event`; implement Outbox production, retry and delivery-state transitions.
4. **Failure and concurrency paths** — verify named-lock contention, transaction rollback, database-connection failure without a false-success Gate, and crash recovery from the persisted business stage.
5. **Operations** — add backup/restore and test-data cleanup scripts. Production must keep physical delete disabled.
6. **Snapshot decision** — with AgentScope 2.0.0, `JdbcSnapshotSpec` auto-initializes its table. Keep it disabled when `initialize-schema=false`, or approve controlled bootstrap/upgrade before enabling persistent snapshots.
## 4. Completion gate

PLAN-004 is complete only when all pending items in section 3 have passed on a real MySQL instance. The current `clean verify` result proves compilation and non-Docker behavior, not database recovery behavior.
## 5. 风险与应对

| 风险                         | 应对                             |
|----------------------------|--------------------------------|
| AgentScope 自动建表与 Flyway 冲突 | 明确所有权；内部表不由项目迁移脚本重复创建          |
| 长事务耗尽连接                    | 模型、文件、网络调用全部在事务外，事务仅包领域落库      |
| JSON 载荷难查询                 | 高频过滤字段拆列并建索引，序列化 JSON 仅以 LONGTEXT 保存非关键扩展信息 |
| 长期保存导致增长                   | MVP 只监控表量；归档策略作为赛后计划，不在本计划删除数据 |

## 6. 变更记录

| 日期         | 变更                                |
|------------|-----------------------------------|
| 2026-07-14 | 创建双层 MySQL、迁移、Repository、事务与恢复计划。 |
| 2026-07-15 | 事件 sequence 对齐技术方案：在 review 范围全局递增，attempt 仅用于隔离业务产物。 |
| 2026-07-15 | 完成持久化基线；将 Docker 验证、运行时接入、事务恢复和运维脚本显式标为待办。 |
| 2026-07-20 | 为兼容 MySQL 5.6，将 V1-V3 中全部 6 个原生 JSON 列改为 LONGTEXT；将 URI、revision、session、Gate 与幂等技术标识改为 ASCII 索引字符集，以满足 utf8mb4 表默认字符集下 767 字节索引限制；新增 V8 对既有数据库执行 JSON 列转换；新增 V9 以受 Flyway 管理的兼容 DDL 创建 AgentScope 状态和工作区表，替代库自身不兼容的自动建表。 |

## 7. Implementation record (2026-07-15)

- Added `flyway-core` and `flyway-mysql`; Spring Boot auto-Flyway is disabled so `ReviewPersistenceConfiguration` is the single migration owner.
- Added `review.persistence` placeholders in `application.yml`. It is disabled by default, so the placeholder JDBC URL never creates a connection pool unless `REVIEW_PERSISTENCE_ENABLED=true` is explicitly supplied.
- Added V1-V5 migrations for core review data, evidence/debate data, audit/outbox/report data, idempotent command outcomes, and PLAN-003 domain-contract columns. Business tables use `utf8mb4`, attempt-scoped indexes, foreign keys, optimistic-lock versions, and append-only event/audit records.
- Added the MyBatis review repository with a conditional lifecycle, aggregate restore support, optimistic-lock writes, idempotency result persistence, and batch Claim/Evidence/Turn reads without per-item selects.
- Added a composed `DistributedStore` using the same datasource with named AgentScope state/workspace/snapshot tables and a unique MySQL named-lock key prefix. In `initialize-schema=false` mode, the AgentScope snapshot component is intentionally `NoopSnapshotSpec`: AgentScope 2.0.0 has no public JDBC snapshot constructor that suppresses auto-DDL.
- Added unit tests for disabled-placeholder binding and repository batch mapping, plus Docker-optional migration and AgentScope shared-datasource integration tests.
- JSON compatibility migration: new databases create all serialized payload columns as LONGTEXT; V8 converts the same six columns for databases that previously used native JSON.
- AgentScope runtime compatibility: V9 creates the default state and workspace tables with MySQL 5.6-safe composite keys; `review.persistence.agentscope.initialize-schema` must remain `false` so AgentScope does not execute its incompatible auto-DDL.

## 7.1 MySQL 5.6 migration recovery

- **未保留数据的本地开发库**：V1 在 MySQL 5.6 上失败后可能已经创建部分表并写入失败的 Flyway 历史。先备份确认无须保留的数据，再删除并重建整个开发数据库，随后重新启动应用，让 Flyway 从 V1 重新执行。
- **已保留数据的数据库**：先完成备份；V1 出现 767 字节索引失败时，必须先评估是否存在部分建表，不能仅执行 `repair`。仅当 V1-V3 已完整执行且 URI、revision、session、Gate 名称和幂等键均符合 ASCII 技术标识约束时，才可审核 JSON→LONGTEXT 与索引字符集的 checksum 变化、使用受控 Flyway `repair`，再启动应用执行 V8。V8 使用 `ALTER TABLE ... MODIFY ... LONGTEXT` 保留已有序列化内容。
- 不得仅通过 `repair` 跳过 V8，也不得在未完成备份和 checksum 审核时修改 `flyway_schema_history`。

## 8. Verification and remaining work

- `./mvnw.cmd test` passed: 36 tests, 0 failures, 3 skipped because Docker is unavailable locally.
- Before enabling a real database, provide all `REVIEW_DB_*` and `REVIEW_AGENTSCOPE_*` values and decide whether controlled AgentScope schema bootstrap (`REVIEW_AGENTSCOPE_INITIALIZE_SCHEMA=true`) is permitted.
- Run the three Testcontainers integration tests with Docker, then add the planned crash-recovery, concurrent named-lock, transaction-rollback, backup/restore, and no-false-success Gate tests before marking this plan complete.
