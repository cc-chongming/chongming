CREATE TABLE review_request (
    review_id CHAR(36) NOT NULL,
    request_id VARCHAR(128) NOT NULL,
    submitter_id VARCHAR(128) NOT NULL,
    stage VARCHAR(32) NOT NULL,
    input_idempotency_key VARCHAR(128) NOT NULL,
    current_attempt_no INT NOT NULL DEFAULT 1,
    version BIGINT NOT NULL DEFAULT 0,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (review_id),
    UNIQUE KEY uk_review_request_request_id (request_id),
    UNIQUE KEY uk_review_request_idempotency (input_idempotency_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE review_plan (
    plan_id CHAR(36) NOT NULL,
    review_id CHAR(36) NOT NULL,
    attempt_no INT NOT NULL,
    plan_json LONGTEXT NOT NULL,
    plan_hash CHAR(64) NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (plan_id),
    UNIQUE KEY uk_review_plan_attempt (review_id, attempt_no),
    CONSTRAINT fk_review_plan_review FOREIGN KEY (review_id) REFERENCES review_request (review_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE requirement_snapshot (
    snapshot_id CHAR(36) NOT NULL,
    review_id CHAR(36) NOT NULL,
    attempt_no INT NOT NULL,
    source_uri VARCHAR(1024) NULL,
    content_text MEDIUMTEXT NOT NULL,
    content_hash CHAR(64) NOT NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (snapshot_id),
    UNIQUE KEY uk_requirement_snapshot_hash (review_id, attempt_no, content_hash),
    CONSTRAINT fk_requirement_snapshot_review FOREIGN KEY (review_id) REFERENCES review_request (review_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE repository_snapshot (
    snapshot_id CHAR(36) NOT NULL,
    review_id CHAR(36) NOT NULL,
    attempt_no INT NOT NULL,
    repository_uri VARCHAR(1024) CHARACTER SET ascii NOT NULL,
    revision VARCHAR(128) CHARACTER SET ascii NOT NULL,
    manifest_json LONGTEXT NOT NULL,
    content_hash CHAR(64) NOT NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (snapshot_id),
    UNIQUE KEY uk_repository_snapshot_revision (review_id, attempt_no, repository_uri(191), revision),
    CONSTRAINT fk_repository_snapshot_review FOREIGN KEY (review_id) REFERENCES review_request (review_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE role_activation (
    activation_id CHAR(36) NOT NULL,
    review_id CHAR(36) NOT NULL,
    attempt_no INT NOT NULL,
    role_code VARCHAR(64) NOT NULL,
    agent_name VARCHAR(128) NOT NULL,
    status VARCHAR(32) NOT NULL,
    session_id VARCHAR(255) CHARACTER SET ascii NULL,
    activated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    deactivated_at DATETIME(3) NULL,
    version BIGINT NOT NULL DEFAULT 0,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (activation_id),
    UNIQUE KEY uk_role_activation_role (review_id, attempt_no, role_code),
    UNIQUE KEY uk_role_activation_session (review_id, attempt_no, session_id),
    CONSTRAINT fk_role_activation_review FOREIGN KEY (review_id) REFERENCES review_request (review_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE agent_run (
    run_id CHAR(36) NOT NULL,
    review_id CHAR(36) NOT NULL,
    attempt_no INT NOT NULL,
    activation_id CHAR(36) NOT NULL,
    run_status VARCHAR(32) NOT NULL,
    input_hash CHAR(64) NULL,
    output_hash CHAR(64) NULL,
    failure_code VARCHAR(64) NULL,
    failure_summary VARCHAR(1024) NULL,
    started_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    finished_at DATETIME(3) NULL,
    version BIGINT NOT NULL DEFAULT 0,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (run_id),
    KEY idx_agent_run_review_attempt (review_id, attempt_no, started_at),
    CONSTRAINT fk_agent_run_review FOREIGN KEY (review_id) REFERENCES review_request (review_id),
    CONSTRAINT fk_agent_run_activation FOREIGN KEY (activation_id) REFERENCES role_activation (activation_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
