# AIREVIEW-PLAN-111 需求详情页渲染上传的 Markdown 需求文档

状态：🚧 实施中

## 背景

需求经上传 Markdown 创建时，Requirement.description 为空，详情页「需求文档」卡片只显示
「未填写需求描述。」；而上传原文实际保存在评审快照（RequirementSnapshotStore，
raw/normalized markdown 文件）。详情页应渲染上传文档。

## 方案

- [#1] 后端 RequirementQueryService 新增 findDocument(RequirementId, visibility)：
  复用既有可见性校验；requirement.reviewId 为空→404；经 ReviewRegistry 取 attemptNo，
  RequirementSnapshotStore.stored(reviewId, attemptNo) 读 rawMarkdownPath（缺失回退
  normalizedMarkdownPath），返回 record RequirementDocumentView(reviewId, attemptNo, filename, markdown)。
- [#2] RequirementQueryController 新增 GET /api/requirements/{id}/document。
- [#3] 前端 review-api.js 增 getRequirementDocument；RequirementDetailView 并行拉取（404→null），
  「需求文档」卡片优先用 SafeMarkdown 渲染 document.markdown（卡片头显示 filename），
  回退 description，再回退「未填写需求描述。」。
- [#4] 测试：后端服务/控制器测试（临时目录存快照→返回原文；无 reviewId→404；越权→既有 403/404）；
  前端 vitest 回归。

## 文件清单

- RequirementQueryService.java / RequirementQueryController.java
- 对应测试；frontend/src/api/review-api.js；frontend/src/views/RequirementDetailView.vue

## 验证

- JAVA_HOME=D:/Tool/Java21 ./mvnw.cmd test 全绿；vitest 全绿；vite build + 同步 target/classes；
  详情页「需求文档」渲染上传 md（SafeMarkdown）。

## 变更记录

- 2026-09-01 立计划；派发后台子代理实施。
