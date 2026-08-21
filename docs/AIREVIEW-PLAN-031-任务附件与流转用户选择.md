# AIREVIEW-PLAN-031 任务附件与流转用户选择

> **状态**: ✅ 全部完成
> **创建日期**: 2026-08-20
> **目标**: 为开发任务补齐交付附件上传/下载能力，并把流转对象从手输用户名改为读取非管理员用户列表。

---

## 背景

任务详情页（PLAN-026/030 交付）目前只有状态流转命令，缺少交付物载体：后端开发完成后无法上传设计文档、补丁、自测报告等文件，下一负责人与验收人看不到交付证据。同时「流转给下一负责人」使用自由文本输入用户名，容易输错且不符合"从用户目录选择"的常规流程；管理员专用目录 `GET /api/users`（PLAN-027）不应对普通持有人开放，因此需要一个受限的可流转用户端点。

## 分段方案

### 0. 数据模型与持久化

- 新建迁移 `V28__create_dev_task_attachment_table.sql`：`dev_task_attachment` 表，`content LONGBLOB` 存文件字节，`task_id` 外键级联删除，索引覆盖按任务列表。
- 新建领域记录 `TaskAttachment`（attachmentId/taskId/fileName/contentType/fileSize/uploadedBy/createdAt）与仓储接口 `TaskAttachmentStore`（save/findByTask/find/findContent/delete）。
- 双实现：`MyBatisTaskAttachmentStore`（`review.persistence.enabled=true`）与 `InMemoryTaskAttachmentStore`（默认），沿用 UTC 墙钟列约定写入 `created_at`。

### 1. 应用服务与 REST 端点

- `DevTaskAttachmentService`：上传（持有人或 ADMIN，状态限 DEVELOPING/PAUSED/PENDING_ACCEPTANCE，单文件 ≤20MB）、列表、下载、删除（上传人或 ADMIN）。
- `TaskAttachmentController`（`/api/tasks/{taskId}/attachments[...]`）：POST multipart `file`、GET 列表、GET 下载（Content-Disposition attachment）、DELETE。
- `DevTaskController` 新增 `GET /api/tasks/assignable-users`：任意已登录用户可读，仅返回 `role != ADMIN` 的账号（username + displayName），供流转下拉使用。
- `DevTaskController.handoff` 增加目标用户存在性校验（用户目录可用时），不存在返回 404 `USER_NOT_FOUND`。
- `TaskErrorCode` 增加 `ATTACHMENT_NOT_FOUND`、`ATTACHMENT_TOO_LARGE`。

### 2. 前端任务详情页

- `task-api.js`：新增 `listAssignableUsers / listAttachments / uploadAttachment / downloadAttachment / deleteAttachment`。
- `TaskDetailView.vue`：
  - 「流转给」改为下拉框，选项来自 assignable-users（客户端再排除当前持有人），保留说明输入；
  - 新增「任务附件」卡片：附件列表（文件名/大小/上传人/时间/下载/删除）+ 上传入口（持有人或 ADMIN 且非终态可见），下载走 Blob + 对象 URL。

### 3. 测试与收尾

- 后端：`InMemoryTaskAttachmentStoreTests`、`TaskAttachmentControllerTests`（standalone MockMvc，覆盖角色门禁、大小限制、下载头）、`DevTaskControllerTests` 增补 assignable-users 与 handoff 校验用例。
- 前端：`task-flow.e2e.js` 增补流转下拉与附件上传/下载用例；`npm run build` 同步静态产物。

## 文件清单

| 文件 | 计划段 | 状态 |
|------|--------|------|
| `src/main/resources/db/migration/V28__create_dev_task_attachment_table.sql` | #0 | ✅ |
| `src/main/java/ai/cc/chongming/task/domain/model/TaskAttachment.java` | #0 | ✅ |
| `src/main/java/ai/cc/chongming/task/domain/repository/TaskAttachmentStore.java` | #0 | ✅ |
| `src/main/java/ai/cc/chongming/task/infrastructure/MyBatisTaskAttachmentStore.java` | #0 | ✅ |
| `src/main/java/ai/cc/chongming/task/infrastructure/InMemoryTaskAttachmentStore.java` | #0 | ✅ |
| `src/main/java/ai/cc/chongming/task/infrastructure/TaskAttachmentMapper.java` | #0 | ✅ |
| `src/main/java/ai/cc/chongming/task/application/DevTaskAttachmentService.java` | #1 | ✅ |
| `src/main/java/ai/cc/chongming/task/api/TaskAttachmentController.java` | #1 | ✅ |
| `src/main/java/ai/cc/chongming/task/api/DevTaskController.java` | #1 | ✅ |
| `src/main/java/ai/cc/chongming/task/domain/exception/TaskErrorCode.java` | #1 | ✅ |
| `src/main/java/ai/cc/chongming/task/api/DevTaskExceptionHandler.java` | #1 | ✅ |
| `frontend/src/api/review-api.js` | #2 | ✅ |
| `frontend/src/api/task-api.js` | #2 | ✅ |
| `frontend/src/views/TaskDetailView.vue` | #2 | ✅ |
| `src/test/java/ai/cc/chongming/task/infrastructure/InMemoryTaskAttachmentStoreTests.java` | #3 | ✅ |
| `src/test/java/ai/cc/chongming/task/api/TaskAttachmentControllerTests.java` | #3 | ✅ |
| `src/test/java/ai/cc/chongming/task/api/DevTaskControllerTests.java` | #3 | ✅ |
| `frontend/tests/task-flow.e2e.js` | #3 | ✅ |

## 实施顺序

1. **步骤 0** → 迁移 + 领域 + 仓储双实现
2. **步骤 1** → 应用服务 + 控制器 + 错误码（依赖步骤 0）
3. **步骤 2** → 前端 API 与详情页（依赖步骤 1）
4. **步骤 3** → 测试、构建、文档收尾（依赖步骤 1/2）

## 风险与应对

- 附件存 MySQL LONGBLOB 会放大数据库体积：限制单文件 20MB，仅任务相关小文件场景，后续可换对象存储。
- `assignable-users` 暴露非管理员账号目录：仅含 username/displayName，且为登录态必需的流程数据，风险可控。

## 变更记录

- 2026-08-20 创建计划并开始实施。
- 2026-08-20 全部段完成：后端 26 个定向测试与全量测试通过，前端 18 个单测文件与 32 个 e2e 通过，静态产物已重新构建。偏差：handoff 未知目标使用新增 `USER_NOT_FOUND`（404）而非复用 `TASK_NOT_FOUND`。
- 2026-08-21 修正：`TaskAttachmentMapper` 最终放置在 `ai.cc.chongming.review.infrastructure.persistence.mapper`（`@MapperScan` 扫描包，与 `DevTaskMapper` 同包）；初版误放 `task.infrastructure` 会导致 MySQL 模式启动时 Bean 缺失。迁移脚本无需手动执行：`review.persistence.enabled=true` 时 `reviewFlyway` Bean 启动自动 `flyway.migrate()`。
- 2026-08-21 修正：补充 `spring.servlet.multipart` 配置（20MB/25MB）。此前未配置，Spring 默认单文件 1MB，交付文件超 1MB 被 413 拒绝、业务层 20MB 校验形同虚设，导致上传全部失败、`dev_task_attachment` 为空；前端对 413 给出明确超限提示。
- 2026-08-21 修正：下载 500。MyBatis `@Select` 直接返回裸 `byte[]` 会被误路由到 `ByteTypeHandler`，对 LONGBLOB 调 `getByte` 抛 `NumberOutOfRange`（Spring 译为 `DataIntegrityViolationException`）。改为返回行记录 `findWithContent` 并在 store 取 `.content()`，走列元数据的 `ByteArrayTypeHandler`。
