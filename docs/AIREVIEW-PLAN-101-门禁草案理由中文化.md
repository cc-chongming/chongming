# AIREVIEW-PLAN-101 门禁草案理由中文化

## 背景
人工决策结论链第 2 卡直接裸渲染后端 GatePolicy 的机器理由：
`required=29, confirmed=14, partial=18, gap=0, unknown=1, notApplicable=0; P0/P1 claim lacks verified evidence`。
人工审核面板早已用 humanizeGateReason 转中文（「29 项必填检查点：14 项确认、18 项部分确认、1 项未知；P0/P1 级主张缺少已验证证据」），结论链与需求详情页却漏接。

## 变更
- [AIREVIEW-PLAN-101#1] `ReviewLiveView.vue` 结论链第 2 卡：`gateDraft.reasonSummary` 经
  `humanizeGateReason` 渲染（未命中映射时原样回退，不损坏原文）。
- [AIREVIEW-PLAN-101#2] `RequirementDetailView.vue` 门禁版本历史理由同处理。
- judgement/claim 的 reasonSummary 为 AI 自由文本（本就中文），不接入。

## 验收
- 结论链第 2 卡显示中文覆盖统计 + 触发原因；vitest 全绿；build 同步 static/review 与 target/classes。
