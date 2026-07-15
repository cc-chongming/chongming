CREATE TABLE evidence_block (
    evidence_id CHAR(36) NOT NULL,
    review_id CHAR(36) NOT NULL,
    attempt_no INT NOT NULL,
    source_type VARCHAR(32) NOT NULL,
    source_uri VARCHAR(1024) NULL,
    locator VARCHAR(512) NULL,
    excerpt MEDIUMTEXT NOT NULL,
    content_hash CHAR(64) NOT NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (evidence_id),
    UNIQUE KEY uk_evidence_block_hash (review_id, attempt_no, content_hash),
    KEY idx_evidence_block_review_attempt (review_id, attempt_no),
    CONSTRAINT fk_evidence_block_review FOREIGN KEY (review_id) REFERENCES review_request (review_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE claim (
    claim_id CHAR(36) NOT NULL,
    review_id CHAR(36) NOT NULL,
    attempt_no INT NOT NULL,
    topic_id CHAR(36) NULL,
    statement_text MEDIUMTEXT NOT NULL,
    severity VARCHAR(32) NOT NULL,
    position VARCHAR(32) NOT NULL,
    status VARCHAR(32) NOT NULL,
    confidence DECIMAL(5,4) NULL,
    idempotency_key VARCHAR(128) NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (claim_id),
    UNIQUE KEY uk_claim_idempotency (review_id, attempt_no, idempotency_key),
    KEY idx_claim_review_status (review_id, attempt_no, status),
    CONSTRAINT fk_claim_review FOREIGN KEY (review_id) REFERENCES review_request (review_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE claim_evidence (
    claim_id CHAR(36) NOT NULL,
    evidence_id CHAR(36) NOT NULL,
    relation_type VARCHAR(32) NOT NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (claim_id, evidence_id),
    CONSTRAINT fk_claim_evidence_claim FOREIGN KEY (claim_id) REFERENCES claim (claim_id),
    CONSTRAINT fk_claim_evidence_evidence FOREIGN KEY (evidence_id) REFERENCES evidence_block (evidence_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE debate_topic (
    topic_id CHAR(36) NOT NULL,
    review_id CHAR(36) NOT NULL,
    attempt_no INT NOT NULL,
    subject_text MEDIUMTEXT NOT NULL,
    status VARCHAR(32) NOT NULL,
    opened_by VARCHAR(64) NOT NULL,
    closed_at DATETIME(3) NULL,
    version BIGINT NOT NULL DEFAULT 0,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (topic_id),
    KEY idx_debate_topic_review_status (review_id, attempt_no, status),
    CONSTRAINT fk_debate_topic_review FOREIGN KEY (review_id) REFERENCES review_request (review_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE debate_turn (
    turn_id CHAR(36) NOT NULL,
    topic_id CHAR(36) NOT NULL,
    turn_no INT NOT NULL,
    turn_type VARCHAR(32) NOT NULL,
    actor_role VARCHAR(64) NOT NULL,
    public_content MEDIUMTEXT NOT NULL,
    evidence_summary JSON NULL,
    idempotency_key VARCHAR(128) NOT NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (turn_id),
    UNIQUE KEY uk_debate_turn_number (topic_id, turn_no),
    UNIQUE KEY uk_debate_turn_idempotency (topic_id, idempotency_key),
    CONSTRAINT fk_debate_turn_topic FOREIGN KEY (topic_id) REFERENCES debate_topic (topic_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE judge_decision (
    decision_id CHAR(36) NOT NULL,
    topic_id CHAR(36) NOT NULL,
    decision_status VARCHAR(32) NOT NULL,
    decision_actor VARCHAR(64) NOT NULL,
    rationale_summary MEDIUMTEXT NOT NULL,
    idempotency_key VARCHAR(128) NOT NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (decision_id),
    UNIQUE KEY uk_judge_decision_topic (topic_id),
    UNIQUE KEY uk_judge_decision_idempotency (topic_id, idempotency_key),
    CONSTRAINT fk_judge_decision_topic FOREIGN KEY (topic_id) REFERENCES debate_topic (topic_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE gate_decision (
    gate_decision_id CHAR(36) NOT NULL,
    review_id CHAR(36) NOT NULL,
    attempt_no INT NOT NULL,
    gate_name VARCHAR(64) NOT NULL,
    gate_result VARCHAR(32) NOT NULL,
    reason_summary MEDIUMTEXT NOT NULL,
    idempotency_key VARCHAR(128) NOT NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (gate_decision_id),
    UNIQUE KEY uk_gate_decision_idempotency (review_id, attempt_no, gate_name, idempotency_key),
    KEY idx_gate_decision_review_attempt (review_id, attempt_no),
    CONSTRAINT fk_gate_decision_review FOREIGN KEY (review_id) REFERENCES review_request (review_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
