---
kind: error_handling
name: 基于 ProblemDetail + 领域错误码的 REST API 错误契约
slug: error_handling
category: error_handling
scope:
    - '**'
---

## 1. 采用的体系

后端采用 Spring Boot 的 `@RestControllerAdvice` + RFC 9457 `ProblemDetail` 作为统一的 HTTP 错误响应格式，所有 API 控制器通过专用的异常处理器将业务异常、参数校验异常和基础设施异常映射为稳定的 JSON 错误体。前端通过读取响应体的 `code` 字段进行客户端侧的错误分支处理。

## 2. 关键文件与分层

- **领域层错误**：
  - `src/main/java/ai/cc/chongming/review/domain/exception/ReviewDomainException` / `RequirementDomainException`：携带稳定枚举 `ReviewErrorCode` / `RequirementErrorCode` 的运行时异常，用于在应用服务中表达领域不变量失败（如状态迁移非法、版本冲突、角色未授权等）。
  - `src/main/java/ai/cc/chongming/review/domain/exception/ReviewErrorCode.java`、`RequirementErrorCode.java`：集中定义领域错误码枚举，是跨 API、Agent Tool 和持久化适配器的稳定契约。

- **应用层错误**：
  - `src/main/java/ai/cc/chongming/review/application/RequirementReviewLaunchException`：编排启动流程的专用异常，除 code/status/message 外还携带 `phase`（INTAKE/BOUND）、`recoverable` 布尔以及可选的 `existingReviewId`，由 `RequirementReviewLaunchExceptionHandler` 直接映射到 `ProblemDetail` 并透传到前端。
  - `ReviewIntakeException`：Markdown 受理阶段抛出的应用级异常，被 `ReviewIntakeExceptionHandler` 捕获后输出带 `code` 的 4xx/5xx 响应。

- **API 层异常处理器（按控制器分组）**：
  - `HumanReviewExceptionHandler`：覆盖 `HumanReviewController`、`HumanGateDecisionController`，将 `NoSuchElementException`→404/HUMAN_REVIEW_NOT_FOUND、`IllegalStateException`→409/HUMAN_REVIEW_CONFLICT、`IllegalArgumentException`→422/INVALID_HUMAN_REVIEW_DRAFT、`SecurityException`→403/HUMAN_REVIEW_FORBIDDEN。
  - `RequirementExceptionHandler`：覆盖需求命令/查询控制器，将 `RequirementDomainException` 按 `REQUIREMENT_NOT_FOUND` 区分 404/409，其余默认 409；`IllegalArgumentException`→400/INVALID_REQUIREMENT_REQUEST。
  - `ReviewLifecycleCommandExceptionHandler`：覆盖评审生命周期命令控制器，将 `ReviewDomainException` 中的 `VERSION_CONFLICT` 映射为 409，其他为 422；聚合 `IllegalArgumentException`、`ConstraintViolationException`、`MethodArgumentNotValidException` 为 400/INVALID_REVIEW_COMMAND；兜底 `Exception`→500/COMMAND_UNEXPECTED_FAILURE。
  - `ReviewIntakeExceptionHandler`：覆盖 Markdown 受理入口，统一处理 `ReviewIntakeException`、缺失 multipart/参数、`MaxUploadSizeExceededException`→413/PAYLOAD_TOO_LARGE，以及兜底 `Exception`→500/INTAKE_UNEXPECTED_FAILURE。
  - `NotificationOutboxExceptionHandler`：通知出站箱控制器，遵循与 Human Review 相同的 404/409/403 模式。
  - `RequirementReviewLaunchExceptionHandler`：覆盖需求评审启动编排，透传 `RequirementReviewLaunchException` 的 phase/recoverable/existingReviewId 属性，并统一处理缺失 header/参数/multipart/大小超限。

## 3. 架构约定与设计决策

- **每类控制器一个 `@RestControllerAdvice`**：错误处理按业务域拆分而非全局单一处理器，便于每个 API 面维护独立的错误码表与标题（如 “Review command rejected”、“Requirement review launch rejected”）。
- **领域异常不泄露实现细节**：`ReviewDomainException`/`RequirementDomainException` 仅暴露 `errorCode()` 枚举名，HTTP 状态码由对应 Handler 根据枚举值决定（例如 `VERSION_CONFLICT`→409），避免在领域层耦合 HTTP 语义。
- **应用编排异常自带恢复语义**：`RequirementReviewLaunchException` 通过 `recoverable` 字段显式告知调用方该错误是否可重试（如 `REVIEW_LAUNCH_IN_PROGRESS` 标记为可恢复），并由 Handler 以 `problem.setProperty("recoverable", ...)` 写入响应体。
- **Spring 标准异常统一收敛**：`MissingServletRequestPartException`、`MissingServletRequestParameterException`、`MissingRequestHeaderException`、`MaxUploadSizeExceededException`、`MethodArgumentNotValidException`、`ConstraintViolationException` 等框架异常全部被映射为带稳定 `code` 的 `ProblemDetail`，保证外部契约稳定。
- **兜底异常保护**：每个 Handler 都提供 `@ExceptionHandler(Exception.class)` 或同类兜底方法，将未知异常转换为 500 + 固定 code（如 COMMAND_UNEXPECTED_FAILURE、INTAKE_UNEXPECTED_FAILURE），防止内部堆栈泄漏给前端。

## 4. 约束与规则

- 所有对外 API 错误响应必须使用 RFC 9457 `ProblemDetail` 结构，且必须包含 `code` 属性（由每个 handler 的私有 `problem(status, code, message)` 方法强制注入）。
- 领域层禁止直接使用 HTTP 状态码或抛出通用 `RuntimeException`；必须通过 `ReviewDomainException`/`RequirementDomainException` 配合 `ReviewErrorCode`/`RequirementErrorCode` 表达。
- 新增控制器若涉及错误映射，需新增对应的 `*ExceptionHandler` 并在 `assignableTypes` 中限定作用范围，不得复用无关处理器。
- 编排类异常（如 `RequirementReviewLaunchException`）必须通过静态工厂方法构造，确保 `phase`、`recoverable`、`existingReviewId` 等属性在编译期受控。
- 前端消费端应依据响应体中的 `code` 字段做分支判断，而不是依赖 HTTP 状态码或消息文本变化。

## 5. 前端侧

前端通过 Vue 组件与服务层（如 `services/ag-ui-runtime-sse.js`、`services/review-sse.js`、`stores/review-store.js` 等）订阅后端 SSE 事件与 REST 响应；当后端返回上述 `ProblemDetail` 时，前端依据 `code` 字段展示用户可读提示或触发重试逻辑（例如对 `recoverable=true` 的启动冲突进行幂等重试）。