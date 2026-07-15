# Repository Guidelines

## Project Structure & Module Organization

This is a single-module Java 21 Spring Boot application built with Maven. Production code lives under `src/main/java/ai/cc/chongming`; `ChongmingApplication` is the web application entry point and `FirstAgent` is the AgentScope example. Runtime configuration belongs in `src/main/resources/application.yml`. Tests mirror the production package under `src/test/java`. Product and design material is kept in `docs/`, while AgentScope runtime state is written beneath `.agentscope/workspace/`. Treat `target/` as generated output and do not commit it.

## Build, Test, and Development Commands

Use the checked-in Maven wrapper so builds use a consistent Maven version:

- `./mvnw.cmd clean verify` — compile, run all tests, and package the application.
- `./mvnw.cmd test` — run the JUnit test suite only.
- `./mvnw.cmd spring-boot:run` — start the Spring Boot application locally.
- `./mvnw.cmd package -DskipTests` — create a local artifact when tests have already passed.

On macOS or Linux, replace `mvnw.cmd` with `./mvnw`.

## Coding Style & Naming Conventions

Use four-space indentation, UTF-8, and standard Java naming: `PascalCase` types, `camelCase` methods and variables, and lowercase package names. Keep classes in the `ai.cc.chongming` package tree and favor constructor injection for Spring components. Avoid database queries inside loops; batch-load or join data instead. Never hard-code credentials—`FirstAgent` expects `DASHSCOPE_API_KEY` from the environment.

## Testing Guidelines

Tests use JUnit 5 and Spring Boot Test. Name test classes `*Tests` and test methods after observable behavior, such as `contextLoads` or `createsAgentWithConfiguredModel`. Mirror source packages in `src/test/java`. Run `./mvnw.cmd test` before every commit and `clean verify` before opening a pull request. No coverage threshold is currently enforced; new behavior should still include focused tests.

## Commit & Pull Request Guidelines

History follows Conventional Commits, for example `feat(project): 初始化 AgentScope 项目结构`. Use `type(scope): summary` with concise imperative summaries (`feat`, `fix`, `test`, `docs`, or `refactor`). Pull requests should explain the change and verification performed, link related issues or requirements, and call out configuration changes. Include screenshots only for visible UI changes. Keep generated files, secrets, and local AgentScope state out of commits.

## Agent-Specific Instructions

Record unexpected failures and reusable discoveries in `.learnings/`. Prefer IDEA MCP for Java search, edits, builds, and diagnostics when it is available.
