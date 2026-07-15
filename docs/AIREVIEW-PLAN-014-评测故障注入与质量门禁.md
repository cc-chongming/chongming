# 评测、故障注入与质量门禁计划

> **状态**: ⏳ 待实施
> **创建日期**: 2026-07-14
> **目标**: 用可复现自动化测试、性能基线、故障矩阵和单/多 Agent 对照证明系统可靠且多 Agent 确有收益。
> **前置计划**: PLAN-005 至 PLAN-013 的可运行链路

## 0. 背景与边界

本计划验证已实现能力，不用真实模型替代确定性单元/集成测试。仓库小/中/大只作为测试负载，不形成业务限制。评测必须同时展示多
Agent
收益和代价，不能只挑成功样本。现场缓存兜底尚未决定，不作为通过条件。

## 1. 分段方案

### 1.1 测试分层与夹具 ⏳

- Maven profile：unit、integration、compatibility、e2e、evaluation。
- 固定 Clock/ID、MockModel、Mock MCP、临时 workspace、MySQL Testcontainers、SSE 客户端。
- 夹具记录 schemaVersion、commitId、AgentScopeVersion、promptVersion、datasetVersion。

### 1.2 覆盖率与静态质量门禁 ⏳

- 核心领域/Guard 行覆盖率 ≥80%，分支覆盖率 ≥70%；Gate/Guard 分支必须 100% 场景覆盖。
- IDEA problems 无 ERROR；启用可用的格式、静态检查和依赖收敛。
- 全套测试连续两次通过，不允许跳过 compatibility/security/recovery。

### 1.3 性能与容量基线 ⏳

- 小/中/大仓库测试快照、检索、Evidence、冲突检测和事件回放。
- 并发 1/3/5 场 review，SSE 1/10/50 连接，事件 1 千/1 万条。
- 连续 20 场检查线程、连接、内存和 workspace 泄漏。
- SQL 数量断言防 N+1；模型等待期间无数据库长事务。

### 1.4 故障注入矩阵 ⏳

| 故障               | 预期                     |
|------------------|------------------------|
| 模型超时/429/断连      | 最多两次退避，核心失败转人工         |
| 非法 JSON/工具参数     | 修复一次，仍失败则审计失败          |
| DebateTools 超时重试 | 不重复 Turn/Event         |
| DB 提交前/后断开       | 状态可判断，恢复不重复            |
| 应用重启             | 从已提交阶段继续，新旧 attempt 隔离 |
| SSE 断开           | sequence 回放后继续实时流      |
| 通知失败             | Outbox 重试，不回滚 Gate     |
| 快照漂移/证据损坏        | 标记漂移或阻止报告/Gate         |
| 用户取消             | CANCELLED 且历史保留        |

### 1.5 评测数据集 ⏳

- 准备 6 组不可变 Markdown + 仓库快照；4 组开发、2 组盲测。
- 人工标注问题、严重度、证据位置、预期冲突和可接受 Gate。
- 数据集只包含可授权演示代码，不含公司密钥或敏感数据。

### 1.6 单 Agent 对照 ⏳

- 单 Agent 与多 Agent 使用同输入、模型、温度、工具、上下文和预算。
- 每样本每方案至少运行 3 次，总计至少 36 次。
- 失败运行不得删除，按预定义规则纳入统计。

### 1.7 指标与统计 ⏳

- 证据有效率、已知问题覆盖率、人工采纳率、P0/P1 误报率、重复 Claim 率。
- 有效冲突率、无效争论率、立场变化率、收敛轮次。
- 耗时、Token、工具成功率、事件完整率、恢复成功率。
- 输出逐样本差异、中位数和 P50/P95/P99，不只报告平均值。

### 1.8 验证证据归档 ⏳

- `target/` 保存临时原始报告；需提交的摘要、矩阵、配置哈希放 `docs/验证记录/{PLAN-ID}/`。
- 统一记录：计划段、验收条件、测试类/执行方式、原始报告、结论、负责人、日期。
- 确定性指标如事件完整率、引用完整率目标为 100%。

## 2. 文件清单

### 2.1 新建

| 文件                                                                                    | 计划段       | 状态 |
|---------------------------------------------------------------------------------------|-----------|----|
| `src/test/java/ai/cc/chongming/review/support/MockModelFixture.java`                  | #1.1      | ⏳  |
| `src/test/java/ai/cc/chongming/review/support/ReviewFixtureFactory.java`              | #1.1      | ⏳  |
| `src/test/java/ai/cc/chongming/review/e2e/ReviewGoldenPathE2ETests.java`               | #1.1-1.2  | ⏳  |
| `src/test/java/ai/cc/chongming/review/performance/ReviewPerformanceBaselineTests.java` | #1.3      | ⏳  |
| `src/test/java/ai/cc/chongming/review/fault/ReviewFaultInjectionTests.java`            | #1.4      | ⏳  |
| `src/test/resources/evaluation/dataset-manifest.yml`                                  | #1.5      | ⏳  |
| `src/test/resources/evaluation/cases/`                                                | #1.5      | ⏳  |
| `src/test/java/ai/cc/chongming/review/evaluation/ReviewEvaluationRunner.java`         | #1.6-1.7  | ⏳  |
| `src/test/java/ai/cc/chongming/review/evaluation/EvaluationMetrics.java`              | #1.7      | ⏳  |
| `docs/验证记录/AIREVIEW-PLAN-014/评测协议.md`                                                 | #1.5-1.8  | ⏳  |
| `docs/验证记录/AIREVIEW-PLAN-014/故障矩阵.md`                                                 | #1.4、#1.8 | ⏳  |
| `docs/验证记录/AIREVIEW-PLAN-014/性能基线.md`                                                 | #1.3、#1.8 | ⏳  |

### 2.2 修改

| 文件        | 计划段      | 状态 |
|-----------|----------|----|
| `pom.xml` | #1.1-1.2 | ⏳  |
| 各计划测试类    | #1.2     | ⏳  |

## 3. 实施顺序

1. **步骤 1**：功能开发期同步建立 Mock/Fixture 和分层 profile。
2. **步骤 2**：功能冻结后跑覆盖率、静态质量和完整 E2E。
3. **步骤 3**：执行性能/容量和 20 场泄漏测试。
4. **步骤 4**：逐项执行故障矩阵并修复。
5. **步骤 5**：冻结数据集，完成至少 36 次单/多 Agent 对照。
6. **步骤 6**：归档原始证据和结论，给 PLAN-015 使用。

## 4. 验证与退出标准

- 覆盖率、分支、静态检查和全套测试达到 #1.2 门禁。
- 无连接/线程/workspace 泄漏；性能回归超过 20% 必须解释。
- 故障矩阵每项都有注入方式、期望状态、恢复动作和审计证据。
- 恢复后无重复 Claim/Turn/Event；任何失败不静默成为成功。
- 6 组数据、至少 36 次运行、原始结果、冻结配置和计算方法可复现。

## 5. 风险与应对

| 风险          | 应对                            |
|-------------|-------------------------------|
| 真实模型评测成本/波动 | 功能测试用 Mock；评测冻结 profile 并重复三次 |
| 开发集过拟合      | 保留 2 组盲测，结果冻结后再揭示标签           |
| 性能测试机器差异    | 报告机器/Java/MySQL 配置，以相对回归为主    |

## 6. 变更记录

| 日期         | 变更                               |
|------------|----------------------------------|
| 2026-07-14 | 创建测试分层、覆盖率、性能、故障注入、6 组评测和证据归档计划。 |
