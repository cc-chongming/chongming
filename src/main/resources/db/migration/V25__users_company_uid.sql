-- [AIREVIEW-PLAN-025] 用户表新增公司内部 uid：注册时可选填写，作为后续消息发送的绑定标识。
-- 列可空兼容存量账号；唯一索引保证同一 uid 只绑定一个账号（MySQL 唯一索引允许多个 NULL）。
ALTER TABLE users ADD COLUMN company_uid VARCHAR(64) NULL;
CREATE UNIQUE INDEX uk_users_company_uid ON users (company_uid);
