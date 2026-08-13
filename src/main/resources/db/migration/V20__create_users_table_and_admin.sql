-- 认证模块：用户表与预置管理员账号。
-- users 表承载登录凭证；password_hash 采用 PBKDF2-HMAC-SHA256，
-- 存储格式为 PBKDF2$iterations$saltBase64$hashBase64（由 PasswordHasher 生成与校验）。
CREATE TABLE users (
    id            BIGINT       NOT NULL AUTO_INCREMENT,
    username      VARCHAR(64)  NOT NULL,
    password_hash VARCHAR(255) NOT NULL,
    display_name  VARCHAR(64)  NULL,
    role          VARCHAR(32)  NOT NULL DEFAULT 'USER',
    created_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_users_username (username),
    KEY idx_users_role (role)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 预置管理员 admin，初始密码为 Admin@123。
-- 下方 password_hash 由与运行时一致的 PasswordHasher（PBKDF2WithHmacSHA256，210000 轮，随机盐）生成，
-- 请登录后尽快修改该初始密码。
INSERT INTO users (username, password_hash, display_name, role)
VALUES ('admin', 'PBKDF2$210000$yNUb5APsF9PpqD67p5uruA==$U9zDPNOvzoRPnTu/av9n0KIr9ByzE4gTBOVIaCOcd+U=', '管理员', 'ADMIN');
