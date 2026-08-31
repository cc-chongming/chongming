# AIREVIEW-PLAN-088 人工决策页中文化：Gate→门禁/结论，状态标签中文化，隐藏乐观锁版本号

状态：✅ 完成

## 背景
用户批注：人工决策页大量“中不中英不英”（Gate/Gate 草案/Judge → AI Gate 草案 → 人工最终 Gate/
SENT/smtp-mail/SMTP_ACCEPTED/FAILED/DEAD），且“当前评审版本：111”令人困惑
（=聚合乐观锁版本号，并发控制用，非业务版本）。

## 文案表（HumanReviewPanel.vue）
- 请选择最终 Gate 结论。→ 请选择最终结论。
- 评审版本尚未加载，暂不能安全提交最终 Gate。→ 评审尚未加载，暂不能安全提交最终结论。
- 最终 Gate 已提交；后续调整会创建新版本。→ 最终结论已提交；后续调整会创建新版本。
- 再次提交会创建新的 Gate 版本。→ 再次提交会创建新的结论版本。
- 「Gate 草案」→「门禁草案」；Judge → AI Gate 草案 → 人工最终 Gate → 议题裁决 → AI 门禁草案 → 人工最终结论
- 确定性 AI Gate 草案 → 确定性 AI 门禁草案；尚未形成 AI Gate 草案 → 尚未形成 AI 门禁草案
- 人工最终 Gate → 人工最终结论；最终 Gate → 最终结论；提交最终 Gate → 提交最终结论
- 当前评审版本：{{…}}。最终决定会触发报告与通知状态更新。→ 最终决定提交后生效，并触发报告生成与通知。（不显示原始版本号）
- 通知说明：FAILED 或 DEAD → 失败或死信
- 状态标签映射 helper：SENT→已发送 FAILED→失败 DEAD→死信 QUEUED→排队中；channel smtp-mail→邮件；
  “Gate v{n}”→“结论 v{n}”；SMTP_ACCEPTED→邮件服务器已接受

## 文案表（ReviewLiveView.vue 人工阶段）
- 人工确认最终 Gate → 人工确认最终结论；人工最终 Gate → 人工最终结论
- 等待最终 Gate 后生成 → 等待最终结论后生成；人工结论与 AI Gate 草案不同 → 人工结论与 AI 门禁草案不同
- Gate 版本历史 → 结论版本历史；通知行 channel/Gate v/状态同上映射；
  最终 Gate 提交后将显示通知 Outbox 状态。→ 最终结论提交后将显示通知投递状态。
- 重试按钮逻辑仍用原始 FAILED/DEAD 值判断，仅显示中文化。

## 变更记录
- 2026-08-31 立计划；派发后台子代理实施。
- 2026-08-31 子代理 f46cb803 交付主体；父代理清扫表外残留（ReviewLiveView 874、ReviewReportView、ag-ui-review-adapter、e2e 断言同步）；vitest 164 全绿；提交。
