# AIREVIEW-PLAN-058 冲突检测停留窗口：内容驱动落位补齐

状态：✅ 完成

## 背景
用户反馈（截图红框冲突检测/多轮辩论）：独立审查结束后视图停留了一段时间（阶段机滞后），
随后冲突检测一闪而过直接跳多轮辩论，「7 个议题登记」的冲突汇总没有停留窗口。

## 根因
落位按 stage 纯映射：INITIAL_REVIEW→CONFLICT_DETECTION 滞后窗口视图停在已完成的独立审查；
CONFLICT_DETECTION→DEBATE 窗口过短且辩论空窗即跳 phase，冲突检测被跳过。

## 方案（与 PLAN-057 同哲学：内容驱动落位）
- [AIREVIEW-PLAN-058#1] 议题已登记可见（conflictStarted）时，即使阶段机仍滞后在 INITIAL_REVIEW
  也前落位到冲突检测（隐含 reviewStarted），缩短初审完成后的视图停留。
- [AIREVIEW-PLAN-058#2] DEBATE 窗口内尚无公开辩论内容（debateStarted：claims 或 turns/答辩对话）时
  停留冲突检测；claims/对话出现后才自动跳多轮辩论。
- [AIREVIEW-PLAN-058#3] 单测同步：DEBATE 旧用例补 debateStarted: true；新增 3 个用例。

## 文件清单
- frontend/src/services/review-phase-presenter.js（#1、#2）
- frontend/src/services/review-phase-presenter.test.js（#3）
- frontend/src/views/ReviewLiveView.vue（传参）

## 顺序
1. presenter → 2. view → 3. tests → 4. vitest → 5. vite build 同步 → 6. 父代理审查提交。

## 风险
- 手动点选阶段不受落位逻辑影响（selectedPhase 覆盖）；
- #1 前跳要求议题已登记，此时初审必然结束，不会提前离开独立审查；
- #2 停留期间辩论 claims 在冲突卡内同样可见，不遮挡信息；JUDGING 及之后落位不受影响。

## 变更记录
- 2026-08-28 立计划；派发后台子代理实施。
- 2026-08-28 两次派发均失败（未指定/显式指定 provider 各一次；根因见 LRN-20260828-002：会话默认 qorder 已删除）；父代理按本计划直接实施 #1/#2/#3：
  vitest 164 全绿（161+3）；vite build 同步 static/review 与 target/classes；父代理独立审查 diff 无夹带后提交。
