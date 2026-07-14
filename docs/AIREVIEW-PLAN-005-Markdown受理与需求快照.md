# Markdown 受理与需求快照计划

> **状态**: ⏳ 待实施
> **创建日期**: 2026-07-14
> **目标**: 安全受理 Markdown 需求文档，生成不可变、可去重、可追溯的需求快照。
> **前置计划**: PLAN-003、PLAN-004

## 0. 背景与边界

MVP 只接收 UTF-8 `.md` 文件，不解析 Word/PDF。当前不设置业务文件大小上限，但必须流式读取、支持取消、监控资源，并受 Web 容器和部署环境
安全上限保护。原始文档视为不可信输入，不能修改系统 Prompt、工具权限或 Gate 规则。

## 1. 分段方案

### 1.1 上传 API 契约 ⏳

- `POST /api/reviews` 使用 multipart：Markdown 文件、仓库路径、可选 branch/commit、提交人信息。
- 返回 202、reviewId、attempt、snapshotHash 和状态链接。
- OpenAPI/DTO 明确 400、409、413/容器限制、422、500 错误结构。

### 1.2 文件类型与编码验证 ⏳

- 文件名扩展必须 `.md`；校验内容为 UTF-8 文本且拒绝二进制/NUL。
- MIME 只作辅助判断，不能单独信任客户端 Content-Type。
- 文件名做路径剥离，禁止 `../`、绝对路径和覆盖服务文件。

### 1.3 规范化与哈希 ⏳

- 原始字节原样保存；另生成 BOM、换行和 Unicode 规范化后的解析文本。
- `sourceHash` 基于原始字节，`contentHash` 基于规范化文本。
- 同提交人、仓库快照和 contentHash 的重复请求返回原 review 或显式新 attempt。

### 1.4 需求结构化 ⏳

- 先用确定性解析提取标题、标题层级、表格、代码块和链接；模型增强放后续异步阶段。
- 生成 RequirementSection，保留 sourceLine，供 EvidenceBlock 引用需求原文。
- Prompt Injection 只作为文本标记，不执行文档内指令。

### 1.5 不可变快照与审计 ⏳

- 写入 `requirement_snapshot` 和受控 workspace `input/requirement.md`。
- 快照创建后不更新；用户修改需求必须创建新 review version/attempt。
- 记录提交人、时间、文件名、哈希、解析器版本和验证结果。

### 1.6 API 与持久化集成测试 ⏳

- 覆盖正常 Markdown、空文件、乱码、伪扩展名、路径文件名、重复上传、超长单行和取消。
- 使用 MockMvc + MySQL Testcontainers 验证响应、事务和快照内容。

## 2. 文件清单

### 2.1 新建

| 文件                                                                                               | 计划段       | 状态 |
|--------------------------------------------------------------------------------------------------|-----------|----|
| `src/main/java/ai/cc/chongming/review/api/ReviewCommandController.java`                          | #1.1      | ⏳  |
| `src/main/java/ai/cc/chongming/review/api/dto/CreateReviewResponse.java`                         | #1.1      | ⏳  |
| `src/main/java/ai/cc/chongming/review/application/ReviewIntakeService.java`                      | #1.1、#1.5 | ⏳  |
| `src/main/java/ai/cc/chongming/review/domain/model/RequirementSnapshot.java`                     | #1.3-1.5  | ⏳  |
| `src/main/java/ai/cc/chongming/review/infrastructure/document/MarkdownRequirementValidator.java` | #1.2      | ⏳  |
| `src/main/java/ai/cc/chongming/review/infrastructure/document/MarkdownRequirementParser.java`    | #1.4      | ⏳  |
| `src/main/java/ai/cc/chongming/review/infrastructure/document/RequirementSnapshotStore.java`     | #1.5      | ⏳  |
| `src/test/java/ai/cc/chongming/review/api/ReviewCommandControllerTest.java`                      | #1.1、#1.6 | ⏳  |
| `src/test/java/ai/cc/chongming/review/document/MarkdownRequirementValidatorTest.java`            | #1.2-1.4  | ⏳  |
| `src/test/java/ai/cc/chongming/review/document/ReviewIntakeIntegrationTest.java`                 | #1.5-1.6  | ⏳  |

### 2.2 修改

| 文件                                                                                                                  | 计划段  | 状态 |
|---------------------------------------------------------------------------------------------------------------------|------|----|
| `src/main/resources/application.yml`                                                                                | #1.1 | ⏳  |
| `src/main/java/ai/cc/chongming/review/infrastructure/persistence/repository/RequirementSnapshotRepositoryImpl.java` | #1.5 | ⏳  |

## 3. 实施顺序

1. **步骤 1**：冻结 multipart 和错误响应契约，先写 MockMvc 失败测试。
2. **步骤 2**：实现编码、文件类型和文件名验证。
3. **步骤 3**：实现双哈希与结构化解析。
4. **步骤 4**：接入不可变快照和幂等事务。
5. **步骤 5**：补安全、取消和数据库集成测试。

## 4. 验证与退出标准

- 只接受 `.md`，Word/PDF/二进制明确返回 422。
- 原始文件、规范化内容、sourceLine 和哈希可相互校验。
- 重复请求不会产生孤立快照或重复 review_event。
- 上传内容无法逃逸受控 workspace，也无法改变 Prompt/权限。
- 大文档使用流式读取；测试记录峰值内存和取消响应时间。

## 5. 风险与应对

| 风险                  | 应对                                  |
|---------------------|-------------------------------------|
| “不设业务上限”造成内存溢出      | 不 `readAllBytes`，流式哈希和解析；部署上限显式记录   |
| Markdown 解析库处理 HTML | 默认把内嵌 HTML 当文本，前端渲染时转义/净化           |
| 重复上传语义不清            | API 提供 `forceNewAttempt` 明确选择，不静默复制 |

## 6. 变更记录

| 日期         | 变更                             |
|------------|--------------------------------|
| 2026-07-14 | 创建 Markdown 受理、双哈希、结构化解析和快照计划。 |
