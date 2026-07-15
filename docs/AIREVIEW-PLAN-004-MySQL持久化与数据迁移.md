# MySQL 持久化与数据迁移计划

> **状态**: ⏳ 待实施
> **创建日期**: 2026-07-14
> **目标**: 建立 AgentScope 运行态与评审领域数据相互隔离、可恢复、可审计的 MySQL 持久化层。
> **前置计划**: PLAN-002、PLAN-003

## 0. 背景与边界

AgentScope 运行态使用 `agentscope-extensions-mysql` 保存 AgentState、workspace KV、snapshot 和锁；评审业务表由 MyBatis 管理。
两层可共用受管 DataSource，但必须使用独立命名空间，业务查询不得依赖扩展内部表。MVP 数据长期保存，不实现 TTL 或物理删除。

## 1. 分段方案

### 1.1 DataSource 与迁移基线 ⏳

- 接入 MySQL Driver、连接池和 Flyway；凭证来自环境变量。
- 生产关闭 AgentScope 自动建表时需有明确初始化方案；测试可按正式 API选择自动初始化。
- 业务表名称以技术方案的数据模型表清单为准，不额外施加统一 `review_` 前缀；AgentScope 表使用扩展默认或配置前缀。

### 1.2 核心评审表迁移 ⏳

- 创建 review_request、review_plan、requirement_snapshot、repository_snapshot、role_activation、agent_run。
- 所有表含 created_at、updated_at、version；只追加表不提供 update/delete Mapper。
- attempt 范围数据使用 `review_id + attempt`；`review_event` 使用 `review_id + sequence` 唯一索引；session/label 建唯一约束。

### 1.3 论点、辩论与 Gate 表迁移 ⏳

- 创建 evidence_block、claim、claim_evidence、debate_topic、debate_turn、judge_decision、gate_decision。
- 幂等键唯一；外键或应用层完整性策略必须统一，避免半落库。
- `excerpt`、Prompt 摘要、公开发言等大字段使用 TEXT/JSON，明确字符集 utf8mb4。

### 1.4 审计、事件、报告和通知表 ⏳

- 创建 review_event、audit_event、model_call_log、notification_outbox、review_report、human_review_item。
- 事件 sequence 在同一 review 内全局单调递增，重试 attempt 不重置；Outbox 使用状态、next_retry_at、attempt_count。
- 不保存隐藏思维链；模型日志只存元数据、公开输出哈希和失败摘要。

### 1.5 MyBatis DO、Mapper 与 Repository ⏳

- DO 只表示表结构，Repository 完成领域对象转换。
- 写命令使用短事务；不要在持有事务时等待模型或执行文件扫描。
- Claim/Evidence/Turn 提供批量 `IN` 查询与关联批量装配，禁止 N+1。

### 1.6 AgentScope MySQL Store 配置 ⏳

- 通过同一 DataSource 创建 `MysqlDistributedStore`/`MysqlAgentStateStore`。
- 配置独立 keyPrefix，验证 MySQL named lock 不与其他应用冲突。
- 做进程重启、并发锁和状态恢复测试；业务表与运行态表分别断言。

### 1.7 数据一致性与恢复 ⏳

- 每次领域命令同事务写业务表和 `review_event`；模型/通知使用状态推进和 Outbox。
- 崩溃恢复从数据库业务阶段重新驱动 Harness，不以 workspace 文件推断最终业务状态。
- 提供数据库备份/恢复和测试数据清理脚本；生产不开放物理删除 API。

## 2. 文件清单

### 2.1 新建

| 文件                                                                                             | 计划段       | 状态 |
|------------------------------------------------------------------------------------------------|-----------|----|
| `src/main/resources/db/migration/V1__create_review_core_tables.sql`                            | #1.2      | ⏳  |
| `src/main/resources/db/migration/V2__create_debate_gate_tables.sql`                            | #1.3      | ⏳  |
| `src/main/resources/db/migration/V3__create_event_audit_outbox_tables.sql`                     | #1.4      | ⏳  |
| `src/main/java/ai/cc/chongming/review/config/ReviewPersistenceConfiguration.java`              | #1.1、#1.6 | ⏳  |
| `src/main/java/ai/cc/chongming/review/infrastructure/persistence/dataobject/`                  | #1.5      | ⏳  |
| `src/main/java/ai/cc/chongming/review/infrastructure/persistence/mapper/`                      | #1.5      | ⏳  |
| `src/main/java/ai/cc/chongming/review/infrastructure/persistence/repository/`                  | #1.5      | ⏳  |
| `src/test/java/ai/cc/chongming/review/persistence/MySqlMigrationIntegrationTests.java`          | #1.1-1.4  | ⏳  |
| `src/test/java/ai/cc/chongming/review/persistence/ReviewRepositoryIntegrationTests.java`        | #1.5      | ⏳  |
| `src/test/java/ai/cc/chongming/review/persistence/AgentScopeMysqlRecoveryIntegrationTests.java` | #1.6-1.7  | ⏳  |

### 2.2 修改

| 文件                                        | 计划段       | 状态 |
|-------------------------------------------|-----------|----|
| `pom.xml`                                 | #1.1      | ⏳  |
| `src/main/resources/application.yml`      | #1.1、#1.6 | ⏳  |
| `src/test/resources/application-test.yml` | #1.1      | ⏳  |

## 3. 实施顺序

1. **步骤 1**：先写迁移完整性和唯一索引失败测试。
2. **步骤 2**：实现 V1-V3 SQL 并通过 Testcontainers。
3. **步骤 3**：实现 DO/Mapper/Repository 和批量查询测试。
4. **步骤 4**：接入 AgentScope MySQL Store。
5. **步骤 5**：执行并发、进程重启和事务回滚测试。

## 4. 验证与退出标准

- 空库可一次迁移到最新版本，重复启动不重复建表。
- 约束、唯一索引和乐观锁均有失败测试。
- SQL 日志证明报告装配、冲突检测没有循环单查。
- 进程重启后 Agent session、workspace 和业务 review 均可恢复且职责不混淆。
- 数据库连接失败不会生成伪成功 Gate。

## 5. 风险与应对

| 风险                         | 应对                             |
|----------------------------|--------------------------------|
| AgentScope 自动建表与 Flyway 冲突 | 明确所有权；内部表不由项目迁移脚本重复创建          |
| 长事务耗尽连接                    | 模型、文件、网络调用全部在事务外，事务仅包领域落库      |
| JSON 字段难查询                 | 高频过滤字段拆列并建索引，JSON 仅存非关键扩展信息    |
| 长期保存导致增长                   | MVP 只监控表量；归档策略作为赛后计划，不在本计划删除数据 |

## 6. 变更记录

| 日期         | 变更                                |
|------------|-----------------------------------|
| 2026-07-14 | 创建双层 MySQL、迁移、Repository、事务与恢复计划。 |
| 2026-07-15 | 事件 sequence 对齐技术方案：在 review 范围全局递增，attempt 仅用于隔离业务产物。 |
