-- [AIREVIEW-PLAN-030] 任务多级流转：当前持有人与不可变 handoff 历史。
-- current_holder_username 与 assignee_username 在 handoff 时同步更新（后者兼容既有可见性/查询索引）；
-- handoff_history 以 JSON 数组持久化 HandoffEntry(seq, fromUsername, toUsername, note, at)。
ALTER TABLE dev_task
    ADD COLUMN current_holder_username VARCHAR(64) NULL AFTER dispatcher_username,
    ADD COLUMN handoff_history LONGTEXT NULL AFTER current_holder_username;
