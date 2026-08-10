# 运行时 Trace 持久化与重启回放计划`

> **状态**: 🚧 实施中（2026-08-06 创建，经用户确认需要持久化回放已完成的评审运行过程后立项；段 1~3 已落地并通过回归，段 4 浏览器验收待用户在本机执行）
> **创建日期**: 2026-08-06
> **目标**: 将当前仅存于进程内存的 AG-UI 运行时 trace 持久化，使 Spring Boot 重启后，已完成评审在 `/live` 观察台仍可完整回放 Scout / Director / 独立审查 / 辩论 / Judge 各阶段的思考、回答、工具入参与结果，不再"只能看到结果、看不到过程"。
> **关联计划**: AIREVIEW-PLAN-017（AG-UI 运行时 trace 与 `/live`）、AIREVIEW-PLAN-020（`/live` 连续对话流收口）、AIREVIEW-PLAN-010（进程重启恢复）

## 0. 背景与问题

2026-08-06 修复"重启后已完成评审观察台报错"时确认：`/live` 观察台依赖两条数据链路，持久化程度不同。

| 链路 | 数据 | 存储 | 重启后 |
|---|---|---|---|
| 领域事实 | 阶段 / 计划 / 辩论 / Claim / Gate / 事件时间线 | 事件溯源（`review_event` 等持久化表） | ✅ 可回放 |
| 运行时 trace | Scout/Director 流式对话、4 角色运行事件、工具入参/结果、"运行调试" | 进程内存（`ReviewRuntimeTraceRegistry`，有界 500 条） | ❌ 丢失 |

结果是：已完成评审在 `/live` 里只能看到领域事实（结论与中间产物），看不到各 Agent 的实际运行过程（思考、工具调用、逐条消息）。本计划补齐运行时 trace 的持久化与重启回放。

## 1. 现状数据流

```text
AgentScopeReviewRuntimeAdapter.emitRawObservation() / publishLifecycle()
   └─> runtimeTraceRegistry.publish(runtimeId, AguiEvent)        [唯一写入口]
         └─> RuntimeTrace.publish():
               sequence.incrementAndGet()                        [内存自增]
               events.add(StampedEvent)                          [内存 buffer ≤500]
               subscriptions 逐个 enqueue                        [实时推送 SSE]

ReviewRuntimeTraceController  GET /api/reviews/{id}/attempts/{n}/runtime/ag-ui
   └─> traceRegistry.subscribe(reviewId, attemptNo, afterSequence)
         └─> RuntimeTrace.subscribe: 重放 events 中 sequence > afterSequence 的历史
              + 此后实时推送

前端 runtime-trace-store / ReviewLiveView
   └─> createAgUiRuntimeSubscription → buildRuntimeConversation → 按角色分组展示
```

关键事实：

1. **写入口唯一**：所有 AG-UI 运行时事件都经 `ReviewRuntimeTraceRegistry.publish(runtimeId, AguiEvent)`，在这里接入持久化即可全覆盖。
2. **runtimeId 稳定且可派生**：`review-{reviewId}-attempt-{attemptNo}`（`ReviewRuntimeContext.runtimeIdFor`），重启后仍可由 reviewId + attemptNo 计算得出，天然适合作为持久化键。
3. **SSE 契约已是游标重放**：`subscribe(afterSequence)` 先补发历史再实时推送，前端无需改动——只要历史能从持久化存储读到，回放即成立。
4. **丢失点唯一**：`ReviewRuntimeTraceRegistry.traces` 是进程内 `ConcurrentHashMap`，进程重启即空。

## 2. 目标与非目标

### 2.1 目标

1. 主评审运行时 trace（`review-{reviewId}-attempt-{attemptNo}`）持久化到数据库，重启后 SSE 端点仍能按 sequence 完整回放。
2. 保持 `/live` 前端契约不变（`runtime-trace-store`、`buildRuntimeConversation`、`ReviewLiveView` 均不改）。
3. 持久化为观测性 best-effort：写库失败只记日志，不阻断评审运行、不阻塞实时 SSE。
4. sequence 重启后单调续接，不重复、不倒退。

### 2.2 非目标

1. 不持久化 Context Scout **预览**辅助 runtime（`ContextScoutPreviewService` 的隔离 runtime，本就有界窗口 + `remove()`，重启后重新生成即可）。
2. 不修改 AG-UI 事件协议、不改变 `/live` 信息架构。
3. 不做全量无限保留：每个 runtime 有保留上限（默认 1000 条，可配置），避免无限增长。
4. 不把运行时过程写入领域事件/评审报告（报告仍只含公开事实）。

## 3. 设计决策

| # | 决策 | 理由 |
|---|---|---|
| D1 | 新增持久化表 `runtime_trace_event`（Flyway V14），按 `(runtime_id, event_sequence)` 主键 | 与 `review_event` 的 `(review_id, event_sequence)` 模式一致；runtimeId 可派生、可跨重启 |
| D2 | runtime_id 用 `ascii` 字符集、VARCHAR(255)，复合键不超过 MySQL 5.6 的 767 字节 InnoDB 限制 | 项目数据库基线是 MySQL 5.6（V9 先例） |
| D3 | 新增 `RuntimeTraceStore` 领域接口 + MyBatis 实现；Registry 持有可选的 store（未启用时退化为纯内存，保持现有行为） | 不破坏本地演示/单测的纯内存模式 |
| D4 | `publish` 时分配 sequence 后**异步**持久化（fire-and-forget，失败仅记日志）；实时推送仍走内存缓冲 | 持久化不能拖慢实时 SSE / 评审链路 |
| D5 | `subscribe` 懒加载恢复：内存无该 runtime 且为 `review-` 前缀时，从 DB 加载 `> afterSequence` 的历史构造内存态，sequence 从 DB `MAX(sequence)` 续接 | 只在需要回放时读库；重启后首次访问自动恢复 |
| D6 | 截断：每个 runtime 写库时删除该 runtime 最旧的超限记录（keep 默认 1000，可配） | 控制数据增长 |
| D7 | 仅持久化 `review-{...}-attempt-{n}` 主 runtime；辅助 runtime（preview）不持久化 | 聚焦用户目标，避免把临时调试数据写入生产表 |

## 4. 数据模型（Flyway V14）

```sql
-- [AIREVIEW-PLAN-022] Runtime trace persistence for restart replay.
CREATE TABLE runtime_trace_event (
    runtime_id     VARCHAR(255) CHARACTER SET ascii NOT NULL,  -- review-{reviewId}-attempt-{attemptNo}
    event_sequence BIGINT NOT NULL,                            -- per-runtime monotonic cursor
    event_id       VARCHAR(255) CHARACTER SET ascii NULL,      -- AG-UI event id, dedupe on replay
    event_type     VARCHAR(64) NOT NULL,                       -- e.g. CUSTOM / AGENT_MESSAGE / TOOL_CALL_BEGIN
    payload_json   LONGTEXT NOT NULL,                          -- Jackson-serialized AguiEvent
    review_id      CHAR(36) NOT NULL,
    attempt_no     INT NOT NULL,
    created_at     DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (runtime_id, event_sequence),
    UNIQUE KEY uk_runtime_trace_event_id (event_id),
    KEY idx_runtime_trace_review (review_id, attempt_no, event_sequence),
    CONSTRAINT fk_runtime_trace_review FOREIGN KEY (review_id) REFERENCES review_request (review_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
```

要点：

- 复合主键 `(runtime_id, event_sequence)`：`runtime_id` 为 255 字节 ascii（255 字节 ≤ 767 字节限制），`event_sequence` 8 字节，复合主键上限 263 字节，MySQL 5.6 安全。
- `event_id` 唯一索引可空：AG-UI 事件多数带稳定 id，用于重放时去重；MySQL 唯一索引允许多个 NULL。
- `review_id` 外键约束与 `review_request` 关联，保证行级归属可审计、可清理。

## 5. 接口与实现

### 5.1 领域接口 `RuntimeTraceStore`

```java
public interface RuntimeTraceStore {
    void append(String runtimeId, long sequence, String eventId, String eventType, String payloadJson,
                ReviewId reviewId, int attemptNo);
    List<RuntimeTraceRow> findAfter(String runtimeId, long afterSequence, int limit);
    long maxSequence(String runtimeId);
    void trim(String runtimeId, int keep);
}
```

`RuntimeTraceRow` 为 `(sequence, eventId, eventType, payloadJson)` 只读记录。实现放 `infrastructure/persistence/repository`（`MyBatisRuntimeTraceStore` + `RuntimeTracePersistenceMapper`），命名与 `MyBatisReviewReportStore` / `MyBatisNotificationOutboxStore` 对齐。

### 5.2 MyBatis 关键 SQL（mapper XML）

```xml
<insert id="append"> INSERT INTO runtime_trace_event
  (runtime_id, event_sequence, event_id, event_type, payload_json, review_id, attempt_no)
  VALUES (#{runtimeId}, #{sequence}, #{eventId}, #{eventType}, #{payloadJson}, #{reviewId}, #{attemptNo})
  ON DUPLICATE KEY UPDATE event_id = VALUES(event_id) </insert>

<select id="findAfter" resultType="..."> SELECT event_sequence, event_id, event_type, payload_json
  FROM runtime_trace_event WHERE runtime_id = #{runtimeId} AND event_sequence > #{afterSequence}
  ORDER BY event_sequence ASC LIMIT #{limit} </select>

<select id="maxSequence" resultType="long"> SELECT COALESCE(MAX(event_sequence), 0)
  FROM runtime_trace_event WHERE runtime_id = #{runtimeId} </select>

<delete id="trim"> DELETE FROM runtime_trace_event WHERE runtime_id = #{runtimeId}
  AND event_sequence <= (SELECT m FROM (SELECT MAX(event_sequence) - #{keep} AS m FROM runtime_trace_event
    WHERE runtime_id = #{runtimeId}) t) </delete>
```

### 5.3 `ReviewRuntimeTraceRegistry` 改造点

1. **构造**：注入 `ObjectMapper` + 可选的 `RuntimeTraceStore` + 新 `ReviewRuntimeTraceProperties`（`runtime-trace.persistence.enabled` 默认 true；`runtime-trace.persistence.max-events` 默认 1000）。
2. **`publish(runtimeId, AguiEvent)`**：现有内存路径不动；增加——
   - 若持久化开启且 `runtimeId.startsWith("review-")`：异步 `store.append(runtimeId, sequence, event.id(), eventTypeName, objectMapper.writeValueAsString(event), reviewId, attemptNo)`，失败仅 `LOGGER.warn`。
   - 由 runtimeId 反推 reviewId/attemptNo：`review-{uuid}-attempt-{n}` 拆分（`ReviewRuntimeContext` 派生方法补充反向解析工具，或 Registry 内部解析）。
3. **`subscribe(runtimeId, afterSequence)`**：`traces.get(runtimeId)` 为空时——
   - 若持久化开启且 `review-` 前缀：从 `store.findAfter(runtimeId, afterSequence, limit)` 构造历史 → 建立 `RuntimeTrace`，`sequence` 初始化为 `store.maxSequence(runtimeId)`，`subscription.pending` 预填历史 → `activate` 后正常 drain。
   - 辅助 runtime 或未开启：维持现状（空 trace）。
4. **`close()` / `@PreDestroy`**：若有未落库的异步写，简单 `flush`（可选：关闭前同步刷一次，MVP 允许少量丢尾）。

### 5.4 配置项（`application.yml`）

```yaml
review:
  runtime-trace:
    persistence:
      enabled: ${RUNTIME_TRACE_PERSISTENCE:true}
      max-events: ${RUNTIME_TRACE_MAX_EVENTS:1000}
```

## 6. 分段实施

| 段 | 内容 | 完成标准 | 状态 |
|---|---|---|---|---|
| 段 1 | V14 迁移 + `RuntimeTraceRow` + `RuntimeTraceStore` 接口 + `MyBatisRuntimeTraceStore` + mapper XML + 集成测试 | `append / findAfter / maxSequence / trim` 通过 MySQL 5.6 兼容断言（参照 V9/V12 先例），JaCoCo 覆盖该 mapper 的 PLAN-022 标记源文件 | ✅ 2026-08-06（mapper 为注解式，无 XML；集成断言已写入 `ReviewPersistenceMigrationIntegrationTests`，需本机 Docker 运行 MySQL 5.6/8.4 验证） |
| 段 2 | Registry 接入持久化（构造注入 + publish 异步写 + subscribe 懒加载恢复 + sequence 续接 + trim），`ReviewRuntimeTraceProperties` | 单元测试：发布 → 新 Registry 实例（模拟重启）→ 完整重放；sequence 从 MAX 续接；截断生效；实时 SSE 不中断 | ✅ 2026-08-06（`ReviewRuntimeTraceRegistryTests` 6 例全绿；额外修正：`publish` 也走 `resolveTrace` 以在重启后续写时从 DB MAX 续接 sequence） |
| 段 3 | 失败降级（写库异常仅记日志、评审/实时流不受影响）+ 辅助 runtime 不持久化的守卫测试 + 全量回归 | api / agentscope / application 相关测试全绿；`ChongmingApplicationTests` 上下文加载通过 | ✅ 2026-08-06（`ReviewAgUiEventJacksonRoundTripTests` 验证 R1 round-trip 成立；写失败不阻塞、辅助 runtime 守卫测试通过；上下文加载通过；agentscope 包 `dispatchesRoleRoundsInParallel` 为 PLAN-020 已知并行 flaky，重跑通过） |
| 段 4 | 浏览器验收：完成一次评审 → 重启服务 → `/live` 完整回放运行过程（阶段流、角色卡运行事件、"运行调试" tab） | 验收记录写入 PLAN-022 §9 与 `docs/验证记录/` | ⏳ 待本机执行 |

## 7. 测试与验收

### 7.1 单元/集成

1. `ReviewRuntimeTraceRegistryTests` 新增：`persistsAndReplaysAfterNewInstance`（模拟重启）、`continuesSequenceAfterRestart`、`trimsOldestBeyondLimit`、`doesNotPersistAuxiliaryRuntime`、`writeFailureDoesNotBlockPublish`。
2. `MyBatisRuntimeTraceStoreTests`：五组 CRUD + MySQL 5.6 复合键/唯一键断言（Testcontainers 或测试库，参照 PLAN-021 已验证模式）。
3. `AguiEvent` Jackson round-trip 测试：任意真实 AG-UI 事件序列化→反序列化后字段一致（先验证，若 Jackson 无法无参重建 sealed record，则改为按 `event.type` 手工反序列化，见风险 R1）。

### 7.2 浏览器验收（段 4）

```text
1. 用测试/真实模型完成一次多角色评审（走完 Scout → Director → 独立审查 → 冲突 → 辩论 → Judge → Gate）。
2. 重启 Spring Boot 服务（模拟进程丢失内存）。
3. 打开 /live 观察台 → 确认：阶段轨道、Scout/Director 流式对话、4 角色运行事件、辩论回合、"运行调试" tab 均能回放；右侧"评审事实"时间线不受影响。
4. 断线重连（reload 页面）不重复事件（event_id 去重生效）。
```

## 8. 风险与缓解

| # | 风险 | 缓解 |
|---|---|---|
| R1 | `AguiEvent` 是 sealed/record，Jackson 反序列化可能无法自动重建 | ✅ 已消除：`AguiEvent` 接口带 `@JsonTypeInfo(NAME, property="type")` + `@JsonSubTypes`，Jackson 可多态重建；`ReviewAgUiEventJacksonRoundTripTests` 对 mapper 产出的全部事件类型验证 round-trip 相等 |
| R2 | 数据增长无限 | D6 截断 + 配置上限；后续可按 review 生命周期清理 |
| R3 | 异步写导致重启瞬间丢尾（最后几笔未落库） | 可接受（观测性 best-effort）；如需更强一致，可在 `@PreDestroy` 同步 flush |
| R4 | 写库加重评审运行延迟 | 异步 fire-and-forget + 独立 executor；写失败不阻塞 |
| R5 | MySQL 5.6 复合键超限 | D2 ascii runtime_id + 复合键长度核算（§4 已核算 263 字节） |

## 9. 变更记录

| 日期 | 变更 |
|---|---|
| 2026-08-06 | 创建计划：基于"重启后已完成评审 `/live` 只能看到结果、看不到过程"的验收结论立项，完成现状数据流、设计决策（新表 + Store 接口 + Registry 接入 + 懒加载恢复 + 截断）、分段实施与验收标准。 |
| 2026-08-06 | 落地段 1~3：新增 `V14__create_runtime_trace_event_table.sql`、`RuntimeTraceStore`（domain/repository）、`RuntimeTracePersistenceMapper`（注解式）、`MyBatisRuntimeTraceStore`（条件注入）、`ReviewRuntimeTraceProperties`；`ReviewRuntimeTraceRegistry` 改造——`publish` 异步持久化（写失败仅记日志）、`subscribe`/`publish` 懒加载从 DB 恢复且 sequence 从 `MAX(sequence)` 续接、按 `max-events` 内存与落库双截断；主 runtime 正则 `review-{uuid}-attempt-{n}` 守卫，排除 `:scout-preview:` 辅助 runtime；application.yml 增加 `review.runtime-trace.persistence.{enabled,max-events}`。测试：`ReviewRuntimeTraceRegistryTests`（模拟重启重放/续接/截断/辅助守卫/写失败不阻塞/deriveEventId）6 例、`ReviewAgUiEventJacksonRoundTripTests` 2 例、`ReviewPersistenceMigrationIntegrationTests` 增加表结构 + mapper CRUD 的 MySQL 5.6 断言（本机 Docker 运行）。回归：application/api/config 全绿，`ChongmingApplicationTests` 上下文加载通过，agentscope 包仅 PLAN-020 已知并行 flaky（重跑通过）；evidence/repository 包 4 例失败均为 VM 无 `dos:` 视图的环境问题。待办：段 4 浏览器验收。 |
