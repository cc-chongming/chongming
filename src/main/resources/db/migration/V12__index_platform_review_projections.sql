-- [AIREVIEW-PLAN-021#8][REQLIFE-M2] Supports newest-first Dashboard activity and platform list projections.
CREATE INDEX idx_review_event_occurred_at ON review_event (occurred_at);
