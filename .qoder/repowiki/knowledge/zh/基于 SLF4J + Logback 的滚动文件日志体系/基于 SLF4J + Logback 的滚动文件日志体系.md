---
kind: logging_system
name: 基于 SLF4J + Logback 的滚动文件日志体系
slug: logging_system
category: logging_system
scope:
    - '**'
---

## 1. 使用的系统与框架

后端采用 **SLF4J API + Logback**（Spring Boot 默认）作为日志实现，通过 `org.slf4j.LoggerFactory` 在每个类中获取 `Logger` 实例；前端为 Vue 3 SPA，未引入专用日志库，仅通过浏览器控制台输出。

## 2. 核心配置文件与位置

- `src/main/resources/application.yml`：集中定义所有日志相关配置（见第 165–175 行）。
- `src/main/resources/application-local.yml`：本地 profile 覆盖部分业务开关，不覆盖日志级别。

## 3. 架构与约定

### 3.1 日志门面与实现
- 所有 Java 类统一使用 `import org.slf4j.Logger; import org.slf4j.LoggerFactory;`，以 `private static final Logger LOGGER = LoggerFactory.getLogger(XXX.class);` 形式声明。
- 未发现 Lombok `@Slf4j`、Log4j2、`java.util.logging` 等其他日志方案。

### 3.2 输出目标与轮转策略
- 通过 Spring Boot 内置的 `logging.file.name` 指定文件路径，默认 `${REVIEW_LOG_FILE:logs/chongming.log}`，即应用根目录下的 `logs/chongming.log`。
- 使用 Logback RollingFileAppender，按大小与保留份数轮转：
  - `max-file-size`: `${REVIEW_LOG_MAX_FILE_SIZE:20MB}`（注释说明默认 10MB 对长时调试过小，改为 20MB）。
  - `max-history`: `${REVIEW_LOG_MAX_HISTORY:7}`，最多保留 7 个历史文件。
- 未配置 console appender 或自定义 pattern，遵循 Spring Boot 默认控制台格式。

### 3.3 日志级别策略
- 根级别由环境变量控制：`level.root: ${REVIEW_LOG_LEVEL:INFO}`，默认 INFO。
- 代码中广泛使用 `LOGGER.info(...)` 记录业务事件（如 `DISPATCH_COMMAND_ISSUED`、`CONFLICT_DETECTION_COMPLETED`、`REVIEW_STARTUP_CANCELLED`），用 `LOGGER.warn(...)` 记录可恢复异常或降级（如 `RUNTIME_TRACE_PERSIST_SKIPPED`、`REBUTTAL_DISPATCH_SKIPPED`），用 `LOGGER.error(..., exception)` 记录不可恢复错误（如通知入队失败、报告生成失败）。
- AgentScope 模型桥接层使用 `log.debug(...)` 输出请求/响应边界信息，便于本地调试。

### 3.4 结构化字段约定
日志消息普遍采用 **大写下划线前缀的事件码 + key=value 参数** 的结构化风格，例如：
- `DISPATCH_COMMAND_ISSUED reviewId={} attemptNo={} commandId={} recipient={} action={} topicId={}`
- `RUNTIME_TRACE_HYDRATE_FAILED runtimeId={} error={}`
- `CONFLICT_DETECTION_COMPLETED reviewId={} attemptNo={} candidates={} noConflictSubjects={} gateRisks={}`
这种模式便于外部日志系统按事件类型聚合与检索。

### 3.5 敏感信息与调试开关
- `review.model-gateway.log-conversation` 默认 `false`，仅在受控本地会话开启，用于打印模型请求/响应体。
- `review.diagnostics.log-startup-failure-stack` 默认 `false`，local profile 中设为 `true`，仅启动失败时输出堆栈摘要。
- 这些开关通过环境变量或 profile 切换，避免在生产环境泄露敏感数据。

## 4. 约束与规则

- **必须通过 SLF4J 接口记录日志**：代码中所有日志均经 `LoggerFactory.getLogger` 获取 logger，未见直接调用 Logback 或 `System.out/err` 的记录点。
- **日志级别由部署环境变量控制**：`REVIEW_LOG_LEVEL`、`REVIEW_LOG_FILE`、`REVIEW_LOG_MAX_FILE_SIZE`、`REVIEW_LOG_MAX_HISTORY` 均可在部署时覆盖，禁止硬编码。
- **生产默认 INFO**：根级别默认 INFO，debug 级日志需显式提升级别。
- **运行时追踪持久化失败不得阻塞主流程**：`ReviewRuntimeTraceRegistry` 中对 trace 写入失败仅 `warn` 并继续执行，体现“best-effort”原则。
- **AgentScope 模型对话日志默认关闭**：`log-conversation=false`，仅在 local profile 中打开，防止生产日志泄露 prompt/response。

## 5. 前端日志

前端 Vue 应用未集成专用日志库，测试文件中出现 `page.once('dialog', ...)` 等 Puppeteer 交互，无业务日志输出。前端调试依赖浏览器开发者工具控制台。

## 6. 关键文件

- `src/main/resources/application.yml`（第 165–175 行：logging 配置）
- `src/main/java/ai/cc/chongming/review/application/ConflictDetectionService.java`
- `src/main/java/ai/cc/chongming/review/application/NotificationOutboxService.java`
- `src/main/java/ai/cc/chongming/review/application/ReviewCommandService.java`
- `src/main/java/ai/cc/chongming/review/application/ReviewDispatchService.java`
- `src/main/java/ai/cc/chongming/review/application/ReviewReportService.java`
- `src/main/java/ai/cc/chongming/review/application/ReviewRuntimeTraceRegistry.java`
- `src/main/java/ai/cc/chongming/review/infrastructure/agentscope/AgentScopeModelBridge.java`
- `src/main/java/ai/cc/chongming/review/infrastructure/agentscope/AgentScopeReviewRuntimeAdapter.java`
- `src/main/java/ai/cc/chongming/review/infrastructure/agentscope/ReviewDebateToolFactory.java`
- `src/main/java/ai/cc/chongming/review/infrastructure/agentscope/ReviewWorkflowDispatcher.java`
