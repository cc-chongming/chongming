-- [AIREVIEW-PLAN-004] MySQL 5.6-compatible AgentScope runtime storage.
-- AgentScope's built-in utf8mb4 composite keys exceed MySQL 5.6's 767-byte InnoDB limit.

CREATE TABLE IF NOT EXISTS chongming_agentscope_state (
    session_id VARCHAR(255) CHARACTER SET ascii NOT NULL,
    state_key VARCHAR(255) CHARACTER SET ascii NOT NULL,
    item_index INT NOT NULL DEFAULT 0,
    state_data LONGTEXT NOT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (session_id, state_key, item_index)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS chongming_agentscope_workspace (
    namespace_path VARBINARY(512) NOT NULL,
    item_key VARBINARY(255) NOT NULL,
    value_json LONGTEXT NOT NULL,
    version BIGINT NOT NULL,
    updated_at BIGINT NOT NULL,
    PRIMARY KEY (namespace_path, item_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
