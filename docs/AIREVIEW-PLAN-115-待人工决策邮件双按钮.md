# AIREVIEW-PLAN-115 待人工决策邮件与流转邮件一致的双按钮

状态：✅ 完成

## 背景

待人工决策邮件此前仅一个按钮且点击无有效落点；用户要求其与流转邮件一致：
两个按钮都可点击到对应页面（人工决策视图 + 需求详情）。
HUMAN_REVIEW_REQUIRED 事件 payload 无需求 id，需反查。

## 方案

- [#1] NotificationOutboxService.enqueueMatrix：payload 无 requirementId 时按 reviewId
  经 RequirementRepository.findByReviewId 反查，补 requirementId 与 objectSubtitle（需求标题）。
- [#2] 适配器无需改动（已有双按钮渲染）：待人工决策邮件得到「进入人工决策」+「查看需求详情」。
- [#3] 测试：outbox 增补断言（command.requirementId/objectSubtitle）；适配器人工决策用例断言双按钮与需求行；
  新增 docs/测试/mail-preview-human.html 预览。

## 文件清单

- NotificationOutboxService.java / NotificationOutboxServiceTests.java
- SmtpMailNotificationAdapterTests.java / docs/测试/mail-preview-human.html

## 验证

- JAVA_HOME=D:/Tool/Java21 ./mvnw.cmd test：867 全绿。

## 变更记录

- 2026-09-01 立计划；父会话直接实施；测试通过后提交。
