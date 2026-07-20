# PLAN-007 模型网关与角色包验证记录

**验证日期**：2026-07-16  
**范围**：逻辑模型 Profile、商业模型网关、结构化输出、RolePack、上下文隔离、调用审计和失败降级。

## 验证结果

| 验证项 | 覆盖测试 | 结果 |
|---|---|---|
| Profile 配置绑定 | `ModelProfilesPropertiesTests`、`ReviewPropertiesTests` | 四类逻辑 Profile 可绑定；默认关闭时不要求凭证，启用时必须提供 Base URL 和受控的 `api-key` 配置 |
| OpenAI-compatible 适配 | `OpenAiCompatibleModelClientTests` | 本地 HTTP 服务验证请求格式、鉴权头、traceId、响应归一化；测试不访问真实厂商 |
| 重试、取消和审计 | `ModelGatewayContractTests`、`ModelFailurePolicyTests` | 429 触发一次退避重试；取消/禁用返回稳定错误；审计只保存哈希和计量信息，失败降级为确定性处置 |
| 结构化输出 | `StructuredOutputDecoderTests` | 合法记录可解析；未知字段被拒绝；修复回调最多一次，二次失败返回确定错误 |
| RolePack 与工具边界 | `RolePackContractTests` | 八个角色包均可加载，角色类型不重复，工具必须来自服务端白名单 |
| 上下文隔离与预算 | `ReviewContextAssemblerTests` | 私有选择器不会进入角色上下文；关键事实、争议状态、更新时间和字符预算按确定顺序处理 |
| Spring 集成 | `ChongmingApplicationTests` | 网关组件、条件化 JSON Mapper 与应用上下文可共同启动 |

## 构建证据

执行命令：

```powershell
./mvnw.cmd clean verify
```

结果：构建成功；78 项测试通过，3 项 MySQL/Testcontainers 集成测试因当前环境无 Docker daemon 按既有条件跳过。

## 明确延后项

- 真实商业模型冒烟：部署方配置受控 Base URL、模型 ID 与 Git 忽略的 profile 专属凭证后，每个 Profile 执行一次；不得记录密钥或完整 Prompt。
- `ModelCallAuditService` 当前是进程内审计投影，MyBatis 持久化与领域事件接入后续执行链路。
- 模型候选输出转换为 Claim/Gate 前的 Evidence、目标和状态机再校验，以及 AgentScope 工具注册/Harness 编排，留给协议守卫和 PLAN-008。
