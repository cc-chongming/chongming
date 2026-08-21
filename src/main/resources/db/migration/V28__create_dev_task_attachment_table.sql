-- [AIREVIEW-PLAN-031#0] Delivery attachments uploaded during development, handoff and acceptance.
CREATE TABLE dev_task_attachment (
    attachment_id CHAR(36) NOT NULL,
    task_id CHAR(36) NOT NULL,
    file_name VARCHAR(255) NOT NULL,
    content_type VARCHAR(128) NULL,
    file_size BIGINT NOT NULL,
    uploaded_by VARCHAR(64) NOT NULL,
    created_at DATETIME(3) NOT NULL,
    content LONGBLOB NOT NULL,
    PRIMARY KEY (attachment_id),
    KEY idx_task_attachment_task (task_id, created_at),
    CONSTRAINT fk_task_attachment_task FOREIGN KEY (task_id) REFERENCES dev_task (task_id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
