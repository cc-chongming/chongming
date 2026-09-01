# AIREVIEW-PLAN-109 邮件 CTA 哈希路由与卡片任务信息

状态：🚧 实施中

## 背景

1. PLAN-108 的 CTA 生成 http://…/review/reviews/{id}/report 打不开；前端为 createWebHashHistory
   （PLAN-012#1.1，静态托管免服务端 rewrite），真实可用地址为 /review/#/reviews/{id}/report。
2. 流转邮件卡片缺少任务名称等必要信息，只有裸 UUID。

## 方案

- [#1] SmtpMailNotificationAdapter CTA href：publicBaseUrl 非空时拼接 "/#/reviews/{id}/report"。
- [#2] DevTaskCommandService.publishTaskEvent payload 增加 taskTitle、requirementTitle（取自 DevTask）。
- [#3] NotificationOutboxService.enqueueMatrix 从 payload 读取 taskTitle/requirementTitle/status/holder，
  经 NotificationCommand.forEvent 新增可选字段 objectTitle/objectSubtitle 传入（兼容既有构造链）；
  适配器 HTML 与纯文本在字段非空时增补行：任务 / 需求 / 当前状态 / 当前持有人。
- [#4] HUMAN_REVIEW_REQUIRED 邮件：payload 已含 result，卡片增补「AI 草案结论」行（中文映射复用徽标文案）。
- [#5] 测试更新：CTA 含 "/#/"；task 通知含任务/需求行；刷新 docs/测试/mail-preview-handoff.html 预览。

## 文件清单

- SmtpMailNotificationAdapter.java / NotificationCommand.java / NotificationOutboxService.java
- DevTaskCommandService.java / SmtpMailNotificationAdapterTests.java / docs/测试/mail-preview-handoff.html

## 验证

- JAVA_HOME=D:/Tool/Java21 ./mvnw.cmd test 全绿；真实发送一封流转邮件可见任务名称与可打开的 CTA。

## 变更记录

- 2026-09-01 立计划；派发后台子代理实施。
- 2026-09-01 实施完成 #1 CTA 哈希路由（/#/reviews/{id}/report）、#2 taskTitle/requirementTitle payload、
  #3 NotificationCommand 新增 objectTitle/objectSubtitle/objectStatus/objectHolder 四可空字段并贯通
  enqueueMatrix 与适配器信息卡（任务/需求/当前状态/当前持有人，中文状态映射）、#5 测试断言与
  docs/测试/mail-preview-handoff.html 预览刷新；#4 HUMAN_REVIEW_REQUIRED 的「AI 草案结论」行按当期
  决策不做（保持现状）。未 git commit。
