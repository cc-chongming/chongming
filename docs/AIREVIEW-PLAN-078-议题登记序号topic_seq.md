# AIREVIEW-PLAN-078 议题登记序号 topic_seq：同毫秒批次不再退化为 UUID 序

状态：✅ 完成

## 背景
评审 1e0759c7：8 议题同批登记，created_at DATETIME(3) 同毫秒 tie → 074 的 ORDER BY created_at, topic_id
退化为 UUID 序 → 焦点先落“议题6”。串行顺序与展示编号错位。

## 方案
- [#1] V30 迁移：review_debate_topic 增 `topic_seq INT NOT NULL DEFAULT 0`（MySQL5.6 兼容；存量行留 0 接受 tie 回退）。
- [#2] DebatePersistenceMapper：两个 INSERT 增 topic_seq（#{row.topicSeq}），ON DUPLICATE KEY UPDATE 不覆盖 seq；
  findTopics SELECT 增 topic_seq，ORDER BY topic_seq, topic_id。
- [#3] MyBatisReviewDebateStore：saveTopics 对新行（findTopic 不存在者）分配 seq =
  当前 MAX(topic_seq)+1+批内下标（一次 SELECT MAX）；更新行保留原 seq。Row record 增 topicSeq。
- [#4] 领域模型 DebateTopic 不加字段（seq 为持久层排序关切）；InMemory 维持插入序（074）。
- [#5] 测试：mapper/store 无 Docker 环境跳过集成；补 DebateFocusResolver 注释说明序来源；
  若既有 MyBatisReviewDebateStoreTests 存在则补 seq 单调用例（环境跳过可接受）。

## 变更记录
- 2026-08-31 立计划；派发后台子代理实施。
- 2026-08-31 子代理 987fec55 交付（maxTopicSeq 按 review 作用域，表无 attempt 维度，已注释说明）；回归 841 全绿；提交。
- 2026-08-31 后续（PLAN-092 并入本计划记录）：ReviewQueryService 的 /debates 投影曾按 UUID 重排覆盖 seq 序，
  导致 UI 编号与串行执行顺序错位（观感 3→10→1）；删除重排，API 与焦点同序。同提交修正 089 后续桥测试断言。
