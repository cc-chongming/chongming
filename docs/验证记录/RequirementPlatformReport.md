# REQLIFE 需求全生命周期平台验证记录

> 对应计划：`AIREVIEW-PLAN-021`
> 记录日期：2026-08-01
> 状态：代码收口、当前工作树回归与新增代码覆盖率门禁已完成；**尚未达到功能验收完成**。MySQL 5.6、`EXPLAIN` 与浏览器运行时证据必须补齐。

## 交付范围

- 需求聚合状态机：`DRAFT → PENDING_REVIEW → REVIEWING → APPROVED / REJECTED / RETURNED → DEVELOPING → DONE`，另支持草稿取消。
- 新接口：`GET /api/dashboard`、`/api/requirements/**`、`GET /api/reviews`、`GET /api/reports`。
- 评审事件单向驱动需求：`PLAN_CREATED` 推进到 `REVIEWING`；`HUMAN_GATE_FINALIZED` 的 PASS/CONDITIONAL、BLOCK、RETURN 分别映射 APPROVED、REJECTED、RETURNED。
- 数据库：V11 新建 `requirement`，并在 `review_request.requirement_id` 写入反向链接；所有结构使用 MySQL 5.6 兼容类型，不引入 JSON 列。
- 平台收口：原子需求—评审绑定、跨评审真实分页、持久化报告投影、列表元数据投影、稳定内存分页排序，以及 V12 的 `review_event(occurred_at)` 索引均已实现。
- 前端：Hash 路由默认 Dashboard，支持需求创建（保存草稿或创建并启动评审）、详情人工流转、评审列表、报告列表；旧工作台、Live、Scout、报告路径保持存在。

## 已执行证据

| 检查 | 结果 |
|------|------|
| IDEA 全量 Rebuild | `isSuccess=true`；只有项目既有 API 弃用 WARNING，无 ERROR。 |
| `frontend/npm run build` | 成功，产物写入 `src/main/resources/static/review/`。 |
| `frontend/npm test` | 7 个文件、15 个测试通过。 |
| H3/H4/M3 失败先行与定向测试 | 修复前 9 项中 H3、H4、M3 各有一项失败；修复后 9 项通过。覆盖同需求双评审竞争、同时间报告分页、列表 SQL 正文列禁止。 |
| Maven 全量测试与 PLAN-021 门禁 | 默认 `mvn verify`：253 项通过，0 failure，0 error，8 项因 Docker/Testcontainers 不可用跳过；JaCoCo 门禁在默认 `verify` 阶段明确通过。 |
| JaCoCo 覆盖率 | 全项目指令覆盖率 68.00%、分支覆盖率 50.19%，不作为 PLAN-021 新增代码的质量结论。默认 `verify` 固定 32 个带 PLAN-021 标记源文件的可执行生产类（含嵌套值类型和读模型），实测指令覆盖率 84.67%，达到并强制 ≥80% 门槛。 |
| 浏览器入口检查 | `http://127.0.0.1:8080/review/` 返回 `ERR_CONNECTION_REFUSED`。本次未启动或重启服务，因此未执行浏览器验收。 |

## 已关闭的代码收口

1. **REQLIFE-H3**：`RequirementCommandService` 已在需求聚合临界区内完成校验、绑定、迁移和保存；双线程回归确认失败评审无孤儿关联。
2. **REQLIFE-H4**：平台评审列表只读报告元数据，Mapper 契约测试确认 SQL 不含 `report_content`、`markdown_content`；报告详情仍保留正文读取。
3. **REQLIFE-M3**：内存报告分页已追加 `reviewId DESC`，同一 `createdAt` 的两页回归与 MyBatis 契约一致。

以上三项已通过复审；覆盖率门禁亦已闭环。后续仅继续本文件的 MySQL、性能和浏览器运行时清单。

## 覆盖率门禁

`pom.xml` 已将 JaCoCo `check` 固定在默认 `verify` 阶段，范围覆盖带 `AIREVIEW-PLAN-021` 标记源文件中的 32 个可执行生产类（含嵌套值类型和读模型），并强制指令覆盖率不低于 80%。当前实测为 84.67%；其中 MyBatis 需求仓储、平台投影仓储和报告仓储使用 Mapper 替身覆盖成功与失败路径，报告替身还验证“每评审仅最大版本”与同时间稳定分页。Docker/Testcontainers 跳过没有计入覆盖率证据。后续代码变更必须重跑：`./mvnw.cmd verify`。

## 接续验证清单

如当前工作树继续发生变更，先在可访问本机 Maven 缓存/中央仓库的终端重跑：

```powershell
$env:JAVA_HOME = 'D:\Tool\Java21'
.\mvnw.cmd -Dtest=RequirementLifecycleStateMachineTests,RequirementTests,RequirementCommandServiceTests,RequirementQueryServiceTests,RequirementLifecycleServiceTests,DashboardQueryServiceTests,ReviewListQueryServiceTests,RequirementControllerTests test
.\mvnw.cmd verify
.\mvnw.cmd -Dtest=ReviewPersistenceMigrationIntegrationTests test
cd frontend
npm test
npm run build
```

当前回归无失败；当前浏览器入口尚未监听。后续在具备 MySQL/Docker 且本机服务已启动后，进入 `/review/#/dashboard`，验收以下链路：

1. 在 local MySQL 5.6 执行 Flyway，确认 `requirement.description_md` 为 `MEDIUMTEXT`，`review_request.requirement_id` 存在并可写入，且 V12 创建 `review_event.occurred_at` 索引。
2. 重复相同 Markdown 受理返回 `reused=true` 时，页面必须保留新建草稿且不得启动或改写旧评审；并发、已启动、已绑定的评审都不得覆盖已有 `requirement_id`。
3. 构造 501 条以上评审，分别验证 stage、hasReport 与无筛选的 `items`、`page`、`total`；在持久化模式生成报告并重启后，验证报告详情、`GET /api/reports`、`GET /api/reviews?hasReport=true`。
4. 使用 1k+ 事件样本运行 Dashboard 近期活动 SQL 的 `EXPLAIN`，将索引使用和 filesort 结果记录在本文件。
5. 新建需求并选择“保存草稿”，确认需求为 `DRAFT`。
6. 新建需求并上传 `.md` 后启动评审，确认需求先为 `PENDING_REVIEW`，Director 计划创建后变为 `REVIEWING`。
7. 人工最终 Gate 选择 PASS、BLOCK、RETURN，确认需求依次为 `APPROVED`、`REJECTED`、`RETURNED`。
8. 对 APPROVED 需求执行“开始开发”和“标记完成”；Dashboard、需求列表、评审/报告列表随之刷新。

## 已知边界

- 新版平台不会替换 Live 对话界面；需求详情通过链接跳入既有 `/reviews/{reviewId}/live`。
- Scout 仅展示现有评审摘要中可用的降级/发现摘要，不伪造模型推理或未产生的 Scout 事实。
- Testcontainers 标记 `disabledWithoutDocker=true`；无 Docker 时应显示跳过，不能视为 MySQL 覆盖已通过。
- 评审—需求链接、跨评审分页、持久化报告投影、列表元数据投影和事件排序索引均已具备代码与当前工作树回归证据；实际 MySQL 5.6/浏览器运行时证据仍以 `AIREVIEW-PLAN-021` 的“实施交接清单”为准，当前不可将平台列表视为已完成的生产能力。
