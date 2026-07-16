# PLAN-006 Repository Snapshot Verification

**验证日期**：2026-07-16  
**范围**：管理员仓库白名单、Git 快照、受限只读检索、EvidenceBlock 与工具 facade。

## 验证结果

| 验证项 | 覆盖测试 | 结果 |
|---|---|---|
| 逻辑仓库 ID、非 Git 目录与路径逃逸 | `RepositoryBoundaryGuardTests` | 仅管理员配置的 ID 可解析；路径、符号链接和真实 Windows junction 均被拒绝 |
| Git 元数据与冻结副本 | `RepositorySnapshotServiceTests` | HEAD/branch/dirty、manifest、固定快照目录和源仓库修改后的快照稳定性已验证 |
| 敏感文件与二进制隔离 | `RepositorySnapshotServiceTests`、`RepositorySearchIndexTests` | `.env`、`secret` 候选、二进制、`.git`、`target` 与 `node_modules` 不进入或不暴露给检索 |
| 受限检索与取消 | `RepositorySearchIndexTests`、`RepositoryToolFacadeTests` | 文件、文本/正则、符号候选、行范围和元数据读取均受预算与快照根限制，并响应取消 |
| 证据去重、批量校验与漂移 | `EvidenceLedgerServiceTests` | excerptHash 去重、伪造 ID、路径越界和快照文件改动均返回确定结果 |
| 工具上下文约束 | `RepositoryToolFacadeTests` | runtime/review/role/快照上下文必须匹配，facade 不接受服务器任意路径 |

## 构建证据

执行命令：

```powershell
./mvnw.cmd clean verify
```

结果：构建成功；68 项测试通过，3 项 MySQL/Testcontainers 集成测试因当前环境无 Docker daemon 而按既有条件跳过。

## 明确延后项

- `EvidenceLedgerService` 现为进程内追加账本；MyBatis 持久化会在评审执行链路开始写入 Claim/Turn 时接入。
- `ReadOnlyRepositoryTools` 与 `EvidenceTools` 已是受限服务端 facade；实际 AgentScope 工具注册由 PLAN-008 编排接入。
- `EVIDENCE_CITED`、`EVIDENCE_REJECTED` 与扫描进度事件由 PLAN-010 领域事件/SSE 通道接入。
