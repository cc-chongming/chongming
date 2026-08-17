-- [AIREVIEW-PLAN-027] 需求可见性与角色权限：存量需求 creator 归口与索引。
-- 1) 备份原始 creator_id（幂等：表已存在则跳过，INSERT IGNORE 保证重复执行不覆盖）。
CREATE TABLE IF NOT EXISTS requirement_creator_backup_plan027 (
    requirement_id CHAR(36) NOT NULL,
    creator_id VARCHAR(128) NOT NULL,
    PRIMARY KEY (requirement_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

INSERT IGNORE INTO requirement_creator_backup_plan027 (requirement_id, creator_id)
SELECT requirement_id, creator_id FROM requirement;

-- 2) 存量需求 creator_id 不在 users 表中的（历史哨兵值，如 anonymous/system）归口 admin，
--    保证 PLAN-027 的可见性谓词（creator_id = 当前用户）不丢数据。
UPDATE requirement SET creator_id = 'admin'
WHERE creator_id NOT IN (SELECT username FROM users);

-- 3) creator 可见性查询索引。MySQL 5.6 的 ADD KEY 不支持 IF NOT EXISTS，
--    Flyway 每版本仅执行一次，直接添加即可。
ALTER TABLE requirement ADD KEY idx_requirement_creator (creator_id);
