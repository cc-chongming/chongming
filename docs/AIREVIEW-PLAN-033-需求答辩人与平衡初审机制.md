# AIREVIEW-PLAN-033 需求答辩人与平衡初审机制

> **状态**: ✅ 全部完成
> **创建日期**: 2026-08-26
> **目标**: 消除独立审查"一边倒"（全部 OPPOSE、无 SUPPORT、冲突检测无候选、辩论无防守方）的结构性缺陷。

## 背景

一次真实评审暴露了结构性问题：独立审查阶段所有角色只产出 OPPOSE 主张，且各自挂在不同主题上；
冲突检测要求同一主题同时存在 SUPPORT 与 OPPOSE 才形成候选，于是检测器报告"无冲突"；
Director 兜底开题后议题没有支持方（防守方为 0），辩论无法形成真正的攻防。

根因有三：

1. 角色提示词是对抗性的，只要求提交反对主张，无人被要求说明"哪里合理"；
2. 需求文档本身是隐含的支持方，但没有任何角色被指派代表它；
3. 冲突定义过窄——"反对与反对之间的分歧"（不同角色对同一主题给出不相容的异议/处置）不可见。

"检测不到冲突"不等于"没有冲突"。用户逐项批准了 A（需求答辩人）、B（平衡初审义务）、C（冲突检测扩展）。

## 分段方案

### #1 制度化的需求答辩人

- 目标：议题无支持方时必须存在防守方；产品经理代表需求文档应诉。
- #1.1 Director 提示词强制规则（`ReviewDirectorHarnessFactory.directorPrompt`）：
  议题没有任何 SUPPORT 主张时，首轮第一个 dispatch 必须指向 PRODUCT，逐条回应议题内每条
  OPPOSE 主张——引用需求原文辩护，或明确承认异议成立；PRODUCT 未激活时指派最相关的已激活角色；
  任何议题不允许无防守方运行。
- #1.2 产品经理答辩义务写入角色身份（`roles/product.yml` 的 `voice.identity`）：
  "辩论阶段你同时是制度化的需求答辩人……不得沉默回避。"
- 不改状态机：dispatch 校验、回合、裁决路径均不变。

### #2 初审平衡义务

- 目标：初审必须同时产出认可点与反对点，冲突检测获得真实素材，裁决不再是只看一面之词。
- #2.1 全部初审角色的提示词新增义务："明确认可需求中设计合理、证据充分的部分；对评审结论有意义的
  认可点须以 SUPPORT 主张提交，初审不得只产出反对意见。"
  - rich 清单角色（product/project/frontend/backend）：新增 `<role>.recognized_strengths` 检查点（required: true）；
  - plain 清单角色（architecture/security/test）：清单新增对应条目；
  - 各角色 promptVersion 递增。
- 义务由既有覆盖率守卫强制：未提交该检查点评估则初审不能完成。

### #3 冲突检测扩展：异议分歧（OPPOSE_DIVERGENCE）

- 目标："大家都反对、但反对的点/处置互不相容"的主题进入候选清单。
- #3.1 `ConflictDetector` 新增规则：同一主题下两个及以上不同角色的未撤回 OPPOSE 主张 → 冲突候选
  （基础分 40，低于立场对立的 80、高于普通风险的 20，叠加严重度权重）。
- #3.2 跳过辩论闸门核验：`DebateService.skipDebateWhenNoConflicts` 自 2026-08-19 修订起已在存在任何
  未撤回 OPPOSE 主张时拒绝跳过，无需改动；`register_topics` 本就接受无 SUPPORT 的主题（仅校验
  claim 归属），无需改动。

## 文件清单

### 新建

| 文件 | 计划段 | 状态 |
|------|--------|------|
| `docs/AIREVIEW-PLAN-033-需求答辩人与平衡初审机制.md` | — | ✅ |

### 修改

| 文件 | 计划段 | 状态 |
|------|--------|------|
| `src/main/java/ai/cc/chongming/review/infrastructure/agentscope/ReviewDirectorHarnessFactory.java` | #1.1 | ✅ |
| `src/main/resources/roles/product.yml` | #1.2 #2.1 | ✅ |
| `src/main/resources/roles/project.yml` | #2.1 | ✅ |
| `src/main/resources/roles/frontend.yml` | #2.1 | ✅ |
| `src/main/resources/roles/backend.yml` | #2.1 | ✅ |
| `src/main/resources/roles/architecture.yml` | #2.1 | ✅ |
| `src/main/resources/roles/security.yml` | #2.1 | ✅ |
| `src/main/resources/roles/test.yml` | #2.1 | ✅ |
| `src/main/java/ai/cc/chongming/review/domain/debate/ConflictDetector.java` | #3.1 | ✅ |
| `src/test/java/ai/cc/chongming/review/debate/ConflictDetectorTests.java` | #3.1 | ✅ |
| `src/test/java/ai/cc/chongming/review/application/AssessmentServiceTests.java` | #2.1 | ✅ |
| `src/test/java/ai/cc/chongming/review/application/InitialReviewProgressServiceTests.java` | #2.1 | ✅ |
| `src/test/java/ai/cc/chongming/review/agentscope/AgentScopeReviewRuntimeAdapterTests.java` | #2.1 | ✅ |

## 实施顺序

1. **步骤 1** ✅ 冲突检测新规则 + 测试（#3，依赖最少先行）
2. **步骤 2** ✅ Director 答辩人规则 + 产品答辩义务（#1）
3. **步骤 3** ✅ 平衡初审义务全角色铺开（#2），3 个硬编码检查点清单的既有测试同步扩展
4. **步骤 4** ✅ 全量回归 727 测试通过后提交（b2b805f）

## 风险与应对

- 平衡义务每角色增加一次评估调用，评审时长略增——属预期成本，换来冲突检测的真实素材。
- 答辩人依赖 PRODUCT 激活：prompt 给出降级路径（最相关角色代行）；PRODUCT 为常激活角色，概率极低。
- 答辩质量依赖模型遵从度：dispatch 校验与回合机制不变，Judge 仍只基于已持久化事实裁决，
  答辩失序不会污染裁决输入。

## 变更记录

- 2026-08-26：实施完成（提交 b2b805f）；按 .codex/rules/plan-driven-development.md 补齐规定结构（头部元信息、文件清单、实施顺序、风险与变更记录）。
- 2026-08-27：**方向修正 + 机制落地**（用户指出原实现方向反了：变成"产品经理质询各位"）。修正后拓扑：① Director 派 DEFENSE 给 PRODUCT 立防守位（以 SUPPORT 主张落库）→ ② 各反对者 CHALLENGE 该 SUPPORT 主张（质询需求立场，信服者可 change_claim_position 收回）→ ③ 服务端自动向答辩人派发反驳信封。落地内容：DispatchedAction 新增 DEFENSE（枚举/校验/工具 schema/信封措辞/口语化词汇表全覆盖）；ClaimService 放开辩论阶段主张闸门（须携带有效 DEFENSE 命令 commandId，subjectKey 强制对齐议题，落库后消费命令）；派发幂等去重（四元组命中既有 PENDING 未过期命令即返回既有 commandId，DISPATCH_COMMAND_DEDUPED 日志）；去重命中时刷新命令过期时间（重派=意图存活，防止仍被需要的命令静默过期；协调者停派则照常回收）。Director 提示词与 product voice 同步修正。新增测试 9 个，全量 736 绿。
