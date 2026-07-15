CREATE TABLE review_command_result (
    review_id CHAR(36) NOT NULL,
    idempotency_key VARCHAR(128) NOT NULL,
    result_reference VARCHAR(1024) NOT NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (review_id, idempotency_key),
    CONSTRAINT fk_review_command_result_review FOREIGN KEY (review_id) REFERENCES review_request (review_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
