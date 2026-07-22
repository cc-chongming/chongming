# 共享仓库快照缓存与生命周期计划

> **状态**: ⏳ 已规划，待实施
> **创建日期**: 2026-07-22
> **目标**: 将仓库快照从 review 私有副本升级为按代码版本共享的只读快照；每个 review 仅持有受控引用，Harness 仍只能读取冻结副本。
> **前置计划**: PLAN-005、PLAN-006、PLAN-008；Director `BYPASS` 已实施，需补行为级验证。

## 0. 范围、边界与不变量

### 0.1 本计划实现

- 共享键为 `SHA-256(repositoryId + "\\0" + commit + "\\0" + worktreeFingerprint)`；目录只使用该摘要，原始字段写入 metadata。
- clean 工作树使用固定指纹 `clean`；dirty 工作树按**实际纳入评审快照**的稳定排序相对路径和内容哈希计算指纹。
- 共享快照发布前必须存在 `repository/`、`repository-files.ndjson` 和 `snapshot-manifest.json`，并通过目录、元数据、清单哈希和文件路径校验。
- review 仅写 `snapshot-reference.json`；Harness 从 `reviewId -> reference -> shared snapshot` 获得既有 `RepositorySnapshot` 只读视图，不接收宿主路径。
- 以每个 snapshotKey 一把 JVM 锁串行创建和清理；原子 staging 发布后才写引用。
- 不完整共享目录可补偿：仅删除该共享目录，告警后重建；完整或 metadata 损坏的快照不自动删除。
- 引用删除与保留期清理采用二次引用检查；清理不得删除原仓库、其他 review 数据或仍被引用的共享快照。

### 0.2 非目标

- 不实现远程 Git Clone、对象存储或跨机器共享缓存。
- 不向模型暴露宿主文件系统、仓库 ID、快照物理路径或写入能力。
- 不以 AgentScope `BYPASS` 绕过 ReviewProtocolGuard、领域命令、角色上限或 RolePack 工具白名单。

## 1. 目录与数据模型

```text
.agentscope/workspace/
├─ repository-snapshots/
│  └─ <repositoryId-hash>/<snapshotKey>/
│     ├─ repository/
│     ├─ repository-files.ndjson
│     └─ snapshot-manifest.json
└─ reviews/<reviewId>/
   └─ snapshot-reference.json
```

新增领域对象：

- `SharedRepositorySnapshot`：snapshotKey、repositoryId、commit、branch、dirty、worktreeFingerprint、manifestHash、fileCount、createdAt、lastAccessedAt 和共享根目录。
- `SnapshotReference`：reviewId、snapshotKey、repositoryId、boundAt、requirementSnapshotHash；引用不得包含宿主根路径。

保留 `RepositorySnapshot` 作为运行时只读视图；它由 `SharedRepositorySnapshot + ReviewId` 构造，工具层接口不变化。

## 2. 实施步骤

### 步骤 1：快照键、完整性校验与安全边界

1. 扩展 Git 读取，读取 HEAD、branch、dirty；dirty 时用现有过滤规则扫描纳入文件并计算工作树指纹。
2. 创建共享 metadata 和文件清单校验器，拒绝 Junction、软链接、目录越界、metadata 不一致和 manifestHash 不一致。
3. 将当前 review 私有 snapshot 创建代码抽取为共享 snapshot 构建器，保留 staging 与原子移动。

**验收**：clean 同 commit 得到相同 key；dirty 变更任一纳入文件得到不同 key；敏感、二进制、排除目录不影响 dirty 指纹。

### 步骤 2：共享复用、引用绑定与并发锁

1. 按 snapshotKey 查找完整共享快照，命中时只更新 `lastAccessedAt`。
2. 未命中时在 key 锁内二次查找，创建 staging，复制、写入 NDJSON 和 metadata、校验后原子发布。
3. 在评审创建/启动的受控流程中绑定 reference；引用成功后才允许启动 Harness。
4. 改造 `ReviewRepositoryToolFactory`：只解析 reference 和共享快照，不再根据角色首次读取而创建宿主仓库副本。

**验收**：两个评审对同一 clean commit 只产出一个共享副本；并发请求只复制一次；Harness 仍无法获得物理路径。

### 步骤 3：引用生命周期与清理

1. 提供 review 删除/过期流程可调用的引用删除入口；当前产品尚无 review 删除或过期命令，取消/重试不删除历史 reference，避免破坏审计回放。
2. 新增可配置保留期的定时清理器；候选快照先检查 lastAccessedAt 和引用，再在 key 锁内二次检查后删除。
3. 清理只允许处理 `repository-snapshots/` 下合法 hash 目录，拒绝 Junction 与路径逃逸。

**验收**：无引用且过期的快照可删除；运行中重新绑定或仍有引用时不会删除。

### 步骤 4：Director 与检索安全回归

1. 以脚本化 Harness 测试验证 `plan_enter -> plan_write -> plan_exit -> todo_write` 在 `BYPASS` 下不进入 `DENIED`、不产生 HITL 等待；业务工具仍由 ReviewProtocolGuard 校验。
2. 保持五类只读仓库工具接口；`searchText` 默认字面量搜索。若保留正则，采用 RE2J 或等效线性时间实现，并限制查询与单行长度，防止 ReDoS。

## 3. 测试矩阵

| 场景 | 预期 |
|---|---|
| 两 review、同 repositoryId/commit/clean | 一个共享副本、两个 reference |
| 同 commit、dirty 内容不同 | 两个 snapshotKey、两个副本 |
| 同 key 并发创建 | 一个实际复制、所有调用返回同一共享快照 |
| 不完整共享目录 | 仅删除残留共享目录并告警重建 |
| metadata 或清单校验失败 | 保留目录并确定性报错 |
| 引用路径篡改、Junction、软链接 | 拒绝，不触达宿主路径 |
| 过期且无引用 / 仍有引用 | 前者删除，后者保留 |
| Director Plan Mode | 无 HITL 等待，协议守卫仍生效 |

## 4. 交付文件

| 文件 | 变更 |
|---|---|
| `review/domain/model/SharedRepositorySnapshot.java` | 新增共享快照元数据 |
| `review/domain/model/SnapshotReference.java` | 新增 review 引用模型 |
| `review/application/RepositorySnapshotService.java` | 重构为共享构建、校验、补偿、引用与清理编排 |
| `review/application/SharedSnapshotLifecycleService.java` | 保留期清理调度；删除/过期流程落地后调用 `removeReference` 解绑 |
| `review/infrastructure/agentscope/ReviewRepositoryToolFactory.java` | 从 reference 解析共享快照 |
| `review/infrastructure/repository/GitSnapshotReader.java` | Git 元数据和 dirty 指纹辅助能力 |
| `review/infrastructure/agentscope/ReviewDirectorHarnessFactory.java` | 补充 Plan Mode BYPASS 行为验证对应装配 |
| `src/test/java/...` | 共享、并发、补偿、迁移、清理与 BYPASS 测试 |
| `docs/AIREVIEW-PLAN-008-Harness主持人与角色编排.md` | 同步 Harness 引用边界与验证状态 |

## 5. 风险与应对

| 风险 | 应对 |
|---|---|
| dirty 指纹扫描成本 | 仅 dirty 时扫描；复用既有过滤、取消和流式哈希机制 |
| 共享目录损坏 | staging 原子发布；完整性校验；仅不完整目录自动补偿 |
| 并发重复复制或清理竞态 | snapshotKey 锁、创建前二次查找、删除前后二次引用检查 |
| 引用与清理竞态 | 删除前后均重新扫描 reference，并在 snapshotKey 锁内确认无引用 |
| 模型构造灾难性正则 | 默认字面量；正则使用线性时间引擎或受限语法 |

## 6. 变更记录

| 日期 | 变更 |
|---|---|
| 2026-07-22 | 创建共享仓库快照、引用、生命周期、迁移、并发与安全验证计划。 |
| 2026-07-22 | 删除旧私有快照迁移范围；新增启动前绑定与共享快照引用清理的实施项。 |
