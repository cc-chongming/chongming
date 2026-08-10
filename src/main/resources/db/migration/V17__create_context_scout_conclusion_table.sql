-- [AIREVIEW-PLAN-023#5] Durable, readable Context Scout conclusions keyed by review attempt.
CREATE TABLE context_scout_conclusion (
    review_id          CHAR(36)      NOT NULL,
    attempt_no         INT           NOT NULL,
    schema_version     INT           NOT NULL,
    summary_text       MEDIUMTEXT    NOT NULL,
    module_roots_json  LONGTEXT      NOT NULL,
    entry_points_json  LONGTEXT      NOT NULL,
    constraints_json   LONGTEXT      NOT NULL,
    risks_json         LONGTEXT      NOT NULL,
    evidence_paths_json LONGTEXT     NOT NULL,
    role_scopes_json   LONGTEXT      NOT NULL,
    raw_public_result  LONGTEXT      NOT NULL,
    created_at         DATETIME(3)   NOT NULL,
    PRIMARY KEY (review_id, attempt_no),
    CONSTRAINT fk_context_scout_conclusion_review
        FOREIGN KEY (review_id) REFERENCES review_request (review_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
