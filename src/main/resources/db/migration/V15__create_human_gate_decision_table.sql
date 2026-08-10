-- [AIREVIEW-PLAN-011#1.3] Durable final human Gate versions so the history view survives a restart.
-- The HUMAN_GATE_FINALIZED event payload only carries gateVersion/result/draftResult; reason,
-- conditions, overrideReason and reviewerId are preserved here at finalize time.
CREATE TABLE human_gate_decision (
    gate_decision_id   CHAR(36)                     NOT NULL,
    review_id          CHAR(36)                     NOT NULL,
    gate_version       BIGINT                       NOT NULL,
    gate_result        VARCHAR(32)                  NOT NULL,
    reason_text        MEDIUMTEXT                   NULL,
    conditions_json    MEDIUMTEXT                   NULL,
    override_reason    MEDIUMTEXT                   NULL,
    reviewer_id        VARCHAR(128)                 NOT NULL,
    supersedes_version BIGINT                       NULL,
    decided_at         DATETIME(3)                  NOT NULL,
    PRIMARY KEY (gate_decision_id),
    UNIQUE KEY uk_human_gate_review_version (review_id, gate_version),
    CONSTRAINT fk_human_gate_review FOREIGN KEY (review_id) REFERENCES review_request (review_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
