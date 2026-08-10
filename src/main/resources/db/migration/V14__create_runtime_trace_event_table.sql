-- [AIREVIEW-PLAN-022#4] Runtime trace persistence for restart replay.
-- Stores the AG-UI observation stream of the main `review-{reviewId}-attempt-{attemptNo}`
-- runtime so a restarted service can still replay the full review process on /live.
--
-- MySQL 5.6 composite-key budget: runtime_id is 255 ascii bytes + event_sequence 8 bytes
-- = 263 bytes, safely under the 767-byte InnoDB limit (see V9 for the same pattern).

CREATE TABLE runtime_trace_event (
    runtime_id     VARCHAR(255) CHARACTER SET ascii NOT NULL,
    event_sequence BIGINT NOT NULL,
    event_id       VARCHAR(255) CHARACTER SET ascii NULL,
    event_type     VARCHAR(64) NOT NULL,
    payload_json   LONGTEXT NOT NULL,
    review_id      CHAR(36) NOT NULL,
    attempt_no     INT NOT NULL,
    created_at     DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (runtime_id, event_sequence),
    UNIQUE KEY uk_runtime_trace_event_id (event_id),
    KEY idx_runtime_trace_review (review_id, attempt_no, event_sequence),
    CONSTRAINT fk_runtime_trace_review FOREIGN KEY (review_id) REFERENCES review_request (review_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
