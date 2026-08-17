# AIREVIEW-PLAN-029：需求级远程仓库接入

> **状态**: ✅ 已完成（实现、测试与 E2E 验收均已通过）
> **创建日期**: 2026-08-17
> **目标**: 创建/编辑需求处支持"线上仓库"模式——由需求创建者直接填写线上代码仓库地址与访问令牌（可选），评审启动时服务端受控克隆该仓库并生成快照；管理员白名单仓库（PLAN-028）继续可用，两种来源并存且互斥。
> **关联计划**: AIREVIEW-PLAN-006（仓库快照与证据账本）、AIREVIEW-PLAN-028（本地与远程双模式仓库接入）
> **需求来源**: 2026-08-17 平台使用方要求"谁创建需求，谁提供线上仓库与令牌"

## 0. 目标与范围

### 0.1 能力声明

1. 需求创建与编辑页新增仓库来源切换：「配置仓库」（PLAN-028 白名单下拉，行为不变）与「线上仓库」（URL + 可选分支 + 可选访问令牌）。
2. 线上仓库绑定随需求落库：`requirement` 表新增 `remote_url`/`remote_ref`/`remote_token_enc` 三列；令牌 AES-GCM 加密存储（密钥只经环境变量 `REVIEW_REMOTE_TOKEN_KEY` 注入），任何响应只回 `{url, ref, tokenConfigured}`，密文与明文令牌绝不外泄。
3. 修订语义：令牌留空且 url/ref 未变则沿用原密文；更换地址且未填令牌则清空凭据。
4. 评审受理（`/api/reviews` 与需求发起评审端点）接受可选 `remoteUrl/remoteRef/remoteToken`；受理快照与磁盘 manifest 携带远程源（密文），重启/重试可重新物化。
5. 快照绑定按 `RepositorySource`（配置 id | 远程源）解析：远程源经 `RemoteRepositoryMaterializer.ensureAdhocMirror` 物化为 `repository-mirrors/<sha256(adhoc:sha256(url\0ref))>` 托管浅克隆；镜像键不含令牌，令牌轮换不产生镜像分叉。
6. 前端详情/列表展示线上仓库徽标与地址；发起评审对远程绑定需求自动使用需求绑定源，无需再选仓库。

### 0.2 范围

**本计划范围**：`RemoteRepositorySource` 领域值对象、`Requirement` 字段与工厂、V24 迁移、双仓储读写、`RemoteTokenCipher`、需求命令/查询契约、受理请求与 manifest 远程源、`RepositorySource` + `bindSnapshot/findExistingSnapshot` 重载、adhoc 物化通道、前端 `RepositorySourcePicker` 与三处接入、生产 bundle 同步。

**非目标**：

1. 不支持调用方指定任意 commit/PR 基线（评审目标为所填分支最新提交，留作扩展）。
2. 不支持用户上传 SSH 私钥（管理员配置的 `ssh-key` 通道仍可用）。
3. 不改变 PLAN-027 可见域与 PLAN-028 管理员远程仓库行为。

## 1. 后端技术方案

### 1.1 领域与持久化

1. `RemoteRepositorySource(url, ref, encryptedToken)`：不可变值对象，长度约束与持久化列对齐；`identitySeed() = url + '\0' + ref`；`repositoryIdentity() = "remote:" + sha256(seed)`，作为快照目录与引用的仓库身份。
2. `Requirement` 新增 `remoteSource` 字段；`draft/restore/revise` 提供携带远程源的重载，历史签名保留。
3. V24 迁移：`requirement` 表新增 `remote_url VARCHAR(512)`、`remote_ref VARCHAR(128)`、`remote_token_enc VARCHAR(1024)`，均可空，存量行为不变。
4. `RequirementMapper` 全部读写语句与 `RequirementRow` 同步三列；MyBatis 仓储以 `remote_url` 是否为空决定是否重建远程源；InMemory 仓储天然透传。

### 1.2 令牌加密（`RemoteTokenCipher`）

1. AES/GCM/NoPadding，12 字节随机 IV，封装 `v1:base64(iv||密文)`；密钥取 `review.remote-token.key`（环境变量 `REVIEW_REMOTE_TOKEN_KEY`）的 SHA-256。
2. 未配置密钥时加密/解密一律抛 `REMOTE_SOURCE_INVALID`（400），不静默降级；篡改密文、跨密钥解密、超长明文（>512）同样稳定拒绝。
3. 明文令牌只存在于一次命令处理周期内，物化时经进程环境变量注入 Git，随后丢弃。

### 1.3 命令与查询契约

1. `POST /api/requirements` 与 `PUT /api/requirements/{id}` 请求体新增可选 `remote{url, ref?, token?}`；与 `repositoryPath` 互斥（同时出现返回 400 `REMOTE_SOURCE_INVALID`）。
2. URL 经 PLAN-028 `RemoteRepositoryUrlValidator` 校验（scheme 白名单、内嵌凭据/路径穿越/危险字符拒绝、私网主机需 `allow-internal`）。
3. `RequirementView` 新增 `remote{url, ref, tokenConfigured}`；令牌只以布尔形式呈现。

### 1.4 受理与快照链路

1. `ReviewIntakeRequest` 与 `RequirementSnapshot` 增加远程源分量；`repositoryPath` 在存在远程源时可为空；幂等键计入远程身份。
2. `RequirementSnapshotStore` manifest 新增 `remoteUrl/remoteRef/remoteTokenEnc`；旧 manifest 反序列化为空远程源，天然兼容。
3. `ReviewCommandController`（`/api/reviews`）与 `RequirementCommandController.launchReview` 的 `repositoryPath` 改为可选，并分别接受 `remoteUrl/remoteRef/remoteToken`（前者）或复用需求绑定源（后者：`RequirementReviewLaunchService` 将 `requirement.remoteSource()` 透传给受理）。
4. 新增应用层 `RepositorySource`（`configured(id)` | `remote(source)`，`from(RequirementSnapshot)`）；`RepositorySnapshotService.bindSnapshot/findExistingSnapshot` 增加按来源解析的重载：配置源走边界守卫，远程源先 `RemoteTokenCipher.decrypt` 再 `ensureAdhocMirror(url, ref, plainToken)`。
5. `RemoteRepositoryMaterializer` 抽出统一 `RemoteTarget/ResolvedCredentials` 内核：配置远程（PLAN-028，凭据来自环境变量）与 adhoc（本计划，凭据为解密令牌）共用克隆/更新/补偿机制；adhoc 镜像身份 `adhoc:sha256(url\0ref)`，令牌不参与。

## 2. 前端技术方案

1. 新组件 `RepositorySourcePicker.vue`：「配置仓库 / 线上仓库」双页签；配置模式内嵌既有 `RepositorySelect`；线上模式提供 URL、分支、令牌（password，编辑态占位"已配置令牌，留空保持不变"）。
2. `RequirementCreateView`：创建/存草稿与创建评审请求按模式组装 `remote`/`remoteUrl` 负载；无配置仓库不再阻塞线上仓库提交。
3. `RequirementDetailView`：编辑面板使用同一选择器（`token-configured` 驱动占位）；发起评审面板对远程绑定需求显示"自动克隆该仓库"提示并免选仓库；详情元信息展示 🌐 线上仓库徽标。
4. `review-api.js`：`createReview`/`launchRequirementReview` 仅在提供时附加 `repositoryPath`，并按需携带 `remoteUrl/remoteRef/remoteToken`。
5. 生产 bundle 已同步（新 hash 资源、旧 hash 删除、`index.html` 更新）。

## 3. 前后端契约

### 3.1 需求写入（变更点）

`POST/PUT /api/requirements` 请求体新增可选：

```json
{ "remote": { "url": "https://git.example.com/group/demo.git", "ref": "main", "token": "明文令牌（只写）" } }
```

`remote` 与 `repositoryPath` 互斥；`remote.url` 必填并通过安全闸。

### 3.2 需求读取（变更点）

`RequirementView.remote = { url, ref, tokenConfigured } | null`；令牌任何形态均不出现在响应中。

### 3.3 评审受理（变更点）

`POST /api/reviews` multipart 新增可选 `remoteUrl/remoteRef/remoteToken`；`repositoryPath` 在有远程源时省略。需求发起评审端点对远程绑定需求免 `repositoryPath`。

### 3.4 错误码

| code | HTTP | 触发场景 |
|---|---|---|
| `REMOTE_SOURCE_INVALID` | 400 | URL 非法/私网未放行、两种仓库绑定同时出现、令牌加密密钥缺失或密文损坏 |

受理端点沿用既有 `ReviewIntakeException` 400 通道输出同名 code。

## 4. 文件清单

### 4.1 新建

| 文件 | 段号 | 状态 |
|---|---|---|
| `docs/AIREVIEW-PLAN-029-需求级远程仓库接入.md` | 本计划 | ✅ |
| `src/main/java/ai/cc/chongming/review/domain/model/RemoteRepositorySource.java` | #1 | ✅ |
| `src/main/java/ai/cc/chongming/review/application/RemoteTokenCipher.java`、`RepositorySource.java` | #1/#1 | ✅ |
| `src/main/resources/db/migration/V24__requirement_remote_source.sql` | #1 | ✅ |
| `src/test/java/ai/cc/chongming/review/application/RemoteTokenCipherTests.java` | #1 | ✅ |
| `frontend/src/components/RepositorySourcePicker.vue` | #2 | ✅ |

### 4.2 修改

| 文件 | 段号 | 状态 |
|---|---|---|
| `src/main/java/.../review/domain/model/Requirement.java`、`RequirementSnapshot.java` | #1 | ✅ |
| `src/main/java/.../review/domain/exception/RequirementErrorCode.java`、`api/RequirementExceptionHandler.java` | #1 | ✅ |
| `src/main/java/.../review/application/RequirementCommandService.java`、`RequirementQueryService.java`、`ReviewIntakeRequest.java`、`ReviewIntakeService.java`、`RequirementReviewLaunchService.java`、`ReviewCommandService.java` | #1 | ✅ |
| `src/main/java/.../review/api/RequirementCommandController.java`、`ReviewCommandController.java` | #1 | ✅ |
| `src/main/java/.../review/infrastructure/repository/RemoteRepositoryMaterializer.java`（adhoc 通道与凭据内核重构） | #1 | ✅ |
| `src/main/java/.../review/infrastructure/document/RequirementSnapshotStore.java`、`persistence/mapper/RequirementMapper.java`、双仓储 | #1 | ✅ |
| `src/main/resources/application.yml`、`application-local.yml`（`review.remote-token.key` 占位） | #1 | ✅ |
| 测试适配：`RequirementCommandServiceTests`、`RequirementQueryServiceTests`、`RequirementSnapshotStoreTests`、`RemoteRepositoryMaterializerTests`、`ReviewCommandServiceTests`、`ReviewRepositoryToolFactoryTests`、`ReviewIntakeServiceTests` | #1 | ✅ |
| `frontend/src/api/review-api.js`、`views/RequirementCreateView.vue`、`views/RequirementDetailView.vue`、`styles/review.css` | #2 | ✅ |
| `frontend/tests/review-workbench.e2e.js`（远程创建用例 + 文案更新） | #2 | ✅ |
| `src/main/resources/static/review/`（新 hash bundle 与 index.html 同步，旧 hash 删除） | #2 | ✅ |

## 5. 已知风险与权衡

| 风险/权衡 | 说明与当前结论 |
|---|---|
| 令牌与仓库地址进入用户输入面 | URL 全量经 PLAN-028 安全闸（含私网解析拦截，`allow-internal` 仅本地 profile 开启）；克隆在受限 git 子进程完成，无 shell 拼接。 |
| 密文随需求与 manifest 存盘 | 仅 AES-GCM 密文；密钥轮换会使旧密文不可解（稳定 400），属预期运维动作，需配合重新填写令牌。 |
| 快照服务在 findExistingSnapshot 时也会触发镜像 fetch | 与既有配置远程行为一致；浅克隆 fetch 成本低，换取重启后引用校验的一致性。 |
| 前端不验证 URL 合法性 | 校验以服务端为准，前端仅做非空与长度限制；服务端错误经 ProblemDetail 稳定回显。 |
| InMemory 模式可保存令牌密文 | 仅测试/演示 profile 使用；生产必须开启持久化（与既有约定一致）。 |

## 6. 未来扩展预留

1. **任意版本评审**：`remote` 增加 `commit` 字段并在物化后 `checkout <commit>`。
2. **令牌轮换提示**：修订响应可携带"令牌需重填"信号，前端主动引导。
3. **平台托管凭据库**：将需求级令牌升级为可复用的凭据条目（管理员可见、审计可控）。

## 7. 验证记录

1. **后端**：`./mvnw.cmd test` 全量 **681 个用例通过**（0 失败；27 个 Testcontainers MySQL 集成用例因本机无 Docker 按仓库惯例跳过，含 V24 相关持久化集成验证需在有 Docker 环境补验）。新增覆盖：`RemoteTokenCipherTests` 7 项（往返、随机 IV、缺钥拒绝、篡改/跨密钥/超长拒绝）、需求命令远程源 3 项（加密落库、令牌沿用/清空、非法 URL 拒绝）、adhoc 物化 2 项（镜像键稳定复用、不安全 URL 拒绝）、manifest 远程源往返、`RequirementView.remote` 投影。
2. **前端单测**：`npm test` **110 项全部通过**。
3. **E2E**：`npx playwright test` **30 项全部通过**（新增"线上仓库创建需求"用例：切换页签、填写 URL/分支/令牌、空配置仓库不阻塞提交、需求负载含 remote、受理表单含 remoteUrl 且不含 repositoryPath）。
4. **生产 bundle**：`npm run build` 产出新 hash 资源，`static/review/` 与 index.html 同步、旧 hash 删除。
5. **环境限制**：真实 https 远端（含令牌注入与内网主机）冒烟需在具备网络与 `REVIEW_REMOTE_TOKEN_KEY` 的环境执行。

## 8. 边界说明

1. **不依赖 MCP**：远程克隆与令牌处理完全在 Spring Boot 进程内闭环。
2. **不依赖外部平台 API**：仅使用 Git 原生 https 传输（`http.extraheader` 注入 Basic 头），不调用 GitHub/GitLab/Gitee API。
3. **MySQL 边界**：V24 由 Flyway 自动迁移；持久化集成测试依赖 Docker/Testcontainers，无 Docker 环境按仓库惯例记录跳过而非视为通过。
4. **持久化关闭边界**：InMemory 双实现保持同一契约；无认证模块的演示 profile 不受影响。

## 9. 变更记录

| 日期 | 变更 |
|---|---|
| 2026-08-17 | 创建 PLAN-029：需求级线上仓库绑定（URL+分支+令牌）、AES-GCM 令牌加密与只写契约、受理快照/manifest 携带远程源、`RepositorySource` 快照解析与 adhoc 镜像物化、前端仓库来源选择器三处接入；验证记录：mvn test 681 通过（27 项 Docker 依赖跳过）、npm test 110、playwright 30 全部通过。 |
