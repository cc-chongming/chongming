# Chongming Repository Guide

## Project Overview

Chongming is a single-module Java 21 Spring Boot application built with Maven. It contains a Vue 3/Vite review workbench that is built into Spring Boot static resources. The project implements the AIREVIEW workflow with AgentScope-compatible runtime behavior.

## Repository Layout

- `src/main/java/ai/cc/chongming/`: production Java code; `ChongmingApplication` is the application entry point.
- `src/test/java/ai/cc/chongming/`: JUnit 5 tests mirroring production packages.
- `src/main/resources/application.yml`: runtime configuration. Keep secrets and gateway credentials in environment variables.
- `src/main/resources/static/review/`: committed production bundle for the review workbench.
- `frontend/`: Vue 3/Vite source, unit tests, and Playwright E2E tests.
- `docs/`: product requirements, architecture, implementation plans, and integration contracts.
- `.agentscope/workspace/`: local AgentScope runtime state; do not commit.
- `.learnings/`: reusable discoveries and unexpected failure records.
- `target/`, `frontend/node_modules/`, and frontend test artifacts: generated local output; do not commit.

## Requirement and Design Authority

- Use the MVP role requirements document as the authority for role-facing behavior.
- Use the technical proposal as the authority for the review state machine.
- When other documents conflict, follow the technical proposal and update the affected Markdown documents in the same change.
- Preserve the explicit boundary notes in each `AIREVIEW-PLAN-*.md`; do not mark a dependency on real MySQL, Docker, MCP, or external platforms complete without an executable integration.

## Build, Test, and Run

On Windows, use the checked-in Maven wrapper:

- `./mvnw.cmd test`: run the Java test suite.
- `./mvnw.cmd clean verify`: compile, test, and package before a pull request.
- `./mvnw.cmd spring-boot:run`: run the backend locally.
- `./mvnw.cmd package -DskipTests`: package only after tests have passed.

For the frontend, run commands from `frontend/`:

- `npm test`: run Vitest tests.
- `npx playwright test`: run the workbench E2E tests.
- `npm run build`: build the Vite application into `src/main/resources/static/review/`.

On macOS or Linux, replace `mvnw.cmd` with `./mvnw`.

## Coding and Documentation Rules

- Use UTF-8 and four-space indentation for Java. Follow standard Java naming: `PascalCase` types, `camelCase` fields and methods, and lowercase package names.
- Keep production code inside `ai.cc.chongming`; prefer constructor injection for Spring components.
- Avoid database queries in loops. Batch-load data or use joins instead.
- Never hard-code credentials, tokens, or gateway configuration.
- Add `@author wangli` to every newly created Java source file, and include the applicable `[AIREVIEW-PLAN-xxx]` source marker when it exists.
- Keep frontend API, Store, page, test, and production-bundle changes consistent. If `npm run build` changes review asset hashes, commit the new assets, remove obsolete hashes, and update `static/review/index.html` together.
- Update the corresponding PLAN document whenever implementation status or deferred scope changes.

## Testing Expectations

- Name Java test classes `*Tests` and test methods after observable behavior.
- Add focused tests for new behavior. For frontend command or state changes, cover the request contract and the user-facing path where practical.
- Run the smallest relevant suite while developing; run `./mvnw.cmd test` before committing backend changes.
- Run `./mvnw.cmd clean verify` before opening a pull request. Run frontend unit, E2E, and build commands when frontend sources or static resources change.
- Clearly record environment-caused skips, such as unavailable Docker/Testcontainers, rather than treating them as passing integration coverage.

## Git and Collaboration

- Inspect `git status --short` before editing. Preserve unrelated user changes and do not reset, checkout, or delete them without explicit approval.
- Use Conventional Commits: `type(scope): concise imperative summary`, such as `feat(review-ui): 补齐评审生命周期操作`.
- Keep generated Java build output, local runtime state, secrets, and frontend dependencies out of commits. The built review bundle under `src/main/resources/static/review/` is the deliberate exception and must be committed with its matching frontend change.
- Pull requests should state the requirement or plan addressed, key behavior changes, verification performed, configuration changes, and any deferred external dependency.
- Prefer IDEA MCP for Java search, editing, builds, and diagnostics when it is available. Record reusable failures and discoveries in `.learnings/`.
