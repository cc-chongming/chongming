CREATE TABLE review_event (
    event_id CHAR(36) NOT NULL,
    review_id CHAR(36) NOT NULL,
    attempt_no INT NOT NULL,
    event_sequence BIGINT NOT NULL,
    event_type VARCHAR(64) NOT NULL,
    actor_type VARCHAR(32) NOT NULL,
    payload_json JSON NOT NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (event_id),
    UNIQUE KEY uk_review_event_sequence (review_id, event_sequence),
    CONSTRAINT fk_review_event_review FOREIGN KEY (review_id) REFERENCES review_request (review_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE human_review_item (
    item_id CHAR(36) NOT NULL,
    review_id CHAR(36) NOT NULL,
    attempt_no INT NOT NULL,
    item_type VARCHAR(64) NOT NULL,
    status VARCHAR(32) NOT NULL,
    question_text MEDIUMTEXT NOT NULL,
    resolution_summary MEDIUMTEXT NULL,
    resolved_by VARCHAR(128) NULL,
    resolved_at DATETIME(3) NULL,
    version BIGINT NOT NULL DEFAULT 0,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (item_id),
    KEY idx_human_review_item_status (review_id, attempt_no, status),
    CONSTRAINT fk_human_review_item_review FOREIGN KEY (review_id) REFERENCES review_request (review_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE audit_event (
    audit_id CHAR(36) NOT NULL,
    review_id CHAR(36) NULL,
    attempt_no INT NULL,
    action_type VARCHAR(64) NOT NULL,
    actor_id VARCHAR(128) NOT NULL,
    summary VARCHAR(1024) NOT NULL,
    metadata_json JSON NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (audit_id),
    KEY idx_audit_event_review (review_id, attempt_no, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE model_call_log (
    call_id CHAR(36) NOT NULL,
    review_id CHAR(36) NULL,
    attempt_no INT NULL,
    agent_run_id CHAR(36) NULL,
    provider VARCHAR(64) NOT NULL,
    model_name VARCHAR(128) NOT NULL,
    request_digest CHAR(64) NOT NULL,
    response_summary VARCHAR(2048) NULL,
    prompt_token_count INT NULL,
    completion_token_count INT NULL,
    latency_ms BIGINT NULL,
    status VARCHAR(32) NOT NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (call_id),
    KEY idx_model_call_log_review (review_id, attempt_no, created_at),
    CONSTRAINT fk_model_call_log_agent_run FOREIGN KEY (agent_run_id) REFERENCES agent_run (run_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE notification_outbox (
    notification_id CHAR(36) NOT NULL,
    review_id CHAR(36) NOT NULL,
    attempt_no INT NOT NULL,
    event_type VARCHAR(64) NOT NULL,
    destination VARCHAR(512) NOT NULL,
    payload_json JSON NOT NULL,
    delivery_status VARCHAR(32) NOT NULL,
    next_retry_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    delivered_at DATETIME(3) NULL,
    attempt_count INT NOT NULL DEFAULT 0,
    version BIGINT NOT NULL DEFAULT 0,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (notification_id),
    KEY idx_notification_outbox_dispatch (delivery_status, next_retry_at),
    CONSTRAINT fk_notification_outbox_review FOREIGN KEY (review_id) REFERENCES review_request (review_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE review_report (
    report_id CHAR(36) NOT NULL,
    review_id CHAR(36) NOT NULL,
    attempt_no INT NOT NULL,
    report_status VARCHAR(32) NOT NULL,
    report_content MEDIUMTEXT NOT NULL,
    content_hash CHAR(64) NOT NULL,
    published_at DATETIME(3) NULL,
    version BIGINT NOT NULL DEFAULT 0,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (report_id),
    UNIQUE KEY uk_review_report_attempt (review_id, attempt_no),
    CONSTRAINT fk_review_report_review FOREIGN KEY (review_id) REFERENCES review_request (review_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
