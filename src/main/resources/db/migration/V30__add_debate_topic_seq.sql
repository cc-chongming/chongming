-- [AIREVIEW-PLAN-078#1] 议题登记序号：register_topics 同批多议题在 DATETIME(3) 同毫秒时，
-- created_at tie 会退化为 UUID 序；topic_seq 记录批次内登记顺序，读模型按 topic_seq, topic_id 排序。
-- 存量行保留默认 0，接受 tie 回退 topic_id 序。MySQL 5.6 兼容（INT 非空带默认值）。
ALTER TABLE review_debate_topic ADD COLUMN topic_seq INT NOT NULL DEFAULT 0;
