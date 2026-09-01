# AIREVIEW-PLAN-108 通知邮件 HTML 化（对标主流邮件通知样式）

状态：✅ 实施完成

## 背景

SMTP 通知邮件当前为纯文本行（「- 事件: …」），观感原始。用户要求对标主流邮件通知样式
（GitHub/GitLab 式品牌头 + 状态徽标 + 卡片正文 + CTA 按钮 + 灰色页脚）。

## 方案

- [#1] SmtpMailNotificationAdapter 改用 MimeMessageHelper(multipart=true)，setText(plain, html)
  生成 multipart/alternative（纯文本兜底 + HTML 主体）。
- [#2] HTML 模板（table 布局 + 全内联样式，宽 640px，浅灰底白卡）：
  - 品牌头：深蓝条「重明 · AI 需求评审」；
  - 标题区：大字标题 + 事件彩色徽标（TASK_HANDOFF 蓝「任务流转」、HUMAN_REVIEW_REQUIRED 琥珀「待人工决策」、
    Gate 通知按结果着色：PASS 绿 / CONDITIONAL·HUMAN_REQUIRED 琥珀 / BLOCK 红 / 其余灰）；
  - 信息卡：事件 / 内容 / 收件人 / 评审 键值行；Gate 变体含 结论 / 理由 / 条件列表；
  - CTA 按钮「查看评审报告」：绝对 URL = publicBaseUrl + 「/reviews/{reviewId}/report」；
    publicBaseUrl 为空时回退 reportUrl；
  - 页脚小灰字：报告接口、幂等键、自动发送提示。
  - 所有动态字段 HTML 转义。
- [#3] NotificationMailProperties 增加 publicBaseUrl（默认空，env REVIEW_PUBLIC_BASE_URL）；
  application.yml 增加 public-base-url 占位；application-local.yml 设 http://127.0.0.1:8080/review。
- [#4] 扩展 SmtpMailNotificationAdapterTests：multipart/alternative 断言、HTML 含品牌头/CTA/转义断言、
  reason 含 <script> 时被转义断言。

## 文件清单

- src/main/java/ai/cc/chongming/review/infrastructure/notification/SmtpMailNotificationAdapter.java
- src/main/java/ai/cc/chongming/review/config/NotificationMailProperties.java
- src/main/resources/application.yml / application-local.yml
- src/test/java/ai/cc/chongming/review/notification/SmtpMailNotificationAdapterTests.java

## 验证

- JAVA_HOME=D:/Tool/Java21 ./mvnw.cmd test 全绿（基线 855+新增）；
- 本地真实发送一封验证邮件可选（mail-enabled 已开），或仅断言 MimeMessage 内容。

## 变更记录

- 2026-09-01 立计划；派发后台子代理实施。
- 2026-09-01 实施完成：multipart/alternative 双体邮件、HTML 模板与徽标映射、publicBaseUrl 配置、转义、测试扩展与静态预览页。
