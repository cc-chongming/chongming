# 对抗辩论、Judge 与 Gate 验证记录

> **对应计划**: PLAN-009  
> **日期**: 2026-07-16  
> **结论**: 强类型 Claim、冲突候选、两轮定向辩论、Judge 采信/拒绝清单和非最终 Gate 草案已通过自动化验证。

## 已验证能力

- Claim 仅在首轮阶段由已激活的非 Director/Judge 角色提交；证据必须归属当前 Review，P0/P1 无证据自动降为 `UNVERIFIED`，重复命令返回原 Claim。
- 每次 Claim 提交都会不可逆地标记角色的首审完成；在四个核心角色都完成前，首轮 Claim 不会公开。
- ConflictDetector 以相同 subject 的相反立场、相差至少两级的严重度，以及同一 Evidence 被相反立场引用产生稳定排序的候选，并对没有规则命中的 subject 给出原因。
- Challenge 必须指向真实 Claim，且必须携带已验证 Evidence 或显式 evidence gap；Rebuttal 必须指向同一 topic/round 的真实 Turn。
- 第二轮上限在服务端状态机中强制执行；相同角色对相同 Claim 的原样第一轮质询在第二轮被拒绝。Evidence request 只生成可追溯请求 Turn，不制造证据。
- Judge 只能引用其终态 topic 已有的 Claim，并分别记录采信和拒绝清单；Gate 在每个 topic 都已有 Judge 结论后才能生成。
- Gate 只能由 AI 写为 `DRAFT`。无证据的 P0/P1 优先生成 `HUMAN_REQUIRED` 并转换 Review 到 `WAITING_HUMAN`；P1 反对意见的默认非最终结果可由 `review.gate.p1-oppose-result` 配置。

## 验证命令

```powershell
$env:JAVA_HOME='C:\Dev\Java\jdk-21.0.10'
$env:Path="$env:JAVA_HOME\bin;$env:Path"
.\mvnw.cmd '-Dtest=ClaimServiceTests,ConflictDetectorTests,DebateToolsContractTests,JudgeServiceTests,GatePolicyTests,DebateGoldenPathIntegrationTests' test
.\mvnw.cmd test
```

结果：聚焦测试 9 个通过；完整测试 98 个通过、0 失败、0 错误、3 个跳过。

## 已知部署边界

- 当前默认 `InMemoryReviewDebateStore` 只用于无真实数据库配置时的协议与回归测试，进程重启后不会保留辩论事实。
- 将其替换为 MyBatis 命令写入时，必须把 Review 乐观锁、幂等结果和 Claim/Topic/Turn/Judge/Gate 同事务提交；不能把内存实现当成生产持久化。
- `DebateTools` 是服务端强类型门面。将这些命令注册为 AgentScope 可调用工具、并由 Director 通过同步 `agent_send` 驱动质询，需要与真实数据库事务一起完成运行时联调。
- 3 个跳过的测试是 Testcontainers MySQL 集成测试；当前机器未提供 Docker daemon。