-- [AIREVIEW-PLAN-044#1] 辩题中文标题：协调者经 register_topics 开题时给出，仅用于工作台展示。
-- 可空列兼容存量行（NULL 由读模型/前端回退 subjectKey）；subjectKey 仍为匹配键与技术标识，语义不变。
ALTER TABLE review_debate_topic ADD COLUMN public_title VARCHAR(255) NULL;
