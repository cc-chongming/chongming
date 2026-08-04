-- [AIREVIEW-PLAN-021#8][REQLIFE-M2] Matches the Dashboard newest-first event projection order on MySQL 5.6.
CREATE INDEX idx_review_event_recent_activity
    ON review_event (occurred_at, review_id, event_sequence);
