# 仓库快照与证据账本计划

> **状态**: ⏳ 待实施
> **创建日期**: 2026-07-14
> **目标**: 对允许的本地仓库建立只读快照和可验证 EvidenceBlock，为所有 Agent 论点提供统一事实来源。
> **前置计划**: PLAN-003、PLAN-004

## 0. 背景与边界

Agent 需要读取代码并给出绝对路径和单行号，但不得执行仓库脚本、构建或测试。仓库暂不设业务文件数/容量上限，扫描必须可取消、可观测、
按预算截断上下文；不能通过软链接、junction、子模块或路径规范化逃逸管理员白名单。

## 1. 分段方案

### 1.1 仓库授权与路径边界 ⏳

- 配置管理员仓库根白名单；客户端路径先 canonicalize，再校验位于白名单。
- 检查 symlink、junction、子模块、`.git` 指向和 UNC/盘符边界。
- 只给 Agent 暴露逻辑 repositoryId，不直接暴露任意服务器路径参数。

### 1.2 Git 与工作区快照 ⏳

- 记录 HEAD、branch、dirty、目标 commit、manifestHash。
- dirty 仓库生成文件 manifest 和快照副本；禁止在源仓库写锁文件。
- 快照目录固定 `reviews/{reviewId}/snapshot/repository`，保存 snapshot metadata。

### 1.3 文件清单与检索索引 ⏳

- 排除 `.git`、target、node_modules、二进制、密钥候选和配置排除项。
- 生成路径、大小、mtime、fileHash、语言和可读性清单。
- 提供按文件名、文本、正则、符号候选的受限检索；限制单次返回和累计上下文，不限制仓库入库规模。

### 1.4 只读工具契约 ⏳

- 工具：`listFiles`、`searchText`、`readLines`、`getFileMetadata`、`submitEvidence`。
- 所有工具校验 review/session/role、快照根目录、最大返回行数和取消信号。
- 不提供 shell、write、delete、build、network 工具。

### 1.5 EvidenceBlock 生成与校验 ⏳

- 字段：sourceAbsolutePath、snapshotRelativePath、lineNumber、excerpt、excerptHash、fileHash、repoRevision。
- `excerptHash = SHA-256(repoRevision + relativePath + lineNumber + normalizedExcerpt)`。
- 提交 Claim、进入辩论和生成报告前都重新校验；源仓库漂移只提示，不改变冻结快照。

### 1.6 EvidenceLedger 与批量 API ⏳

- Evidence 只追加，重复哈希去重并保留引用者关系。
- 提供按 evidenceIds 批量加载和一次性校验，禁止 Claim 循环逐条查库。
- 证据采信产生 `EVIDENCE_CITED`；EvidenceValidator 拒绝时产生 `EVIDENCE_REJECTED` 并记录详细拒绝原因。

### 1.7 安全与性能测试 ⏳

- 覆盖 `..`、大小写路径、symlink/junction、超长路径、二进制、密钥文件和快照漂移。
- 构造大目录验证取消、进度、内存和结果预算；不执行仓库任何代码。

## 2. 文件清单

### 2.1 新建

| 文件                                                                                                 | 计划段       | 状态 |
|----------------------------------------------------------------------------------------------------|-----------|----|
| `src/main/java/ai/cc/chongming/review/domain/model/RepositorySnapshot.java`                        | #1.2      | ⏳  |
| `src/main/java/ai/cc/chongming/review/domain/model/EvidenceBlock.java`                             | #1.5      | ⏳  |
| `src/main/java/ai/cc/chongming/review/application/RepositorySnapshotService.java`                  | #1.1-1.3  | ⏳  |
| `src/main/java/ai/cc/chongming/review/application/EvidenceLedgerService.java`                      | #1.5-1.6  | ⏳  |
| `src/main/java/ai/cc/chongming/review/infrastructure/repository/RepositoryBoundaryGuard.java`      | #1.1      | ⏳  |
| `src/main/java/ai/cc/chongming/review/infrastructure/repository/GitSnapshotReader.java`            | #1.2      | ⏳  |
| `src/main/java/ai/cc/chongming/review/infrastructure/repository/RepositorySearchIndex.java`        | #1.3      | ⏳  |
| `src/main/java/ai/cc/chongming/review/infrastructure/agentscope/tool/ReadOnlyRepositoryTools.java` | #1.4      | ⏳  |
| `src/main/java/ai/cc/chongming/review/infrastructure/agentscope/tool/EvidenceTools.java`           | #1.4-1.6  | ⏳  |
| `src/test/java/ai/cc/chongming/review/repository/RepositoryBoundaryGuardTests.java`                 | #1.1、#1.7 | ⏳  |
| `src/test/java/ai/cc/chongming/review/repository/RepositorySnapshotIntegrationTests.java`           | #1.2-1.3  | ⏳  |
| `src/test/java/ai/cc/chongming/review/evidence/EvidenceLedgerIntegrationTests.java`                 | #1.5-1.7  | ⏳  |

### 2.2 修改

| 文件                                                                                                       | 计划段       | 状态 |
|----------------------------------------------------------------------------------------------------------|-----------|----|
| `src/main/resources/application.yml`                                                                     | #1.1、#1.3 | ⏳  |
| `src/main/java/ai/cc/chongming/review/infrastructure/persistence/repository/EvidenceRepositoryImpl.java` | #1.6      | ⏳  |

## 3. 实施顺序

1. **步骤 1**：先写路径逃逸和工具越权失败测试。
2. **步骤 2**：实现只读快照、manifest 和 Git metadata。
3. **步骤 3**：实现受限检索与取消/进度。
4. **步骤 4**：实现 EvidenceBlock 哈希、落库和批量校验。
5. **步骤 5**：接入 Agent 工具，完成安全与性能样本测试。

## 4. 验证与退出标准

- 白名单外、symlink/junction 逃逸、敏感文件和写操作全部被拒绝。
- 任一 Evidence 可回跳到冻结快照的绝对路径和单行号，哈希校验通过。
- Agent 伪造 evidenceId、lineNumber 或 excerpt 时提交失败。
- 大目录扫描可取消，有进度事件，内存不随文件总量无界增长。
- SQL 检查无 Evidence N+1 查询。

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
