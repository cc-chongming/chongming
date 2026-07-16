# 学习通通知 MCP 契约交接清单

> **状态**：⏸ 外部已验证契约尚未同步到本仓库，生产调用保持关闭。
>
> **关联**：`AIREVIEW-PLAN-011#1.6`

## 当前可验证事实

- 项目需求与技术方案确认“学习通通知 MCP”是首期通知通道。
- 当前仓库没有工具名、输入 JSON Schema、鉴权字段、幂等请求示例或错误码样本。
- 因此代码不会猜测 HTTP 地址、MCP 工具名、请求字段或 Authorization 格式。

## 已落地边界

- `NotificationCommand` 是项目内部的稳定领域命令，幂等键固定为
  `reviewId:gateVersion:channel`。
- `LearningPlatformMcpAdapter` 只将该命令交给部署侧提供的
  `LearningPlatformMcpClient`；没有已验证 Client 时返回 `MCP_CLIENT_UNAVAILABLE`。
- `review.notification.worker-enabled` 与 `review.notification.mcp-enabled` 默认均为 `false`。
- MCP 启用时，凭证只从 `review.notification.credential-environment-variable` 指定的环境变量读取；
  代码、配置样例、日志和 Outbox 都不保存凭证或响应正文。

## 接入前必须交接的权威材料

1. MCP 服务地址与工具名，以及版本号。
2. 完整输入/输出 JSON Schema，含收件人、标题、正文、报告链接与幂等字段的映射。
3. 鉴权方式、令牌环境变量名、刷新或过期处理要求。
4. 超时、限流、网络错误、业务拒绝、重复请求的返回样例和可重试判定。
5. 测试环境调用凭据与受控冒烟的收件人。

收到上述材料后，应以真实 Schema 替换 `LearningPlatformMcpClient` 的部署实现，并将验证过的请求/响应样例
写入 `src/test/resources/contracts/learning-platform-notification.json`，再解除生产调用开关。
