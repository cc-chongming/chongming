# AIREVIEW-PLAN-027：需求可见性与角色权限

> **状态**: ✅ 已完成（实现、测试与 E2E 验收均已通过）
> **创建日期**: 2026-08-17
> **目标**: 在 PLAN-025 登录认证基础上引入正式角色集与需求级授权：注册可选角色、按角色限制需求创建、非管理员仅可见自己创建或被指派开发任务的需求，并保证存量数据与无认证演示场景的兼容。
> **关联计划**: AIREVIEW-PLAN-025（登录认证与登录界面）、AIREVIEW-PLAN-021（需求全生命周期管理平台）、AIREVIEW-PLAN-026（任务流转与派发验收）
> **需求来源**: 2026-08-17 平台多角色使用要求

## 0. 目标与范围

### 0.1 能力声明

1. 定义正式角色集 `ADMIN` / `PRODUCT_MANAGER` / `PROJECT_MANAGER` / `DEVELOPER`；历史 `USER` 与任何未知角色一律按最小权限的 `DEVELOPER` 语义解析。
2. 自助注册可选角色（白名单 `PRODUCT_MANAGER` / `PROJECT_MANAGER` / `DEVELOPER`，缺省 `DEVELOPER`）；`ADMIN` 永不可自注册，白名单外的值按 400 拒绝。
3. 需求创建仅允许 `ADMIN` 与两个经理角色；创建时认证主体用户名写入 `creator_id`。
4. 需求可见性：管理员与无 principal 的演示/测试场景看全量；其余角色仅可见「自己创建的需求 ∪ 自己被指派开发任务所绑定的需求」。分页列表、详情、仪表盘状态计数统一收敛到该可见域。
5. 需求修订/删除按所有权门控：仅创建者或管理员；越权详情访问以 404（`REQUIREMENT_NOT_FOUND`）呈现，不泄露存在性；越权命令以 403（`FORBIDDEN`）呈现。
6. 前端同步：注册页角色选择、路由 `meta.roles` 守卫、需求库/评审列表的创建与删除入口按角色隐藏。

### 0.2 范围

**本计划范围**：`UserRole`、`PrincipalAccessor`、`RequirementVisibilityResolver`、注册角色白名单、需求读写可见性（领域仓储契约 + MyBatis/InMemory 双实现）、需求命令所有权门控、仪表盘计数收敛、Flyway V23 存量归口迁移、前端 `roles.js` 与相关视图/守卫、生产 bundle 同步。

**非目标**：

1. 不引入 RBAC 权限点框架（角色语义集中在 `UserRole` 谓词方法，见 #7）。
2. 不改变评审（Review）域自身的可见性与操作契约。
3. 不修改既有 JWT 结构与签发流程，仅消费既有 `role` claim。

## 1. 后端技术方案

### 1.1 角色模型：`UserRole`

`ai.cc.chongming.auth.domain.UserRole`：

1. 四个正式角色：`ADMIN`、`PRODUCT_MANAGER`、`PROJECT_MANAGER`、`DEVELOPER`。
2. 谓词方法 `canCreateRequirement()`（ADMIN 与两个经理）与 `viewsAllRequirements()`（仅 ADMIN），授权判断集中在此，不散落在控制器。
3. `parse(String)`：`null`、空白、历史 `USER`、未知值一律归约为 `DEVELOPER`，保证权限检查总是落在受支持的最小权限集上。
4. `/api/users`（用户目录）与任务中心的管理员门控由字符串比较 `ADMIN` 改为 `UserRole.parse(...).viewsAllRequirements()`，历史 `USER` 令牌不再可能误判为管理员。

### 1.2 Principal 读取：`PrincipalAccessor`

`ai.cc.chongming.auth.api.PrincipalAccessor` 统一读取 `AuthJwtFilter` 写入请求属性的已验证 principal，返回 `Optional`：

1. 无 principal（未装配认证模块的演示/测试 profile）返回空，调用方保持历史开放行为。
2. `AuthController#/me`（保持 401 契约）与 `DevTaskController`（保持 403 契约）各自把空值翻译为自身的稳定错误码。

### 1.3 注册角色白名单

1. `RegisterRequest` 新增可选 `role`（≤32 字符）；`AuthService.register` 校验：缺省 `DEVELOPER`；仅接受 `PRODUCT_MANAGER` / `PROJECT_MANAGER` / `DEVELOPER`；其他值（含 `ADMIN`）抛 `IllegalArgumentException`，经既有 advice 归一为 400 `INVALID_AUTH_REQUEST`。
2. 新用户落库角色即所选角色，JWT `role` claim 随之携带正式角色。

### 1.4 需求可见性契约

1. `RequirementRepository.RequirementVisibility(viewerUsername, assignedRequirementIds)`：可见谓词 = `creator_id = viewer` 或 `requirement_id ∈ assigned`；`null` 表示全平台可见（管理员/无 principal）。
2. `RequirementFilter` 增加 `visibility` 字段并保留三参历史构造器；`findPage`、`countByStatus(visibility)` 均支持可见域参数。
3. `RequirementQueryService.findById(id, visibility)`：域外需求抛与缺失一致的 `REQUIREMENT_NOT_FOUND`，不泄露存在性。
4. `RequirementVisibilityResolver`（api 层）：无 principal → `null`；`ADMIN` → `null`；其余角色通过 `DevTaskRepository.findRequirementIdsByAssignee(username)` 取被指派任务绑定的需求集合后构造可见域。
5. `RequirementQueryController`、`DashboardController` 注入 resolver，列表/详情/仪表盘状态计数全部按可见域收敛。
6. MyBatis 实现新增 `findPageForViewer` / `countPageForViewer` / `countByStatusForViewer`（动态 SQL，空 assigned 集合退化为仅 creator 谓词）；InMemory 双实现同步过滤。

### 1.5 需求命令门控

`RequirementCommandController`：

1. 创建：有 principal 时要求 `canCreateRequirement()`，否则 403 `FORBIDDEN`（「当前角色无权新建需求」）；principal 用户名作为 `creatorUsername` 传入 `CreateRequirementCommand`，服务层优先使用，缺省回退既有 `IdentityProvider`（演示行为不变）。
2. 修订/删除：`requireOwnership` —— 无 principal 保持开放；`ADMIN` 放行；其余角色必须是创建者，否则 403。越权前经可见域查询，域外需求先以 404 呈现。
3. `RequirementErrorCode` 新增 `FORBIDDEN`，由既有 advice 映射为 HTTP 403。

### 1.6 存量数据归口（Flyway V23）

`V23__requirement_role_visibility.sql`：

1. 备份原始 `creator_id` 到 `requirement_creator_backup_plan027`（`CREATE TABLE IF NOT EXISTS` + `INSERT IGNORE` 保证幂等）。
2. `creator_id` 不在 `users` 表中的历史哨兵值（anonymous/system 等）归口 `admin`，保证可见性谓词不丢数据。
3. 为 `requirement.creator_id` 添加 `idx_requirement_creator` 索引（MySQL 5.6 `ADD KEY` 不支持 `IF NOT EXISTS`，Flyway 每版本仅执行一次故直接添加）。

## 2. 前端技术方案

### 2.1 roles.js：角色语义单一来源

`frontend/src/services/roles.js`：`ROLE_LABELS`（含历史 `USER` 回退标签）、`roleLabel()`、`canCreateRequirements()`（仅 ADMIN/PRODUCT_MANAGER/PROJECT_MANAGER）、`REGISTRABLE_ROLES`（三个可注册角色，ADMIN 不可选）、`DEFAULT_REGISTRATION_ROLE=DEVELOPER`。与后端角色集一一对应。

### 2.2 注册与存储

1. `RegisterView` 新增角色下拉（默认开发）；`auth-api.register` 仅在提供时携带 `role` 字段；`auth-store.register` 透传。
2. `auth-store` 新增派生态 `canCreateRequirement`，供各页面统一判断创建权限。

### 2.3 路由与入口

1. `/requirements/create` 声明 `meta.roles: ['ADMIN','PRODUCT_MANAGER','PROJECT_MANAGER']`；`beforeEach` 守卫在令牌校验之后追加角色白名单检查，不符合者重定向回 `/requirements`。
2. `RequirementListView`：新建按钮按 `canCreate` 显示；删除按钮仅对 `ADMIN` 或创建者显示。
3. `ReviewListView`：「发起评审」入口指向需求创建流程，同样按 `canCreate` 隐藏。

### 2.4 生产 bundle

`npm run build` 已同步：新 hash `index-sT7bwxwX.js`、`LoginView-B9xIfP2m.js`、`RegisterView-BTw2XfKm.js`、`TaskListView-CE2E3gHz.js`、`TaskDetailView-CJTHIJWN.js` 等，旧 hash 删除，`static/review/index.html` 一并更新；重复构建 hash 稳定。

## 3. 前后端契约

### 3.1 注册请求（变更点）

`POST /api/auth/register` 请求体新增可选 `role`：`PRODUCT_MANAGER` | `PROJECT_MANAGER` | `DEVELOPER`；缺省 `DEVELOPER`；`ADMIN` 或白名单外值返回 400 `INVALID_AUTH_REQUEST`。

### 3.2 需求域错误码（新增）

| code | HTTP | 触发场景 |
|---|---|---|
| `FORBIDDEN` | 403 | 无创建权限角色尝试新建需求；非创建者/非管理员修订或删除需求 |

越权读取域外需求详情保持 404 `REQUIREMENT_NOT_FOUND`（不泄露存在性）。

### 3.3 可见域语义

| 调用者 | 需求列表/详情/仪表盘计数 |
|---|---|
| 无 principal（演示/测试 profile） | 全量（历史行为不变） |
| `ADMIN` | 全量 |
| `PRODUCT_MANAGER` / `PROJECT_MANAGER` / `DEVELOPER` / 历史 `USER` | 仅自己创建的需求 ∪ 自己被指派任务绑定的需求 |

## 4. 文件清单

### 4.1 新建

| 文件 | 段号 | 状态 |
|---|---|---|
| `docs/AIREVIEW-PLAN-027-需求可见性与角色权限.md` | 本计划 | ✅ |
| `src/main/java/ai/cc/chongming/auth/domain/UserRole.java` | #1 | ✅ |
| `src/main/java/ai/cc/chongming/auth/api/PrincipalAccessor.java` | #1 | ✅ |
| `src/main/java/ai/cc/chongming/review/api/RequirementVisibilityResolver.java` | #1 | ✅ |
| `src/main/resources/db/migration/V23__requirement_role_visibility.sql` | #1 | ✅ |
| `frontend/src/services/roles.js`、`roles.test.js` | #2 | ✅ |
| `src/test/java/ai/cc/chongming/auth/domain/UserRoleTests.java` | #1 | ✅ |
| `src/test/java/ai/cc/chongming/review/api/DashboardControllerTests.java`、`RequirementCommandControllerTests.java`、`RequirementQueryControllerTests.java` | #1 | ✅ |
| `src/test/java/ai/cc/chongming/review/infrastructure/persistence/RequirementCreatorVisibilityMigrationIntegrationTests.java`、`RequirementViewerVisibilityIntegrationTests.java` | #1 | ✅（依赖 Docker/Testcontainers，无 Docker 环境按仓库惯例跳过并记录） |

### 4.2 修改

| 文件 | 段号 | 状态 |
|---|---|---|
| `src/main/java/ai/cc/chongming/auth/api/AuthController.java`（注册 role、/api/users 门控改 UserRole） | #1 | ✅ |
| `src/main/java/ai/cc/chongming/auth/application/AuthService.java`（注册角色白名单） | #1 | ✅ |
| `src/main/java/ai/cc/chongming/review/api/RequirementCommandController.java`（创建/修订/删除门控） | #1 | ✅ |
| `src/main/java/ai/cc/chongming/review/api/RequirementQueryController.java`、`DashboardController.java`（可见域接入） | #1 | ✅ |
| `src/main/java/ai/cc/chongming/review/api/RequirementExceptionHandler.java`（FORBIDDEN → 403） | #1 | ✅ |
| `src/main/java/ai/cc/chongming/review/application/RequirementQueryService.java`、`RequirementCommandService.java`、`DashboardQueryService.java` | #1 | ✅ |
| `src/main/java/ai/cc/chongming/review/domain/exception/RequirementErrorCode.java`、`domain/repository/RequirementRepository.java` | #1 | ✅ |
| `src/main/java/ai/cc/chongming/review/infrastructure/persistence/mapper/RequirementMapper.java`、`repository/MyBatisRequirementRepository.java`、`review/InMemoryRequirementRepository.java` | #1 | ✅ |
| `src/main/java/ai/cc/chongming/task/**`（`findRequirementIdsByAssignee`、管理员门控改 UserRole） | #1 | ✅ |
| `frontend/src/api/auth-api.js`、`stores/auth-store.js`、`router/index.js`、`views/RegisterView.vue`、`RequirementListView.vue`、`ReviewListView.vue` | #2 | ✅ |
| `frontend/src/api/auth-api.test.js`、`stores/auth-store.test.js`、`tests/auth.e2e.js`、`tests/platform-shell.e2e.js` 等既有测试适配 | #2 | ✅ |
| `src/main/resources/static/review/`（新 hash bundle 与 index.html 同步，旧 hash 删除） | #2 | ✅ |

## 5. 已知风险与权衡

| 风险/权衡 | 说明与当前结论 |
|---|---|
| 权限判断基于 JWT `role` claim | 角色变更需重新登录（TTL 12h 内旧令牌沿用旧角色）。当前单应用规模可接受；与 PLAN-025 无令牌黑名单的取舍一致。 |
| 历史 `USER` 账号归约为 DEVELOPER | 语义收紧（无法创建需求）；属预期行为，存量演示账号如需创建权限应重新注册经理角色或由管理员处置。 |
| 存量哨兵 creator 归口 admin | 历史 anonymous/system 需求全部转为 admin 可见；原始值已备份到 `requirement_creator_backup_plan027`，可追溯还原。 |
| 可见域过滤在应用层与 SQL 双实现 | MyBatis 用动态 SQL 谓词下推；InMemory 内存过滤。两者行为由同一组契约测试约束。 |
| 前端按角色隐藏入口仅为体验层 | 真正的授权门控全部在服务端（403/404），前端隐藏不构成安全边界。 |

## 6. 未来扩展预留

1. **RBAC 权限点**：`UserRole` 谓词方法是唯一授权入口，后续可替换为权限点表而无需改动调用方。
2. **管理员代管**：管理员目前仅豁免可见性与删除，如需代编辑可放宽 `requireOwnership`。
3. **角色管理端点**：当前无管理员改角色 API（用户目录只读），需要时补充 `PATCH /api/users/{username}/role` 并配合令牌角色刷新策略。

## 7. 验证记录

1. **后端**：`./mvnw.cmd test` 全量 **646 个用例通过**（0 失败；27 个 Testcontainers MySQL 集成用例因本机无 Docker 按仓库惯例跳过，含 V23 迁移与可见域持久化集成测试，需在具备 Docker 的环境补验）。
2. **前端单测**：`npm test` **109 项全部通过**（含 `roles.test.js` 角色语义、auth-api 注册 role 契约、auth-store `canCreateRequirement` 派生态）。
3. **E2E**：`npx playwright test` **29 项全部通过**（含注册携带角色、DEVELOPER 会话隐藏创建入口并重定向、任务中心 ADMIN 指派流等 PLAN-027 相关用例）。
4. **生产 bundle**：`npm run build` 重复构建 hash 稳定，`static/review/` 与源码一致。

## 8. 边界说明

1. **不依赖 MCP**：角色与可见性全流程在 Spring Boot 进程内闭环。
2. **不依赖外部平台**：无 OAuth/SSO 或外部授权服务，授权语义完全本地。
3. **MySQL 边界**：`V23__requirement_role_visibility.sql` 由 Flyway 自动迁移；持久化集成测试依赖 Docker/Testcontainers，无 Docker 环境按仓库惯例记录跳过而非视为通过。
4. **持久化关闭边界**：`review.persistence.enabled=false` 时使用 InMemory 双实现，可见域语义一致；无认证模块的演示 profile 保持全量开放行为。

## 9. 变更记录

| 日期 | 变更 |
|---|---|
| 2026-08-17 | 创建 PLAN-027：正式角色集与注册角色白名单、需求创建角色门控、creator ∪ 指派任务的可见域（列表/详情/仪表盘）、修订删除所有权门控、V23 存量归口迁移、前端 roles.js/路由守卫/入口隐藏；验证记录：mvn test 646 通过（27 项 Docker 依赖跳过）、npm test 109、playwright 29 全部通过。 |
