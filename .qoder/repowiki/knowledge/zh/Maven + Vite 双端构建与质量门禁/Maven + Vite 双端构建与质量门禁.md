---
kind: build_system
name: Maven + Vite 双端构建与质量门禁
slug: build_system
category: build_system
scope:
    - '**'
---

## 1. 使用的构建系统

本项目采用 **前后端分离、双构建管线** 的体系：
- 后端为 Spring Boot 4.0.7（Java 21）应用，使用 **Apache Maven 3.9.16** 作为唯一构建工具，通过 `.mvn/wrapper/maven-wrapper.properties` 锁定版本，仓库根目录提供 `mvnw`/`mvnw.cmd` 包装器。
- 前端为 Vue 3 + Vite 8 单页应用，使用 `npm`/`pnpm`（由 `package-lock.json` 锁定依赖），通过 `vite.config.js` 配置构建输出。

项目没有 Dockerfile、docker-compose、GitHub Actions/Jenkinsfile 等 CI/CD 配置文件；也没有 Makefile 或 shell 脚本统一编排多端构建。构建与测试命令以文档和 README 约定为主。

## 2. 关键文件

| 文件 | 作用 |
|---|---|
| `pom.xml` | 后端依赖管理、插件编排、JaCoCo 覆盖率门禁、maven-enforcer 基线校验、Spring Boot 打包 |
| `.mvn/wrapper/maven-wrapper.properties` | 强制 Maven 3.9.16 分发地址，保证构建可重现 |
| `frontend/package.json` | 前端脚本入口：`dev`、`build`、`preview`、`test`、`test:e2e` |
| `frontend/vite.config.js` | Vite 构建目标为 `../src/main/resources/static/review`，使前端产物直接嵌入 Spring Boot 静态资源目录 |
| `frontend/playwright.config.js` | E2E 测试配置（Playwright） |
| `src/main/resources/application.yml` / `application-local.yml` | Spring Boot 运行时配置（含 Flyway、Actuator、AgentScope 等） |
| `src/main/resources/db/migration/V*.sql` | Flyway 数据库迁移脚本（V1–V19） |
| `AGENTS.md`、`README.md` | 约定本地开发命令（`./mvnw clean verify`、`./mvnw spring-boot:run` 等） |

## 3. 架构与约定

### 后端构建流程（Maven 生命周期）

1. **编译阶段**：`maven-compiler-plugin` 启用 Lombok 注解处理器（compile 与 test-compile 两个 execution）。
2. **测试阶段**：默认执行 JUnit 5 单元测试；`jacoco-maven-plugin` 在 `prepare-agent` 收集覆盖率，在 `test` 阶段生成报告。
3. **验证阶段（verify）**：
   - `maven-enforcer-plugin` 强制执行：
     - Java 版本 `[21,22)`（即必须使用 Java 21）。
     - `requireReleaseDeps`：禁止引入 SNAPSHOT 依赖。
     - `dependencyConvergence`：确保依赖版本收敛。
   - `jacoco-maven-plugin` 的 `check` 目标运行，对 `BUNDLE` 级别指定包路径集合强制指令覆盖率 ≥ 80%（见 pom.xml 中 `<includes>` 列表，覆盖 `ai/cc/chongming/review/api/*Controller`、`application/*Service`、`domain/*`、`infrastructure/persistence/repository/*` 等核心类）。
   - `spring-boot-maven-plugin` 将应用打包为可执行 JAR，并排除 Lombok。
4. **发布制品**：`target/chongming-0.0.1-SNAPSHOT.jar`（版本号来自 `pom.xml` 的 `version` 字段）。

### 前端构建流程（Vite）

- `npm run build` → `vite build` → 输出到 `src/main/resources/static/review`，并清空旧产物（`emptyOutDir: true`）、关闭 sourcemap。
- 该输出路径是 Spring Boot 启动后自动托管的静态资源目录，因此后端只需单独部署 JAR 即可同时提供 API 与前端页面。
- 开发时 `npm run dev` 通过 Vite proxy 将 `/api` 转发到 `http://127.0.0.1:8080`（可通过 `VITE_API_TARGET` 环境变量覆盖）。
- 单元测试：`vitest run --exclude 'tests/**'`，仅运行 `src/**/*.test.js`。
- E2E 测试：`playwright test`，位于 `frontend/tests/`。

### 数据库迁移

- 使用 Flyway（`flyway-core` + `flyway-mysql`），迁移脚本按 `src/main/resources/db/migration/V{n}__描述.sql` 命名，当前已演进至 V19。
- 集成测试通过 Testcontainers 拉起 MySQL 容器执行迁移（无 Docker 时自动跳过）。

## 4. 约定与约束

- **Java 版本约束**：`maven-enforcer-plugin` 要求 Java 21，低于 21 或高于等于 22 会直接导致构建失败。
- **禁止 SNAPSHOT 依赖**：`requireReleaseDeps` 规则阻止引入任何 `-SNAPSHOT` 版本的第三方依赖。
- **依赖版本收敛**：`dependencyConvergence` 要求同一依赖在不同模块/传递依赖中版本一致。
- **覆盖率门禁**：JaCoCo 的 `check` 绑定在 `verify` 阶段，针对 pom.xml 中显式列出的生产类集合强制指令覆盖率不低于 80%，不达标则 `mvn verify` 失败。
- **前端产物位置固定**：Vite 构建目标被硬编码为 `../src/main/resources/static/review`，这是后端静态资源目录，违反此路径会导致 Spring Boot 无法加载前端页面。
- **API 代理约定**：开发环境通过 `VITE_API_TARGET` 环境变量控制后端地址，默认 `http://127.0.0.1:8080`。
- **测试环境约定**：Testcontainers 相关测试在无 Docker daemon 的环境中应标记为 skip，不得伪装成通过（见 `AGENTS.md` 与多处验证记录中的明确要求）。
- **本地开发命令约定**：`./mvnw clean verify` 用于 PR 前全量检查；`./mvnw spring-boot:run` 启动后端；`npm run build` 构建前端；`npm run test:e2e` 运行 Playwright E2E。
- **无全局 CI/CD 配置**：仓库未包含 GitHub Actions、Jenkinsfile、Dockerfile 等自动化流水线定义，持续集成需外部补充。

## 5. 置信度

**high** — 构建系统清晰且有多处强制约束（enforcer、JaCoCo check、Maven wrapper 版本锁定、Vite 输出路径），证据来自 `pom.xml`、`frontend/package.json`、`frontend/vite.config.js`、`.mvn/wrapper/maven-wrapper.properties` 以及多处实施计划与验证记录的交叉引用。