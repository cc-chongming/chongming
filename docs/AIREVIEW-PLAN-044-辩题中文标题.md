# AIREVIEW-PLAN-044 辩题中文标题

> **状态**: ✅ 全部完成
> **创建日期**: 2026-08-27
> **目标**: 冲突检测/辩论页的议题以协调者给出的中文标题展示，subjectKey 降级为“技术标识”。

## 背景

- 用户批注：冲突检测的议题标题目前是英文技术标识（如 `review.execution.human_required_handling`），希望使用中文。
- 现状：议题标题 = `DebateTopic.subjectKey`（Claim 的技术标识，冲突检测按 subject 归组）。议题由协调者经 `register_topics` 工具按候选提案（`TopicProposal(subjectKey, claimIds)`）登记，`DebateService.registerTopics` 落库 `review_debate_topic`。
- 方案：协调者（LLM）在开题时一并给出中文议题标题（工具新增可选参数 publicTitle），落库并在视图展示；subjectKey 保留为匹配键与技术标识。

## 分段方案

### 段 1：后端议题中文标题（后端）

**涉及文件**：
- 新建：`src/main/resources/db/migration/V29__debate_topic_public_title.sql`（现最新 V28）
- 修改：`src/main/java/ai/cc/chongming/review/domain/model/DebateTopic.java`（新增可空 `publicTitle`；构造重载 + restore 透传，既有 4 参构造委托传 null）
- 修改：`src/main/java/ai/cc/chongming/review/infrastructure/agentscope/tool/DebateToolCommands.java`（`TopicProposal` 增 `publicTitle`）
- 修改：`src/main/java/ai/cc/chongming/review/infrastructure/agentscope/ReviewDebateToolFactory.java`（register_topics 工具 schema 增可选 `publicTitle` 字符串参数，描述要求“简明中文标题、概括争议点、建议不超过 20 字”；参数解析）
- 修改：`src/main/java/ai/cc/chongming/review/application/DebateService.java`（登记时透传，空白归一为 null）
- 修改：`src/main/java/ai/cc/chongming/review/infrastructure/persistence/mapper/DebatePersistenceMapper.java` + `MyBatisReviewDebateStore.java`（insert/select/restore 含 public_title）
- 修改：`src/main/java/ai/cc/chongming/review/application/ReviewQueryService.java`（`DebateView` 追加 `title` 字段）
- 修改：`src/main/java/ai/cc/chongming/review/infrastructure/agentscope/ReviewDirectorHarnessFactory.java`（冲突处置提示词补一句：为每个议题给出简明中文标题）
- 新增/修改：对应单测

**关键实现细节**：
- `public_title VARCHAR(255) NULL`；旧行 NULL，读模型与前端均回退 subjectKey，无需回填。
- 兼容：InMemory 存储随对象自然携带；restore 路径补字段。
- 标题仅展示用途，不参与任何匹配/去重（subjectKey 语义不变，DEFENSE 匹配不受影响）。

### 段 2：前端展示切换（前端，依赖段 1）

**涉及文件**：修改 `frontend/src/views/ReviewLiveView.vue`
- 冲突卡头部、辩论页大标题（debateSubject）、对话流/合成回合的 subject 展示，优先 `topic.title`；subjectKey 保留为小字“技术标识”。

## 文件清单

### 新建

| 文件 | 计划段 | 状态 |
|------|--------|------|
| `src/main/resources/db/migration/V29__debate_topic_public_title.sql` | #1 | ✅ |

### 修改

| 文件 | 计划段 | 状态 |
|------|--------|------|
| `DebateTopic.java` / `DebateToolCommands.java` / `ReviewDebateToolFactory.java` / `DebateService.java` | #1 | ✅ |
| `DebatePersistenceMapper.java` / `MyBatisReviewDebateStore.java` / `ReviewQueryService.java` / `ReviewDirectorHarnessFactory.java` | #1 | ✅ |
| `frontend/src/views/ReviewLiveView.vue` / `frontend/src/styles/review.css` | #2 | ✅ |

## 实施顺序

1. **步骤 1** ✅ → 后端子代理实施段 1 + 全量回归 756/0/30（+6 新用例）。
2. **步骤 2** ✅ → 独立审查通过（领域构造/restore 重载向后兼容、mapper insert/select/TopicRow、工具 schema+200 截断、提示词只补中文标题一句未动答辩拓扑、DebateView 尾部追加 title）；段 2 内联实施：冲突卡与裁决卡标题优先 `title`，subjectKey 降为“技术标识”小字；辩论页标题/议题 Tab 已由 045 就位回退逻辑。
3. **步骤 3** ✅ → 提交推送；后端需 IDEA 重启（Flyway V29 随启动执行）。

## 风险与应对

- **风险**：模型给的标题质量不稳/超长 → 工具描述约束 + 服务端超长截断（255 上限），空白回退 subjectKey。
- **风险**：存量议题无标题 → 前端回退显示 subjectKey，无迁移回填。
- **风险**：Flyway 迁移在既有库执行 → 单条 ALTER 加列，MySQL 5.6 兼容（与 V9 口径一致）。

## 变更记录

- 2026-08-27：创建计划，派发后端实施子代理。
- 2026-08-27：段 1/段 2 完成并通过独立审查。存量议题 title=NULL 自动回退 subjectKey；新评审由协调者按提示词与工具 schema 给出中文标题。
