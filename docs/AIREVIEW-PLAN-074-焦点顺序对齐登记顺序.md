# AIREVIEW-PLAN-074 焦点顺序对齐登记顺序（created_at）

状态：✅ 完成

## 背景
串行辩论焦点取 store findTopics 顺序=topic_id(UUID) 序，与用户感知的登记编号错位
（评审 e77ee354 先辩“议题3”）。debate_topic 表有 created_at 登记时间戳可用。

## 方案
- [#1] DebatePersistenceMapper.findTopics：ORDER BY created_at, topic_id（排序用原始列即可，无需时区还原）。
- [#2] InMemoryReviewDebateStore.findTopics：改插入序（LinkedHashMap 或维护登记列表），不再 UUID 排序。
- [#3] DebateFocusResolver 不动；测试：InMemory 登记 A/B/C 乱序 UUID→findTopics 返回登记序；
  focus 取第一个非终态=登记序首项。

## 影响面
- 显示 Tab 顺序同步变为登记序，编号与推进顺序一致；
- 其余 findTopics 消费方（冲突召回、裁决遍历）仅顺序变化，语义不变。

## 变更记录
- 2026-08-31 立计划；派发后台子代理实施。
- 2026-08-31 子代理 4f61ef9f 交付；父代理审查 diff 无夹带；回归 826 全绿；提交。
