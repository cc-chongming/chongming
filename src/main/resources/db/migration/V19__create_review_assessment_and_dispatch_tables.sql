-- [AIREVIEW-PLAN-024#方案5] Durable stores for five-status checkpoint assessments and directed
-- dispatch commands so Gate coverage, report counts and dispatch replay survive a restart.
-- Version number is V19 because V17/V18 were already taken by PLAN-023 (see PLAN-024 deviation log).
-- Column/index sizes stay inside the MySQL 5.6 InnoDB 767-byte index limit (utf8mb4).
CREATE TABLE review_assessment (
    review_id         CHAR(36)                     NOT NULL,
    attempt_no        INT                          NOT NULL,
    role_type         VARCHAR(32)                  NOT NULL,
    checkpoint_key    VARCHAR(64)                  NOT NULL,
    status            VARCHAR(32)                  NOT NULL,
    summary           MEDIUMTEXT                   NOT NULL,
    reason_summary    MEDIUMTEXT                   NULL,
    evidence_ids_json LONGTEXT                     NULL,
    idempotency_key   VARCHAR(191)                 NOT NULL,
    created_at        DATETIME(3)                  NOT NULL,
    PRIMARY KEY (review_id, attempt_no, role_type, checkpoint_key),
    KEY idx_review_assessment_attempt (review_id, attempt_no),
    CONSTRAINT fk_review_assessment_review FOREIGN KEY (review_id) REFERENCES review_request (review_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE review_dispatch_command (
    command_id        CHAR(36)                     NOT NULL,
    review_id         CHAR(36)                     NOT NULL,
    attempt_no        INT                          NOT NULL,
    stage             VARCHAR(32)                  NOT NULL,
    round_no          INT                          NOT NULL,
    recipient_role    VARCHAR(32)                  NOT NULL,
    allowed_action    VARCHAR(32)                  NOT NULL,
    topic_id          CHAR(36)                     NULL,
    target_claim_id   CHAR(36)                     NULL,
    target_turn_id    CHAR(36)                     NULL,
    expires_at        DATETIME(3)                  NOT NULL,
    status            VARCHAR(32)                  NOT NULL,
    idempotency_key   VARCHAR(191)                 NOT NULL,
    created_at        DATETIME(3)                  NOT NULL,
    PRIMARY KEY (command_id),
    UNIQUE KEY uk_review_dispatch_idempotency (idempotency_key),
    KEY idx_review_dispatch_attempt (review_id, attempt_no),
    CONSTRAINT fk_review_dispatch_command_review FOREIGN KEY (review_id) REFERENCES review_request (review_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
