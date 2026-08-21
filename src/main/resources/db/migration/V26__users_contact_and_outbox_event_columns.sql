-- [AIREVIEW-PLAN-030] 用户邮箱（流转通知邮件收件人解析基础）。
-- “发短信”语义由现有邮件通道承载；email 可空，未录入的用户保留 LOCAL 审计记录。
-- 注：通知 Outbox 当前为内存实现，事件泛化字段不落库，故本迁移仅扩展 users 表。
ALTER TABLE users
    ADD COLUMN email VARCHAR(128) NULL AFTER company_uid;
