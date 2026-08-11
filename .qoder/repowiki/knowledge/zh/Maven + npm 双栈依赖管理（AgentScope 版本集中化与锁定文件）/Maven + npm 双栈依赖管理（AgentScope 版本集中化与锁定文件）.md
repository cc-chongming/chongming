---
kind: dependency_management
name: Maven + npm 双栈依赖管理（AgentScope 版本集中化与锁定文件）
slug: dependency_management
category: dependency_management
scope:
    - '**'
---

## 1. 使用的系统/工具

- **后端（Java）**：使用 Maven，以 `spring-boot-starter-parent:4.0.7` 作为父 POM，通过 `<properties>` 中的 `agentscope.version=2.0.0` 集中声明 AgentScope 全家桶版本；所有 `agentscope-*` 依赖均引用该属性，避免散落的版本号。
- **前端（Vue 3 + Vite）**：使用 npm（由 `frontend/package.json` 和 `frontend/package-lock.json` 体现），依赖通过 `^` 范围声明（如 `vue: ^3.5.0`、`vite: ^8.1.5`），并由 `package-lock.json` 锁定实际解析版本。
- **构建期约束**：Maven 启用 `maven-enforcer-plugin`，强制 Java 版本 `[21,22)`、要求依赖为 release 版本（`requireReleaseDeps`）、并检查依赖收敛（`dependencyConvergence`）。
- **运行时配置**：应用配置集中在 `src/main/resources/application.yml`，所有外部凭据（数据库、邮件、模型网关、AgentScope 状态库等）通过 `${ENV_VAR:default}` 占位符注入，不入库不入仓。

## 2. 关键文件

- `pom.xml`：后端依赖清单、版本属性、插件与覆盖率门禁。
- `frontend/package.json`：前端依赖与脚本入口。
- `frontend/package-lock.json`：npm 依赖树锁定文件。
- `src/main/resources/application.yml`：运行时依赖的外部化配置（DB、SSE、通知、模型网关、AgentScope 持久化表名等）。
- `docs/AIREVIEW-PLAN-002-工程基线与AgentScope正式版验证.md`：记录了“修改 `pom.xml` 集中管理 AgentScope 2.0.0、MySQL 扩展、Driver、Validation、Actuator、Testcontainers、JaCoCo”的交付约定。

## 3. 架构与约定

### 后端（Maven）
- **版本集中化**：`agentscope.version` 在 `<properties>` 中统一声明，`agentscope-harness`、`agentscope-extensions-model-openai`、`agentscope-extensions-mysql`、`agentscope-extensions-agui` 全部通过 `${agentscope.version}` 引用，确保 AgentScope 各组件版本一致。
- **Spring Boot 继承**：通过 `spring-boot-starter-parent` 统一管理 Spring 生态及传递依赖版本；MyBatis Starter 显式声明 `4.0.1` 覆盖父 POM 默认版本。
- **测试依赖隔离**：`testcontainers`、`mybatis-spring-boot-starter-test`、`spring-boot-starter-webmvc-test` 均标记 `<scope>test</scope>`，不参与生产包。
- **构建期强制规则**：
  - `requireJavaVersion=[21,22)`：禁止在非 Java 21 环境构建。
  - `requireReleaseDeps`：禁止引入 SNAPSHOT 依赖。
  - `dependencyConvergence`：同一依赖不得出现多个不同版本。
- **覆盖率门禁**：`jacoco-maven-plugin` 在 `verify` 阶段对 AIREVIEW-PLAN-021 标记的生产类集合执行 `check`，指令覆盖率最低 80%；未达标则构建失败。
- **Lombok 排除打包**：`spring-boot-maven-plugin` 的 `excludes` 排除 Lombok，避免将注解处理器产物打入可执行 JAR。

### 前端（npm/Vite）
- 依赖通过 `dependencies`（`vue`、`vue-router`、`@ag-ui/core`）与 `devDependencies`（`vite`、`vitest`、`@playwright/test`、`@vitejs/plugin-vue`）分离。
- 版本使用 `^` 语义化范围，实际安装版本由 `package-lock.json` 锁定，保证团队与 CI 复现一致。
- 无私有 registry 或 `.npmrc` 配置，依赖来源为默认 npm registry。

### 运行时依赖外部化
- `application.yml` 中所有敏感/环境相关值（DB URL、用户名、密码、AgentScope 状态库名、锁前缀、SSE 超时、通知通道、模型网关 base-url 与 API key、日志路径等）均以 `${ENV_VAR:default}` 形式声明，部署时通过环境变量覆盖，配置文件本身不包含真实凭据。

## 4. 约定与约束

| 领域 | 约定/约束 | 依据 |
|---|---|---|
| Java 版本 | 构建必须使用 Java 21（`[21,22)`） | `pom.xml` 中 `maven-enforcer-plugin` 的 `requireJavaVersion` |
| 依赖发布态 | 禁止引入 SNAPSHOT 依赖 | `pom.xml` 中 `requireReleaseDeps` 规则 |
| 依赖版本一致性 | 同一依赖不得出现多个版本 | `pom.xml` 中 `dependencyConvergence` 规则 |
| AgentScope 版本 | 所有 `io.agentscope:*` 组件共享单一属性 `agentscope.version=2.0.0` | `pom.xml` `<properties>` 与依赖声明 |
| 测试依赖 | 仅用于测试的依赖必须声明 `<scope>test</scope>` | `pom.xml` 中 Testcontainers / MyBatis test starter 的 scope |
| 覆盖率 | AIREVIEW-PLAN-021 标记的生产类指令覆盖率 ≥ 80%，否则 `mvn verify` 失败 | `pom.xml` 中 JaCoCo `check-plan-021-production-coverage` 执行 |
| 敏感配置 | DB、邮件、模型网关密钥等不得写入仓库，通过环境变量注入 | `application.yml` 中 `${REVIEW_*}` 占位符与注释说明 |
| 前端锁定 | 前端依赖通过 `package-lock.json` 锁定实际解析版本 | `frontend/package-lock.json` 存在且随仓库提交 |
| 构建脚本 | 后端通过 Maven Wrapper（`./mvnw.cmd`）触发构建与覆盖率校验 | `docs/验证记录/RequirementPlatformReport.md` 中命令引用 |

## 5. 未发现的模式

- 未发现 vendoring（如 `vendor/` 目录或 Go module vendor）策略。
- 未发现私有 Maven/NPM registry 配置（无 `settings.xml`、`.npmrc`、`~/.gradle/` 等）。
- 未发现 Gradle 构建文件；项目仅使用 Maven 与 npm。