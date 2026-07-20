# PLAN-004 Persistence Baseline Verification

> Date: 2026-07-15  
> Runtime: Java 21.0.10, Spring Boot 4.0.7, MyBatis 4.0.1, AgentScope 2.0.0  
> Result: Build and non-Docker tests pass. Real MySQL checks are present but skipped locally because Docker is unavailable.

## Implemented coverage

| Capability | Test | Result |
|---|---|---|
| Disabled placeholder binding | `ReviewPersistencePropertiesTests` | PASS |
| Conditional repository lifecycle | `ChongmingApplicationTests` | PASS |
| Aggregate rehydration and idempotency mapping | `MyBatisReviewRepositoryTests` | PASS |
| Batch Claim/Evidence mapping | `MyBatisReviewRepositoryTests` | PASS |
| 全部当前 Flyway 迁移（V1-V8）在 MySQL 5.6 上执行 | `ReviewPersistenceMigrationIntegrationTests` | SKIPPED (Docker unavailable) |
| Shared datasource and AgentScope runtime state | `ReviewPersistenceConfigurationIntegrationTests` | SKIPPED (Docker unavailable) |
| Existing AgentScope state compatibility | `MysqlAgentStateCompatibilityTests` | SKIPPED (Docker unavailable) |

## Commands and outcome

`./mvnw.cmd test` completed successfully with 36 tests: 33 passed, 3 skipped, 0 failures.

## Operational guardrails

- `review.persistence.enabled` defaults to `false`; the placeholder URL does not create a datasource or attempt a connection.
- Production credentials and AgentScope table names are supplied only through `REVIEW_DB_*` and `REVIEW_AGENTSCOPE_*` environment variables.
- `initialize-schema=false` does not allow AgentScope auto-DDL. The current AgentScope 2.0.0 JDBC snapshot API cannot disable its constructor-side DDL, so snapshots are explicitly disabled in that mode.
- Docker-backed verification and recovery/concurrency/failure-path tests remain required before PLAN-004 can be marked complete.
## Explicit pending verification

- Run `ReviewPersistenceMigrationIntegrationTests` with Docker/MySQL 5.6, and run `ReviewPersistenceConfigurationIntegrationTests` plus `MysqlAgentStateCompatibilityTests` with the supported AgentScope MySQL environment available.
- For an existing database that predates V8, back up first, perform a reviewed Flyway `repair` only for the intentional V1-V3 checksum changes, then verify V8 converts all serialized payload columns to LONGTEXT.
- Add real MySQL assertions for duplicate/idempotency keys, foreign keys, optimistic-lock conflicts, and Flyway repeat-start behavior.
- Verify process restart restores the AgentScope session/workspace together with the persisted review stage through the production runtime adapter.
- Verify named-lock contention, transaction rollback, Outbox retry/delivery transitions, database-failure handling, and absence of false-success Gate decisions.
- Verify backup/restore and test-data cleanup procedures before production enablement.
