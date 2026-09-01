# AIREVIEW-PLAN-112 需求文档读取改为磁盘快照扫描

状态：✅ 完成

## 背景

PLAN-111 上线后详情页仍「未填写需求描述」：findDocument 经内存 ReviewRegistry 取 attemptNo，
而重启后已完成评审不在注册表 → 404。文档快照实际持久化在磁盘
（reviews/{reviewId}/attempt-{n}/input/snapshot-manifest.json），应只依赖磁盘。

## 方案

- [#1] RequirementSnapshotStore 新增 latestStoredAttempt(ReviewId)：扫描 attempt-* 目录，
  取带快照 manifest 的最大 attempt（注意目录名去前缀后再 parseInt）；IOException/缺失→empty。
- [#2] RequirementQueryService.findDocument 改用 latestStoredAttempt，移除 ReviewRegistry 依赖
  （构造器收敛为两参），404 语义不变。
- [#3] 测试：文档三用例去注册表；新增「多 attempt 取最新且无需注册表」用例。

## 文件清单

- RequirementSnapshotStore.java / RequirementQueryService.java
- RequirementQueryServiceTests.java

## 验证

- JAVA_HOME=D:/Tool/Java21 ./mvnw.cmd test：865 全绿（0 失败 0 错误，30 跳过）。

## 变更记录

- 2026-09-01 立计划；父会话直接实施；期间修复扫描管线把完整目录名误送 parseInt 的缺陷；测试通过后提交。
