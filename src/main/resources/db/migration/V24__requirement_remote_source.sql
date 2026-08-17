-- [AIREVIEW-PLAN-029] 需求级远程仓库来源：创建需求时直填线上仓库地址与加密令牌。
-- 三列均可空，兼容存量需求（存量需求继续使用 requirement.repository_path 的配置仓库 id）。
ALTER TABLE requirement ADD COLUMN remote_url VARCHAR(512) NULL;
ALTER TABLE requirement ADD COLUMN remote_ref VARCHAR(128) NULL;
ALTER TABLE requirement ADD COLUMN remote_token_enc VARCHAR(1024) NULL;
