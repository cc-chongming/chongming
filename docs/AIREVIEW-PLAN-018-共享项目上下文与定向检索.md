# 共享项目上下文与角色定向检索计划

> **状态**：✅ 已实施（保留运行环境 E2E 验收）
> **创建日期**：2026-07-23
> **目标**：在初审前一次性建立可审计的项目概览，使角色按职责接收共享上下文并执行最小必要的快照检索。
> **关联计划**：AIREVIEW-PLAN-007、AIREVIEW-PLAN-008、AIREVIEW-PLAN-016、AIREVIEW-PLAN-017

## 1. 问题与约束

- 角色包与通用提示词不一致时，角色可能尝试调用未授权工具；例如 PRODUCT 不拥有 `listFiles`。
- 仓库工具不能把所有异常折叠为同一错误文本，否则模型和实时页面均无法判断快照、参数或访问范围问题。
- `ReviewContextAssembler` 的隔离语义已经存在，但角色启动链路尚未注入需求摘要、仓库概览与模块信息。
- 所有仓库读取必须继续经过共享快照；不向 Agent 暴露宿主物理路径，也不允许 Junction、软链接或越界读取。
- 公开上下文只能包含已验证的需求快照、快照元数据、受控文件清单和证据；不得传播隐藏推理、角色草稿或凭证。

## 2. 目标流程

```text
需求快照 + 共享仓库快照
          ↓
Context Scout（服务端显式创建、只读、无 Claim/Gate 权限）
          ↓
requirement-summary / repository-overview / module-map / role-scopes
          ↓
Director 分派角色
          ↓
RoleContext（按 contextSelectors 与角色范围筛选）
          ↓
定向 searchText / readLines / findSymbol
          ↓
公开事实、辩论、Judge
```

## 3. 实施项

### 3.1 工具可观测性与契约一致性 ✅

- 依据 RolePack 实际白名单生成仓库工具提示；未授权 `listFiles` 的角色不会再被要求调用它。
- 仓库工具将失败转换为安全错误码：`SNAPSHOT_FAILED`、`REPOSITORY_PATH_UNSAFE`、`INVALID_ARGUMENT`、`SNAPSHOT_UNAVAILABLE`。
- 服务端日志记录工具名、角色和错误码，不记录物理路径、凭证或原始异常内容。

### 3.2 共享项目概览 ✅（基础实现）

- 以 `reviewId + attemptNo` 缓存一次性共享概览。
- 概览由不可变需求章节、快照 Git 元数据、可评审文件数、模块根目录与有限示例文件构成。
- 快照不含可评审文本文件时，角色启动直接失败，不再产生“无发现”的空评审。
- PRODUCT 仅接收需求摘要和高层仓库元数据；其他角色额外接收模块根目录和示例文件。
- 取消评审时清理该 attempt 的进程内快照与上下文缓存，避免单例工厂持续持有已结束评审的路径和摘要。

### 3.3 模型化 Context Scout ✅

- 服务端在 Director 之前显式创建独立的 `CONTEXT_SCOUT` Harness；它不计入角色数，不拥有 Claim、辩论或 Gate 工具。
- Scout 仅获得冻结快照的五个只读检索工具，并在工作区写入带模块根、示例证据路径和角色范围的 `context-scout-baseline.json` 公共工件。
- Scout 的最终可见 JSON 结论会写入 `context-scout-result.json`，同步成为 `scout-overview` ContextFact；隐藏推理不会被读取、存储或传播。其运行失败会中断 Director 启动，因此不会激活后续角色。

### 3.4 RoleContext 注入与路径范围 ✅

- 需求、仓库、Scout 概览和角色范围均转为 `ReviewContextAssembler.ContextFact`；RolePack 通过 `contextSelectors` 选择并在 8,000 字符预算内注入模型请求。
- PRODUCT 仅收到需求和高层元数据；其他角色收到本职责的模块、示例证据路径及服务器强制的可检索范围。范围外读取、搜索和符号结果均会被服务端过滤，路径穿越在范围判断前拒绝。
- 初审和辩论工具严格按 RolePack 白名单注册；未获 `complete_initial_review` 权限的按需角色不会被运行时错误地要求完成初审。

### 3.5 实时展示与测试 ✅

- 实时观察台单独展示 Context Scout 的准备、完成和失败状态，并可从角色席位进入其 AG-UI 流式过程。
- 已覆盖单次概览复用、PRODUCT 不接收示例代码文件、范围外读取拒绝、路径穿越拒绝、运行时取消释放与启动失败/通知完成释放链。

## 4. 非目标

- 不开放宿主文件系统，不实现远程 Clone、对象存储或跨机器缓存。
- 不将 AgentScope 的 BYPASS 扩展至领域协议、数据库或快照边界。
- 不展示或持久化模型隐藏推理。

## 5. 验收标准

- PRODUCT 首轮可获得需求内容而不调用 `listFiles`。
- 相同评审尝试只构建一次共享概览，且快照为空时角色无法以空 findings 完成。
- 工具错误在实时流中可按安全错误码定位。
- Context Scout、角色上下文和服务端范围过滤均已接入；部署验收仍需在真实模型网关下验证一次完整评审运行。
