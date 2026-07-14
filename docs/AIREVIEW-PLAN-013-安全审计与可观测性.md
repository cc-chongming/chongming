# 安全、审计与可观测性计划

> **状态**: ⏳ 待实施
> **创建日期**: 2026-07-14
> **目标**: 对输入、仓库、Agent 工具、人工接口、模型和通知建立信任边界，并提供可诊断、可审计的运行观测。
> **前置计划**: PLAN-002、PLAN-003；贯穿所有后续计划

## 0. 背景与边界

需求、代码、README、模型输出和 MCP 响应都属于不可信数据。安全不能只依赖 Prompt，必须由输入校验、目录边界、权限白名单、ProtocolGuard、
版本化人工决定和审计共同保证。身份认证方案尚未确认，生产发布前必须完成该决策。

## 1. 分段方案

### 1.1 威胁模型与信任边界 ⏳

- 资产：需求/代码、密钥、模型上下文、评审结论、人工决定、通知凭证。
- 边界：Web 输入、仓库文件、Agent 工具、模型服务、MCP、数据库、浏览器。
- 输出攻击树和风险登记表，Critical/High 必须有测试和处置人。

### 1.2 Web 输入与输出安全 ⏳

- DTO 使用 Validation，统一错误响应，禁止回显内部异常和绝对敏感路径。
- Markdown、代码和模型文本输出前转义/净化；响应头设置 CSP、nosniff、frame 限制。
- 限制 multipart 部署级资源、请求超时和并发，区别于业务仓库规模限制。

### 1.3 仓库与 workspace 权限 ⏳

- 路径白名单、realPath、symlink/junction、敏感文件过滤形成双层校验。
- Harness/角色工具显式 allowlist；禁用 shell、写源仓库、执行和任意网络。
- PermissionEngine 拒绝必须产生日志/事件，不能仅返回模型可忽略文本。

### 1.4 Prompt Injection 与跨对象越权 ⏳

- 构造需求/代码中的 system override、工具调用、数据外传指令。
- Claim/Evidence/Turn/Review 所有 ID 校验归属，拒绝跨 review/attempt 引用。
- 主持人不能直接写业务表，只能通过带 Guard 的工具。

### 1.5 人工审核认证与授权 ⏳

- 隔离 ReviewerIdentityProvider，定义 reviewer/admin/override 权限。
- 未确定企业认证接入前，production profile 禁止最终提交和 override。
- 审核草稿、最终版本和 override 访问均有授权测试和审计。

### 1.6 密钥与依赖安全 ⏳

- API Key、DB/MCP 凭证全部环境注入；配置样例只用占位符。
- 检查 Git diff、日志、异常、模型元数据和测试夹具中的密钥。
- Maven 依赖漏洞扫描；Critical/High 阻断，例外必须有理由、到期日和负责人。

### 1.7 审计模型 ⏳

- audit_event 记录 actor、action、resource、before/after hash、reason、traceId、occurredAt。
- 只追加；业务代码无 update/delete Mapper；不保存隐藏思维链。
- 提供按 review/actor/action 查询和导出，不在循环中单查详情。

### 1.8 日志、指标和追踪 ⏳

- 结构化日志统一 reviewId、attempt、stage、role、topicId、traceId。
- 指标：阶段耗时、Agent 成败、模型延迟/重试、工具拒绝、SSE 连接、Outbox、恢复次数。
- Actuator 仅暴露必要 health/metrics；敏感端点不公开。

### 1.9 告警与运行手册 ⏳

- 告警：核心角色失败、事件 sequence 冲突、恢复循环、Outbox DEAD、数据库连接耗尽。
- 每个告警给出定位 SQL/日志字段、恢复动作和升级条件。
- Demo 环境至少有可见健康页，不依赖外部监控平台。

## 2. 文件清单

### 2.1 新建

| 文件                                                                                       | 计划段       | 状态 |
|------------------------------------------------------------------------------------------|-----------|----|
| `docs/安全/ThreatModel.md`                                                                 | #1.1      | ⏳  |
| `docs/安全/SecurityRiskRegister.md`                                                        | #1.1、#1.6 | ⏳  |
| `src/main/java/ai/cc/chongming/review/api/GlobalExceptionHandler.java`                   | #1.2      | ⏳  |
| `src/main/java/ai/cc/chongming/review/config/ReviewSecurityConfiguration.java`           | #1.2、#1.5 | ⏳  |
| `src/main/java/ai/cc/chongming/review/infrastructure/security/PromptInjectionGuard.java` | #1.4      | ⏳  |
| `src/main/java/ai/cc/chongming/review/infrastructure/audit/AuditService.java`            | #1.7      | ⏳  |
| `src/main/java/ai/cc/chongming/review/infrastructure/observability/ReviewMetrics.java`   | #1.8      | ⏳  |
| `src/test/java/ai/cc/chongming/review/security/InputOutputSecurityTest.java`             | #1.2      | ⏳  |
| `src/test/java/ai/cc/chongming/review/security/AgentToolPermissionTest.java`             | #1.3-1.4  | ⏳  |
| `src/test/java/ai/cc/chongming/review/security/HumanReviewAuthorizationTest.java`        | #1.5      | ⏳  |
| `src/test/java/ai/cc/chongming/review/audit/AuditAppendOnlyIntegrationTest.java`         | #1.7      | ⏳  |
| `docs/运维/ReviewAgentRunbook.md`                                                          | #1.9      | ⏳  |

### 2.2 修改

| 文件                                   | 计划段            | 状态 |
|--------------------------------------|----------------|----|
| `pom.xml`                            | #1.6、#1.8      | ⏳  |
| `src/main/resources/application.yml` | #1.2、#1.5、#1.8 | ⏳  |
| 各计划新增 Controller/Tool/Adapter        | #1.2-1.8       | ⏳  |

## 3. 实施顺序

1. **步骤 1**：在功能开发前完成威胁模型和安全测试夹具。
2. **步骤 2**：随 PLAN-005/006 落地输入、路径和 workspace 安全。
3. **步骤 3**：随 PLAN-008/009 落地工具权限、注入和跨对象授权。
4. **步骤 4**：随 PLAN-011 落地人工认证门禁和审计。
5. **步骤 5**：统一日志、指标、依赖扫描和运行手册。

## 4. 验证与退出标准

- 所有目录逃逸、Prompt Injection、伪造 ID、越权角色和敏感文件用例被确定性拒绝。
- 无未处置 Critical/High 漏洞；无明文密钥或敏感日志。
- production profile 在身份/override 策略未配置时无法提交最终 Gate。
- 审计事件不可通过应用 Mapper 修改/删除，且可按 review 一次批量导出。
- 关键失败能通过 health、metrics、traceId 和运行手册定位。

## 5. 风险与应对

| 风险          | 应对                                              |
|-------------|-------------------------------------------------|
| 安全作为最后阶段补做  | 本计划为贯穿计划，每个专项退出前执行相关子集                          |
| 认证未决影响 Demo | Demo profile 明确标记本地身份；production profile 强制关闭提交 |
| 日志过多泄露内容    | 默认记录 ID/哈希/摘要，正文只在受控报告中展示                       |

## 6. 变更记录

| 日期         | 变更                           |
|------------|------------------------------|
| 2026-07-14 | 创建威胁模型、权限、注入防护、审计、指标和运行手册计划。 |
