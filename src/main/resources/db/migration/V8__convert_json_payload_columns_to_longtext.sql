ALTER TABLE review_plan
    MODIFY plan_json LONGTEXT NOT NULL;

ALTER TABLE repository_snapshot
    MODIFY manifest_json LONGTEXT NOT NULL;

ALTER TABLE debate_turn
    MODIFY evidence_summary LONGTEXT NULL;

ALTER TABLE review_event
    MODIFY payload_json LONGTEXT NOT NULL;

ALTER TABLE audit_event
    MODIFY metadata_json LONGTEXT NULL;

ALTER TABLE notification_outbox
    MODIFY payload_json LONGTEXT NOT NULL;
