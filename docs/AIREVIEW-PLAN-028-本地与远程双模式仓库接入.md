# AIREVIEW-PLAN-028：本地与远程双模式仓库接入

> **状态**: ✅ 已完成（实现、测试与 E2E 验收均已通过）
> **创建日期**: 2026-08-17
> **目标**: 在不改变"管理员白名单 + 不透明 repositoryId + 内容寻址只读快照"安全模型的前提下，仓库来源支持本地目录与线上远程仓库两种模式：远程仓库由服务端受控克隆到托管镜像，快照、证据与评审链路零改动复用。
> **关联计划**: AIREVIEW-PLAN-006（仓库快照与证据账本）、AIREVIEW-PLAN-015（共享仓库快照缓存与生命周期）、AIREVIEW-PLAN-023（评审入口与公开对话体验收口）
> **需求来源**: 2026-08-17 平台多仓库来源接入要求

## 0. 目标与范围

### 0.1 能力声明

1. `review.repositories.allowed` 条目新增 `type: local|remote`（缺省 `local`，存量配置零迁移）；remote 条目携带 `url`、`ref`、`auth`、`clone-timeout`。
2. 远程仓库由 `RemoteRepositoryMaterializer` 物化为 `workspace/repository-mirrors/<sha256(repositoryId)>/` 下的托管浅克隆：首次 `--depth 1 --single-branch` 克隆（staging + 原子发布），之后 `fetch + checkout -B + clean` 复用镜像并同步到 ref 最新提交；镜像损坏整体重克隆。
3. 凭据只经环境变量注入（`https-token` → `GIT_CONFIG_*` 的 `http.extraheader` Basic 头；`ssh-key` → `GIT_SSH_COMMAND` 指定密钥），绝不进入命令行参数、配置文件或日志。
4. URL 安全闸：仅允许 `https://`、`ssh://` 与 scp 形态 `git@host:path`；拒绝内嵌凭据、父目录穿越、控制字符与 shell 元字符；主机解析到私网/环回地址默认拒绝，`allow-internal` 开关可放行内网 Git 服务器；`file://` 仅测试开关可用。
5. 物化后的工作树恒为 clean，天然命中既有共享快照的 clean 指纹路径；同 commit 跨评审快照复用自动生效，`RepositorySnapshotService` 及下游（清单、检索、证据）不感知仓库来源。
6. 前端经 `GET /api/repositories` 新增的 `type` 字段在仓库选择器上标注"远程"，不透明 id 契约不变。

### 0.2 范围

**本计划范围**：`RepositoryAccessProperties` 配置扩展、`RemoteRepositoryUrlValidator`、`RemoteRepositoryMaterializer`、`RepositoryBoundaryGuard` 分流、`RepositoryAccessException` 新增 `REMOTE_FETCH_FAILED`/`REMOTE_AUTH_FAILED`、`RepositoryOptionController` type 字段、`application.yml`/`application-local.yml` 配置占位、前端仓库选项徽标、生产 bundle 同步。

**非目标**：

1. 不接受 API 调用方直传 URL 或任意 commit/PR diff（调用方始终只传不透明 repositoryId；任意版本选择留作扩展，见 #6）。
2. 不引入 JGit 等新依赖，克隆/更新沿用受限 `git` 子进程（与 `GitSnapshotReader` 同风格）。
3. 不实现镜像保留期清理（镜像可复用 fetch，体量可控；需要时接入 `SharedSnapshotLifecycleService`）。
4. 子模块与 LFS 不纳入快照（沿用 PLAN-006 排除规则）。

## 1. 后端技术方案

### 1.1 配置模型（`review/config`）

`RepositoryAccessProperties`：

1. 顶层新增 `allow-internal`（私网主机放行开关）与 `allow-file-scheme`（仅测试）两个布尔开关。
2. `RepositoryDefinition` 规范化构造器按 `type` 分流：`LOCAL` 必须有 `root`；`REMOTE` 必须有 `remote` 块且 `root` 归空；`type` 缺省 `LOCAL`。重复 id 依旧在启动期拒绝。
3. `Remote`：`url` 必填；`ref` 可空（空则用远端默认分支）；`auth` 缺省 `NONE`；`clone-timeout` 缺省 `PT10M` 且必须为正。
4. `Auth`：`NONE` / `HTTPS_TOKEN` / `SSH_KEY`；`https-token` 必须给 `token-env`，`ssh-key` 必须给 `key-path-env`；两个 env 字段只存环境变量名，绝不存密钥值。

### 1.2 URL 安全闸（`RemoteRepositoryUrlValidator`）

1. 形态白名单：`https://`、`ssh://` 经 `URI` 解析要求 host 非空、无 userInfo、路径无 `..`；scp 形态正则约束 user/host 字符集并拒绝路径穿越；其余一律拒绝（含 `http://`、`ext::`、裸路径）。
2. 危险字符：控制字符、DEL、反引号、反斜杠直接拒绝。
3. 主机解析：`InetAddress.getAllByName` 后若命中环回/站点本地/链路本地/通配地址，须 `allow-internal=true` 才放行；解析失败归 `REMOTE_FETCH_FAILED`。
4. `file://` 仅当 `allow-file-scheme=true`（测试以本地 bare 仓库充当远端）时放行，且校验路径无 `..`。

### 1.3 镜像物化（`RemoteRepositoryMaterializer`）

1. 镜像根：`<workspaceRoot>/repository-mirrors/<sha256(repositoryId)>`；按 repositoryId 的 JVM 锁串行化同一仓库的克隆与更新。
2. 首次克隆：`git clone --depth 1 --single-branch [--branch ref] --quiet -- <url> <staging>`，staging 目录成功后原子 move 发布；失败清理 staging。
3. 复用更新：校验 `remote.origin.url` 与配置一致后，`fetch --depth 1 --force` → `checkout -B <ref> origin/<ref>` → `clean -fdx`；任一步失败删除镜像整体重克隆（与快照服务"不完整目录补偿重建"语义一致）。
4. 凭据注入仅在网络命令（clone/fetch）上生效：`https-token` 经 `GIT_CONFIG_COUNT/KEY_0/VALUE_0` 注入 `http.extraheader: Authorization: Basic base64(x-access-token:<token>)`；`ssh-key` 经 `GIT_SSH_COMMAND` 注入密钥路径（`BatchMode=yes`、`accept-new`）。凭据缺失直接归 `REMOTE_AUTH_FAILED`。
5. 全程 `GIT_TERMINAL_PROMPT=0`、`GIT_OPTIONAL_LOCKS=0`；每个命令带可配置超时（轮询 + `destroyForcibly`），输出由守护线程有界吸收（4KB）防管道阻塞；失败输出仅截断记日志，异常消息稳定且不回显 URL/凭据。
6. 失败分类：认证类关键词（authentication failed、could not read username、terminal prompts disabled、permission denied、repository not found、returned error: 401/403 等）→ `REMOTE_AUTH_FAILED`，其余 → `REMOTE_FETCH_FAILED`。
7. 残留治理：物化前清理超过 1 小时的 `.mirror-staging-*` 目录；不完整镜像（无 `.git`）删除重建，完整镜像绝不自动删除。

### 1.4 边界守卫分流（`RepositoryBoundaryGuard`）

1. 构造器改为持有 `Map<String, RepositoryDefinition>`；`@Autowired` 主构造器注入 `RemoteRepositoryMaterializer`，保留单参构造器供无远程支持的测试装配（remote 条目此时以 `REMOTE_FETCH_FAILED` 稳定拒绝）。
2. `requireAuthorized`：LOCAL 走既有 UNC/链接/reparse 点与 `.git` 校验；REMOTE 先经物化，再对镜像工作树执行同一套链接与 Git 元数据校验，返回同一 `AuthorizedRepository(repositoryId, root)` 契约。

### 1.5 错误码与契约

| code | 触发场景 |
|---|---|
| `REMOTE_FETCH_FAILED` | 克隆/更新失败、超时、主机不可解析、镜像不可读、git 不可用 |
| `REMOTE_AUTH_FAILED` | 凭据未配置或被远端拒绝 |

既有 `RepositoryAccessException` 传播链路（评审受理失败契约）自动承接新错误码，无需新增 advice。

## 2. 前端技术方案

1. `use-repository-options.js`：选项映射新增 `type`（`remote` 原样保留，其余归 `local`），id/displayName 契约不变。
2. `RepositorySelect.vue`：远程条目在下拉项上追加" · 远程"后缀（原生 `option` 纯文本），本地条目无后缀。
3. 生产 bundle 已同步：`npm run build` 产出新 hash（`index-BRjH8j0-.js` 等），旧 hash 删除，`static/review/index.html` 一并更新。

## 3. 前后端契约

### 3.1 仓库选项（变更点）

`GET /api/repositories` 响应项新增 `type`：`local` | `remote`；`id`/`displayName` 不变，依旧不暴露物理 root 与 URL。

### 3.2 配置示例

```yaml
review:
  repositories:
    allow-internal: ${REVIEW_REPOSITORIES_ALLOW_INTERNAL:false}
    allowed:
      - id: cx-ai
        root: ${CX_AI_REPOSITORY_ROOT:../cx-ai}
      - id: sample-remote
        type: remote
        display-name: 示例远程仓库
        remote:
          url: ${SAMPLE_REMOTE_REPOSITORY_URL:}
          ref: main
          clone-timeout: PT10M
          auth:
            type: https-token
            token-env: SAMPLE_REMOTE_REPOSITORY_TOKEN
```

## 4. 文件清单

### 4.1 新建

| 文件 | 段号 | 状态 |
|---|---|---|
| `docs/AIREVIEW-PLAN-028-本地与远程双模式仓库接入.md` | 本计划 | ✅ |
| `src/main/java/ai/cc/chongming/review/infrastructure/repository/RemoteRepositoryUrlValidator.java` | #1 | ✅ |
| `src/main/java/ai/cc/chongming/review/infrastructure/repository/RemoteRepositoryMaterializer.java` | #1 | ✅ |
| `src/test/java/ai/cc/chongming/review/repository/RemoteRepositoryUrlValidatorTests.java` | #1 | ✅ |
| `src/test/java/ai/cc/chongming/review/repository/RemoteRepositoryMaterializerTests.java` | #1 | ✅ |

### 4.2 修改

| 文件 | 段号 | 状态 |
|---|---|---|
| `src/main/java/ai/cc/chongming/review/config/RepositoryAccessProperties.java`（type/remote/auth/开关） | #1 | ✅ |
| `src/main/java/ai/cc/chongming/review/application/RepositoryAccessException.java`（新错误码） | #1 | ✅ |
| `src/main/java/ai/cc/chongming/review/infrastructure/repository/RepositoryBoundaryGuard.java`（remote 分流） | #1 | ✅ |
| `src/main/java/ai/cc/chongming/review/api/RepositoryOptionController.java`（type 字段） | #2 | ✅ |
| `src/main/resources/application.yml`、`application-local.yml`（allow-internal 与远程示例占位） | #3 | ✅ |
| `src/test/java/ai/cc/chongming/review/api/RepositoryOptionControllerTests.java`（type 契约 + remote 绑定） | #1 | ✅ |
| `src/test/java/ai/cc/chongming/review/repository/RepositoryBoundaryGuardTests.java`（remote 物化用例） | #1 | ✅ |
| `src/test/java/ai/cc/chongming/review/repository/RepositorySnapshotServiceTests.java`（构造适配） | #1 | ✅ |
| `frontend/src/composables/use-repository-options.js`、`components/RepositorySelect.vue`（远程徽标） | #2 | ✅ |
| `frontend/src/composables/use-repository-options.test.js`、`tests/review-workbench.e2e.js`（远程选项用例） | #2 | ✅ |
| `src/main/resources/static/review/`（新 hash bundle 与 index.html 同步，旧 hash 删除） | #2 | ✅ |

## 5. 已知风险与权衡

| 风险/权衡 | 说明与当前结论 |
|---|---|
| 克隆无取消信号 | `requireAuthorized` 契约不带 `IntakeCancellation`，克隆以可配置超时（默认 10 分钟）兜底；快照捕获阶段依旧可取消。接入取消需扩展守卫签名，留作后续。 |
| 私网主机依赖 DNS 解析判定 | IP 字面量直接判定；域名在解析阶段拦截。`allow-internal` 默认关闭，内网部署显式开启。 |
| 浅克隆不支持任意历史 | `--depth 1` 只覆盖 ref 最新提交；评审目标即"当前 ref 最新提交"，任意 commit 评审留作扩展。 |
| 镜像无保留期清理 | 镜像数量等于远程仓库配置数且可复用 fetch，磁盘风险可控；`repository-mirrors/` 位于 workspace 内，随既有生命周期体系演进。 |
| git 凭据 header 对 https 全量生效 | 每次网络命令只访问单一配置仓库，`http.extraheader` 作用域与命令同生命周期，无横向泄露面。 |

## 6. 未来扩展预留

1. **任意版本评审**：`Remote` 增加 `commit` 字段并在物化后 `checkout <commit>`，即可支持指定 commit/PR 基线。
2. **GitHub App / 安装令牌**：`Auth` 增加 `app-token` 类型，由令牌换取服务短期注入，替代静态 PAT。
3. **镜像生命周期**：接入 `SharedSnapshotLifecycleService`，按 lastAccessedAt 清理长期未用的远程镜像。
4. **进度事件**：克隆进度经 PLAN-010 事件通道上报，前端呈现物化状态。

## 7. 验证记录

1. **后端**：`./mvnw.cmd test -Dtest=*Repository*` **93 个用例通过**（含新增 RemoteRepositoryUrlValidatorTests 10 项、RemoteRepositoryMaterializerTests 5 项：首克隆、镜像复用快进、本地篡改重置、不可达远端稳定错误码、不安全 URL 拒绝；Guard remote 物化 2 项；Controller type 契约与 remote 配置绑定 4 项）。
2. **前端单测**：`npm test` **110 项全部通过**（含远程选项映射新用例）。
3. **E2E**：`npx playwright test` **29 项全部通过**（独立评审表单仓库选项用例覆盖本地/远程双选项与" · 远程"标注）。
4. **生产 bundle**：`npm run build` 产出新 hash 资源，`static/review/` 与 index.html 同步、旧 hash 删除。
5. **环境限制**：Testcontainers MySQL 集成测试因本机无 Docker 按仓库惯例跳过（与本计划无新增关联用例）；真实 https 远端冒烟需在具备网络与令牌的环境执行。

## 8. 边界说明

1. **不依赖 MCP**：远程克隆与物化完全在 Spring Boot 进程内经受限 git 子进程闭环，不接入任何 MCP 客户端。
2. **不依赖外部平台 API**：仅使用 Git 原生传输（https/ssh），不调用 GitHub/GitLab/Gitee 平台 API；平台专属授权（App 令牌等）见 #6 扩展预留。
3. **MySQL 边界**：本计划无新增表与迁移；仓库配置仍为运维侧 `application.yml` / 环境变量管理。
4. **持久化关闭边界**：远程接入与持久化开关无关，InMemory/演示 profile 下同样可用；无认证模块的演示 profile 不受影响。

## 9. 变更记录

| 日期 | 变更 |
|---|---|
| 2026-08-17 | 创建 PLAN-028：配置层 type/remote/auth 扩展与启动期校验、URL 安全闸、托管浅克隆镜像物化（staging 原子发布、fetch 复用、损坏重克隆）、边界守卫 remote 分流、REMOTE_FETCH_FAILED/REMOTE_AUTH_FAILED 错误码、仓库选项 type 契约与前端“远程”徽标；验证记录：mvn test（*Repository* 93 项）、npm test 110、playwright 29 全部通过。 |
| 2026-08-17 | AIREVIEW-PLAN-029 复用本计划的 URL 安全闸与物化引擎，新增需求级 adhoc 远程源通道（令牌随需求加密存储）；两种仓库来源并存且互斥，本计划行为不变。 |
