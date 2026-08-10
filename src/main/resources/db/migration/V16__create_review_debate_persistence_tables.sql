-- [AIREVIEW-PLAN-010#1.3] Durable debate/conflict store so claims, topics, turns, judge decisions and
-- the AI Gate draft survive a restart. The legacy V2 claim/debate_* tables were built for an earlier
-- domain contract (turn_no conflated with round, missing topic round/resolution, judge result and
-- gate status/actor) and were never wired to any store, so these tables match the current models
-- exactly and carry the mutable topic state that must be re-saved on every mutation.
CREATE TABLE review_debate_claim (
    claim_id        CHAR(36)                     NOT NULL,
    review_id       CHAR(36)                     NOT NULL,
    role_type       VARCHAR(32)                  NOT NULL,
    subject_key     VARCHAR(512)                 NOT NULL,
    severity        VARCHAR(32)                  NOT NULL,
    position        VARCHAR(32)                  NOT NULL,
    status          VARCHAR(32)                  NOT NULL,
    statement_text  MEDIUMTEXT                   NOT NULL,
    reason_summary  MEDIUMTEXT                   NOT NULL,
    evidence_json   LONGTEXT                     NULL,
    created_at      DATETIME(3)                  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (claim_id),
    KEY idx_review_debate_claim_review (review_id, position),
    CONSTRAINT fk_review_debate_claim_review FOREIGN KEY (review_id) REFERENCES review_request (review_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE review_debate_topic (
    topic_id        CHAR(36)                     NOT NULL,
    review_id       CHAR(36)                     NOT NULL,
    subject_key     VARCHAR(512)                 NOT NULL,
    claim_ids_json  MEDIUMTEXT                   NOT NULL,
    status          VARCHAR(32)                  NOT NULL,
    current_round   INT                          NOT NULL DEFAULT 0,
    resolution      MEDIUMTEXT                   NULL,
    closed_at       DATETIME(3)                  NULL,
    created_at      DATETIME(3)                  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (topic_id),
    KEY idx_review_debate_topic_review (review_id, status),
    CONSTRAINT fk_review_debate_topic_review FOREIGN KEY (review_id) REFERENCES review_request (review_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE review_debate_turn (
    turn_id           CHAR(36)                   NOT NULL,
    topic_id          CHAR(36)                   NOT NULL,
    round_no          INT                        NOT NULL,
    actor_role        VARCHAR(32)                NOT NULL,
    target_role       VARCHAR(32)                NULL,
    turn_type         VARCHAR(32)                NOT NULL,
    target_claim_id   CHAR(36)                   NULL,
    target_turn_id    CHAR(36)                   NULL,
    public_content    MEDIUMTEXT                 NOT NULL,
    evidence_ids_json LONGTEXT                   NULL,
    stance_before     VARCHAR(32)                NULL,
    stance_after      VARCHAR(32)                NULL,
    created_at        DATETIME(3)                NOT NULL,
    PRIMARY KEY (turn_id),
    KEY idx_review_debate_turn_topic (topic_id, round_no),
    CONSTRAINT fk_review_debate_turn_topic FOREIGN KEY (topic_id) REFERENCES review_debate_topic (topic_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE review_judge_decision (
    topic_id                 CHAR(36)             NOT NULL,
    result                   VARCHAR(32)          NOT NULL,
    public_reason_summary    MEDIUMTEXT           NOT NULL,
    accepted_claim_ids_json  LONGTEXT             NULL,
    rejected_claim_ids_json  LONGTEXT             NULL,
    decided_at               DATETIME(3)          NOT NULL,
    PRIMARY KEY (topic_id),
    CONSTRAINT fk_review_judge_decision_topic FOREIGN KEY (topic_id) REFERENCES review_debate_topic (topic_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE review_gate_draft (
    review_id              CHAR(36)             NOT NULL,
    result                 VARCHAR(32)          NOT NULL,
    decision_status        VARCHAR(32)          NOT NULL,
    decision_actor         VARCHAR(32)          NOT NULL,
    public_reason_summary  MEDIUMTEXT           NOT NULL,
    decided_at             DATETIME(3)          NOT NULL,
    PRIMARY KEY (review_id),
    CONSTRAINT fk_review_gate_draft_review FOREIGN KEY (review_id) REFERENCES review_request (review_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
