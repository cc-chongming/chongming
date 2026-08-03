CREATE TABLE requirement (
    requirement_id CHAR(36) NOT NULL,
    title VARCHAR(255) NOT NULL,
    description_md MEDIUMTEXT NULL,
    requirement_status VARCHAR(32) NOT NULL,
    creator_id VARCHAR(128) NOT NULL,
    assignee_id VARCHAR(128) NULL,
    repository_path VARCHAR(1024) NULL,
    priority VARCHAR(8) NULL,
    review_id CHAR(36) NULL,
    version BIGINT NOT NULL DEFAULT 0,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (requirement_id),
    KEY idx_requirement_status (requirement_status),
    KEY idx_requirement_assignee (assignee_id),
    KEY idx_requirement_review (review_id),
    CONSTRAINT fk_requirement_review FOREIGN KEY (review_id) REFERENCES review_request (review_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

ALTER TABLE review_request
    ADD COLUMN requirement_id CHAR(36) NULL,
    ADD KEY idx_review_request_requirement_id (requirement_id);
