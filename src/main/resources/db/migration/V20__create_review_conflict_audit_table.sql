-- [AIREVIEW-PLAN-024#方案5] Restart-safe one-to-one audit trail for deterministic conflict subjects.
-- subject_hash keeps the composite key within the MySQL 5.6 InnoDB 767-byte index limit.
CREATE TABLE review_conflict_audit (
    review_id       CHAR(36)                     NOT NULL,
    attempt_no      INT                          NOT NULL,
    subject_hash    CHAR(64) CHARACTER SET ascii NOT NULL,
    subject_key     MEDIUMTEXT                   NOT NULL,
    claim_ids_json  LONGTEXT                     NULL,
    rules           MEDIUMTEXT                   NOT NULL,
    disposition     VARCHAR(32)                  NOT NULL,
    updated_at      DATETIME(3)                  NOT NULL,
    PRIMARY KEY (review_id, attempt_no, subject_hash),
    CONSTRAINT fk_conflict_audit_review FOREIGN KEY (review_id) REFERENCES review_request (review_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
