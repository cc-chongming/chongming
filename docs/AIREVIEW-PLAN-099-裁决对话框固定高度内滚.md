# AIREVIEW-PLAN-099 裁决阶段对话框固定高度内部滚动

## 背景 / 现象
议题裁决阶段产生裁决卡后，裁决者运行流面板被压缩成一条（仅剩页头行），对话内容不可见。

## 根因
- `.flow-content > .flow-stream-live` 为 `flex: 1 1 0`（basis 0），裁决卡区
  `.flow-judgement-section` 为 `flex: 1 1 auto`（basis = 内容高度）；
- PLAN-065 的 `.flow-stream-capped { max-height: 55% }` 只限最高、不保最低，
  裁决卡出现后 basis 0 的流面板在 flex 收缩中让出全部高度，`min-height: 0` 链条使其压至近乎 0。

## 变更
- [AIREVIEW-PLAN-099#1] `frontend/src/styles/review.css`：裁决阶段（flow-stream-capped）
  面板 `flex: 0 0 auto` 退出伸缩；`.live-agent-scroll` 固定高度 `min(22rem, 42vh)` 内部滚动；
  裁决卡区保留自身 overflow-y 滚动，两区互不挤压。

## 验收
- 裁决卡出现后裁决者对话框保持固定高度且内部可滚；vite build 同步 static/review 与 target/classes。
