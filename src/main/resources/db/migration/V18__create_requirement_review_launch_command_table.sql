CREATE TABLE requirement_review_launch_command (
    requirement_id CHAR(36) NOT NULL,
    idempotency_key VARCHAR(128) NOT NULL,
    request_fingerprint CHAR(64) CHARACTER SET ascii NOT NULL,
    owner_token CHAR(36) CHARACTER SET ascii NOT NULL,
    lease_until DATETIME(3) NULL,
    review_id CHAR(36) NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (requirement_id, idempotency_key),
    KEY idx_requirement_review_launch_review (review_id),
    CONSTRAINT fk_requirement_review_launch_requirement
        FOREIGN KEY (requirement_id) REFERENCES requirement (requirement_id) ON DELETE CASCADE,
    CONSTRAINT fk_requirement_review_launch_review
        FOREIGN KEY (review_id) REFERENCES review_request (review_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
