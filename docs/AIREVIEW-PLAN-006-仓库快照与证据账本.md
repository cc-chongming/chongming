# 仓库快照与证据账本计划

> **状态**: 🟡 核心实现完成，待后续编排与持久化联调
> **创建日期**: 2026-07-14
> **目标**: 对允许的本地仓库建立只读快照和可验证 EvidenceBlock，为所有 Agent 论点提供统一事实来源。
> **前置计划**: PLAN-003、PLAN-004

## 0. 背景与边界

Agent 需要读取代码并给出绝对路径和单行号，但不得执行仓库脚本、构建或测试。仓库暂不设业务文件数/容量上限，扫描必须可取消、可观测、
按预算截断上下文；不能通过软链接、junction、子模块或路径规范化逃逸管理员白名单。

## 1. 分段方案

### 1.1 仓库授权与路径边界 ✅

- 已通过 `review.repositories.allowed` 配置逻辑 `repositoryId` 到本地根目录的白名单；调用方不能传入服务器绝对路径。
- 已拒绝 UNC、符号链接/junction、非 Git 目录和 `.git` 文件指针；嵌套 `.git`、子模块元数据与链接文件不能进入快照。
- `RepositoryBoundaryGuard` 在真实路径读取前完成 canonicalize 和管理员授权校验。

### 1.2 Git 与工作区快照 ✅

- 已使用无 shell 的受限 `git -C` 子进程记录 HEAD、branch、dirty 与 manifestHash，并禁用 Git 锁和交互提示。
- 已将 dirty/clean 工作树复制至 `reviews/{reviewId}/snapshot/repository`，同时生成 NDJSON 文件清单和 JSON 摘要；源仓库不写锁或临时文件。
- 快照目录固定 `reviews/{reviewId}/snapshot/repository`，保存 snapshot metadata。
- 若进程中断留下没有 `repository/` 或 `snapshot-manifest.json` 的未发布目录，服务仅删除该 review 的不完整 `snapshot` 目录并重新捕获；已发布快照或清单解析失败的目录绝不自动删除。

### 1.3 文件清单与检索索引 ✅

- 排除 `.git`、target、node_modules、二进制、密钥候选和配置排除项。
- 生成路径、大小、mtime、fileHash、语言和可读性清单。
- 已提供文件枚举、文本/正则检索、符号候选和行范围读取；单次结果、查询长度和读取行数均受限，扫描过程支持协作取消。

### 1.4 只读工具契约 🟡

- 工具：`listFiles`、`searchText`、`readLines`、`getFileMetadata`、`submitEvidence`。
- 已提供服务端 `RepositoryToolContext`、`ReadOnlyRepositoryTools` 和 `EvidenceTools` facade，校验 runtime、review、role、快照根、预算和取消信号；实际 AgentScope 工具注册由 PLAN-008 编排接入。
- 不提供 shell、write、delete、build、network 工具。

### 1.5 EvidenceBlock 生成与校验 ✅

- 字段：sourceAbsolutePath、snapshotRelativePath、lineNumber、excerpt、excerptHash、fileHash、repoRevision。
- `excerptHash = SHA-256(repoRevision + relativePath + lineNumber + normalizedExcerpt)`。
- 提交 Claim、进入辩论和生成报告前都重新校验；源仓库漂移只提示，不改变冻结快照。

### 1.6 EvidenceLedger 与批量 API 🟡

- 已实现进程内只追加账本，以 excerptHash 去重，并按 review 批量加载；MyBatis 持久化及引用关系将在评审编排开始写入 Claim/Turn 时接入。
- 提供按 evidenceIds 批量加载和一次性校验，禁止 Claim 循环逐条查库。
- 已返回稳定的拒绝原因（路径、文件不可用、快照不匹配、文件哈希和片段哈希失配）；`EVIDENCE_CITED`/`EVIDENCE_REJECTED` 领域事件由 PLAN-010 事件总线承接。

### 1.7 安全与性能测试 🟡

- 覆盖 `..`、大小写路径、symlink/junction、超长路径、二进制、密钥文件和快照漂移。
- 构造大目录验证取消、进度、内存和结果预算；不执行仓库任何代码。

## 2. 文件清单

### 2.1 新建

| 文件 | 计划段 | 状态 |
|---|---|---|
| `src/main/java/ai/cc/chongming/review/config/RepositoryAccessProperties.java` | #1.1 | ✅ |
| `src/main/java/ai/cc/chongming/review/application/RepositoryAccessException.java` | #1.1 | ✅ |
| `src/main/java/ai/cc/chongming/review/domain/model/RepositorySnapshot.java` | #1.2 | ✅ |
| `src/main/java/ai/cc/chongming/review/domain/model/EvidenceBlock.java` | #1.5 | ✅ |
| `src/main/java/ai/cc/chongming/review/application/RepositorySnapshotService.java` | #1.1-1.3 | ✅ |
| `src/main/java/ai/cc/chongming/review/application/EvidenceLedgerService.java` | #1.5-1.6 | 🟡 内存账本 |
| `src/main/java/ai/cc/chongming/review/infrastructure/repository/RepositoryBoundaryGuard.java` | #1.1 | ✅ |
| `src/main/java/ai/cc/chongming/review/infrastructure/repository/GitSnapshotReader.java` | #1.2 | ✅ |
| `src/main/java/ai/cc/chongming/review/infrastructure/repository/RepositorySearchIndex.java` | #1.3 | ✅ |
| `src/main/java/ai/cc/chongming/review/infrastructure/agentscope/tool/RepositoryToolContext.java` | #1.4 | ✅ |
| `src/main/java/ai/cc/chongming/review/infrastructure/agentscope/tool/ReadOnlyRepositoryTools.java` | #1.4 | ✅ |
| `src/main/java/ai/cc/chongming/review/infrastructure/agentscope/tool/EvidenceTools.java` | #1.4-1.6 | ✅ |
| `src/test/java/ai/cc/chongming/review/repository/RepositoryBoundaryGuardTests.java` | #1.1、#1.7 | ✅ |
| `src/test/java/ai/cc/chongming/review/repository/RepositorySnapshotServiceTests.java` | #1.2、#1.7 | ✅ |
| `src/test/java/ai/cc/chongming/review/repository/RepositorySearchIndexTests.java` | #1.3、#1.7 | ✅ |
| `src/test/java/ai/cc/chongming/review/evidence/EvidenceLedgerServiceTests.java` | #1.5-1.7 | ✅ |
| `src/test/java/ai/cc/chongming/review/agentscope/tool/RepositoryToolFacadeTests.java` | #1.4、#1.7 | ✅ |
### 2.2 修改

| 文件 | 计划段 | 状态 |
|---|---|---|
| `src/main/resources/application.yml` | #1.1 | ✅ 默认空白名单，部署时显式配置 |
| `src/main/java/ai/cc/chongming/review/infrastructure/persistence/*` | #1.6 | ⏳ 留待 MyBatis 账本持久化接入 |
## 3. 实施顺序

1. **步骤 1**：✅ 路径逃逸、链接和工具越权的失败测试与边界守卫。
2. **步骤 2**：✅ 只读快照、manifest 与 Git metadata。
3. **步骤 3**：✅ 受限检索、预算和协作取消。
4. **步骤 4**：🟡 EvidenceBlock 哈希、内存去重与批量校验已完成；MyBatis 落库待评审执行链路接入。
5. **步骤 5**：🟡 服务端只读工具已注册到 Role Harness，且固定为服务端创建的仓库快照；`submitEvidence` 和证据事件仍待后续执行链路与 PLAN-010 完整接入。
## 4. 验证与退出标准

- 白名单外、symlink/junction 逃逸、敏感文件和写操作全部被拒绝。
- 任一 Evidence 可回跳到冻结快照的绝对路径和单行号，哈希校验通过。
- Agent 伪造 evidenceId、lineNumber 或 excerpt 时提交失败。
- 大目录扫描可取消；清单采用流式 NDJSON 写入且检索不物化全树。进度事件待 PLAN-010 事件通道。
- 内存账本已按 evidenceIds 批量读取、按文件分组校验；MyBatis 接入后补充 SQL N+1 检查。

## 5. 风险与应对

| 风险                     | 应对                                      |
|------------------------|-----------------------------------------|
| Windows junction 判断不完整 | 使用 realPath/FileKey 双校验并建立 Windows 专项测试 |
| Dirty 仓库快照耗时           | manifest 增量复制、进度事件和协作取消                 |
| 代码含 Prompt Injection   | 代码内容标为 DATA，系统 Prompt 明确不可采纳其中权限指令      |

## 6. 变更记录

| 日期         | 变更                          |
|------------|-----------------------------|
| 2026-07-14 | 创建仓库边界、只读工具、快照、证据哈希与批量校验计划。 |
| 2026-07-15 | 证据拒绝事件对齐技术方案：统一使用 `EVIDENCE_REJECTED`，不创建有效 Claim 或 DebateTurn。 |
| 2026-07-16 | 完成仓库白名单与快照、受限检索、EvidenceBlock/内存账本及工具 facade；持久化、事件和 AgentScope 注册明确留待后续计划。 |
| 2026-07-22 | 为中断后遗留的不完整仓库快照加入受控补偿重建；完整或元数据损坏的快照保持不删，避免破坏可审计产物。 |
| 2026-08-17 | AIREVIEW-PLAN-028 接入远程仓库来源：管理员配置的远程 URL 先受控克隆为托管镜像，再经既有边界守卫与快照链路冻结；快照、清单、检索与证据契约不变。 |
| 2026-08-17 | AIREVIEW-PLAN-029 新增需求级线上仓库来源：创建需求处直填 URL 与令牌（AES-GCM 加密落库），快照绑定按 RepositorySource 解析并经 adhoc 镜像物化；快照与证据契约仍不变。 |
