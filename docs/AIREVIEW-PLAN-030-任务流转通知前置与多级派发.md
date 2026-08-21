# AIREVIEW-PLAN-030：任务流转通知前置与多级派发

> **状态**:  起草中（待评审后实施）
> **创建时间**: 2026-08-20
> **目标**: 将通知从"仅最终 Gate 事后告知"前置为"每个责任迁移点即时触达下一责任人"，并把单段单负责人任务扩展为可多级流转（后端 → 前端 → 需求提出人验收）的派发链，使评审通过后的研发执行闭环与市面主流开源协作平台（GitLab CE / Redmine / 禅道 / OpenProject / Camunda）的"流转即通知"惯例对齐。

## 背景

PLAN-011 建立了通知 Outbox（幂等、重试、通道路由），PLAN-026 建立了任务中心，但两者交集只有**一个触发点**：`HUMAN_GATE_FINALIZED` 后发送"最终 Gate 通知"。真实协作链条上的关键触达全部缺失：

1. **AI 评审完成无人知晓**：评审进入 `WAITING_HUMAN` 后，需要人工决策的人不会收到任何提醒，决策时机依赖主动刷页面；
2. **指派/流转无触达**：任务指派给后端开发、后端流转给前端，被指派人收不到通知；
3. **验收人与提出人脱节**：验收由管理员执行，需求提出人（评审该需求的人）在"前端开发完成 → 验收"环节缺席；
4. **用户无联系方式**：auth 用户模型只有 username/displayName/password/role，邮件收件人解析没有数据基础；
5. **流转无通知**：现有邮件通道仅服务最终 Gate，流转事件不产生通知。

### 开源实践对照

| 开源项目 | 惯例 | 本计划吸收点 |
|---|---|---|
| GitLab CE | assignee 变更、状态迁移即通知下一负责人；用户级通知偏好 | 流转事件驱动通知（偏好设置列为非目标，v1 固定矩阵） |
| Redmine | issue 更新通知 assignee + watchers；按事件类型决定收件人 | 通知矩阵：事件 → 收件人 → 模板 |
| 禅道（ZenTao） | 任务指派/完成/验收逐节点通知对应角色，短信作通道扩展 | “发短信”语义由现有邮件通道承载（不新增 SMS 通道），作为 `NotificationDeliveryPort` 既有 MAIL 实现的矩阵化使用 |
| OpenProject | work package 的 assignee/responsible 变更触发通知 | handoff（持有人变更）即通知新持有人 |
| Camunda / Flowable | BPMN task assignment 监听器；终端验收通知发起人 | 提交验收通知需求提出人；验收结论通知当前持有人 |

共同原则：**通知绑定"状态/负责人迁移事件"而非终端决策；每次流转通知下一个责任人；终端验收通知发起人；通道抽象 + 幂等 + 重试**（后三者复用 PLAN-011 Outbox 既有能力）。

### 范围与非目标

**本计划范围**：通知事件矩阵与 `NotificationCommand` 泛化、用户邮箱字段与迁移、DevTask handoff 链与状态机扩展、验收角色改为需求提出人、任务流转领域事件进入事件总线/SSE、前端流转 UI 与通知可见性、跨文档（011/026/021/027/010）契约回写。

**非目标**：

- 不做用户级通知偏好设置界面（v1 固定矩阵，偏好列未来扩展）；
- 不新增 SMS 通道：用户口语中的“发短信”由现有邮件通道承载，未录入邮箱的用户保留 LOCAL 审计记录；
- 不改变评审内核（Agent 编排、辩论、Gate 草案）与 PLAN-011 的最终 Gate 通知行为；
- 不做任务拆分/子任务/工时（延续 PLAN-026 非目标）。

## 技术方案

### 1. 通知事件矩阵（PLAN-011 扩展）

`NotificationOutboxService` 从单事件监听扩展为矩阵监听；每条矩阵行生成"每收件人 × 每通道"一条 `NotificationCommand`：

| # | 触发事件 | 收件人 | 模板主题 |
|---|---|---|---|
| N1 | 评审进入 `WAITING_HUMAN`（AI 完成，复用既有 `HUMAN_REVIEW_REQUIRED` 事件） | 需求创建者 + 全部 ADMIN | 【重明】AI 评审完成，待人工决策 |
| N2 | `TASK_ASSIGNED` | 被指派人 | 【重明】新开发任务已指派给你 |
| N3 | `TASK_HANDOFF` | 新持有人 | 【重明】任务流转给你，请接续开发 |
| N4 | `TASK_SUBMITTED_FOR_ACCEPTANCE` | 需求创建者（提出人） | 【重明】任务已提交验收，请验收 |
| N5 | `TASK_ACCEPTED` / `TASK_REJECTED` | 当前持有人 | 【重明】验收通过 / 验收打回（附意见） |
| N6 | `HUMAN_GATE_FINALIZED`（现状保留） | 现状收件人 | 现状模板不变 |
| N7 | `TASK_PAUSED` / `TASK_RESUMED` | 需求创建者 + ADMIN（ADMIN 发起时加收当前持有人） | 【重明】任务暂停 / 恢复（附原因） |
| N8 | `TASK_CANCELLED` | 当前持有人 + 需求创建者 | 【重明】任务已关闭（附原因） |

**`NotificationCommand` 泛化**：新增 `eventType`、`recipientUsername`、`templateKey` 字段；`gateVersion` 对非 Gate 事件取 0；幂等键由 `reviewId:gateVersion:channel` 扩展为 `reviewId:eventType:eventSeq:recipientUsername:channel`，保证同一事件重放不重复发送、不同流转事件互不覆盖。Outbox 当前为内存实现，泛化字段不落库。

**收件人解析**：`NotificationOutboxService.enqueueMatrix` 按 username 查 auth 用户邮箱；有邮箱走 `smtp-mail` 通道，无邮箱写一条 `LOCAL` 通道 Outbox 记录（页面可见、可审计），不静默丢失。

### 2. 邮件通道承载流转通知（PLAN-011 扩展）

不新增通道：矩阵行复用既有 `SmtpMailNotificationAdapter`，`templateKey != null` 时渲染“任务流转通知”模板（事件/内容/收件人/评审/幂等键），否则保持最终 Gate 模板；邮件未启用时 Outbox 条目保留待投递状态，worker 启用后补发。失败语义沿用 Outbox 重试/退避/DEAD，**投递失败不回滚业务事实**。

### 3. DevTask handoff 链与状态机扩展（PLAN-026 扩展）

参考禅道等开源状态模型但**不照搬九态**（Bug 中心模型对开发任务过拟合）：保留四态骨干，补齐两个真实缺口——**暂停中**（进行中的阻塞态）与**已拒绝/已关闭**（终态，需求退回/取消时任务不再悬空）。状态对照：新建=PENDING_ASSIGN、已指派/进行中=DEVELOPING、暂停中=PAUSED、已解决/待测试=PENDING_ACCEPTANCE、测试中=handoff 给测试人（持有人变更而非状态）、已关闭=DONE、已拒绝=CANCELLED：

```text
PENDING_ASSIGN     → DEVELOPING            （指派，通知 N2）
DEVELOPING         → PAUSED                （暂停：等依赖/需求挂起，通知 N7）
PAUSED             → DEVELOPING            （恢复，通知 N7）
DEVELOPING         → DEVELOPING[holder']   （handoff：持有人变更，通知 N3，写 HandoffEntry）
DEVELOPING         → PENDING_ACCEPTANCE    （提交验收=已解决待验证，通知 N4，验收人=需求创建者）
PENDING_ACCEPTANCE → DONE                  （创建者或 ADMIN 验收通过，通知 N5）
PENDING_ACCEPTANCE → DEVELOPING            （打回返工，通知 N5）
非终态             → CANCELLED             （关闭/拒绝任务：需求退回或取消时，通知 N8）
```

- `DevTask` 新增 `handoffHistory: List<HandoffEntry(seq, fromUsername, toUsername, note, at)>` 与 `currentHolderUsername`（assign 写首任 holder）；MyBatis 以 JSON 列持久化，V27 迁移加列；
- `DevTaskCommandService.handoff(taskId, toUsername, note, expectedVersion)`：仅当前持有人或 ADMIN 可发起；`toUsername` 必须为平台注册用户；同事务写 `HandoffEntry` 并发布 `TASK_HANDOFF`；
- `pause/resume/cancel` 命令：pause 仅 DEVELOPING 可发起（持有人或 ADMIN，必填原因）；resume 仅 PAUSED；cancel 仅非终态且仅 ADMIN 或需求创建者；三者均写备注并发布对应事件；
- **流转不固定角色顺序**：handoff 是“任意注册用户间的定向交接”，N2–N5 矩阵中的“后端 → 前端 → 提出人”仅为示例链路。单角色任务（如纯后端）开发完成后可直接 `submitForAcceptance`（N4 通知提出人验收），也可由持有人直接 handoff 给任何用户（含提出人本人接手）；中间是否插入前端等节点完全由持有人/ADMIN 按实际分工决定，状态机与通知矩阵对任意 handoff 序列一致生效；
- **验收权限**：`accept/reject` 从"仅 ADMIN"放宽为"需求创建者或 ADMIN"（消费 PLAN-021 `requirement.creatorId`）；
- 任务查询视图 `DevTaskView` 增加 `currentHolder` 与 `handoffHistory`，任务详情页展示流转时间线。

### 4. 任务流转领域事件（PLAN-010 扩展）

`ReviewEventType` 增加 `TASK_ASSIGNED / TASK_HANDOFF / TASK_SUBMITTED_FOR_ACCEPTANCE / TASK_ACCEPTED / TASK_REJECTED / TASK_PAUSED / TASK_RESUMED / TASK_CANCELLED` 八个类型（沿用现有事件总线：持久化 + SSE + 监听器，payload 携带 `taskId/requirementId/from/to/note`）；`DevTaskCommandService` 在对应命令成功后发布；live 页事实时间线渲染流转节点（前端 `runtime-conversation-adapter` 增加映射）。事件发布失败不得回滚任务命令（与 ProvisioningListener 相同的隔离语义：记录并吞没）。

### 5. 用户邮箱（auth 扩展）

- V26 迁移：`users` 表加 `email VARCHAR(128) NULL`（“发短信”语义由邮件通道承载，不引入 phone 字段）；
- 注册页邮箱保持可选；`POST /api/auth/me/contacts` 自助补录/修改；`GET /api/users` 目录对 ADMIN 返回邮箱；
- `User` 领域 record 与 `UserRepository` 双实现同步扩展。

### 6. 可见性（PLAN-027 扩展）

需求/任务可见范围在现有"创建者 + 当前任务负责人"基础上，并入 **handoff 历史持有人**（`DevTaskRepository.findRequirementIdsByAssignee` 语义扩展为"当前或历史持有人"），保证流转过任务的开发者仍能回看需求与报告。

### 7. 前端

- 任务详情页：新增“流转给下一负责人”按钮（仅 DEVELOPING 且当前持有人/ADMIN 可见），用户名 + 流转说明；暂停/恢复/关闭操作；流转历史时间线；验收区文案标明验收人=需求提出人；
- 注册页：邮箱（可选）录入；
- 通知 Outbox 面板（live 页人工决策相已有）展示 N1–N5 行，含收件人与通道；
- live 页事实时间线展示 TASK_* 事件。

## 文件清单（实施时落点）

| 层 | 文件 | 变更 |
|---|---|---|
| resources | `V26__users_contact_and_outbox_event_columns.sql`（仅 email 列）、`V27__dev_task_handoff_history.sql` | 新增 |
| auth | `User`、`UserRepository` 双实现、`AuthController`（注册/contacts） | 邮箱字段 |
| task/domain | `DevTask`、`DevTaskTypes`（HandoffEntry）、`DevTaskStateMachine`（PAUSED/CANCELLED/handoff 校验） | 扩展 |
| task/application | `DevTaskCommandService`（handoff/pause/resume/cancel/验收权限/事件发布）、`DevTaskQueryService`（视图扩展） | 扩展 |
| review/application | `NotificationOutboxService`（矩阵监听 enqueueMatrix） | 扩展 |
| review/domain | `NotificationCommand`（泛化）、`ReviewEventType`（TASK_*） | 扩展 |
| review/infrastructure | `SmtpMailNotificationAdapter`（流转模板）、`NotificationDeliveryRouter` 不变 | 扩展 |
| frontend | 任务详情流转 UI、注册页邮箱、task-api 流转方法 | 扩展 |
| test | 矩阵幂等/收件人解析/handoff 状态机/验收权限单测；E2E 流转链通知验收 | 新增/扩展 |

## 实施顺序与依赖

`1（邮箱+迁移）→ 2（handoff 域+事件）→ 3（通知矩阵+邮件模板）→ 4（前端）→ 5（测试与验证）`；每步独立可回归，旧单负责人流程全程保持兼容。

## 验证矩阵

| 验收项 | 证据 |
|---|---|
| N1–N5 矩阵每行产生正确收件人的 Outbox 记录 | 单测 + 真实评审 E2E：指派后端→流转前端→提交验收→提出人验收，Outbox 面板逐条可见 |
| 幂等：同一事件重放不重复发送 | 单测（重放命令断言 Outbox 行数不变） |
| 通知失败不回滚任务迁移 | 单测（邮件适配器抛错后任务状态仍迁移，Outbox 进入重试/DEAD） |
| 验收权限：创建者可验收、非创建者非 ADMIN 被拒 | 单测 + E2E（403 契约） |
| handoff 历史不可变、可见性并入历史持有人 | 单测 + PLAN-027 可见性回归 |
| 旧流程兼容：单负责人指派→验收全链路回归 | PLAN-026 既有 E2E 全绿 |

## 边界说明

- 本计划是 011/026/021/027/010 的**跨文档增量契约**：实施完成后须在各 PLAN 的偏差/修订记录中回写对应条目（011 通知矩阵与邮件模板、026 handoff 与验收权限、021 提出人验收、027 历史持有人可见性、010 TASK_* 事件）；
- 邮件未启用时平台行为不退化：矩阵行保留 LOCAL/待投递记录，页面 Outbox 仍完整可见；
- 通知与流转均为"提醒+审计"语义，不替代平台内权限校验（任何写操作仍以服务端 JWT 权限为准）。

## 附录：模型网关运行时韧性（2026-08-20 补充）

验收期间发现本地网关（new-api 代理）对同一令牌**并发大于 1** 的并发请求一律返回 `Invalid token`：串行、长上下文、带 tools 的探测全部正常，2 并发与 5 并发全部失败；而平台初审以 4 角色并行发流，导致全部空流、评审在 INITIAL_REVIEW 失败（昨日代理未收紧策略故未暴露）。对策：

- `review.model-gateway.max-concurrent-calls`（默认 4）：`CommercialModelGateway` 用公平信号量串行化 provider 调用，本地 profile 设为 1；
- `MODEL_RESPONSE_INVALID` 纳入有界可重试集合，瞬时空流可自愈并触发 fallback profile；
- `OpenAiCompatibleModelClient` 空流诊断增强：区分“仅思考链无公开文本”措辞，并以 `MODEL_STREAM_EMPTY_SAMPLE` 输出前 6 行原始 SSE 供日志自诊断。

## 附录：学习通（Chaoxing）uid 多端通知通道（2026-08-21 补充）

用户提供了按 uid 发送学习通通知的参考实现（`sendNotice`），要求“先继续用 QQ 邮箱、但做成可同时多端发送”。落地方式：

- **多端扇出**：`NotificationOutboxService.enqueueMatrix` 在“每收件人 × 每通道”矩阵中，除 `smtp-mail`（有邮箱）与 `LOCAL`（兑底审计）外，当 `review.notification.chaoxing.enabled=true` 且收件人 `users.company_uid` 为数字 uid 时并行追加一条 `chaoxing` 通道 Outbox 行（幂等键含 channel，重放不重复）；默认关闭，故 QQ 邮箱仍是主通道。
- **路由**：`NotificationDeliveryRouter` 新增 `CHAOXING_CHANNEL="chaoxing"` 分支与 `ObjectProvider<ChaoxingNotificationAdapter>`；未启用时 `CHAOXING_DISABLED`（不可重试）失败关闭。
- **适配器/客户端**：新增 `ChaoxingNotificationAdapter`（`@ConditionalOnProperty chaoxing.enabled`）与 `ChaoxingNoticeClient`（将参考 `sendNotice` 适配为 RestTemplate+Jackson，无 hutool/fastjson；`destination` 携带 uid）。
- **签名实现**：官方 `fillParams_encnew` 已按参考实现落地于 `ChaoxingNoticeClient.fillParamsEncNew`（注入 `_time`/`token`，对按 key 排序的 `k=v&...` 串拼 `&_key=` 后取 MD5 作为 `inf_enc`）；`api/puid/pcode/token/key` 经 `review.notification.chaoxing.*`（环境变量 REVIEW_CHAOXING_*）注入，缺配置时 `CHAOXING_SIGNING_UNCONFIGURED` 失败关闭，不影响邮件通道。
- 验证：`NotificationDeliveryRouterTests` 新增 chaoxing 禁用失败关闭用例；`mvnw test-compile` 通过（`test` 目标在当前沙箱被拦截，未实跑）。
