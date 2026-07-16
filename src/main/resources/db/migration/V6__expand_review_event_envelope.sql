-- [AIREVIEW-PLAN-010#1.1,#1.2] Expands the append-only review_event envelope for durable SSE replay.
-- Existing actor_type/payload_json columns remain for backwards-compatible migration of already deployed data.

ALTER TABLE review_event
    ADD COLUMN event_category VARCHAR(32) NOT NULL DEFAULT 'ERROR' AFTER event_type,
    ADD COLUMN stage VARCHAR(32) NOT NULL DEFAULT 'PENDING' AFTER event_category,
    ADD COLUMN actor_role VARCHAR(32) NULL AFTER stage,
    ADD COLUMN target_role VARCHAR(32) NULL AFTER actor_role,
    ADD COLUMN topic_id CHAR(36) NULL AFTER target_role,
    ADD COLUMN claim_id CHAR(36) NULL AFTER topic_id,
    ADD COLUMN turn_id CHAR(36) NULL AFTER claim_id,
    ADD COLUMN debate_round INT NULL AFTER turn_id,
    ADD COLUMN progress INT NULL AFTER debate_round,
    ADD COLUMN payload_version INT NOT NULL DEFAULT 1 AFTER progress,
    ADD COLUMN occurred_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) AFTER payload_version;

CREATE INDEX idx_review_event_replay ON review_event (review_id, event_sequence);
