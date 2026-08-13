# AIREVIEW-PLAN-025：登录认证与登录界面

> **状态**: ✅ 已完成（实现、测试与真实浏览器验收均已通过）
> **创建日期**: 2026-08-12
> **目标**: 按主界面风格新增登录/注册界面，为平台引入用户名密码 + JWT（HS256）认证；除登录注册页外全部页面受保护，侧栏展示真实用户名，并预置 admin 管理员账号。
> **关联计划**: AIREVIEW-PLAN-012（前端辩论工作台）、AIREVIEW-PLAN-021（需求全生命周期管理平台）、AIREVIEW-PLAN-023（评审入口与公开对话体验收口）
> **需求来源**: 2026-08-12 平台安全基线要求

## 0. 目标与范围

### 0.1 能力声明

1. 新增与平台浅色主界面风格一致的登录页（`/login`）与注册页（`/register`），复用平台品牌标识、表单与按钮语义。
2. 认证采用用户名密码 + JWT（HS256）方案：登录/注册成功签发 Bearer token，后续所有 `/api/**` 请求由服务端过滤器校验。
3. 提供登录、注册、退出登录能力；`/me` 端点返回经服务端验证的当前用户。
4. 侧栏用户区展示真实登录用户名（displayName 优先、回退 username），并提供“退出登录”。
5. 除 `/login`、`/register` 两个公开路由外，全部前端页面受路由守卫保护；未登录访问受保护页面重定向到登录页并保留原始目的地。
6. 预置管理员账号 `admin`（初始密码 `Admin@123`，角色 `ADMIN`），**须登录后尽快修改该初始密码**。

### 0.2 范围

**本计划范围**：`ai.cc.chongming.auth` 认证模块（API/应用/领域/基础设施/配置分层）、Flyway V20 迁移、`application.yml` 认证配置段、前端认证三件套（auth-token/auth-store/auth-api）、路由守卫、API 客户端与 SSE 三服务的令牌接入、App.vue 用户区与 `.auth-page` 样式、生产 bundle 同步。

**非目标**：

1. 不引入 spring-security（理由见 #1.2）。
2. 不实现服务端会话黑名单、refresh token、RBAC 权限点控制（见 #7 扩展预留）。
3. 不修改评审领域（review 包）的业务逻辑与既有 API 契约，仅在请求入口统一加令牌校验。

## 1. 后端技术方案

### 1.1 模块结构

认证代码独立于 review 领域，位于 `src/main/java/ai/cc/chongming/auth/`，沿用项目四层分层：

| 层 | 文件 | 职责 |
|---|---|---|
| api | `AuthController`、`AuthJwtFilter`、`AuthExceptionHandler` | 登录/注册/`me` 端点、`/api/**` 令牌守卫、ProblemDetail 错误契约 |
| application | `AuthService`、`JwtTokenService`、`PasswordHasher` | 认证命令、JWT 签发与验证、密码哈希 |
| domain | `User`、`UserRepository`、`AuthException`、`AuthErrorCode` | 用户聚合、仓储接口、错误码 |
| infrastructure | `InMemoryUserRepository`、`MyBatisUserRepository` | 两种仓储实现，按持久化开关条件装配 |
| config | `AuthConfiguration`、`AuthProperties` | Bean 装配与 `review.auth.*` 配置绑定 |

MyBatis 映射器 `UserMapper` 位于既有持久化层 `review/infrastructure/persistence/mapper/`，与既有 mapper 扫描保持一致。

### 1.2 轻量 JWT 过滤器：为什么不引入 spring-security

实现采用一个 `OncePerRequestFilter` 子类 `AuthJwtFilter`，而非 spring-security 过滤器链：

1. 平台当前只需要“`/api/**` 需要有效 token”这一条规则，没有会话、CSRF、remember-me、OAuth 等 spring-security 面向的场景；引入整条安全过滤器链会带来大量默认行为与配置面，超出当前需要。
2. 既有评审 API 已经用 ProblemDetail + 领域错误码统一错误契约；轻量过滤器可以在短路时输出**同一契约**（`code` + `x-trace-id` 扩展属性与响应头），避免 spring-security 默认 401 响应体与前端解析逻辑分裂。
3. 令牌校验本身是无状态的 HS256 验签 + 过期判断（`JwtTokenService.parse`），没有 spring-security 上下文模型可以简化的部分。

过滤器行为（与代码一致）：

1. 豁免范围**仅** `/api/auth/login` 与 `/api/auth/register` 两个凭证端点，以及 `/api/` 前缀之外的请求（静态资源）。**`/api/auth/me` 保持受保护**，因为它依赖过滤器验证后的 principal。
2. 令牌双通道读取：优先 `Authorization: Bearer <token>` 请求头；其次 `access_token` query 参数（浏览器 `EventSource` 无法携带自定义请求头，SSE 依赖该通道）。
3. 校验通过的 principal 写入请求属性 `auth.principal`，供 `/me` 等下游处理器读取；`/me` 在属性缺失时显式抛 `UNAUTHENTICATED`。
4. 校验失败直接短路，返回 401 + `application/problem+json`，携带 `code=UNAUTHENTICATED`、`x-trace-id` 与 `WWW-Authenticate: Bearer`。
5. 过滤器经 `FilterRegistrationBean` 注册到 `/api/*`、仅 `REQUEST` 派发、`order=-100`，并借此阻止 Spring Boot 对 Filter Bean 的二次自动注册。

### 1.3 密码哈希

`PasswordHasher` 使用 `PBKDF2WithHmacSHA256`：

1. 迭代次数下限 **210000 轮**（OWASP 对 PBKDF2-HMAC-SHA256 的推荐值），构造器拒绝更低的迭代数。
2. 每用户 16 字节 `SecureRandom` 随机盐，输出 256 位摘要。
3. 存储格式为可移植字符串 `PBKDF2$iterations$saltBase64$hashBase64`：迁移种子（admin）与运行时注册共用同一校验逻辑；验证时尊重存量哈希自带的迭代数。
4. 校验使用 `MessageDigest.isEqual` 常量时间比较；畸形存储值按“密码错误”处理，不抛异常。

### 1.4 JWT 签发与验证

`JwtTokenService` 基于 **jjwt 0.12.6**（`jjwt-api` 编译期 + `jjwt-impl`/`jjwt-jackson` runtime）：

1. 算法 HS256；签名密钥来自 `review.auth.jwt-secret`，**只允许环境变量注入**。构造器 fail-fast：密钥缺失或 UTF-8 字节数 < 32（HS256 要求 256 位）时拒绝构造（作为启动契约的兜底保障）。
2. Claims：`sub`=username、`role`、`displayName`（可空）、`iat`、`exp`；有效期由 `review.auth.token-ttl`（默认 `PT12H`）决定。
3. 解析失败、过期、缺 subject 一律归一为 `AuthException(UNAUTHENTICATED)`，不向客户端泄露具体失败原因。
4. 支持测试注入 `Clock`，过期场景可确定性验证。

### 1.5 users 表与预置 admin（Flyway V20）

迁移脚本 `src/main/resources/db/migration/V20__create_users_table_and_admin.sql`：

1. `users` 表：`id` 自增主键、`username` 唯一键、`password_hash VARCHAR(255)`、`display_name`、`role`（默认 `USER`，带索引）、创建/更新时间；InnoDB + utf8mb4。
2. 预置一行 `admin`（`role=ADMIN`，显示名“管理员”），初始密码 `Admin@123`；种子哈希由与运行时一致的 `PasswordHasher`（PBKDF2WithHmacSHA256、210000 轮、随机盐）离线生成，脚本注释明确要求尽快修改。

### 1.6 条件装配与 InMemory 兜底

`AuthConfiguration` 与 `AuthController`/`AuthExceptionHandler` 均由 `AuthModuleEnabledCondition`（基于 `AuthStartupContract` 纯函数决策）条件装配，默认启用；启动契约如下：

1. `review.auth.enabled=false`（显式关闭）：整个认证模块不装配。
2. 密钥可用（≥32 字节）：正常装配。
3. 密钥缺失/过短且开关**未被运维显式设为 true**（仅来自打包默认占位）：打 WARN 日志并自动禁用认证模块，应用正常启动（既有未配置 `REVIEW_AUTH_JWT_SECRET` 的环境保持可启动）。
4. 密钥缺失/过短且开关被显式设为 true（环境变量/系统属性/profile 配置等打包默认之外的属性源）：fail-fast 拒绝启动。

用户仓储跟随既有持久化开关：

1. `review.persistence.enabled=true`：装配 `MyBatisUserRepository`（mapper 扫描与数据源此时可用），账号持久化在 `users` 表。
2. `review.persistence.enabled=false` 或未配置：装配 `InMemoryUserRepository` 兜底，登录/注册在无数据库场景（如 test profile）仍可工作；进程内数据重启即失，且**不预置 admin**（admin 种子只存在于 V20 迁移），仅支持自助注册。
3. `application-test.yml` 显式设置持久化关闭与一个仅用于测试上下文的 `jwt-secret`，不出现在任何生产或提交配置中。

### 1.7 认证服务行为

`AuthService`：

1. `login`：未知用户与密码错误共用同一分支与同一消息“用户名或密码错误”（`INVALID_CREDENTIAL`），避免用户名枚举。
2. `register`：用户名 trim 后 1-64 字符、密码 8-128 字符、displayName ≤64 字符；用户名已存在抛 `USERNAME_TAKEN`；新用户角色固定为 `USER`，注册成功即签发令牌。
3. 登录/注册共用成功响应信封 `{token, expiresAt, user{username, displayName, role}}`；`UserView` 投影绝不携带密码哈希。

## 2. 前端技术方案

### 2.1 auth-token.js：共享令牌访问层

`frontend/src/services/auth-token.js` 位于 API 客户端、SSE 服务与 auth store 之下，三者都只依赖它而不互相 import，避免 `review-api ↔ auth-store` 循环依赖：

1. 会话（token + user）持久化在 `localStorage` 键 `chongming-auth`；读写失败均被容忍（降级为仅内存会话）。
2. `decodeJwtPayload`/`isTokenExpired`：本地 base64url 解码 JWT payload 做 `exp` **预判**（不校验签名，签名验证始终是服务端职责）；不可解码的不透明令牌视为交由服务端裁决。
3. `isStoredTokenUsable`：SSE 重连决策探针——无令牌放行（保持匿名兼容行为），令牌存在但已过期则停止重试。
4. `withAuthToken(url)`：为 EventSource URL 拼接 `access_token=<token>`。
5. `redirectToLogin()`：hash 路由友好跳转 `#/login`，供 401 处理器与过期 SSE 复用。

### 2.2 auth-store.js（工厂模式）与 auth-api.js

1. `createAuthStore({ api })` 沿用 review-store 的工厂约定：依赖注入，单测可替换内存 fake；导出共享实例 `authStore`。
2. `restore()` 在创建与每次路由导航时重读持久化会话，本地已过期则直接清除；`isTokenValid()` 提供路由守卫用的本地预判。
3. `login`/`register` 成功后同时写持久化与响应式状态；`logout()` 为**纯客户端清除**（清存储 + 清状态），无服务端注销端点、无令牌黑名单。
4. `auth-api.js` 是 login/register/me 的薄 REST 客户端，复用 `review-api` 的 `request()` 管道，ProblemDetail 解析、`ReviewApiError` 语义与 Bearer 注入完全一致。

### 2.3 路由守卫

`frontend/src/router/index.js`：

1. `/login`、`/register` 为懒加载路由并标记 `meta.public`；其余全部路由默认受保护。
2. `beforeEach` 每次导航先 `authStore.restore()`（丢弃关页签期间过期的令牌）：非公开路由要求 `isTokenValid()` 通过，否则重定向 `/login?redirect=<原始目的地>`；已登录用户访问登录/注册页则重定向回 `/dashboard`。

### 2.4 review-api.js 统一注入与 401 跳转

`request()` 管道：存在会话时统一附加 `Authorization: Bearer <token>`（auth 端点本身无令牌）；收到 HTTP 401 且请求路径**不在** `/api/auth/` 前缀下时，清除会话并 `redirectToLogin()`（登录失败的 401 留在原页展示错误，不触发跳转）。

### 2.5 SSE 三服务接入

`review-sse.js`（领域事件流）、`ag-ui-runtime-sse.js`（AG-UI 运行流）、`scout-preview-sse.js`（Scout 预览流）统一：

1. 建立 `EventSource` 前用 `withAuthToken()` 拼接 `access_token` query 参数。
2. `onerror` 时检查 `isStoredTokenUsable()`：令牌过期则 `close()`、上报 `auth-expired` 状态并 `redirectToLogin()`，**不让 EventSource 默认重连反复撞 401**；其他网络错误维持既有退避重连。

### 2.6 界面与样式

1. 新增 `LoginView.vue`、`RegisterView.vue`；`App.vue` 侧栏用户区展示真实用户：头像取 displayName/username 首字、显示 displayName（回退 username，再回退“未登录”）与 username 副行，“退出登录”按钮调用 `logout()` 后跳转 `/login`。
2. `review.css` 新增 `.auth-page` 一族样式：全屏 grid 居中、平台浅色渐变背景（落色 `#f4f7fb`）、`.auth-card`/`.auth-brand`/`.auth-title`/`.auth-subtitle`/`.auth-actions`/`.auth-switch`（登录注册互相切换链接）、`.auth-flow-shell` 隐藏侧栏布局，与平台浅色变量保持一致。
3. 生产 bundle 已同步：`npm run build` 产出 `LoginView-*.js`、`RegisterView-*.js` 懒加载块与新 `index-*.js/css`，旧 hash 资源删除，`static/review/index.html` 一并更新。

## 3. 前后端契约

### 3.1 端点

| 方法 | 路径 | 认证 | 说明 |
|---|---|---|---|
| POST | `/api/auth/login` | 豁免 | 用户名密码登录，成功签发 token |
| POST | `/api/auth/register` | 豁免 | 自助注册并立即登录 |
| GET | `/api/auth/me` | 受保护 | 返回过滤器已验证的当前用户 |
| 其余 `/api/**` | | 受保护 | Bearer 头或 `access_token` query 双通道 |

### 3.2 请求/响应

登录请求体 `{username, password}`；注册请求体 `{username, password, displayName?}`（Bean Validation：username 1-64、password 8-128、displayName ≤64）。登录与注册共用成功信封：

```json
{
    "token": "eyJhbGciOiJIUzI1NiJ9...",
    "expiresAt": "2026-08-13T02:00:00Z",
    "user": { "username": "admin", "displayName": "管理员", "role": "ADMIN" }
}
```

`/me` 返回 `{username, displayName, role}`。

### 3.3 错误码（ProblemDetail + `code` 扩展属性 + `x-trace-id` 头）

| code | HTTP | 触发场景 |
|---|---|---|
| `INVALID_CREDENTIAL` | 401 | 登录时用户名不存在或密码错误（同一消息，防枚举） |
| `USERNAME_TAKEN` | 409 | 注册时用户名已存在 |
| `UNAUTHENTICATED` | 401 | 受保护请求缺失/无效/过期令牌（过滤器短路或 `/me` 无 principal） |
| `INVALID_AUTH_REQUEST` | 400 | 请求体校验失败或参数非法 |

所有 401 响应附 `WWW-Authenticate: Bearer`；过滤器短路与 controller advice 输出同一契约。

## 4. 配置与密钥管理

`application.yml` 的 `review.auth` 段：

| 配置项 | 环境变量 | 默认值 | 说明 |
|---|---|---|---|
| `review.auth.enabled` | `REVIEW_AUTH_ENABLED` | `true` | 认证模块总开关（启动契约条件装配，缺省启用） |
| `review.auth.jwt-secret` | `REVIEW_AUTH_JWT_SECRET` | 空占位 | HS256 签名密钥，≥32 字节；缺失或过短时：开关未显式置 true 则 WARN 并自动禁用认证模块（正常启动），显式 `enabled=true` 则 fail-fast |
| `review.auth.token-ttl` | `REVIEW_AUTH_TOKEN_TTL` | `PT12H` | 令牌有效期 |

约束：

1. 签名密钥**只经环境变量注入**，默认占位为空；仓库内不出现任何生产密钥，本地调试密钥沿用既有约定留在被 Git 忽略的 `application-local.yml`。
2. `application-test.yml` 中的测试密钥仅服务于无持久化的测试上下文装配，不用于任何生产环境。

## 5. 文件清单

### 5.1 新建

| 文件 | 段号 | 状态 |
|---|---|---|
| `docs/AIREVIEW-PLAN-025-登录认证与登录界面.md` | 本计划 | ✅ |
| `src/main/java/ai/cc/chongming/auth/api/AuthController.java` | #1 | ✅ |
| `src/main/java/ai/cc/chongming/auth/api/AuthJwtFilter.java` | #1 | ✅ |
| `src/main/java/ai/cc/chongming/auth/api/AuthExceptionHandler.java` | #1 | ✅ |
| `src/main/java/ai/cc/chongming/auth/application/AuthService.java` | #1 | ✅ |
| `src/main/java/ai/cc/chongming/auth/application/JwtTokenService.java` | #1 | ✅ |
| `src/main/java/ai/cc/chongming/auth/application/PasswordHasher.java` | #1 | ✅ |
| `src/main/java/ai/cc/chongming/auth/config/AuthConfiguration.java` | #1 | ✅ |
| `src/main/java/ai/cc/chongming/auth/config/AuthProperties.java` | #1 | ✅ |
| `src/main/java/ai/cc/chongming/auth/config/AuthStartupContract.java`、`AuthModuleEnabledCondition.java`（启动契约降级决策与条件装配） | #1 | ✅ |
| `src/main/java/ai/cc/chongming/auth/domain/User.java`、`UserRepository.java`、`AuthException.java`、`AuthErrorCode.java` | #1 | ✅ |
| `src/main/java/ai/cc/chongming/auth/infrastructure/InMemoryUserRepository.java`、`MyBatisUserRepository.java` | #1 | ✅ |
| `src/main/java/ai/cc/chongming/review/infrastructure/persistence/mapper/UserMapper.java` | #1 | ✅ |
| `src/main/resources/db/migration/V20__create_users_table_and_admin.sql` | #1 | ✅ |
| `src/test/java/ai/cc/chongming/auth/**`（AuthControllerTests、AuthJwtFilterTests、AuthServiceTests、JwtTokenServiceTests、PasswordHasherTests、MyBatisUserRepositoryTests、AuthStartupContractTests） | #1 | ✅ |
| `frontend/src/services/auth-token.js` | #2 | ✅ |
| `frontend/src/stores/auth-store.js` | #2 | ✅ |
| `frontend/src/api/auth-api.js` | #2 | ✅ |
| `frontend/src/views/LoginView.vue`、`RegisterView.vue` | #2 | ✅ |
| `frontend/src/api/auth-api.test.js`、`frontend/src/stores/auth-store.test.js` | #2 | ✅ |
| `frontend/tests/auth.e2e.js` | #2 | ✅ |

### 5.2 修改

| 文件 | 段号 | 状态 |
|---|---|---|
| `pom.xml`（新增 jjwt-api/impl/jackson 0.12.6） | #1 | ✅ |
| `src/main/resources/application.yml`（`review.auth` 段） | #4 | ✅ |
| `src/test/resources/application-test.yml`（测试密钥与持久化关闭） | #1 | ✅ |
| `frontend/src/router/index.js`（公开路由 + beforeEach 守卫） | #2 | ✅ |
| `frontend/src/api/review-api.js`（Bearer 注入 + 401 跳转，导出 request 供 auth-api 复用） | #2 | ✅ |
| `frontend/src/services/review-sse.js`、`ag-ui-runtime-sse.js`、`scout-preview-sse.js`（access_token 拼接 + 过期停连） | #2 | ✅ |
| `frontend/src/App.vue`（用户区 + 退出登录） | #2 | ✅ |
| `frontend/src/styles/review.css`（`.auth-page` 一族样式） | #2 | ✅ |
| `frontend/tests/platform-shell.e2e.js`、`review-workbench.e2e.js`（既有 E2E 前置登录适配） | #2 | ✅ |
| `src/main/resources/static/review/`（新 hash bundle 与 index.html 同步，旧 hash 删除） | #2 | ✅ |
| `src/test/java/ai/cc/chongming/review/infrastructure/persistence/ReviewPersistenceMigrationIntegrationTests.java`（V20 迁移纳入持久化集成验证） | #1 | ✅ |

## 6. 已知风险与权衡

| 风险/权衡 | 说明与当前结论 |
|---|---|
| SSE `access_token` query 参数会进入访问日志 | EventSource 无法携带自定义请求头，query 通道是当前唯一可行方案；令牌会出现在反向代理/容器访问日志中。当前演示与内网规模可接受，增强方案（一次性 ticket）见 #7。 |
| token 存 localStorage 依赖无 XSS 前提 | localStorage 可被同源脚本读取，安全边界是页面不存在 XSS；评审前端已禁用 Markdown raw HTML 与危险 URL（PLAN-023），该前提持续成立。若未来引入第三方脚本需重新评估。 |
| 登出为纯客户端清除 | 无服务端注销端点与令牌黑名单，登出后旧令牌在 TTL 内技术上仍有效；当前单应用、短 TTL（12h）场景可接受。 |
| 共享库 10.0.28.99 存在旧草稿 V20 迁移冲突 | 共享 MySQL 库中曾存在同版本号的旧草稿迁移记录，与本计划 `V20__create_users_table_and_admin.sql` 冲突。**接入共享库前必须由负责人处理**：删除旧历史行后重放，或 `flyway repair` 后手工补建 users 表并插入 admin 种子。本地 Docker 容器库不受影响。 |
| InMemory 兜底不预置 admin | 持久化关闭时（如 test profile）无 admin 种子，仅支持自助注册；这是条件装配的明确取舍，生产必须开启持久化。 |
| 登录失败与成功无速率限制 | 当前无防爆破限流；内网演示规模可接受，公网暴露前需补充（见 #7）。 |
| 密钥缺失时认证模块静默降级 | 开关未显式开启而密钥缺失/过短时模块不装配（WARN 日志），部署可能误以为认证生效。缓解：日志明确提示；生产显式设置 `REVIEW_AUTH_ENABLED=true` 即可强制 fail-fast。 |

## 7. 未来扩展预留

1. **RBAC 铺路**：`users.role` 列与 JWT `role` claim 已就位，后续可在过滤器之后按 role 施加权限点，无需改变令牌结构。
2. **refresh token 二期**：当前仅单一 access token + TTL；需要更长会话或静默续期时，再引入 refresh token 与轮换策略。
3. **SSE 一次性 ticket**：以短期单次使用的 ticket 换取 SSE 连接，替代长期令牌出现在 query/访问日志中。
4. **多实例部署**：JWT 校验为无状态 HS256 验签，不依赖进程内会话，天然支持水平扩容；唯一需要共享的是 `users` 表。
5. **登录限流与审计**：失败计数、锁定与登录审计可在 `AuthService` 层叠加，不影响现有契约。

## 8. 验证记录

1. **后端**：`./mvnw.cmd clean verify` 全量 **512 个用例全部通过**（含认证模块定向测试 AuthControllerTests、AuthJwtFilterTests、AuthServiceTests、JwtTokenServiceTests、PasswordHasherTests、MyBatisUserRepositoryTests，与真实 MySQL 容器的 V20 迁移集成测试）。
2. **前端单测**：`npm test` **68 项全部通过**（含 auth-api 请求契约与 auth-store 会话/过期/登出行为）。
3. **E2E**：`npx playwright test` **20 项全部通过**（含新增 `auth.e2e.js`：登录、注册、错误密码、未登录重定向、登出；既有 platform-shell/review-workbench 用例经前置登录适配后保持通过）。
4. **真实浏览器验收**：登录成功进入 dashboard、错误密码展示 `INVALID_CREDENTIAL` 错误、注册新用户并自动登录、未登录直接访问受保护页重定向 `/login` 且登录后回到原目的地、退出登录清空会话并回到登录页——全部通过；过程截图存档于 `frontend/test-results/`（e2e-01-login-page、e2e-02-redirect-to-login、e2e-03-wrong-password-error、e2e-04-dashboard-admin、e2e-06-logout-to-login 等）。
5. **生产 bundle**：`npm run build` 后 `src/main/resources/static/review/` 已同步新 hash 资源（含 LoginView/RegisterView 懒加载块），旧 hash 已删除，`index.html` 已更新。

## 9. 边界说明

按 AGENTS.md 对 PLAN 文档边界的要求，明确本计划的外部依赖边界：

1. **不依赖 MCP**：认证全流程（登录、注册、`/me`、过滤器、SSE 令牌通道）在 Spring Boot 进程内闭环，未接入学习通通知 MCP 或任何 MCP 客户端；通知侧集成（PLAN-011）与本计划无耦合。
2. **不依赖外部平台**：不依赖 OAuth/SSO 提供商、外部认证服务或商业网关；JWT 验签完全本地完成。
3. **MySQL 边界**：`users` 表由 Flyway V20 自动迁移；本地验证使用 **Docker MySQL 容器**（Testcontainers 集成测试与本地容器库），共享库 10.0.28.99 的 V20 冲突在接入前按 #6 处理，不作为本计划完成的前置条件。
4. **持久化关闭边界**：`review.persistence.enabled=false` 时认证以 InMemory 仓储兜底，功能可用但无持久账号；该模式仅用于测试与免数据库演示。

## 10. 变更记录

| 日期 | 变更 |
|---|---|
| 2026-08-12 | 创建 PLAN-025：登录/注册界面、JWT（HS256）认证、AuthJwtFilter 双通道守卫、PBKDF2 密码哈希、V20 users 表与 admin 种子、前端认证三件套与路由守卫、SSE access_token 通道、用户区与 `.auth-page` 样式；记录共享库 V20 冲突风险与未来扩展预留；验证记录：clean verify 512 用例、npm test 68、playwright 20 与真实浏览器 E2E 全部通过。 |
