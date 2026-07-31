-- [AIREVIEW-PLAN-018#3.3] Supports attempt-isolated lookup of durable Context Scout degradation facts.
CREATE INDEX idx_review_event_type_attempt_sequence
    ON review_event (review_id, event_type, attempt_no, event_sequence);
