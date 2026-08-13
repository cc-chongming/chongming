# AIREVIEW-PLAN-026：任务流转与派发验收

> **状态**: ✅ 已完成（代码、测试与浏览器真实 E2E 均已收口，见「验证记录」）
> **创建时间**: 2026-08-13
> **目标**: 在 PLAN-021 需求全生命周期平台与 PLAN-025 登录认证的基础上，补齐"评审通过 → 自动建任务 → 管理员指派 → 开发 → 提交验收 → 管理员验收/打回 → 需求 DONE"的研发执行闭环，使最终 Gate 通过的需求自动进入任务中心管理，需求与任务状态在同一事务内保持一致。

## 背景

PLAN-021 已落地需求生命周期 `DRAFT → PENDING_REVIEW → REVIEWING → APPROVED / REJECTED / RETURNED → DEVELOPING → DONE`，但 `APPROVED → DEVELOPING → DONE` 一段仅靠需求详情页的"开始开发 / 标记完成"两个手工按钮驱动，没有任务实体、没有指派与验收概念，也无法回答"这个需求现在谁在开发、开发到什么程度、验收结论是什么"。

本计划新增独立的**开发任务（DevTask）聚合**与**任务中心**界面：

1. 人工最终 Gate 通过（PASS 族）后，**自动为对应需求创建恰好一个 `PENDING_ASSIGN` 任务**，无需人工补录；
2. **管理员指派**任务给平台注册用户，指派即开始需求开发；
3. 任务走四态状态机 `PENDING_ASSIGN → DEVELOPING → PENDING_ACCEPTANCE → DONE`，验收**打回**时退回 `DEVELOPING` 返工；
4. **验收通过驱动需求 DONE**：任务与需求的状态迁移在同一事务内完成，不允许出现"任务已完成但需求还在开发中"的漂移；
5. 任务中心提供全部任务 / 我的任务 / 任务详情三个页面，需求详情页展示关联任务卡片。

### 范围与非目标

**本计划范围**：task 领域包（聚合、状态机、错误码）、事件驱动建任务监听器与对账、任务命令/查询服务、`/api/tasks` REST 契约与 V21 迁移、双实现仓储、`GET /api/users` 用户目录端点、前端任务中心与需求详情页联动。

**非目标**：

- 不引入新的评审 Agent 或评审协议变更，评审内核保持不动；
- 不改造 PLAN-025 的认证体系本身，仅消费其 JWT principal 与用户目录；
- 不做任务拆分、子任务、工时统计与消息通知（见「未来扩展预留」）；
- 不删除需求手工流转端点（见「边界说明」与 PLAN-021 的遗留兼容标注）。

## 技术方案

### 1. task 包四层结构

新增 `ai.cc.chongming.task` 顶级包，与 `review`、`auth` 并列，遵循 api → application → domain → infrastructure 分层，domain 不依赖 infrastructure：

| 层 | 文件 | 职责 |
|---|---|---|
| api | `DevTaskController` | `/api/tasks` 路由；权限判定（读 `AuthJwtFilter` 写入请求属性的 principal，校验 `role == ADMIN`） |
| api | `DevTaskExceptionHandler` | `@RestControllerAdvice(assignableTypes = DevTaskController.class)`，ProblemDetail + `code` + `x-trace-id` 稳定错误契约 |
| application | `DevTaskCommandService` | 指派/提交验收/验收/打回命令编排，同事务联动需求生命周期 |
| application | `DevTaskQueryService` | 列表分页与详情读模型（`TaskPage` / `DevTaskView`） |
| application | `DevTaskProvisioningListener` | Gate 通过自动建任务 + `reconcile()` 对账补偿 |
| domain | `DevTask` | 任务聚合：状态迁移、乐观锁版本、验收备注（≤512 字符）不变量 |
| domain | `DevTaskTypes` | `DevTaskId`（UUID）与 `DevTaskStatus` 四态枚举 |
| domain/protocol | `DevTaskStateMachine` | 表驱动迁移矩阵 |
| domain/exception | `TaskDomainException` / `TaskErrorCode` | 稳定错误码 |
| domain/repository | `DevTaskRepository` | 仓储接口（save / findById / findByRequirementId / findPage / countByStatus） |
| infrastructure | `InMemoryDevTaskRepository` / `MyBatisDevTaskRepository` | 双实现，`review.persistence.enabled` 开关条件装配 |

### 2. DevTaskStateMachine：表驱动四态状态机

`EnumMap<DevTaskStatus, Set<DevTaskStatus>>` 表驱动，非法迁移抛 `ILLEGAL_TASK_TRANSITION`：

```text
PENDING_ASSIGN     → DEVELOPING                       （管理员指派）
DEVELOPING         → PENDING_ACCEPTANCE               （负责人提交验收）
PENDING_ACCEPTANCE → DONE                             （管理员验收通过）
PENDING_ACCEPTANCE → DEVELOPING                       （管理员打回返工）
```

`DONE` 为终态（`isTerminal()`），无任何出边；打回后可再次提交验收，形成验收闭环。

### 3. DevTaskProvisioningListener：零注册事件驱动建任务

- **零注册**：实现既有 `ReviewEventListener` 函数式接口（`onCommitted`），Spring 收集全部 listener bean 即完成接入，评审内核无需任何改动；
- **通过族**：仅消费 `HUMAN_GATE_FINALIZED` 事件，且 payload `result` 属于 `AI_PASS / CONDITIONAL / PASS / OVERRIDE` 四种通过族结果；BLOCK/RETURN 不建任务；
- **幂等唯一键**：先 `findByRequirementId` 探测，再由数据库唯一键 `uk_dev_task_requirement` 兜底"每个需求至多一个任务"；InMemory 实现在 `save` 内复刻同一唯一性检查，两种模式下重复建任务以同样方式失败；
- **try/catch 隔离**：建任务任何异常只记 error 日志并吞掉，绝不让任务派发失败回滚或阻断 Gate 决策本身（与通知 outbox 监听器同一隔离模式）；
- **reconcile 对账**：`reconcile()` 按每页 50 条扫描全部 `APPROVED` 需求，为缺失任务的需求补建 `PENDING_ASSIGN` 任务并返回补建数量，由 `POST /api/tasks/reconcile`（仅 ADMIN）触发，作为监听器失败或历史数据的补偿通道。

### 4. DevTaskCommandService：同事务联动需求生命周期

任务命令不重复实现需求状态机，而是**复用 `RequirementCommandService` 的权威方法**，并在同一 `@Transactional` 内完成：

| 命令 | 任务侧 | 需求侧（同事务） | 前置校验 |
|---|---|---|---|
| `assign` | `PENDING_ASSIGN → DEVELOPING`，写入 assignee/dispatcher | `startDevelopment`（APPROVED → DEVELOPING） | 需求必须仍为 APPROVED，否则 `TASK_REQUIREMENT_STATE_CONFLICT`；负责人必须存在于用户目录 |
| `submitAcceptance` | `DEVELOPING → PENDING_ACCEPTANCE` | 无 | 仅负责人本人可提交（聚合内校验，否则 FORBIDDEN） |
| `accept` | `PENDING_ACCEPTANCE → DONE`，写入验收备注 | `complete`（DEVELOPING → DONE） | 需求必须仍为 DEVELOPING，否则 `TASK_REQUIREMENT_STATE_CONFLICT` |
| `reject` | `PENDING_ACCEPTANCE → DEVELOPING`，写入打回备注 | 无（需求保持 DEVELOPING） | — |

所有命令先执行 `requireExpectedVersion` 乐观锁校验（不匹配抛 `VERSION_CONFLICT`）；MyBatis 仓储的 `UPDATE ... WHERE version = #{expectedVersion}` 影响行数不为 1 时同样抛 `VERSION_CONFLICT`。`UserRepository` 以 `@Autowired(required = false)` 可选注入（保留无用户目录的兼容构造器），目录不可用时指派返回 FORBIDDEN。

### 5. DevTaskMapper：LEFT JOIN 分页与双写仓储

- `DevTaskMapper` 有意放在 `review.infrastructure.persistence.mapper` 包下，以便复用既有 `ReviewPersistenceConfiguration` 的 `@MapperScan` 装配，不改动该接线（见「偏差记录」）；
- 分页 `findPage` / `findById` / `countPage` 均 `LEFT JOIN requirement`，把关联需求标题随每行一次查出（聚合上以 `withRequirementTitle` 做**只读期富化**，不落 `dev_task` 表），避免逐行回查需求；关键词过滤同时匹配任务标题与需求标题；排序 `updated_at DESC, task_id ASC`，`LIMIT #{offset}, #{size}`；
- 列表按 `task_status` / `assignee_username` 条件过滤，另有 `countByStatus` 供状态分布统计；
- **双写仓储与开关**：`MyBatisDevTaskRepository`（`review.persistence.enabled=true`）与 `InMemoryDevTaskRepository`（`havingValue=false, matchIfMissing=true`，默认生效）实现同一 `DevTaskRepository` 接口；MyBatis 实现 insert/update 按 `version == 0` 分流，时间以 UTC `LocalDateTime` 往返。

### 6. 权限与用户目录

- 任务端点权限不走 `ReviewerIdentityProvider`，而是直接读取 `AuthJwtFilter.PRINCIPAL_ATTRIBUTE` 请求属性中的 `AuthPrincipal`：无有效凭据 → FORBIDDEN；指派/验收/打回/对账要求 `role == ADMIN`；提交验收要求登录人即任务负责人（聚合内校验）；
- 新增 `GET /api/users`（`AuthController`，仅 ADMIN）：返回全量账号的**无凭据投影** `[{username, displayName, role}]`，供指派下拉选择用户；非管理员或用户目录不可用均返回 FORBIDDEN。

## 前后端契约

### `/api/tasks` 端点

| 方法与路径 | 权限 | 请求体 / 参数 | 响应 |
|---|---|---|---|
| `GET /api/tasks` | 登录用户 | `status`、`assignee`、`keyword`、`requirementId`（可选，服务端过滤）、`mine`（true 时强制 assignee=当前用户）、`page`（默认 1）、`size`（默认 20，上限 100） | `TaskPage`：`{items: DevTaskView[], page, size, total}` |
| `GET /api/tasks/{taskId}` | 登录用户 | — | `DevTaskView` |
| `POST /api/tasks/{taskId}/assign` | ADMIN | `{assigneeUsername: 非空且 ≤64, expectedVersion}` | 最新 `DevTaskView` |
| `POST /api/tasks/{taskId}/submit-acceptance` | 登录用户（仅负责人） | `{expectedVersion}` | 最新 `DevTaskView` |
| `POST /api/tasks/{taskId}/accept` | ADMIN | `{note?: ≤512, expectedVersion}` | 最新 `DevTaskView` |
| `POST /api/tasks/{taskId}/reject` | ADMIN | `{note?: ≤512, expectedVersion}` | 最新 `DevTaskView` |
| `POST /api/tasks/reconcile` | ADMIN | — | `{created: 补建任务数}` |
| `GET /api/users` | ADMIN | — | `[{username, displayName, role}]` |

`DevTaskView` 字段：`taskId、requirementId、requirementTitle、reviewId、title、status、assigneeUsername、assigneeDisplayName、dispatcherUsername、acceptanceNote、version、createdAt、updatedAt`。

> 契约注记：`GET /api/tasks` 支持可选 `requirementId` 过滤参数（服务端下推：Mapper 动态条件 + InMemory 对偶实现）；非法 UUID 映射 400 `INVALID_TASK_REQUEST`。

### 错误码（ProblemDetail + `code` 属性 + `x-trace-id` 头）

| code | HTTP | 触发场景 |
|---|---|---|
| `FORBIDDEN` | 403 | 未携带有效凭据；非管理员执行管理员操作；非负责人提交验收；用户目录不可用 |
| `TASK_NOT_FOUND` | 404 | 任务不存在；指派的负责人账号不存在；关联需求缺失；详情查询无此任务 |
| `VERSION_CONFLICT` | 409 | `expectedVersion` 与当前版本不符（命令前置校验或持久化乐观锁失败） |
| `ILLEGAL_TASK_TRANSITION` | 409 | 状态机不允许的迁移（如对 DONE 任务再指派） |
| `TASK_REQUIREMENT_STATE_CONFLICT` | 409 | 指派时需求已非 APPROVED / 验收时需求已非 DEVELOPING |
| 需求错误码原样透传 | 409 | 同事务内 `RequirementDomainException`（如需求侧 `VERSION_CONFLICT`、非法生命周期迁移）以原错误码返回 409 |
| `INVALID_TASK_REQUEST` | 400 | 请求体校验失败、不可读、参数类型不匹配或非法参数 |

前端 `task-api.js` 复用 `review-api` 的共享 `request()` 管道，ProblemDetail 解析、`ReviewApiError` 语义与 Bearer 令牌注入保持一致。

## V21 迁移说明

`V21__create_dev_task_table.sql` 新建 `dev_task` 表：

- 主键 `task_id CHAR(36)`；`requirement_id` 非空并外键关联 `requirement`，外键带 `ON DELETE CASCADE`（删除需求时级联清理其开发任务，保持既有“删除需求”契约不因外键而 500），`review_id` 可空（记录产生该任务的评审）；
- **唯一键 `uk_dev_task_requirement (requirement_id)`**：从存储层兜底"每个需求至多一个任务"；
- 辅助索引 `idx_dev_task_assignee_status (assignee_username, task_status)`（我的任务 + 状态过滤）、`idx_dev_task_status_updated (task_status, updated_at)`（列表排序）；
- 时间戳 `DATETIME(3)` 毫秒精度；索引列长度均不超过 utf8mb4 下 MySQL 5.6 的 191 字符限制；全表 `utf8mb4 / utf8mb4_unicode_ci`、InnoDB，延续 PLAN-021 的 5.6 兼容约束（无 JSON 列）。

## 前端

- **任务中心导航**：`App.vue` 侧边栏新增"任务中心"分组——"全部任务"（`/tasks`）与"我的任务"（`/tasks/mine`，列表请求强制 `mine=true`）；路由对 `TaskListView`/`TaskDetailView` 采用懒加载；
- **任务列表**（`TaskListView`）：四态状态过滤 chips（待指派/开发中/待验收/已完成）、任务标题/关联需求/状态/负责人/指派人/更新时间表格；ADMIN 对 `PENDING_ASSIGN` 任务可见"指派"操作，指派面板从 `GET /api/users` 拉取用户下拉（`displayName（username）` 展示、标注管理员），`VERSION_CONFLICT` 时自动刷新列表并提示重新指派；
- **任务详情**（`TaskDetailView`）：展示任务与关联需求信息、验收备注；`DEVELOPING` 状态下负责人可见"提交验收"；`PENDING_ACCEPTANCE` 状态下 ADMIN 可见验收表单（备注 + 通过/打回）；
- **需求详情页联动**（`RequirementDetailView`）：新增"开发任务"卡片，列出关联任务（标题、四态中文标签、负责人、更新时间），点击进入任务详情；**手工按钮隐藏逻辑**——一旦存在关联任务（`hasRelatedTasks`），"开始开发 / 标记完成"手工按钮即隐藏，改为提示"该需求已接入任务流，开发/完成状态由任务验收驱动"，避免任务流与手工流转双写。

## 不变量

1. 每个需求至多一个开发任务（应用层探测 + `uk_dev_task_requirement` 唯一键双保险）。
2. 只有 `HUMAN_GATE_FINALIZED` 且结果为通过族（AI_PASS/CONDITIONAL/PASS/OVERRIDE）才自动建任务。
3. 指派必须满足：任务是 `PENDING_ASSIGN`、需求仍是 `APPROVED`、负责人存在于用户目录，三者缺一即失败且无任何状态残留。
4. 验收通过必须满足：任务是 `PENDING_ACCEPTANCE`、需求仍是 `DEVELOPING`；任务 DONE 与需求 DONE 在同一事务内生效。
5. 只有任务负责人本人可提交验收；只有 ADMIN 可指派、验收、打回与对账。
6. 任何命令携带的 `expectedVersion` 必须与当前版本一致，否则 `VERSION_CONFLICT`。
7. 监听器建任务失败不影响 Gate 决策本身的提交与持久化。

## 边界说明（已知风险与注记）

- **Gate 先 PASS 后 BLOCK 的修订场景**：任务在人工 Gate 通过时即建立。若该需求后续被修订并再次评审且被 BLOCK，**已创建的任务不会被自动撤销或回收**，由管理员人工处理（不打回、不指派或在需求层面处置）。本计划刻意不做自动回收，避免任务状态机与评审重试语义耦合；监听器建任务另加状态守卫：仅当需求当前状态为 APPROVED 或 REVIEWING 时才建任务，REJECTED/RETURNED/DONE/CANCELLED 一律不建（容忍事件乱序与 Gate 改判重放）；
- **监听器失败靠对账补偿**：自动建任务被 try/catch 隔离，极端情况下（存储瞬时故障等）可能漏建；以 `POST /api/tasks/reconcile` 扫描 APPROVED 需求补建作为补偿通道，不引入自动重试或告警体系；
- **InMemory 模式重启丢数据**：默认 `review.persistence.enabled=false` 下任务与需求等全部聚合均为进程内存储，重启即丢失——这与全平台现状一致，不是本计划新增的边界；InMemory 仓储已补齐乐观版本校验（`existing.version()+1 != task.version()` 抛 `VERSION_CONFLICT`）、requirement 唯一性校验（对齐 `uk_dev_task_requirement`）、查重+写入整体加锁与防御性副本；
- **跨聚合无真事务（残余限制）**：`assign`/`accept` 联动需求与任务两个聚合，InMemory 模式下不存在真实事务边界。已通过顺序调整（先执行需求侧迁移，成功后再保存任务）+ 双侧乐观版本校验将不一致窗口压缩到最小；若需求侧迁移抛错（如 `VERSION_CONFLICT`），任务状态保持原样不落库。MySQL 模式下两仓储仍各自提交，极端失败窗口同样靠版本校验与对账兜底，未引入分布式事务；
- **手工生命周期端点服务端屏障**：`start-development`/`complete`/`cancel` 三个手工端点在服务端经 `TaskFlowGuard` 检查：需求存在非 DONE 的关联开发任务时拒绝（409 `REQUIREMENT_HAS_ACTIVE_TASK`），不再仅依赖前端隐藏按钮；`TaskFlowGuard` 经新增 `@Autowired` 构造器注入 `RequirementCommandController`，旧构造器保留（guard 缺省时跳过守卫，既有测试零改动通过）；
- **删除需求级联删除任务**：V21 外键 `fk_dev_task_requirement` 带 `ON DELETE CASCADE`，删除需求时其开发任务由存储层级联清理，删除需求 API 契约不变；
- **负责人展示优先显示名**：任务列表/详情的负责人优先渲染 `assigneeDisplayName`（Mapper 分页/详情 LEFT JOIN `users` 取 `display_name`；InMemory 经可选注入的 `UserRepository` 富化），缺失时回退 `assigneeUsername`；
- **`GET /api/tasks` 支持 requirementId 服务端过滤**：控制器可选参数 → `TaskFilter` → Mapper `<script>` 动态条件（`#{}` 参数化）与 InMemory 对偶实现三处一致；
- **指派目标不存在映射为 `TASK_NOT_FOUND`（404）**：负责人账号缺失复用任务域的 404 错误码，语义上偏向“被引用对象不存在”，前端按错误文本展示，未新增独立错误码；
- **兜底异常处理**：`DevTaskExceptionHandler` 对未预期异常提供 `@ExceptionHandler(Exception.class)` 兜底 → 500 + 固定 `code`（`TASK_UNEXPECTED_FAILURE`）+ `x-trace-id`，避免裸堆栈泄露。

## 未来扩展预留

1. **用户认领**：负责人自助认领 `PENDING_ASSIGN` 任务（当前仅管理员指派）；
2. **任务拆分**：一个需求派生多个子任务（需先解除 `uk_dev_task_requirement` 一对一约束并设计父子任务状态聚合）；
3. **CANCELLED 终态**：需求被取消或 Gate 翻转为 BLOCK 时回收任务；
4. **JWT-backed ReviewerIdentityProvider**：把任务域的 `auth.principal` 直读方式与评审域 `ReviewerIdentityProvider` 统一为同一权限抽象；
5. 负责人 displayName 展示、按需求过滤任务的服务端参数已于评审修复回环落地；剩余：任务变更通知（复用通知 outbox）。

## 验证记录

| 验证项 | 结果 |
|---|---|
| `./mvnw.cmd clean verify` | 566 项测试全部通过（0 failure / 0 error / 0 skipped），BUILD SUCCESS（`verify-backend.log` 留存） |
| 定向测试 | `DevTaskStateMachineTests`、`DevTaskCommandServiceTests`、`DevTaskProvisioningListenerTests`、`DevTaskControllerTests`、`MyBatisDevTaskRepositoryTests`、`UserDirectoryEndpointTests` 全绿 |
| `npm test`（Vitest） | 79 项通过，含 `task-api.test.js` 请求契约测试 |
| `npx playwright test` | 26 项通过，含新增 `task-flow.e2e.js` 6 项（列表与导航、我的任务、指派、提交验收、验收通过、打回） |
| 浏览器真实 E2E | 8 步全通过：① 任务列表待指派 → ② 管理员指派进入开发中 → ③ 需求同步 DEVELOPING → ④ 我的任务视图 → ⑤ 负责人提交验收、管理员打回返工 → ⑥ 重新提交验收并验收通过 → ⑦ 需求同步 DONE；截图留存于 `frontend/test-results/e2e-step*.png` |
| 前端构建 | `npm run build` 产物已同步 `src/main/resources/static/review/`（新增 `TaskListView-*.js/.css`、`TaskDetailView-*.js` 资源） |

## 偏差记录

- 2026-08-13：`DevTaskMapper` 未放入 `task` 包而置于 `review.infrastructure.persistence.mapper`。原因：复用既有 `ReviewPersistenceConfiguration` 的 `@MapperScan` 扫描范围，避免改动评审域持久化接线；影响：仅包位置偏差，接口与条件装配语义不变；替代方案：为 task 域新增独立 MapperScan 配置——拒绝（增加装配面且无收益）。
- 2026-08-13：`DevTaskCommandService` 采用"三参构造器 + `@Autowired(required = false)` 四参构造器"模式可选注入 `UserRepository`。原因：兼容无用户目录的测试夹具与早期调用点，与 PLAN-024 中 `JudgeService` 等既有扩展模式一致；影响：目录缺省时指派以 FORBIDDEN 明确拒绝，生产装配始终注入；替代方案：强制注入——拒绝（破坏既有测试构造路径）。

## 变更日志

| 日期 | 变更 |
|---|---|
| 2026-08-13 | 创建 PLAN-026：基于已落地实现回写目标范围、技术方案、前后端契约、V21 迁移、边界说明与验证记录；同步将 PLAN-021 的 `start-development`/`complete` 手工入口标注为遗留兼容。 |
| 2026-08-13 | 评审修复回环：V21 外键改 `ON DELETE CASCADE`；InMemory 仓储补版本校验/唯一性/加锁/防御性副本；assign/accept 顺序调整（先需求侧迁移后保存任务）；监听器加 APPROVED/REVIEWING 状态守卫；`GET /api/tasks` 支持 `requirementId` 服务端过滤；Bean Validation 生效 + 500 兜底异常；新增 `TaskFlowGuard` 手工端点服务端屏障；`GET /api/tasks/{taskId}` 补鉴权；任务视图增 `assigneeDisplayName`（LEFT JOIN users）；前端修复导航双高亮与负责人显示名。 |

实施过程中任何接口、文件、状态机或验收标准调整，都必须先记录原因、影响与替代方案，再修改对应章节。
