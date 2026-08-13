-- 任务流转与派发：开发任务表。
-- 每个通过人工 Gate 的需求至多派生一个开发任务（uk_dev_task_requirement 唯一键兜底）。
-- 时间戳采用 DATETIME(3) 毫秒精度；索引列均不超过 utf8mb4 下 191 字符的 5.6 限制。
-- 外键 ON DELETE CASCADE：删除需求时级联清理其开发任务，保持既有删除契约（不产生 500）。
CREATE TABLE dev_task (
    task_id             CHAR(36)     NOT NULL,
    requirement_id      CHAR(36)     NOT NULL,
    review_id           CHAR(36)     NULL,
    title               VARCHAR(255) NOT NULL,
    task_status         VARCHAR(32)  NOT NULL,
    assignee_username   VARCHAR(64)  NULL,
    dispatcher_username VARCHAR(64)  NULL,
    acceptance_note     VARCHAR(512) NULL,
    version             BIGINT       NOT NULL DEFAULT 0,
    created_at          DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at          DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (task_id),
    UNIQUE KEY uk_dev_task_requirement (requirement_id),
    KEY idx_dev_task_assignee_status (assignee_username, task_status),
    KEY idx_dev_task_status_updated (task_status, updated_at),
    CONSTRAINT fk_dev_task_requirement FOREIGN KEY (requirement_id) REFERENCES requirement (requirement_id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
