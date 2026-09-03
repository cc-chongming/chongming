# AIREVIEW-PLAN-114 邮件 CTA 按邮件类型落点

状态：✅ 完成

## 背景

「AI 评审完成，待人工决策」邮件的「查看评审报告」按钮指向报告页，但报告仅在
HUMAN_GATE_FINALIZED 后生成（ReviewReportService 仅该事件触发），点击落在「尚无报告」空页；
任务类邮件同理（评审仍在开发中）。

## 方案

- [#1] 适配器 CTA 按类型：Gate 邮件（templateKey==null）→ 报告页「查看评审报告」；
  HUMAN_REVIEW_REQUIRED → 工作台 live 深链「进入人工决策」；其余任务类 → live 深链「查看评审工作台」；
  publicBaseUrl 缺失时回退 reportUrl。
- [#2] 纯文本兜底增补「工作台:」深链行（matrix 邮件且 publicBaseUrl 存在）。
- [#3] 测试：任务邮件断言 /live + 查看评审工作台；新增待人工决策邮件断言 /live + 进入人工决策且不含查看评审报告；
  刷新 docs/测试/mail-preview-handoff.html。

## 文件清单

- SmtpMailNotificationAdapter.java / SmtpMailNotificationAdapterTests.java / docs/测试/mail-preview-handoff.html

## 验证

- JAVA_HOME=D:/Tool/Java21 ./mvnw.cmd test 全绿。

## 变更记录

- 2026-09-01 立计划；父会话直接实施；测试通过后提交。
