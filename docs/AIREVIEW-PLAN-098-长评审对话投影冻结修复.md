# AIREVIEW-PLAN-098 长评审公开对话投影冻结修复

## 背景 / 现象
长评审（运行事件超过 20000 保留上限，如评审 40558999 游标 21376）中，「评审观察」抽屉的
全部对话与中央阶段流面板停止更新，而左侧流程导航（summary 独立数据源）继续推进——
用户感知为「左边还在推进，右边没有最新事件」。

## 根因
- runtime-trace-store 的 flush() 在超过 EVENT_RETENTION(20000) 时对**同一数组引用**原地
  push + 头部 splice，数组长度恒等于 20000；
- runtime-conversation-adapter 的增量缓存快路径条件为
  `cached.events === list && cached.consumed === list.length`——同引用 + 长度不变永久命中，
  直接返回旧 filtered，公开对话投影从此冻结（node 实验复现：截断后 judge 事件 0 条可见）。

## 变更
- [AIREVIEW-PLAN-098#1] `frontend/src/stores/runtime-trace-store.js`：截断改为
  `state.events = state.events.slice(overflow)` 替换数组引用；增量缓存前缀校验在 index 0
  即失败 → 全量重建投影（仅超长评审每次 flush 付出 20k 归约成本，正确性优先）。
- [AIREVIEW-PLAN-098#2] `runtime-trace-store.test.js` 回归：饱和截断后新 runId 的事件
  必须出现在 buildRuntimeConversation 投影中。

## 验收
- vitest 全绿（169）；vite build 同步 static/review 与 target/classes；强刷后长评审对话继续滚动。
