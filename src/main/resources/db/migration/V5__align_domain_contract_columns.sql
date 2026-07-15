ALTER TABLE evidence_block
    ADD COLUMN snapshot_id VARCHAR(128) NOT NULL AFTER attempt_no,
    ADD COLUMN relative_path VARCHAR(1024) NOT NULL AFTER locator,
    ADD COLUMN line_number INT NOT NULL AFTER relative_path,
    ADD COLUMN snippet_hash CHAR(64) NOT NULL AFTER line_number;

ALTER TABLE claim
    ADD COLUMN role_type VARCHAR(32) NOT NULL AFTER attempt_no,
    ADD COLUMN subject_key VARCHAR(512) NOT NULL AFTER role_type,
    ADD COLUMN reason_summary MEDIUMTEXT NOT NULL AFTER statement_text;

ALTER TABLE debate_turn
    ADD COLUMN target_role VARCHAR(64) NULL AFTER actor_role,
    ADD COLUMN target_claim_id CHAR(36) NULL AFTER target_role,
    ADD COLUMN target_turn_id CHAR(36) NULL AFTER target_claim_id,
    ADD COLUMN stance_before VARCHAR(32) NULL AFTER target_turn_id,
    ADD COLUMN stance_after VARCHAR(32) NULL AFTER stance_before;

CREATE TABLE debate_turn_evidence (
    turn_id CHAR(36) NOT NULL,
    evidence_id CHAR(36) NOT NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (turn_id, evidence_id),
    CONSTRAINT fk_debate_turn_evidence_turn FOREIGN KEY (turn_id) REFERENCES debate_turn (turn_id),
    CONSTRAINT fk_debate_turn_evidence_evidence FOREIGN KEY (evidence_id) REFERENCES evidence_block (evidence_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
