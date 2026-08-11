---
kind: configuration_system
name: Spring Boot 分层配置与环境变量注入体系
slug: configuration_system
category: configuration_system
scope:
    - '**'
---

## 1. 采用的系统与框架

- **Spring Boot 配置绑定**：通过 `@ConfigurationProperties` + `record` 将 YAML 配置强类型化，集中在 `src/main/java/ai/cc/chongming/review/config/` 下按业务域拆分（review、model-gateway、agentscope、persistence、notification、sse、runtime-trace 等）。
- **Profile 切换**：使用 `application.yml` 中 `spring.profiles.active: local` 作为默认环境，并通过 `application-local.yml`、`src/test/resources/application-test.yml` 覆盖。`ReviewerIdentityConfiguration` 用 `@Profile({"local", "demo", "test"})` 与 `@Profile("!local & !demo & !test")` 在开发/测试与生产之间切换身份提供者。
- **条件装配**：关键子系统通过 `@ConditionalOnProperty(prefix = "review.persistence", name = "enabled", havingValue = "true")` 控制是否加载数据库、Flyway、AgentScope 持久化 Bean。
- **环境变量优先**：所有外部敏感或易变参数统一以 `${ENV_VAR:default}` 形式写在 `application.yml` 中，由部署环境注入，不随代码入库。

## 2. 核心文件与包

- **配置文件**
  - `src/main/resources/application.yml`：全局默认配置，定义 `spring.*`、`review.*`、`logging.*` 及全部环境变量占位符。
  - `src/main/resources/application-local.yml`：本地开发覆盖（数据库、模型网关、QQ 邮箱、仓库根路径）。
  - `src/test/resources/application-test.yml`：测试环境关闭持久化、禁用模型网关、使用临时目录工作空间。
  - `src/main/resources/roles/*.yml`：角色 Prompt/系统提示词（architecture/backend/frontend/judge/product/project/security/test），属于 Agent 行为配置而非运行时参数。
- **配置属性类（`review.config`）**
  - `ReviewProperties`：顶层 `review.workspaceRoot`、`maxAgents`、`maxDebateRounds`，带 `@NotBlank` / `@Min` 校验。
  - `ModelGatewayProperties`：`review.model-gateway.enabled/baseUrl/apiKey/logConversation`，启用时强制要求 baseUrl+apiKey（`@AssertTrue`）。
  - `ModelProfilesProperties`：`review.model-gateway.profiles.<name>` 逻辑模型 profile（provider/modelName/temperature/timeout/maxTokens/retry/fallback/streamEnabled）。
  - `AgentScopeProperties`：`review.agentscope.persistSession/stateHome/directorMaxIterations/scoutMaxIterations/scoutMaxToolCalls/scoutTimeout`。
  - `ReviewPersistenceProperties`：`review.persistence.enabled/jdbcUrl/username/password/maximumPoolSize` 及嵌套 `AgentScopeMysqlProperties`（databaseName/stateTableName/workspaceTableName/snapshotTableName/lockKeyPrefix/initializeSchema，表名受 `Pattern` 校验）。
  - `NotificationOutboxProperties`：`review.notification.workerEnabled/mcpEnabled/channel/destination/credentialEnvironmentVariable/maxAttempts/initialRetryDelay/workerDelay`，构造期强制非空与范围校验。
  - `ReviewRuntimeTraceProperties`、`ReviewGateProperties`、`ReviewOrchestrationProperties`、`RepositoryAccessProperties`、`ReviewDiagnosticsProperties`、`NotificationMailProperties`、`ReviewSseProperties` 分别对应 `review.runtime-trace.persistence`、`review.gate`、`review.orchestration`、`review.repositories`、`review.diagnostics`、`review.notification.mail`、`review.sse` 子树。
- **配置装配类**
  - `ReviewPersistenceConfiguration`：在 `review.persistence.enabled=true` 时创建 HikariDataSource、Flyway（`classpath:db/migration`）、AgentScope `DistributedStore`（含 JdbcSnapshotSpec/NoopSnapshotSpec 切换、JdbcSandboxExecutionGuard 分布式锁）。
  - `ReviewerIdentityConfiguration`：按 Profile 注入本地可审用户或拒绝匿名用户。
  - `ChongmingApplication.java`：标注 `@ConfigurationPropertiesScan`，自动扫描 `review.config` 下的属性类。

## 3. 架构与设计约定

- **分层前缀组织**：所有应用级开关集中在 `review.*` 命名空间下，按功能域再分 `gate`、`orchestration`、`diagnostics`、`repositories`、`agentscope`、`model-gateway`、`persistence`、`runtime-trace`、`sse`、`notification` 子树，便于按模块独立替换。
- **安全默认值**：所有对外部依赖的开关默认关闭（如 `model-gateway.enabled=false`、`notification.worker-enabled=false`、`notification.mcp-enabled=false`、`review.diagnostics.log-startup-failure-stack=false`、`review.diagnostics.context-scout-preview-enabled=false`、`flyway.enabled=false`），必须显式开启；敏感凭据（邮件授权码、模型 API Key）仅允许通过环境变量或本地 profile 注入，注释明确“不入库不入仓”。
- **环境变量占位符集中声明**：`application.yml` 中每个 `review.*` 字段都写成 `${REVIEW_*_...:默认值}`，部署侧只需设置环境变量即可覆盖，无需修改源码或提交新配置。
- **可选子系统条件加载**：MySQL 持久化、AgentScope 状态存储、通知 Worker/MCP 均通过 `@ConditionalOnProperty` 或属性布尔开关控制，未启用时不会引入相关依赖或连接。
- **运行时边界约束**：AgentScope 迭代次数、工具调用次数、超时时间、SSE 心跳/超时/回放批次、日志滚动大小等均以配置项暴露，并在属性类中使用 `@Min`、`Duration` 等类型约束，防止恶意或错误配置导致资源耗尽。
- **角色 Prompt 与运行时配置分离**：`resources/roles/*.yml` 是静态角色定义，不属于运行时配置；运行时行为仍通过 `review.*` 属性控制。

## 4. 约定与约束

- **所有外部凭据必须通过环境变量注入**：邮件授权码、模型 API Key、数据库密码、Learning Platform Token 等均以 `${REVIEW_*_AUTH_CODE}`、`${REVIEW_MODEL_GATEWAY_BASE_URL}`、`${REVIEW_DB_PASSWORD}`、`${REVIEW_LEARNING_PLATFORM_MCP_TOKEN_ENV}` 等形式声明，禁止硬编码到非本地 profile。
- **生产默认关闭危险能力**：模型网关、通知 Worker、Context Scout Preview、Flyway 初始化、日志堆栈输出在生产默认均为 false/false，需显式开启并配合环境变量提供真实端点。
- **数据库表名必须合法标识符**：`AgentScopeMysqlProperties` 中的 databaseName/stateTableName/workspaceTableName/snapshotTableName 受 `@Pattern("[A-Za-z_][A-Za-z0-9_]*")` 校验，防止 SQL 注入。
- **通知重试与延迟必须为正数且有限制**：`NotificationOutboxProperties` 构造期强制 `maxAttempts ∈ [1,20]`、`initialRetryDelay` 与 `workerDelay` 必须为正 Duration，否则抛 `IllegalArgumentException`。
- **模型网关启用时必须提供 baseUrl 与 apiKey**：`ModelGatewayProperties.isEnabledConfigurationValid()` 在 enabled=true 时校验两者非空，否则启动失败。
- **Profile 隔离身份策略**：`local/demo/test` 注入带 REVIEW/OVERRIDE 权限的本地评审者，其他环境一律返回无权限匿名主体，避免生产误授权。
- **测试环境隔离**：`application-test.yml` 显式禁用 persistence、模型网关、排除 DataSource 自动装配，并使用 `java.io.tmpdir` 下的临时工作空间，保证测试无外部依赖。
- **Flyway 迁移位置固定**：仅扫描 `classpath:db/migration` 下的 V1..V19 脚本，生产模式通过 `baselineOnMigrate(true)` 兼容已有库。
- **AgentScope 分布式锁键前缀可配置**：`lockKeyPrefix` 默认 `chongming:agentscope:lock:`，可通过环境变量覆盖以避免多实例冲突。
- **日志级别与滚动策略可配置**：`REVIEW_LOG_LEVEL`、`REVIEW_LOG_FILE`、`REVIEW_LOG_MAX_FILE_SIZE`、`REVIEW_LOG_MAX_HISTORY` 全部通过环境变量覆盖，默认 INFO + 20MB/7 天滚动。