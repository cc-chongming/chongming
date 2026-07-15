# 模型网关与角色包计划

> **状态**: ⏳ 待实施
> **创建日期**: 2026-07-14
> **目标**: 建立厂商无关、可测试的模型网关和配置化 RolePack，为主持人、角色 Agent 和 Judge 提供稳定结构化输出。
> **前置计划**: PLAN-002、PLAN-003

## 0. 背景与边界

项目使用公司商业合作模型，不设置额度 Gate。模型 ID、Base URL、凭证、超时和路由由部署配置注入；RolePack 只引用逻辑 profile。
本计划不组织多 Agent 流程，也不允许模型直接写业务表。

## 1. 分段方案

### 1.1 模型配置与逻辑 Profile ⏳

- 定义 `director`、`role-reviewer`、`judge`、`fallback` 四类逻辑 profile。
- 每个 profile 配置 provider、modelId、temperature、timeout、maxTokens、retryPolicy。
- 凭证使用环境变量/密钥管理，不进入配置文件、Prompt 或日志。

### 1.2 ModelGateway 抽象 ⏳

- 输入包含 reviewId、role、promptVersion、公开上下文和工具集合；输出统一 usage、latency、finishReason、公开文本。
- Adapter 屏蔽 OpenAI-compatible/DashScope formatter 差异。
- 所有调用支持协作取消、超时和 traceId。

### 1.3 结构化输出与校验 ⏳

- 为 Plan、RoleAssessment、JudgeDecision 定义 JSON Schema/Java record。
- 解析失败最多修复一次；仍失败返回可审计错误，不从自由文本猜业务字段。
- Severity、position、evidenceIds、targetIds 必须经领域层再次验证。

### 1.4 失败、重试与降级 ⏳

- 网络错误/429 指数退避最多两次；业务校验失败不盲目重试。
- 按需角色失败可部分完成；核心角色失败禁止 AI_PASS；Judge 失败交给确定性 Gate 草案。
- 全部模型不可用时只返回证据与规则结果，生成 HUMAN_REQUIRED Gate 草案并进入 WAITING_HUMAN。

### 1.5 RolePack 规范 ⏳

- 四核心：product、project、frontend、backend。
- 三按需：security、architecture、test 中按需求选择；角色库可扩展但单场上限由 Guard 控制。
- MVP 只实现上述三个按需角色；其余角色模板保留契约，不进入首期交付范围。
- Judge 只读取已落库 Claim/Evidence/Turn，不重新浏览仓库或编造新事实。
- 每个 RolePack 包含职责、关注点、允许工具、输入视图、输出 schema、promptVersion。

### 1.6 上下文组装与隔离 ⏳

- 共享事实：需求快照、仓库快照、Evidence、公开 Claim、DebateTopic/Turn。
- 私有上下文：角色会话、角色 Prompt、暂存推理；不得向其他角色复制隐藏历史。
- ContextAssembler 按预算批量加载并排序，超预算时保留 P0/P1、被质询 Claim 和最新证据。

### 1.7 模型调用审计 ⏳

- 保存模型、Prompt、RolePack、工具版本、输入哈希、耗时、Token、重试和降级原因。
- 不保存隐藏思维链；公开结果保存文本或哈希，遵循长期持久化决策。
- 日志统一脱敏 Authorization、Cookie、API Key 和连接地址凭证。

## 2. 文件清单

### 2.1 新建

| 文件                                                                                       | 计划段           | 状态 |
|------------------------------------------------------------------------------------------|---------------|----|
| `src/main/java/ai/cc/chongming/review/domain/gateway/ModelGateway.java`                  | #1.2          | ⏳  |
| `src/main/java/ai/cc/chongming/review/infrastructure/model/CommercialModelGateway.java`  | #1.1-1.4      | ⏳  |
| `src/main/java/ai/cc/chongming/review/infrastructure/model/StructuredOutputDecoder.java` | #1.3          | ⏳  |
| `src/main/java/ai/cc/chongming/review/infrastructure/model/ModelCallAuditService.java`   | #1.7          | ⏳  |
| `src/main/java/ai/cc/chongming/review/domain/role/RolePack.java`                         | #1.5          | ⏳  |
| `src/main/java/ai/cc/chongming/review/domain/role/RolePackRegistry.java`                 | #1.5          | ⏳  |
| `src/main/java/ai/cc/chongming/review/application/ReviewContextAssembler.java`           | #1.6          | ⏳  |
| `src/main/resources/roles/product.yml`                                                   | #1.5          | ⏳  |
| `src/main/resources/roles/project.yml`                                                   | #1.5          | ⏳  |
| `src/main/resources/roles/frontend.yml`                                                  | #1.5          | ⏳  |
| `src/main/resources/roles/backend.yml`                                                   | #1.5          | ⏳  |
| `src/main/resources/roles/security.yml`                                                  | #1.5          | ⏳  |
| `src/main/resources/roles/architecture.yml`                                              | #1.5          | ⏳  |
| `src/main/resources/roles/test.yml`                                                      | #1.5          | ⏳  |
| `src/main/resources/roles/judge.yml`                                                     | #1.5          | ⏳  |
| `src/test/java/ai/cc/chongming/review/model/StructuredOutputDecoderTests.java`            | #1.3          | ⏳  |
| `src/test/java/ai/cc/chongming/review/model/ModelGatewayContractTests.java`               | #1.2-1.4、#1.7 | ⏳  |
| `src/test/java/ai/cc/chongming/review/role/RolePackContractTests.java`                    | #1.5-1.6      | ⏳  |

### 2.2 修改

| 文件                                                                        | 计划段       | 状态 |
|---------------------------------------------------------------------------|-----------|----|
| `src/main/resources/application.yml`                                      | #1.1、#1.4 | ⏳  |
| `src/main/java/ai/cc/chongming/review/config/ModelGatewayProperties.java` | #1.1      | ⏳  |

## 3. 实施顺序

1. **步骤 1**：先冻结 ModelGateway、schema 和 MockModel 合约。
2. **步骤 2**：实现结构化解析失败测试与最小 Decoder。
3. **步骤 3**：实现商业模型 Adapter、超时、重试和取消。
4. **步骤 4**：配置 RolePack 和工具白名单。
5. **步骤 5**：实现上下文组装与审计，执行泄密检查。

## 4. 验证与退出标准

- 单元/合约测试不访问真实商业模型，结果确定。
- 真实模型仅执行显式冒烟：每类 profile 一次，记录模型 ID 和响应，不记录密钥。
- 非法 JSON、缺字段、伪造 Evidence、超时、429、取消均有确定结果。
- RolePack 不能绕过 Guard，也不能请求未授权工具。
- ContextAssembler SQL 为批量加载，无循环单查。

## 5. 风险与应对

| 风险          | 应对                                |
|-------------|-----------------------------------|
| 公司模型接口变化    | 变化只影响 Adapter 和配置，RolePack/领域契约不变 |
| Prompt 版本漂移 | 每次修改递增 promptVersion，评测绑定版本       |
| JSON 修复掩盖错误 | 最多一次且保存原错误摘要，领域校验仍为最终门禁           |

## 6. 变更记录

| 日期         | 变更                                            |
|------------|-----------------------------------------------|
| 2026-07-14 | 创建模型 Profile、Gateway、结构化输出、RolePack 和上下文隔离计划。 |
| 2026-07-15 | MVP 按需角色对齐需求文档：security、architecture、test。 |
