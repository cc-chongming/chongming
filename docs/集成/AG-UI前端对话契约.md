# AG-UI 前端公开对话契约

> **状态**：已实现前端兼容适配；原生 AG-UI HTTP 运行端点待后端提供。
> **关联计划**：AIREVIEW-PLAN-012#1.4、#1.6

## 1. 边界

辩论工作台只展示可公开的 Claim、质询、答辩、立场变化、裁决和 Gate 摘要。角色隐藏思维链、模型原始推理和未授权工具参数均不进入 AG-UI 对话状态。

现有 `GET /api/reviews/{reviewId}/events` 继续输出项目领域 SSE 事件。前端在 `ag-ui-review-adapter.js` 将其转换为 AG-UI 事件；这避免在前端到后端的传输协议尚未冻结时猜测一个不存在的交互命令端点。

## 2. 事件映射

| 领域事实 | AG-UI 事件 | 说明 |
|---|---|---|
| 任一 `ReviewEvent` | `CUSTOM` | `name` 固定为 `chongming.review.domain-event.v1`，`value` 为原始公开领域事实。 |
| Claim、质询、答辩、立场变化、裁决、Gate | `TEXT_MESSAGE_START` → `TEXT_MESSAGE_CONTENT` → `TEXT_MESSAGE_END` | 仅从 `publicSummary`、`publicContent`、`statement`、`reasonSummary` 白名单字段派生显示文本。 |
| 评审 SSE 连接建立 | `RUN_STARTED` | `threadId = review:{reviewId}`；`runId` 使用当前 attempt。 |
| `REVIEW_CANCELLED` | `RUN_FINISHED` | 关闭当前公开对话运行。 |
| `REVIEW_FAILED` | `RUN_ERROR` | 只传递公开错误摘要与错误码。 |
| 所有 `REASONING_*` | 不处理 | 不写入 Store、不渲染、不持久化。 |

## 3. 前端状态要求

- 对话 Store 以 `reviewId + sequence` 对领域事实幂等去重；`attempt` 不是去重键。
- `CUSTOM` 事件保留结构化领域事实；时间线、证据回链和 Gate 仍使用此结构，不通过解析自然语言恢复数据。
- `TEXT_MESSAGE_*` 仅服务于公开对话展示。消息 ID 固定为 `review-{reviewId}-sequence-{sequence}`，避免 SSE 回放产生重复消息。
- Markdown、代码和错误文本都以纯文本渲染，不执行 HTML。

## 4. 原生端点交接项

若后端后续提供完整 AG-UI 双向会话，端点应接收官方 `RunAgentInput`：`threadId`、`runId`、`state`、`messages`、`tools`、`context` 和 `forwardedProps`，并通过 SSE 返回 `RUN_*`、`TEXT_MESSAGE_*`、`CUSTOM` 等事件。

在该端点具备明确的用户输入语义、权限控制和幂等策略之前，前端不得伪造 `POST` 请求或将人工 Gate 表单误作自由对话消息。人工审核继续使用现有版本化 REST API。

## 5. 验证

```powershell
cd frontend
npm test
npx playwright test --repeat-each=3
```

