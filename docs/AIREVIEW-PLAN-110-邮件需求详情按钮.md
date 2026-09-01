# AIREVIEW-PLAN-110 邮件「查看需求详情」次按钮

状态：✅ 完成

## 背景

PLAN-109 后邮件仅有「查看评审报告」主按钮；用户要求再加「查看需求详情」按钮，
深链 /review/#/requirements/{requirementId}。

## 方案

- [#1] 任务事件 payload 已含 requirementId；enqueueMatrix 读取并经 NotificationCommand
  新增可空字段 requirementId 传递（record 尾部扩参，legacy 构造器补 null）。
- [#2] 适配器新增 requirementUrl()：publicBaseUrl + "/#/requirements/{id}"，缺失时 null；
  HTML 主按钮旁渲染白底蓝边次按钮「查看需求详情」；纯文本兜底增补「需求详情:」行。
- [#3] 测试断言次按钮与深链；刷新 docs/测试/mail-preview-handoff.html。

## 文件清单

- NotificationCommand.java / NotificationOutboxService.java / SmtpMailNotificationAdapter.java
- SmtpMailNotificationAdapterTests.java / docs/测试/mail-preview-handoff.html

## 验证

- JAVA_HOME=D:/Tool/Java21 ./mvnw.cmd test 全绿。

## 变更记录

- 2026-09-01 立计划；父会话直接实施（小改动）；测试通过后提交。
