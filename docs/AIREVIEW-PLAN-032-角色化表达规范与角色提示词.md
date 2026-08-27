> **状态**: ✅ 全部完成
> **创建日期**: 2026-08-26
> **目标**: 参考 ECC 角色级系统提示词资产，为重明每个评审角色配置角色化「表达规范」（身份、岗位词汇、禁用术语、协议词中文化），消除评审输出中英夹杂与角色串味问题。

---

## 背景

重明多角色对抗评审的公开输出存在三个已确认问题（经 77f85913… 评审快照量化验证，正文英文占比 ~32%）：

1. **中英夹杂**：正文大量回显协议词汇（OPPOSE/SUPPORT/CHALLENGE/EVIDENCE_REQUEST/Claim/Assessment/Judge/Gate/claimId/command.mysql_authority）。
2. **非技术角色使用技术术语**：产品经理、项目经理直接使用 @Transactional、仓储层、InMemory、MyBatisReviewEventStore.append 等实现细节作为主要论据。
3. **产品经理缺少产品语言**：无目标用户、场景、价值、成本收益、优先级等本职词汇，通篇是「范畴混淆/命题成立/验收证据」等评审方法论套话。

**根因**：所有核心角色的系统提示词由 RoleSubagentFactory.rolePrompt() 同一模板拼装；唯一的表达约束是所有角色一字不差的 “Use Simplified Chinese for every visible response…”。角色差异仅在于一行英文 description、一句 workflow、若干英文 checklist 指令，提示词层面不存在按角色的表达规范。

**约束条件**：不改评审协议/状态机/工具白名单/事件持久化/前端功能；checkpointKey 为持久化稳定键，不得变更；历史评审数据不受影响（instruction 仅进入新评审的提示词）。

**参考素材**（E:\plus\ECC）：

| 重明角色 | ECC 参考 | 取其精髓 |
|---|---|---|
| PRODUCT | skills/product-lens、product-capability | 用户-痛点-时机-价值-反目标-验收判据；产品经理词汇 |
| PROJECT | agents/planner.md | 需求分析/依赖/风险/分阶段/验收条件；交付视角 |
| BACKEND | agents/java-reviewer.md、code-reviewer.md | 事务边界/数据一致性/API 契约/幂等；证据纪律（可引用行、具体失败模式、>80% 才上报） |
| ARCHITECTURE | agents/architect.md | 模块化/耦合/可扩展/权衡（Pros-Cons-Alternatives） |
| SECURITY | agents/security-reviewer.md | 信任边界/认证授权/输入校验/敏感数据/OWASP |
| FRONTEND | agents/react-reviewer.md | 交互流/UI 状态/加载错误恢复态/可访问性 |
| TESTING | agents/tdd-guide.md、skills/verification-loop | 验收→用例推导/边界与回归/最小确定性证据 |
| JUDGE | agents/code-reviewer.md 的证据纪律 | 只依据已落库事实/区分不确定与已证伪/结论人话化 |

---

## 分段方案

### 段 0：素材梳理与计划文档（✅ 已完成）

- 梳理 ECC skills/agents 参考资产并形成上文映射表（本文件即产出）。
- 识别全部改造触点：RolePack 模型、Registry 解析、RoleSubagentFactory 三处 prompt、8 个角色 yml、4 处 RolePack 构造点（1 生产 + 3 测试）、3 个相关测试文件、2 份文档。

### 段 1：RolePack 模型支持「表达规范」（voice）

- 目标：让角色包数据能携带中文身份/词汇/禁用项/检查点视角，并保持可选兼容。
- 涉及文件：
  - 新建：无
  - 修改：src/main/java/ai/cc/chongming/review/domain/role/RolePack.java
  - 修改：src/main/java/ai/cc/chongming/review/domain/role/RolePackRegistry.java
  - 修改（同步构造点）：src/test/java/ai/cc/chongming/review/role/RolePackContractTests.java、src/test/java/ai/cc/chongming/review/agentscope/ReviewRepositoryToolFactoryTests.java、src/test/java/ai/cc/chongming/review/application/ReviewContextAssemblerTests.java
- 关键细节：
  - RolePack 新增字段 `Voice voice`（可为 null ⇒ 空 voice，兼容既有构造）。
  - `record Voice(String identity, List<String> focus, List<String> avoid, String lens)`，内部校验非空；提供 isEmpty()。
  - Registry 解析 YAML 可选 `voice:` 块；缺失时不抛错（voice = null）。

### 段 2：角色文案（8 个 roles yml）

- 目标：每个角色获得中文身份、岗位词汇、禁用术语、检查点表达要求；checklist 指令改写成角色母语但保留协议机制词（OPPOSE/SUPPORT/claim 等）以兼容契约测试与工具语义。
- 涉及文件（全部修改）：src/main/resources/roles/{product,project,backend,frontend,architecture,security,test,judge}.yml
- 关键细节：
  - 新增 `voice:` 段（identity/focus/avoid/lens），聚焦各自 ECC 参考；
  - checklist instruction 中文化，checkpointKey 与 required 保持不变；
  - promptVersion 递增：product-v4、project-v3、backend-v3、frontend-v3、architecture-v2、security-v2、testing-v2、judge-v2；
  - description 保留英文（agent description 契约），中文身份放 voice.identity。

### 段 3：RoleSubagentFactory 提示词组装

- 目标：把「公共表达规范 + 角色 voice」注入三类提示词（rolePrompt / finalizationPrompt / JUDGE prompt）。
- 涉及文件：src/main/java/ai/cc/chongming/review/infrastructure/agentscope/RoleSubagentFactory.java
- 关键细节：
  - 新增公共常量块【表达规范·通用】：简体中文；必要代码标识保留但须中文说明；协议词中文化映射（OPPOSE→反对、SUPPORT→支持、CHALLENGE→质询、REBUTTAL→答辩、EVIDENCE_REQUEST→证据请求、Claim→主张、Assessment→评估、Judge→裁决、Gate→门禁）；claimId/subjectKey/commandId/topicId/checkpointKey/角色代号等机器标识禁止进入正文；结论须可被他人验证。
  - 新增静态方法 `roleVoiceGuidance(RolePack)`（package-private static，便于单测）渲染 voice；
  - rolePrompt() 在原有 “Use Simplified Chinese…” 处替换为公共块 + 角色 voice；
  - finalizationPrompt() 与 JUDGE prompt 追加公共块（Judge 另加 judge voice 的 lens）。
  - 代码注释标注 [AIREVIEW-PLAN-032#3.x]。

### 段 4：测试

- 目标：保证 voice 解析、渲染与既有契约不回退。
- 涉及文件：
  - 新建：src/test/java/ai/cc/chongming/review/role/RoleVoicePromptTests.java
  - 修改：src/test/java/ai/cc/chongming/review/role/RolePackContractTests.java（新增 voice 断言；校验 8 个角色 voice 齐全、核心角色 checklist 仍含 OPPOSE/SUPPORT 协议词）
  - 运行：./mvnw.cmd test（至少 role 包 + agentscope 包相关测试，随后全量）

### 段 5：文档与收尾

- 目标：按规则 4/5/6 完成文档同步与收尾动作。
- 涉及文件：
  - 修改：docs/AIREVIEW-PLAN-007-模型网关与角色包.md（补充 voice 说明与提示词版本表）
  - 修改：docs/验证记录/ModelGatewayRolePackReport.md（补充 voice 验证记录）
  - 修改：.learnings/LEARNINGS.md（新增本次 learning 条目）
  - 说明：本变更不涉及目录结构/API/流程图变化，AGENTS.md 无需同步。

---

## 文件清单

### 新建

| 文件 | 计划段 | 状态 |
|------|--------|------|
| docs/AIREVIEW-PLAN-032-角色化表达规范与角色提示词.md | 段 0 | ✅ |
| src/test/java/ai/cc/chongming/review/infrastructure/agentscope/RoleVoicePromptTests.java | #4.1 | ✅ |

### 修改

| 文件 | 计划段 | 状态 |
|------|--------|------|
| src/main/java/ai/cc/chongming/review/domain/role/RolePack.java | #1.1 | ✅ |
| src/main/java/ai/cc/chongming/review/domain/role/RolePackRegistry.java | #1.2 | ✅ |
| src/main/resources/roles/product.yml | #2.1 | ✅ |
| src/main/resources/roles/project.yml | #2.2 | ✅ |
| src/main/resources/roles/backend.yml | #2.3 | ✅ |
| src/main/resources/roles/frontend.yml | #2.4 | ✅ |
| src/main/resources/roles/architecture.yml | #2.5 | ✅ |
| src/main/resources/roles/security.yml | #2.5 | ✅ |
| src/main/resources/roles/test.yml | #2.5 | ✅ |
| src/main/resources/roles/judge.yml | #2.6 | ✅ |
| src/main/java/ai/cc/chongming/review/infrastructure/agentscope/RoleSubagentFactory.java | #3.1/#3.2/#3.3/#3.4 | ✅ |
| src/test/java/ai/cc/chongming/review/role/RolePackContractTests.java | #1.3/#4.2 | ✅ |
| src/test/java/ai/cc/chongming/review/agentscope/ReviewRepositoryToolFactoryTests.java | #1.3 | ✅ |
| src/test/java/ai/cc/chongming/review/application/ReviewContextAssemblerTests.java | #1.3 | ✅ |
| docs/AIREVIEW-PLAN-007-模型网关与角色包.md | #5.1 | ✅ |
| docs/验证记录/ModelGatewayRolePackReport.md | #5.2 | ✅ |
| .learnings/LEARNINGS.md | #5.3 | ✅ |

---

## 实施顺序

1. **段 0** ✅ 素材梳理与计划文档（本文件）。
2. **段 1** ✅ RolePack 增加 Voice、Registry 解析、同步 3 处测试构造点。
3. **段 2** ✅ 起草并写入 8 个角色 yml 的 voice 与中文化 checklist/promptVersion。
4. **段 3** ✅ RoleSubagentFactory 注入公共表达规范与每角色 voice（rolePrompt/finalizationPrompt/JUDGE）。
5. **段 4** ✅ 补充/更新测试；相关测试类全部通过（JDK 21）。
6. **段 5** ✅ 更新计划文档状态、AIREVIEW-PLAN-007、验证记录、LEARNINGS；全量 mvnw test 构建成功（725 项，0 失败，0 错误，30 跳过），git status 与文件清单核对一致。

---

## 风险与应对

| 风险 | 应对 |
|------|------|
| checklist instruction 改写破坏契约测试（OPPOSE/SUPPORT 断言） | instruction 保留协议机制词，测试断言继续成立；同步更新测试 |
| RolePack 增加字段破坏 4 处构造点 | 已识别全部调用点（1 生产 + 3 测试），一次性同步 |
| 提示词约束非硬保证，模型仍可能回显协议词 | 公共规范明确禁止；后续可视效果增加前端弱提示/输出过滤（本期不做，记为后续项） |
| 新增 voice 增加 token 开销 | 每角色控制在 ~400 字符内，8 角色合计 < 4K token，可接受 |
| promptVersion 变更影响历史评审展示 | promptVersion 仅进入新生成提示词，历史数据不受影响 |

---

## 变更记录

| 日期 | 变更 |
|------|------|
| 2026-08-26 | 创建计划文档（段 0）；按 plan-driven-development 规则 1/2 落盘 |
| 2026-08-26 | 段 1–4 完成：RolePack/RolePackRegistry 解析 voice；8 个 yml 配置 voice 并中文化 checklist（product-v4/project-v3/backend-v3/frontend-v3/architecture-v2/security-v2/testing-v2/judge-v2）；RoleSubagentFactory 注入公共表达规范与每角色 voice；新增 RoleVoicePromptTests、更新 RolePackContractTests 等，相关测试全部通过。偏差记录：RoleVoicePromptTests 落在 infrastructure.agentscope 包（与工厂同包便于访问包私有静态方法）；product.core_value_stance 契约断言由小写 oppose 改为大写 OPPOSE 以匹配中文文案保留的协议机制词。 |
| 2026-08-26 | 段 5 收尾：AIREVIEW-PLAN-007、ModelGatewayRolePackReport、LEARNINGS 已更新；全量 mvnw test 构建成功（725 项运行，0 失败，0 错误，30 项为无 Docker 环境跳过的集成测试）；git status 与文件清单核对一致（docs/测试/ 为用户既有未跟踪文档，保留不动）。 |
