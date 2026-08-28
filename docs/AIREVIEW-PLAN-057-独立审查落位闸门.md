# AIREVIEW-PLAN-057 独立审查落位闸门：至少一个角色创建后才自动跳转

状态：✅ 完成

## 背景
用户反馈（截图）：上下文侦察完成后视图两连跳，评审规划一闪而过直接落到独立审查，
而独立审查仍 0/0「尚未激活独立审查角色」。期望：至少有一个角色被创建后再跳到独立审查。

## 根因
`resolvePhaseLanding` 按 stage 纯映射：INITIAL_REVIEW → 索引 2（独立审查）。
阶段机先于角色激活进入 INITIAL_REVIEW（协调者尚未发布激活决策），落位因此先跳进空窗。

## 方案
- [AIREVIEW-PLAN-057#1] `resolvePhaseLanding` 新增 `reviewStarted` 入参：
  `byStage === 2 && !reviewStarted` 时返回 1（继续停留评审规划）。
- [AIREVIEW-PLAN-057#2] ReviewLiveView 传 `reviewStarted: reviewRoleCodes.length > 0`，
  与审查卡/0-0 副标题同一投影口径（activatedRoles + claims + 运行角色），所见即所得：
  只要至少一个角色可投影就自动跳转独立审查。
- [AIREVIEW-PLAN-057#3] 单测同步：新增闸门用例；INITIAL_REVIEW 旧用例补 `reviewStarted: true`。

## 文件清单
- frontend/src/services/review-phase-presenter.js（#1）
- frontend/src/services/review-phase-presenter.test.js（#3）
- frontend/src/views/ReviewLiveView.vue（#2）

## 顺序
1. presenter #1 → 2. view #2 → 3. tests #3 → 4. vitest 回归 → 5. vite build 同步 static/review 与 target/classes → 6. 父代理审查提交。

## 风险
- 停留评审规划期间阶段徽章显示规划 running（scout 已结束、协调者正在创建角色），符合实际；
- 闸门仅作用于 byStage===2，CONFLICT_DETECTION 及之后阶段落位不受影响；
- 手动点选阶段（selectedPhase）不受落位逻辑影响。

## 变更记录
- 2026-08-28 立计划；派发后台子代理实施。
- 2026-08-28 子代理中途失败（零写入，本会话第三次）；父代理按本计划直接实施 #1/#2/#3：
  vitest 161 全绿（159+2）；vite build 同步 static/review 与 target/classes；父代理独立审查 diff 无夹带后提交。
