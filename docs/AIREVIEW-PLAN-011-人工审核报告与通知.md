# 人工审核、报告与通知计划

> **状态**: 🟡 核心链路已完成；生产持久化与外部 MCP 联调待完成
> **创建日期**: 2026-07-14
> **目标**: 提供人工审核草稿 CRUD、不可变版本化决定、可追溯报告和幂等学习通通知闭环。
> **前置计划**: PLAN-009、PLAN-010

## 0. 背景与边界

AI 只能生成 Gate 草案。人工通过页面对审核条目增删改查，最终提交后生成不可原地修改的 Gate 版本。学习通通知 MCP 已由其他
Agent 验证，
本计划复用既有 Schema、鉴权和幂等约定，并补项目级合约测试。身份认证和 P1 默认策略未最终确认，必须保留发布门禁。

## 1. 分段方案

### 1.1 人工审核草稿模型 ⏳

- 审核条目字段：itemId、type、severity、title、content、claim/evidence 引用、action、version。
- 仅 WAITING_HUMAN 阶段允许新增、编辑、删除草稿；查询支持状态和严重度过滤。
- 每次 CRUD 写 audit_event，删除采用草稿状态删除，不删除审计记录。

### 1.2 草稿 CRUD API ⏳

- GET/POST `/api/reviews/{id}/human-review-items`，PATCH/DELETE `/api/reviews/{id}/human-review-items/{itemId}`。
- expectedVersion 防并发覆盖；错误返回 404、409、422。
- ReviewerIdentity 由可替换接口提供；未接认证时只允许本地 Demo profile。

### 1.3 最终人工决定 ⏳

- 决定：PASS、CONDITIONAL、BLOCK、RETURN、OVERRIDE；包含 reason、conditions、overrideReason。
- 提交校验全部草稿、当前 Gate 草案和 expectedVersion，生成新 gate_decision version。
- 已提交版本只读；调整必须创建新版本，并保留 supersedesVersion。

### 1.4 报告生成 ⏳

- 结构化 JSON 与 Markdown 同步生成，包含 Plan、角色、Claim、Evidence、Debate、Judge、Gate、人工决定。
- 每个结论可反向链接到单行证据；不展示隐藏思维链。
- 报告生成失败不回滚人工决定，可重试并保留版本。
- 提供 `GET /api/reviews/{id}/report`、`GET /api/reviews/{id}/report/versions` 和 `GET /api/reviews/{id}/report?format=markdown`；使用 golden-file 固定
  Markdown 格式。

### 1.5 Notification Outbox ⏳

- 人工最终状态提交后同事务写 outbox，幂等键含 reviewId、gateVersion、channel。
- worker 异步发送，记录 requestHash、responseCode、attempt、nextRetryAt。
- 通知失败不回滚 Gate；支持人工重试但幂等键不变。

### 1.6 学习通 MCP Adapter ⏳

- 将领域通知命令映射为既有 MCP Schema，不把数据库实体直接暴露给 MCP。
- 先把其他 Agent 已验证的 Schema、鉴权字段、幂等规则和错误样本沉淀为仓库内契约文档与 fixture，不在实施时重新猜测。
- 鉴权从安全配置注入；日志脱敏；超时、错误码、重复请求统一转换。
- 使用 Mock MCP 做合约回归，再在测试环境做一次受控冒烟。

### 1.7 审计与权限门禁 ⏳

- 审核人认证、role 权限、override 权限和 P1 默认 Gate 未配置时，生产 profile 禁止提交最终决定。
- 记录 actor、时间、前后版本、理由、IP/traceId（按可用范围），不可修改。
- 查询报告与人工操作也记录必要审计，不记录敏感正文到普通日志。

## 2. 文件清单

### 2.1 新建

| 文件                                                                                                 | 计划段       | 状态 |
|----------------------------------------------------------------------------------------------------|-----------|----|
| `src/main/java/ai/cc/chongming/review/domain/model/HumanReviewItem.java`                           | #1.1      | ⏳  |
| `src/main/java/ai/cc/chongming/review/application/HumanReviewService.java`                         | #1.1-1.3  | ⏳  |
| `src/main/java/ai/cc/chongming/review/api/HumanReviewController.java`                              | #1.2-1.3  | ⏳  |
| `src/main/java/ai/cc/chongming/review/domain/security/ReviewerIdentityProvider.java`               | #1.2、#1.7 | ⏳  |
| `src/main/java/ai/cc/chongming/review/application/ReviewReportService.java`                        | #1.4      | ⏳  |
| `src/main/java/ai/cc/chongming/review/application/NotificationOutboxService.java`                  | #1.5      | ⏳  |
| `src/main/java/ai/cc/chongming/review/infrastructure/notification/LearningPlatformMcpAdapter.java` | #1.6      | ⏳  |
| `src/main/java/ai/cc/chongming/review/infrastructure/notification/NotificationOutboxWorker.java`   | #1.5-1.6  | ⏳  |
| `docs/集成/学习通通知MCP契约.md`                                                                            | #1.6      | ⏳  |
| `src/test/resources/contracts/learning-platform-notification.json`                                 | #1.6      | ⏳  |
| `src/test/java/ai/cc/chongming/review/human/HumanReviewServiceTests.java`                           | #1.1-1.3  | ⏳  |
| `src/test/java/ai/cc/chongming/review/api/HumanReviewControllerTests.java`                          | #1.2-1.3  | ⏳  |
| `src/test/java/ai/cc/chongming/review/report/ReviewReportIntegrationTests.java`                     | #1.4      | ⏳  |
| `src/test/resources/golden/review-report.md`                                                       | #1.4      | ⏳  |
| `src/test/java/ai/cc/chongming/review/notification/NotificationOutboxIntegrationTests.java`         | #1.5      | ⏳  |
| `src/test/java/ai/cc/chongming/review/notification/LearningPlatformMcpContractTests.java`           | #1.6      | ⏳  |

### 2.2 修改

| 文件                                                                 | 计划段       | 状态 |
|--------------------------------------------------------------------|-----------|----|
| `src/main/resources/application.yml`                               | #1.5-1.7  | ⏳  |
| `src/main/java/ai/cc/chongming/review/domain/gate/GatePolicy.java` | #1.3、#1.7 | ⏳  |

## 3. 实施顺序

1. **步骤 1**：先写草稿 CRUD、版本冲突和阶段限制测试。
2. **步骤 2**：实现最终决定版本化和生产权限门禁。
3. **步骤 3**：实现报告生成与可追溯链接。
4. **步骤 4**：实现 Outbox 状态机和幂等重试。
5. **步骤 5**：接入 Mock/测试环境 MCP 并归档合约结果。

## 4. 验证与退出标准

- 草稿可 CRUD；已提交决定无法 PATCH/DELETE，只能新建版本。
- overrideReason 缺失、版本过期、无权限、错误阶段均拒绝并审计。
- 报告中每个 P0/P1 和 Gate 条件可回溯到 Evidence。
- 同一 gateVersion 重试通知不会产生重复业务通知。
- MCP 失败不改变 Gate，Outbox 最终成功或进入 DEAD 并可人工处理。

## 5. 风险与应对

| 风险           | 应对                                   |
|--------------|--------------------------------------|
| 身份体系未确定      | ReviewerIdentityProvider 隔离，生产提交默认关闭 |
| 通知 Schema 漂移 | 合约夹具固定已验证版本，Adapter 内版本转换            |
| 人工 CRUD 破坏审计 | 草稿和已提交版本分离，所有写操作追加 audit_event       |

## 6. 变更记录

| 日期         | 变更                                     |
|------------|----------------------------------------|
| 2026-07-14 | 创建人工草稿、版本化 Gate、报告、Outbox 和学习通 MCP 计划。 |
| 2026-07-15 | 人工审核阶段名称对齐技术方案：WAITING_HUMAN。 |
| 2026-07-15 | 人工审核与报告 API 对齐技术方案：统一使用 `/api/reviews/{id}/...` 路径。 |

## 7. 当前实施状态（2026-07-16）

### 已完成

- 人工审核条目实现了 `WAITING_HUMAN` 阶段限制、版本冲突控制、软删除、审计事件和本地 Demo 审核人边界。
- 最终 Gate 支持 PASS、CONDITIONAL、BLOCK、RETURN、OVERRIDE；旧版本不可修改，`NOTIFYING` 阶段的调整会创建带 `supersedesVersion` 的新版本并推进 Review 乐观锁版本。
- `GET/POST /api/reviews/{id}/report`、`GET /api/reviews/{id}/report/versions` 与 Markdown 格式已实现；报告只含公开 Claim/Turn/Judge 摘要，并提供证据回链。Markdown 由 golden 文件固定。
- 最终 Gate 事件会自动创建报告与通知 Outbox；Outbox 以 `reviewId:gateVersion:channel` 去重，支持 PENDING/FAILED/SENT/DEAD、指数退避、人工重试、发送结果哈希和通知事件。
- `GET /api/reviews/{id}/notifications` 与受 `ReviewerIdentityProvider` 控制的 `POST /api/reviews/{id}/notifications/{notificationId}/retry` 已提供。
- 新增 V7 迁移，为版本化报告与 Outbox 的幂等/请求哈希/响应结果字段预留数据库结构；通知 worker 默认关闭。

### 尚未完成 / 发布门禁

1. **MyBatis 事务化持久化**：当前人工审核、报告和 Outbox 仍使用进程内 Store。V7 只准备了表结构；需要补齐 Mapper、`NotificationOutboxStore` 的数据库实现，并让最终 Gate 与 Outbox 在同一数据库事务提交。
2. **学习通 MCP 真实联调**：仓库内缺少已验证的工具名、Schema、鉴权与错误码样例。适配器默认 fail-closed，契约交接清单见 `docs/集成/学习通通知MCP契约.md`；在收到权威材料前不得启用 `review.notification.mcp-enabled` 或 worker。
3. **生产身份与审计扩展**：生产 profile 已默认禁止审核/重试，但真实认证、角色映射、IP、traceId 与访问审计需在 PLAN-013 中接入。

### 本轮验证

- `mvn -Dtest=HumanGateDecisionServiceTests,NotificationOutboxServiceTests,ReviewReportServiceTests,ChongmingApplicationTests test`：10 tests passed。
- `mvn -Dtest=NotificationOutboxControllerTests test`：2 tests passed。
