# AIREVIEW-PLAN-080 领域事件白名单补 INITIAL_REVIEW_COMPLETED

状态：✅ 完成

## 背景
077 部署后 4/4 初审完成页面仍不跳冲突检测。根因：review-store 的阶段更新走
`/api/reviews/{id}/events` 命名事件 SSE，白名单 REVIEW_EVENT_TYPES 缺 INITIAL_REVIEW_COMPLETED——
EventSource 未注册该监听，事件被丢弃；ROLE_COMPLETED 在白名单内故卡片更新而 stage 不动，
直到 DEBATE_TOPIC_OPENED 等后续白名单事件才迟到跳变。

## 方案
- [#1] review-sse.js REVIEW_EVENT_TYPES 增 'INITIAL_REVIEW_COMPLETED'。

## 变更记录
- 2026-08-31 父代理直修（一行白名单+注释），vitest 164 全绿，提交。
