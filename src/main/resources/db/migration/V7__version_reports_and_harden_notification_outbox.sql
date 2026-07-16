-- [AIREVIEW-PLAN-011#1.4,#1.5] Preserve report history and durable notification retry/idempotency metadata.

ALTER TABLE review_report
    DROP INDEX uk_review_report_attempt,
    ADD COLUMN report_version BIGINT NOT NULL DEFAULT 1 AFTER attempt_no,
    ADD COLUMN gate_version BIGINT NULL AFTER report_version,
    ADD COLUMN markdown_content MEDIUMTEXT NULL AFTER report_content,
    ADD UNIQUE KEY uk_review_report_version (review_id, report_version);

ALTER TABLE notification_outbox
    ADD COLUMN gate_version BIGINT NULL AFTER attempt_no,
    ADD COLUMN channel VARCHAR(64) NULL AFTER event_type,
    ADD COLUMN idempotency_key VARCHAR(256) NULL AFTER channel,
    ADD COLUMN request_hash CHAR(64) NULL AFTER payload_json,
    ADD COLUMN response_code VARCHAR(128) NULL AFTER attempt_count,
    ADD COLUMN response_hash CHAR(64) NULL AFTER response_code,
    ADD COLUMN last_error_code VARCHAR(128) NULL AFTER response_hash,
    ADD UNIQUE KEY uk_notification_outbox_idempotency (idempotency_key);
