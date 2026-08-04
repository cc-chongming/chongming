# REQLIFE 需求全生命周期平台验证记录

> 对应计划：`AIREVIEW-PLAN-021`
> 记录日期：2026-08-04
> 状态：代码收口、覆盖率门禁、测试库 MySQL 5.6/`EXPLAIN` 与浏览器基础路径已完成；**尚未达到功能验收完成**。真实模型评审产出的 Gate、报告持久化和 Gate→开发→完成闭环仍待获授权执行。

## 交付范围

- 需求聚合状态机：`DRAFT → PENDING_REVIEW → REVIEWING → APPROVED / REJECTED / RETURNED → DEVELOPING → DONE`，另支持草稿取消。
- 新接口：`GET /api/dashboard`、`/api/requirements/**`、`GET /api/reviews`、`GET /api/reports`。
- 评审事件单向驱动需求：`PLAN_CREATED` 推进到 `REVIEWING`；`HUMAN_GATE_FINALIZED` 的 PASS/CONDITIONAL、BLOCK、RETURN 分别映射 APPROVED、REJECTED、RETURNED。
- 数据库：V11 新建 `requirement`，并在 `review_request.requirement_id` 写入反向链接；所有结构使用 MySQL 5.6 兼容类型，不引入 JSON 列。
- 平台收口：原子需求—评审绑定、跨评审真实分页、持久化报告投影、列表元数据投影、稳定内存分页排序，以及 V13 的 `review_event(occurred_at, review_id, event_sequence)` 索引均已实现。
- 前端：Hash 路由默认 Dashboard，支持需求创建（保存草稿或创建并启动评审）、详情人工流转、评审列表、报告列表；旧工作台、Live、Scout、报告路径保持存在。

## 已执行证据

| 检查 | 结果 |
|------|------|
| IDEA 全量 Rebuild | `isSuccess=true`；只有项目既有 API 弃用 WARNING，无 ERROR。 |
| `frontend/npm run build` | 成功，产物写入 `src/main/resources/static/review/`。 |
| `frontend/npm test` | 7 个文件、15 个测试通过。 |
| H3/H4/M3 失败先行与定向测试 | 修复前 9 项中 H3、H4、M3 各有一项失败；修复后 9 项通过。覆盖同需求双评审竞争、同时间报告分页、列表 SQL 正文列禁止。 |
| Maven 全量测试与 PLAN-021 门禁 | 2026-08-04 的 `mvn clean verify`：256 项运行，0 failure，0 error，6 项因 Docker/Testcontainers 不可用跳过；JaCoCo 门禁在默认 `verify` 阶段明确通过。 |
| JaCoCo 覆盖率 | 全项目指令覆盖率 68.00%、分支覆盖率 50.19%，不作为 PLAN-021 新增代码的质量结论。默认 `verify` 固定 32 个带 PLAN-021 标记源文件的可执行生产类（含嵌套值类型和读模型），实测指令覆盖率 84.67%，达到并强制 ≥80% 门槛。 |
| 测试库 Flyway | 使用 `application-local.yml` 的测试库（未使用 Docker）启动本地服务；MySQL `5.6.40-log` 成功迁移至 V13。 |
| 历史事件兼容 | 测试库存在 `progress=NULL` 的历史事件；`GET /api/reviews` 与 `GET /api/dashboard` 均返回 200，列表进度默认 0。 |
| H1 跨进程受理幂等 | 首次受理返回 `reused=false, attempt=1`；停止并重启本地服务后，提交同一输入返回相同 `reviewId`、`reused=true, attempt=1`。 |
| H2 实库分页 | 无筛选共 43 条，第 1、2 页各 20 条且无重复；`stage=PENDING` 共 42 条；`hasReport=true` 和 `/api/reports` 均正常返回空分页。 |
| H3 实库并发绑定 | 同一需求并发提交两个 PENDING 评审，得到一条 `200` 与一条 `409 VERSION_CONFLICT`；失败评审随后可绑定另一草稿需求，无残留反向关联。 |
| M2 MySQL 5.6 执行计划 | V13 的 `idx_review_event_recent_activity` 已生效。以会话临时表生成 1,000 条事件后，Dashboard 排序 `EXPLAIN` 使用该索引，`Extra=Using index`，无 filesort；临时表随会话结束自动删除。 |
| 浏览器运行时 | `http://127.0.0.1:18080/review/` 已验证保存草稿为 DRAFT、评审列表第 1/2 页翻页、Dashboard 投影，以及报告页“尚未生成评审报告”空态。 |

## 已关闭的代码收口

1. **REQLIFE-H3**：`RequirementCommandService` 已在需求聚合临界区内完成校验、绑定、迁移和保存；双线程回归确认失败评审无孤儿关联。
2. **REQLIFE-H4**：平台评审列表只读报告元数据，Mapper 契约测试确认 SQL 不含 `report_content`、`markdown_content`；报告详情仍保留正文读取。
3. **REQLIFE-M3**：内存报告分页已追加 `reviewId DESC`，同一 `createdAt` 的两页回归与 MyBatis 契约一致。

以上三项已通过复审；覆盖率门禁、测试库 MySQL/性能和浏览器基础路径亦已留证。后续仅继续本文件的真实 Gate、报告正例和完整生命周期清单。

## 覆盖率门禁

`pom.xml` 已将 JaCoCo `check` 固定在默认 `verify` 阶段，范围覆盖带 `AIREVIEW-PLAN-021` 标记源文件中的 32 个可执行生产类（含嵌套值类型和读模型），并强制指令覆盖率不低于 80%。当前实测为 84.67%；其中 MyBatis 需求仓储、平台投影仓储和报告仓储使用 Mapper 替身覆盖成功与失败路径，报告替身还验证“每评审仅最大版本”与同时间稳定分页。Docker/Testcontainers 跳过没有计入覆盖率证据。后续代码变更必须重跑：`./mvnw.cmd verify`。

## 接续验证清单

如当前工作树继续发生变更，先在可访问本机 Maven 缓存/中央仓库的终端重跑：

```powershell
$env:JAVA_HOME = 'C:\Dev\Java\jdk-21.0.10'
.\mvnw.cmd -Dtest=RequirementLifecycleStateMachineTests,RequirementTests,RequirementCommandServiceTests,RequirementQueryServiceTests,RequirementLifecycleServiceTests,DashboardQueryServiceTests,ReviewListQueryServiceTests,RequirementControllerTests test
.\mvnw.cmd verify
.\mvnw.cmd -Dtest=ReviewPersistenceMigrationIntegrationTests test
cd frontend
npm test
npm run build
```

当前仅剩真实 Gate 与报告正例。测试库已可直接使用，Docker 不是前置条件；在获授权启用模型网关后，进入 `/review/#/dashboard`，验收以下链路：

1. 生成至少一份真实评审报告，重启服务后验证报告详情、`GET /api/reports` 和 `GET /api/reviews?hasReport=true` 的正例；补齐 H4/M1/M3 的实库语义。
2. 在新需求启动评审后确认 `PENDING_REVIEW → REVIEWING`，并对 Gate 的 PASS、BLOCK、RETURN 分别确认 `APPROVED`、`REJECTED`、`RETURNED`。
3. 对 APPROVED 需求执行“开始开发”和“标记完成”；Dashboard、需求列表、评审/报告列表随之刷新。

## 已知边界

- 新版平台不会替换 Live 对话界面；需求详情通过链接跳入既有 `/reviews/{reviewId}/live`。
- Scout 仅展示现有评审摘要中可用的降级/发现摘要，不伪造模型推理或未产生的 Scout 事实。
- Testcontainers 标记 `disabledWithoutDocker=true`；本轮不使用 Docker，测试库的直接运行时验证不应与被跳过的 Testcontainers 测试混同。
- 评审—需求链接、跨评审分页、持久化报告投影、列表元数据投影和事件排序索引均已具备代码与测试库证据；报告正例和完整 Gate 生命周期缺失时，仍不可将平台列表视为已完成的生产能力。

## 2026-08-04 模型网关实测

在不使用 Docker 的前提下，以 `application-local.yml` 指向的测试库启动服务，并通过隔离 HTTP 请求创建需求和评审。真实模型流程确认：四个核心角色均可完成初审，Director 能通过 `list_persisted_claims` 读取 18 条持久化 Claim，评审推进到 `CONFLICT_DETECTION`。本轮据此修复了启动时序、持久化辩题标识可见性、无冲突转换、AI Gate 等待人工确认，以及角色/Director 未调用阶段工具后的受限收尾。

最终 Gate 正例未取得：最后一次调用的模型网关返回了非 JSON 响应，服务记录 `ModelGatewayException` 并将隔离评审标为 `FAILED`。这证明失败路径生效，但不构成报告正例或需求生命周期正例；`GET /api/reports`、`GET /api/reviews?hasReport=true`、报告重启回读、PASS→DEVELOPING→DONE 与报告页面正例仍保持待验收。
