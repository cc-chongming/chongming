# 领域事件、SSE 与恢复计划

> **状态**: 🟡 核心能力与进程内 HTTP 命令接口已完成；持久化写模型和多实例恢复仍待接入
> **创建日期**: 2026-07-14
> **目标**: 建立可重放领域事件、Spring MVC SSE、幂等取消/重试和进程恢复能力。
> **前置计划**: PLAN-003、PLAN-004；可与 PLAN-005 至 009 并行

## 0. 背景与边界

前端需要实时看到计划、角色、论点和辩论过程，但 AgentScope 原始事件不能直接成为业务事实。本计划建立项目领域事件总线和持久化
sequence，
再使用 `SseEmitter` 提供断线续传；不引入 WebFlux 或消息队列。

## 1. 分段方案

### 1.1 统一事件信封 ✅

字段：eventId、sequence、reviewId、attempt、type、category、stage、actorRole、targetRole、topicId、claimId、turnId、round、progress、occurredAt、payloadVersion、payload。

- type 使用 PLAN_CREATED、ROLE_ACTIVATED 等具体事件名；category 初始冻结为 PLAN、ROLE、CLAIM、EVIDENCE、DEBATE、JUDGEMENT、HUMAN、GATE、NOTIFICATION、ERROR。
- payload 版本化，未知字段向后兼容。

### 1.2 事务事件写入 🟡

- 领域命令成功后同事务写状态和 `review_event`。
- sequence 按 review 全局单调递增，重试 attempt 不重置；`review_id + sequence` 唯一索引处理并发。
- 事件只追加，不提供修改/删除；审计事件独立记录操作者。

### 1.3 读模型与查询 API ✅

- `GET /api/reviews/{id}` 聚合概要、阶段、进度和 Gate 状态。
- `GET /api/reviews/{id}/plans`、`GET /api/reviews/{id}/debates` 和 `GET /api/reviews/{id}/evidence/{evidenceId}` 使用批量 Repository 装配；Evidence API 只接受服务端
  ID，不接受文件路径。
- 所有时间输出 `yyyy-MM-dd HH:mm:ss`，列表分页或基于 sequence 游标。

### 1.4 SSE 会话管理 ✅

- `GET /api/reviews/{id}/events` 接受 `Last-Event-ID` 或 `afterSequence`。
- 采用“注册缓冲订阅者 → 查询并顺序发送历史 → 排空缓冲 → 切换实时”的流程，避免回放/订阅窗口丢事件。
- 心跳、超时、客户端断开和 emitter 清理均有指标。
- 心跳不占用业务 sequence，不写 `review_event`。

### 1.5 AgentScope 原始事件桥接 ✅

- 原始事件经 Adapter 转运行观察事件，可选择不持久化详细 payload。
- DebateTools 成功后才产生正式 `CHALLENGE_SUBMITTED` 等业务事件。
- 去重同一工具调用的开始/结束/业务成功事件，保留 parent/agent 来源。

### 1.6 启动、取消与失败 🟡

- `POST /api/reviews/{id}/start` 使用 `Idempotency-Key`、expectedVersion 和公开计划请求体；从 PENDING 预置到 PLANNING 后异步启动编排，并立即返回 202。
- `POST /api/reviews/{id}/cancel?expectedVersion=...` 幂等；先向运行时发安全停止请求，再写最终状态 CANCELLED。未知的本进程运行时取消为安全空操作。
- 已完成人工决定的 review 不允许取消；取消不删除历史。
- 未捕获异常统一写 REVIEW_FAILED，敏感错误只存内部摘要。

### 1.7 重试与 attempt 隔离 🟡

- `POST /api/reviews/{id}/retry?expectedVersion=...` 仅从终态创建新 attempt；旧 attempt 全部只读，新 attempt 保持 PENDING 直到再次调用 `/start`。
- 输入快照可复用，运行态 session/sequence/idempotency namespace 必须重新生成。
- 重试不得覆盖旧报告、Gate、事件或通知结果。

### 1.8 进程重启恢复 ⏳

- 启动扫描可恢复的非终态 review，使用数据库 lease 防多实例重复恢复。
- 从已提交阶段重新驱动，不重放未提交的内存动作。
- 恢复成功/失败写事件；连续失败转人工而非无限重试。

## 1.9 当前实现与部署边界

- 已完成：版本化事件信封、同一 review 的全局 sequence、默认内存存储，以及在 `review.persistence.enabled=true` 时启用的 MyBatis `review_event` 追加存储；V6 迁移补齐了完整事件列。
- 已完成：`GET /api/reviews/{id}`、`/plans`、`/debates`、`/evidence/{evidenceId}` 查询，所有对外时间统一为 `yyyy-MM-dd HH:mm:ss`；证据详情只接受服务端 ID。
- 已完成：SSE 的“先注册缓冲、历史回放、排空、实时”切换，含超时、心跳、断开清理与轻量计数；心跳不写业务事件。
- 已完成：Claim、DebateTools、Judge/Gate、计划、角色激活和取消/重试仅在命令成功且非幂等重放时发布正式事件；AgentScope 原始事件继续只作为脱敏运行观察。
- 待部署写模型：现有 Claim/Debate 命令的 MyBatis 写入尚未启用，因而“聚合状态与事件同一数据库事务”只能在该写模型接入后完成端到端验证。
- 已完成（进程内命令接口）：`ReviewCommandService` 与 `/start`、`/cancel`、`/retry` 端点复用领域状态机、expectedVersion 和事件发布；启动要求 `Idempotency-Key`，返回 202 后由后台编排，取消先请求运行时安全点。404、409、422 使用稳定 ProblemDetail code。
- 明确边界：接口当前依赖 `InMemoryReviewRegistry`，进程重启后无法定位旧聚合；start 的 userId 仍沿用受理接口的调用方传入约定；状态变更和事件尚不能同 MySQL 事务提交。因此仅适用于本地/演示联调，不能作为多实例生产命令面。
- 未完成：启动扫描、数据库 lease、多实例恢复、恢复失败转人工，以及基于真实 MySQL 的 1 千/1 万事件回放演练。
## 2. 文件清单

### 2.1 新建

| 文件                                                                                   | 计划段      | 状态 |
|--------------------------------------------------------------------------------------|----------|----|
| `src/main/java/ai/cc/chongming/review/domain/event/ReviewEvent.java`                 | #1.1     | ✅  |
| `src/main/java/ai/cc/chongming/review/domain/event/ReviewEventType.java`             | #1.1     | ✅  |
| `src/main/java/ai/cc/chongming/review/application/ReviewEventService.java`           | #1.2     | ✅  |
| `src/main/java/ai/cc/chongming/review/application/ReviewQueryService.java`           | #1.3     | ✅  |
| `src/main/java/ai/cc/chongming/review/api/ReviewQueryController.java`                | #1.3     | ✅  |
| `src/main/java/ai/cc/chongming/review/api/ReviewEventController.java`                | #1.4     | ✅  |
| `src/main/java/ai/cc/chongming/review/application/ReviewSseRegistry.java`            | #1.4     | ✅  |
| `src/main/java/ai/cc/chongming/review/application/ReviewLifecycleService.java`       | #1.6-1.8 | 🟡  |
| `src/main/java/ai/cc/chongming/review/application/ReviewCommandService.java`         | #1.6-1.7 | ✅（进程内） |
| `src/main/java/ai/cc/chongming/review/api/ReviewLifecycleCommandController.java`     | #1.6-1.7 | ✅（进程内） |
| `src/main/java/ai/cc/chongming/review/api/ReviewLifecycleCommandExceptionHandler.java` | #1.6-1.7 | ✅（进程内） |
| `src/test/java/ai/cc/chongming/review/event/ReviewEventStoreIntegrationTests.java`    | #1.1-1.2 | ✅  |
| `src/test/java/ai/cc/chongming/review/api/ReviewQueryControllerTests.java`            | #1.3     | ✅  |
| `src/test/java/ai/cc/chongming/review/sse/ReviewSseReplayIntegrationTests.java`       | #1.4-1.5 | ✅  |
| `src/test/java/ai/cc/chongming/review/lifecycle/ReviewLifecycleIntegrationTests.java` | #1.6-1.8 | ✅  |

### 2.2 修改

| 文件                                                                                      | 计划段       | 状态 |
|-----------------------------------------------------------------------------------------|-----------|----|
| `src/main/java/ai/cc/chongming/review/infrastructure/agentscope/AgentEventAdapter.java` | #1.5      | ⏳  |
| `src/main/java/ai/cc/chongming/review/application/ReviewOrchestrationService.java`      | #1.5-1.7  | ✅  |
| `src/main/java/ai/cc/chongming/review/application/ReviewRuntimeContext.java`           | #1.6       | ✅  |
| `src/main/java/ai/cc/chongming/review/infrastructure/agentscope/AgentScopeReviewRuntimeAdapter.java` | #1.6 | ✅  |
| `src/test/java/ai/cc/chongming/review/application/ReviewCommandServiceTests.java`      | #1.6-1.7  | ✅  |
| `src/test/java/ai/cc/chongming/review/api/ReviewLifecycleCommandControllerTests.java`  | #1.6-1.7  | ✅  |
| `src/main/resources/application.yml`                                                    | #1.4、#1.8 | ✅  |

## 3. 实施顺序

1. **步骤 1**：冻结事件信封和 sequence 并发测试。
2. **步骤 2**：实现事件事务写入与查询读模型。
3. **步骤 3**：实现 SSE 回放/实时切换和断线测试。
4. **步骤 4**：接入 AgentScope 观察事件并做去重。
5. **步骤 5**：实现取消、重试、attempt 隔离和启动恢复。

## 4. 验证与退出标准

- 1 千/1 万条事件回放顺序正确，无重复/缺失。
- 断线后使用 Last-Event-ID 恢复，再接实时流，不丢窗口事件。
- 同一 cancel/retry 重复调用幂等；旧 attempt 不可修改。
- 应用重启后能继续非终态评审，且无重复 Claim/Turn/Event。
- SSE 关闭后 emitter、线程和连接及时释放。

## 5. 风险与应对

| 风险             | 应对                                  |
|----------------|-------------------------------------|
| 回放与实时订阅竞态      | 以 sequence 水位注册并补读第二次差值             |
| payload 演进破坏前端 | envelope/payloadVersion 固定，新增字段向后兼容 |
| 多实例重复恢复        | 数据库 lease + expectedVersion 双重保护    |

## 6. 变更记录

| 日期         | 变更                        |
|------------|---------------------------|
| 2026-07-14 | 创建事件信封、SSE 回放、取消、重试和恢复计划。 |
| 2026-07-15 | sequence 对齐技术方案：重试保留 attempt 字段但不重置同一 review 的事件序号。 |
| 2026-07-15 | 查询 API 对齐技术方案：统一使用 `/api/reviews/{id}/...` 路径并明确证据详情接口。 |
| 2026-07-16 | 实现事件信封、内存/MyBatis 事件存储、查询 API、SSE 回放、正式命令事件和取消/重试服务；明确多实例恢复及 HTTP 写端点依赖持久化写模型，尚未完成。 |
