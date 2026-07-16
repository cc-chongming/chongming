# Harness 主持人与角色编排验证记录

> **对应计划**: PLAN-008  
> **日期**: 2026-07-16  
> **结论**: 主体编排实现通过；多实例租约、领域业务工具和生产模型联调移交后续计划。

## 已验证能力

- Director 与角色运行时由 `ReviewRuntimeContext` 推导稳定 label/session，调用方不能伪造 role 身份。
- Director 和角色 Harness 显式禁用 shell、文件系统、memory、动态 skill/subagent；仅允许 RolePack 工具和 AgentScope 内置计划工具。
- 工作区固定为 `reviews/{reviewId}/.../attempts/{attempt}`，角色使用独立目录；公开 artifact 带 schemaVersion 与 SHA-256。
- 总计划与修订计划记录版本、原因和编排事件；四个核心角色经 ProtocolGuard 批准后启动。
- 角色激活携带 PLAN/RULE/HUMAN 来源，重复或超上限请求在创建运行时前拒绝。
- 单 JVM 内同一 review 只允许一个活动 Director；取消传播到已注册 agent、释放下一 attempt，并禁止恢复已取消 runtime。
- 未知 AgentScope 事件安全忽略；原始事件仅产生红脱敏运行观测，不替代业务事实。
- 缺失运行时可按既有 director/role session 重建，且不会重复写入领域角色激活。

## 验证命令

```powershell
$env:JAVA_HOME='C:\Dev\Java\jdk-21.0.10'
$env:Path="$env:JAVA_HOME\bin;$env:Path"
.\mvnw.cmd '-Dtest=ReviewWorkspaceLayoutTests,AgentEventAdapterTests,AgentScopeModelBridgeTests,RoleSubagentIsolationTests,AgentScopeReviewRuntimeAdapterTests,ReviewOrchestrationServiceTests,ReviewRecoveryServiceTests' test
```

结果：10 tests run，0 failures，0 errors，0 skipped。

完整验证：.\mvnw.cmd clean verify 通过，88 tests run，0 failures，0 errors，3 skipped；跳过项均为当前机器未提供 Docker daemon 时的 Testcontainers MySQL 集成测试。

## 已知边界

- 数据库 lease、多实例重启扫描、持久化领域事件与 SSE 由 PLAN-010 提供。
- Claim、Debate、Judge、Gate 及把不可变 `RepositorySnapshot` 绑定到 AgentScope 工具的正式调用链由 PLAN-009 提供。
- 生产商业模型 smoke test 需用户后续填入真实模型配置；本轮真实 Harness 测试使用无凭证的确定性 ModelGateway。
