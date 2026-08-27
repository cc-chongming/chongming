# AIREVIEW-PLAN-035 快照治理与 Scout 契约可观测

> **状态**: ✅ 全部完成（补齐文档，实施先行）
> **创建日期**: 2026-08-26
> **目标**: 修复"自评审仓库把运行时状态冻进快照、模块根退化、Scout 违规无迹可寻"的评审启动期缺陷链。

## 背景

一次真实评审（a55e1e20）出现 Context Scout 降级：日志考古还原出——仓库快照把 `.agentscope/workspace`
等运行时状态（342+ 文件）当作评审内容冻入；模块根探测取清单前 40 个文件的顶级目录，全部命中
`.agentscope`，INIT 清单告诉 Scout"唯一模块根是 .agentscope"；Scout 两次检索落空后继续循环检索，
第 4 次 `glob_files` 撞硬预算被斩停，而违规调用在事件发布前被抛异常吞掉，日志无任何工具名。
另发现提示词配额（2/3/4）与硬预算（3/6/8）不一致。实施已完成并提交（f222e6d）。

## 分段方案

### #1 快照排除运行时目录

- 目标：本地代理/构建运行时状态永远不成为评审内容；工作区指纹对同类目录免疫，快照缓存命中恢复稳定。
- 修改：`RepositorySnapshotService.java`
  - #1.1 `EXCLUDED_DIRECTORIES` 增加 `.agentscope`、`.qoder`、`.claude`、`.codex`、`.learnings`（任意深度）；
  - #1.2 新增 `EXCLUDED_ROOT_DIRECTORIES`（`output`、`logs`，仅仓库根，避免误伤深层同名目录）；
  - #1.3 `isExcluded`（指纹遍历）与复制遍历同步同一套排除规则。

### #2 模块根探测去偏

- 目标：单个字母序靠前的垃圾目录无法劫持 INIT 清单的模块根。
- 修改：`ReviewRepositoryToolFactory.buildSharedProjectContext`（#2.1）——改为遍历全量快照清单，
  按顶级目录计数，数量降序取前 8；全清单为空时回退旧逻辑（前 40 文件采样）。

### #3 Scout 契约统一与违规可观测

- 目标：守约模型永不撞墙，撞墙必是真违规；违规时日志直接点名工具与次数。
- 修改：`AgentScopeReviewRuntimeAdapter.java`
  - #3.1 `SCOUT_INIT_TOOL_LIMITS` 收紧为 glob 2 / grep 3 / read 4，与提示词及基线契约（2/3/4）统一；
  - #3.2 `ScoutLimitExceededException` 增加 `detail` 字段，`consume()` 抛异常携带
    `violatingTool/calls/perToolLimit`（或"不在契约内"）；
  - #3.3 `recoverScoutFailure` 的 `context_scout_degraded` WARN 输出 detail。

## 文件清单

### 修改

| 文件 | 计划段 | 状态 |
|------|--------|------|
| `src/main/java/ai/cc/chongming/review/application/RepositorySnapshotService.java` | #1.1 #1.2 #1.3 | ✅ |
| `src/main/java/ai/cc/chongming/review/infrastructure/agentscope/ReviewRepositoryToolFactory.java` | #2.1 | ✅ |
| `src/main/java/ai/cc/chongming/review/infrastructure/agentscope/AgentScopeReviewRuntimeAdapter.java` | #3.1 #3.2 #3.3 | ✅ |

## 实施顺序

1. **步骤 1** ✅ 快照排除 + 指纹同步（#1）
2. **步骤 2** ✅ 模块根全量统计去偏（#2）
3. **步骤 3** ✅ 配额统一 + 违规可观测（#3）；725 个测试全绿后提交

## 风险与应对

- `output`/`logs` 为通用名：仅根级排除，深层同名目录不受影响。
- 收紧硬预算可能让"差一次检索"的 Scout 更早降级：这是契约本意（信息不足标 unknown），
  且与提示词/基线一致后可观测性兜底（日志点名）。
- 已缓存的污染快照：指纹算法变更后新评审生成新 key，不复用旧快照。

## 变更记录

- 2026-08-26：实施完成（提交 f222e6d）；按 .codex/rules/plan-driven-development.md 补齐本计划文档。
- 2026-08-27：**偏差记录（#3.1）**——评审 0e88379a 中快照/模块根修复生效（grep 命中真实代码），
  但需求横跨 FR-1~FR-9，grep 第 4 次调用撞统一后的 2/3/4 硬预算降级，且 read_file 配额完全未用。
  结论：提示词配额是"期望值"，硬预算应是"执行包络"。硬预算恢复为 glob 3 / grep 6 / read 8，
  提示词与基线保持 2/3/4 严格口径；同时给 Scout 提示词补充配额意识（优先 read_file 验证、
  配额不足以支撑验证时立即收敛并标 unknown，不得用尽配额）。
- 2026-08-27：**偏差记录（#3.1 重设计）**——评审 0e88379a 暴露：Scout 在 grep_files 第 4 次调用
  撞"单工具硬上限"被整体降级，而 read_file 配额完全未用——单工具额度用尽就终止整个运行是错误的惩罚。
  重设计（经用户确认）：
  1. 单工具额度转为**建议性**（glob 3 / grep 6 / read 6）：超额仅记录 context_scout_tool_over_quota
     WARN，不终止运行，模型可转向仍有额度的其他工具；
  2. 硬性边界收敛为两条：总预算 scoutMaxToolCalls（默认 16 → 20，application.yml 与
     AgentScopeProperties 同步）与白名单外工具（仍按 CONTEXT_SCOUT_INIT_CONTRACT_VIOLATED 降级）；
  3. 提示词与基线 retrievalContract 从 2/3/4 提升为 3/6/6，与新的建议额度一致；
  4. ScoutInitToolBudget.consume 语义变更：返回超额告警详情（供 WARN 输出），仅在禁用工具/总额
     耗尽时抛异常。
