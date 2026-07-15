# Markdown 受理与需求快照计划

> **Status**: PARTIALLY COMPLETE（文件系统受理链路与非数据库测试已完成；MyBatis/Flyway 持久化和 Docker 集成验证待环境就绪后完成。）

## 0. 背景与边界

MVP 只接收 UTF-8 `.md` 文件，不解析 Word/PDF。当前不设置业务文件大小上限，但必须流式读取、支持取消、监控资源，并受 Web 容器和部署环境安全上限保护。原始文档视为不可信输入，不能修改系统 Prompt、工具权限或 Gate 规则。

## 1. 分段方案

### 1.1 Upload API contract — DONE

- `POST /api/reviews` 使用 multipart：Markdown 文件、仓库路径、可选 branch/commit、提交人信息。
- 返回 202、reviewId、attempt、snapshotHash、状态链接及幂等重放标记。
- [OpenAPI 契约](openapi/review-intake.yaml) 明确 202、400、409、413、422、500 的 RFC 9457 问题响应结构。

### 1.2 File type and encoding validation — DONE

- 文件名扩展必须 `.md`；校验内容为严格 UTF-8 文本且拒绝空文件、二进制、NUL 和不允许的控制字符。
- MIME 只作辅助信息，不信任客户端 Content-Type；文件名禁止路径穿越、绝对路径和服务文件覆盖。

### 1.3 Normalization and hashing — DONE

- 原始字节受控暂存；另生成换行和 Unicode NFC 规范化后的解析文本。
- `sourceHash` 基于原始字节，`contentHash` 基于规范化文本。
- 同提交人、仓库快照和 contentHash 的重复请求返回原 review；`forceNewAttempt=true` 创建下一不可变 attempt。

### 1.4 Requirement structure extraction — DONE

- 用确定性、逐行解析提取标题、标题层级、表格、代码块和链接；模型增强留给后续异步阶段。
- 生成 `RequirementSection` 并保留 `sourceLine`，供后续 `EvidenceBlock` 引用需求原文。
- Prompt Injection 只作为中英文文本标记保存，绝不执行文档内指令。

### 1.5 Immutable snapshot and audit — PARTIAL

- 已完成受控 workspace 快照：`reviews/{reviewId}/attempt-{attempt}/input/` 中的原文、规范化文本和 `snapshot-manifest.json` 作为 staging 目录一次原子发布。
- manifest 记录快照 ID、review/attempt、提交人、仓库标识、文件名、双哈希、解析器版本、时间与解析结构；已发布 attempt 不会覆盖。
- 用户修改需求只能建立新 attempt；取消或落盘失败会清理 staging，最终目录不会出现半成品。
- 待完成：在同一短事务中写入 `requirement_snapshot`、原始内容、规范化内容和结构化 section，并与文件系统目录建立故障恢复策略。

### 1.6 API and persistence integration tests — PARTIAL

- 已完成 JUnit/MockMvc 覆盖：正常 Markdown、空文件、乱码、伪扩展名、路径文件名、重复上传、超长单行、取消、并发重放、manifest、缺失 multipart/参数、413、422、500。
- 已完成纯文件系统下的快照原子性、取消清理和进程内幂等验证。
- 待完成：使用 MySQL Testcontainers 验证事务、数据库幂等、回滚、孤儿清理与容器实际大小限制；当前本机无 Docker，相关测试继续跳过。

## 2. 已实现文件

| 文件 | 计划段 | 状态 |
|---|---|---|
| `docs/openapi/review-intake.yaml` | #1.1 | DONE |
| `review/api/ReviewCommandController.java`、`ReviewIntakeExceptionHandler.java` | #1.1 | DONE |
| `review/application/ReviewIntakeService.java`、`IntakeCancellation.java` | #1.1、#1.5 | DONE（文件系统范围） |
| `review/domain/model/RequirementSnapshot.java` | #1.3-1.5 | DONE（内存/manifest 范围） |
| `review/infrastructure/document/MarkdownRequirementValidator.java` | #1.2-1.3 | DONE |
| `review/infrastructure/document/MarkdownRequirementParser.java` | #1.4 | DONE |
| `review/infrastructure/document/RequirementSnapshotStore.java` | #1.5 | DONE（文件系统范围） |
| API、应用服务与文档解析的对应测试类 | #1.1-1.6 | DONE |

## 3. 已实现受理顺序

1. 冻结 multipart 与 RFC 9457 错误响应契约。
2. 流式暂存原始上传，严格校验 UTF-8、控制字符和安全文件名。
3. 生成规范化文本与双 SHA-256，逐行提取确定性结构。
4. 在进程内根据提交人与内容哈希做幂等判定；必要时创建下一 attempt。
5. 将完整 attempt 写入 staging 目录，并原子移动为最终 `input/` 快照目录。
6. 删除临时上传文件；数据库事务与跨介质恢复将在后续持久化工作中接入。

## 4. 验证与退出标准

- 已验证：只接受 `.md`，空文件、Word/PDF、二进制和路径逃逸返回 422。
- 已验证：原始文件、规范化内容、sourceLine 和哈希可相互校验；manifest 与文件同目录原子发布。
- 已验证：同进程并发重复提交只生成一个初始快照，`forceNewAttempt` 才创建下一个 attempt。
- 已验证：上传内容无法逃逸受控 workspace，也无法改变 Prompt/权限；取消不会发布最终目录。
- 待验证：数据库失败时的事务回滚和无孤儿保证，以及 Docker 环境中的完整 MySQL 集成。

## 5. 待完成工作

1. Flyway migration、MyBatis `RequirementSnapshotRepository` 与 review 请求/快照短事务。
2. 数据库唯一约束或 claim，替代进程内幂等以支持多实例。
3. 数据库和文件系统双写的恢复/孤儿清理策略。
4. Docker 环境中的 MySQL Testcontainers、容器上传大小限制与部署资源指标验证。

## 6. 变更记录

| 日期 | 变更 |
|---|---|
| 2026-07-14 | 创建 Markdown 受理、双哈希、结构化解析和快照计划。 |
| 2026-07-15 | 完成非数据库的 API、流式校验、原子文件系统快照、manifest、取消、错误契约和自动化验证；明确数据库与 Docker 待办。 |
