# Errors

## [ERR-20260810-004] npm dependency probe returned non-zero

**Logged**: 2026-08-10T00:00:00+08:00
**Priority**: low
**Status**: resolved
**Area**: frontend

### Summary
`npm ls marked --depth=0` returned exit code 1 because the optional Markdown renderer is not installed.

### Error
```
chongming-review-workbench@0.1.0 E:\aicode\chongming\frontend
`-- (empty)
```

### Context
- The command was a dependency capability probe before implementing safe Markdown rendering for PLAN-023.
- The empty tree is an expected negative result, not an npm installation failure.

### Suggested Fix
When probing optional dependencies, interpret npm's exit code 1 together with the dependency tree instead of treating it as a build failure.

### Metadata
- Reproducible: yes
- Related Files: frontend/package.json

### Resolution
- **Resolved**: 2026-08-10T00:00:00+08:00
- **Commit/PR**: n/a
- **Notes**: Confirmed that `marked` is absent; implementation must add a reviewed dependency or provide an internal safe renderer.

---

## [ERR-20260810-005] playwright-chromium-executable-missing

**Logged**: 2026-08-10T16:00:00+08:00
**Priority**: medium
**Status**: resolved
**Area**: tests

### Summary
PLAN-023 前端聚焦 E2E 未能启动，因为本机 Playwright 对应版本的 Chromium Headless Shell 尚未安装。

### Error
```
browserType.launch: Executable doesn't exist at C:\Users\cxwhdev\AppData\Local\ms-playwright\chromium_headless_shell-1228\chrome-headless-shell-win64\chrome-headless-shell.exe
```

### Context
- 在 `frontend` 目录运行 PLAN-023 #2/#3 的四个聚焦 Playwright 用例。
- Vite webServer 正常准备，失败发生在浏览器进程启动之前。
- 未经用户授权不自动执行 `npx playwright install` 下载外部二进制。

### Suggested Fix
在允许下载浏览器依赖的环境执行 `npx playwright install chromium`，随后重跑聚焦 E2E 与完整 E2E。

### Metadata
- Reproducible: yes
- Related Files: frontend/tests/review-workbench.e2e.js, frontend/playwright.config.js

---

## [ERR-20260810-003] IDEA MCP large Markdown payload orchestration

**Logged**: 2026-08-10T00:00:00+08:00
**Priority**: low
**Status**: resolved
**Area**: docs

### Summary

通过 JavaScript 编排 IDEA MCP 创建大型 Markdown 计划时，模板字符串中的 Markdown 反引号触发语法错误，随后使用不可用的 `TextEncoder` 编码又失败。

### Error

```
SyntaxError: Unexpected identifier 'subjectKey'
ReferenceError: TextEncoder is not defined
```

### Context

- 目标是通过 IDEA MCP `create_new_file` 创建 PLAN-023。
- 两次失败均发生在调用 IDEA MCP 前，没有生成半成品计划文件。

### Suggested Fix

避免在 JavaScript 模板字符串内嵌未转义的 Markdown 反引号；直接使用 PowerShell 单引号 here-string 承载正文，再由 HttpClient 发送 JSON-RPC。

### Metadata

- Reproducible: yes
- Related Files: docs/AIREVIEW-PLAN-023-评审入口与公开对话体验收口.md

### Resolution

- **Resolved**: 2026-08-10T00:00:00+08:00
- **Notes**: 使用 here-string 后，IDEA MCP 创建、替换、格式化和检查均成功。

---

## [ERR-20260810-002] PowerShell Invoke-WebRequest failed on IDEA MCP stream

**Logged**: 2026-08-10T00:00:00+08:00
**Priority**: low
**Status**: resolved
**Area**: config

### Summary

PowerShell `Invoke-WebRequest` 调用 IDEA MCP stream 端点时在响应处理阶段触发空引用，未能取得 MCP session header。

### Error

```
Invoke-WebRequest : Object reference not set to an instance of an object.
```

### Context

- 目标为 `http://127.0.0.1:64342/stream`。
- 请求是标准 MCP initialize JSON-RPC。
- 未修改项目业务文件。

### Suggested Fix

Windows 下对 IDEA MCP streamable-http 端点使用 `curl.exe -D` 获取响应头，再携带 `Mcp-Session-Id` 调用工具。

### Metadata

- Reproducible: yes
- Related Files: E:/plus/ECC/.agents/skills/idea-mcp-http/SKILL.md

### Resolution

- **Resolved**: 2026-08-10T00:00:00+08:00
- **Notes**: 切换为 curl.exe 传输。

---

## [ERR-20260810-001] broad ripgrep output exceeded tool budget

**Logged**: 2026-08-10T00:00:00+08:00
**Priority**: low
**Status**: resolved
**Area**: tests

### Summary

一次跨前后端目录的宽范围 `rg` 检索输出超过工具预算，命令结果被截断并以非零状态返回。

### Error

```
Warning: truncated output
Exit code: 1
```

### Context

- 同时检索仓库、草稿、Context Scout、Claim 和 runtime conversation 相关实现。
- 没有修改业务文件。

### Suggested Fix

按功能或文件分组执行定向检索，并限制每次返回的匹配范围。

### Metadata

- Reproducible: yes
- Related Files: frontend/src/views/ReviewLiveView.vue

### Resolution

- **Resolved**: 2026-08-10T00:00:00+08:00
- **Notes**: 改为按组件和接口分批读取。

---

## [ERR-20260720-001] Maven test dependency resolution blocked by sandbox network policy

**Logged**: 2026-07-20T00:00:00+08:00
**Priority**: low
**Status**: open
**Area**: verification

### Summary
Focused Maven tests could not start because the Spring Boot parent POM was absent locally and the sandbox denied the Maven Central connection.

### Error
```
Could not transfer artifact ... Permission denied: getsockopt
```

### Context
- IDEA incremental build completed successfully after the Tool Calling and first-review completion changes.
- The checked-in PowerShell wrapper separately failed with `Cannot index into a null array`; invoking the bundled Maven executable reached dependency resolution but was blocked by network policy.

### Suggested Fix
Run the focused test set in an environment with the Maven dependencies cached or with approved Maven Central access.

### Metadata
- Reproducible: yes
- Related Files: pom.xml

---

## 2026-07-30 Context Scout preview inherited historical session

### Symptom

The standalone Scout preview sent a first model request with 28 messages, including unrelated historical Scout turns. A local reasoning model exhausted its output budget before emitting any public result.

### Cause

`HarnessAgent.streamEvents(String)` delegates to `RuntimeContext.empty()`. That bypassed the preview Harness's explicit session identity, allowing AgentScope state restoration to use an unintended session.

### Resolution

Always call the `streamEvents(String, RuntimeContext)` overload for independent previews. Use a session key derived from review attempt and preview ID, and retain the review runtime context as a typed attribute. Keep `reasoning` separate from public result text; increase the configured public-output budget instead of publishing hidden reasoning.

### Related Files

- `src/main/java/ai/cc/chongming/review/application/ContextScoutPreviewService.java`
- `src/test/java/ai/cc/chongming/review/application/ContextScoutPreviewServiceTests.java`

---

## [ERR-20260728-001] PLAN-019 本地浏览器验收服务未启动

**Logged**: 2026-07-28T15:10:00+08:00
**Priority**: medium
**Status**: resolved
**Area**: verification

### Summary
Context Scout 对话式工具流的代码、构建与自动化测试已完成，但本地 `127.0.0.1:8080` 连续三次 TCP 连接被拒绝，无法执行真实浏览器 E2E 验收。用户随后恢复服务，TCP 检测和真实 Scout 预览均已通过。

### Suggested Fix
由用户启动 Chongming 服务并确认端口 8080 可访问后，再执行 `/#/reviews/{reviewId}/scout` 的真实预览；不要把端口不可达误判为前端或 AgentScope 行为失败。

### Resolution

- **Resolved**: 2026-07-28T15:55:00+08:00
- **Notes**: `Test-NetConnection 127.0.0.1 -Port 8080` 成功；浏览器中的真实 `list_files` 与 `glob_files` 工具流已到达并可展开。

---

## [ERR-20260728-002] 原生文件工具的结构化参数未参与 AS2 schema 校验

**Logged**: 2026-07-28T15:55:00+08:00
**Priority**: high
**Status**: resolved
**Area**: AgentScope integration

### Summary

`AgentScopeModelBridge` 仅向 `ToolUseBlock.input` 写入参数时，AS2 `ToolExecutor` 会改从空的 `ToolUseBlock.content` 执行 schema 校验，导致 `list_files` 的 `path` 等必填参数被误判缺失。

### Resolution

- Bridge 同时以 canonical JSON 填充 `ToolUseBlock.content`。
- `AgentScopeModelBridgeTests` 覆盖 schema 校验能够读取该 JSON。
- IDEA 调试热替换后的真实 Scout 预览已成功读取冻结快照文件列表。

---

## [ERR-20260728-003] Scout 下一轮模型请求丢失原生工具结果

**Logged**: 2026-07-28T16:05:00+08:00
**Priority**: high
**Status**: pending-runtime-verification
**Area**: AgentScope integration

### Summary

Bridge 用 `Msg#getTextContent()` 生成模型公共上下文，原生 `ToolResultBlock` 的嵌套文本没有被包含。真实 Scout 因而反复执行根目录 `list_files`，无法基于已读取结果收束。

### Fix and Verification

- Bridge 现将原生工具结果以 `TOOL_RESULT <toolName>` 形式附加到下一轮上下文，并将单项限制为 12,000 字符。
- 工具结果现有显式不可信数据边界，整次模型请求限制为 48,000 字符；回归测试已通过。
- 新增私有方法不能对运行中 JVM 做结构热替换，必须重启 Chongming 后创建新的 Scout 预览验证最终中文概览。

---

## 2026-07-28 Maven 本地仓库校验阻断 Scout 预览编译

**Status**: open
**Area**: build environment

### Summary
使用显式 `-Dmaven.repo.local=C:\Users\cxwhdev\.m2\repository` 后，Maven 已能解析项目模型并进入 Java 编译，但在读取 `spring-jdbc-7.0.8.jar` 时发生编译器致命错误。

### Error
```
Fatal error compiling: C:\Users\cxwhdev\.m2\repository\org\springframework\spring-jdbc\7.0.8\spring-jdbc-7.0.8.jar
```

### Suggested Fix
确认该 jar 的完整性；若缓存损坏，删除该单个依赖目录后允许 Maven 重新下载，再重新执行编译。不要删除整个 Maven 本地仓库。

---

## [ERR-20260714-001] git repository ownership check

**Logged**: 2026-07-14T00:00:00+08:00
**Priority**: low
**Status**: resolved
**Area**: config

### Summary
Git history inspection was blocked by Git's dubious-ownership protection.

### Error
```
fatal: detected dubious ownership in repository at 'E:/aicode/chongming'
```

### Context
- `git log` ran under a Windows account different from the repository owner.
- The repository configuration was not modified globally.

### Suggested Fix
For read-only repository inspection, pass `-c safe.directory=E:/aicode/chongming` to the individual Git command.

### Metadata
- Reproducible: yes
- Related Files: .git

### Resolution
- **Resolved**: 2026-07-14T00:00:00+08:00
- **Commit/PR**: n/a
- **Notes**: Re-ran history and status inspection with a command-scoped safe-directory override.

---

## [ERR-20260714-003] hatch-pet prepare_pet_run

**Logged**: 2026-07-14T15:00:00+08:00
**Priority**: low
**Status**: resolved
**Area**: config

### Summary
`prepare_pet_run.py` rejected a pure-Chinese pet name because the generated pet ID contained no ASCII letter or digit.

### Error
```
pet id must contain at least one letter or digit
```

### Context
- Attempted to initialize the pet with `--pet-name 重明`.
- The visual display name can remain Chinese, but the package ID must include a letter or digit.

### Suggested Fix
Use the stable package-safe name `chongming`, then preserve “重明” in the description and user-facing metadata.

### Metadata
- Reproducible: yes
- Related Files: output/pets/chongming-run/pet_request.json

### Resolution
- **Resolved**: 2026-07-14T15:00:00+08:00
- **Commit/PR**: n/a
- **Notes**: Retried preparation with the ASCII-compatible name `chongming`.

---

## [ERR-20260714-002] IDEA MCP edit orchestration

**Logged**: 2026-07-14T14:30:05+08:00
**Priority**: low
**Status**: resolved
**Area**: docs

### Summary
A JavaScript orchestration snippet for an IDEA MCP text replacement contained an extra positional string and failed to parse.

### Error
```
SyntaxError: Unexpected string
```

### Context
- The failed operation attempted to change a learning status.
- No repository file was modified by the failed call.

### Suggested Fix
Pass only the declared object fields to `replace_text_in_file` and validate object syntax before execution.

### Metadata
- Reproducible: yes
- Related Files: .learnings/LEARNINGS.md

### Resolution
- **Resolved**: 2026-07-14T14:30:05+08:00
- **Commit/PR**: n/a
- **Notes**: Re-ran the IDEA MCP replacement with valid arguments.

---

## [ERR-20260714-004] apply_patch learning status target

**Logged**: 2026-07-14T17:15:00+08:00
**Priority**: low
**Status**: resolved
**Area**: docs

### Summary
A broad status replacement updated the first learning entry instead of the intended correction entry.

### Error
```
The patch matched the first `**Status**: pending` occurrence in LEARNINGS.md.
```

### Context
- The operation intended to resolve LRN-20260714-002 after rewriting the technical plan.
- The unintended status change was detected during diff review.

### Suggested Fix
Include the learning heading and adjacent metadata in patch context when updating repeated fields.

### Metadata
- Reproducible: yes
- Related Files: .learnings/LEARNINGS.md

### Resolution
- **Resolved**: 2026-07-14T17:15:00+08:00
- **Commit/PR**: n/a
- **Notes**: Applied a heading-scoped patch and verified LRN-20260714-002 is resolved.

---

## [ERR-20260714-005] apply_patch markdown list marker

**Logged**: 2026-07-14T17:20:00+08:00
**Priority**: low
**Status**: resolved
**Area**: docs

### Summary
A patch for a Markdown list item omitted the extra diff deletion marker and failed verification.

### Error
```
apply_patch verification failed: Failed to find expected lines
```

### Context
- The target line itself began with a Markdown hyphen.
- The diff line required two leading hyphens: one patch marker and one content character.

### Suggested Fix
When deleting Markdown list items, encode the patch line as `-- item text`.

### Metadata
- Reproducible: yes
- Related Files: docs/技术方案/AI需求评审Agent_AgentScope2技术方案.md

### Resolution
- **Resolved**: 2026-07-14T17:20:00+08:00
- **Commit/PR**: n/a
- **Notes**: Reapplied the patch with the correct diff marker and verified the field name.

---

## [ERR-20260714-006] PowerShell Markdown fence validation

**Logged**: 2026-07-14T19:35:00+08:00
**Priority**: low
**Status**: resolved
**Area**: docs

### Summary
PowerShell treated Markdown backticks inside a double-quoted regex as escape characters and reported an unterminated string.

### Error
```
The string is missing the terminator: ".
```

### Context
- A structural validation command attempted to match triple-backtick fences with a double-quoted PowerShell pattern.
- The command failed twice before the quoting source was isolated.

### Suggested Fix
Construct the fence pattern with `[char]96` or use a safely single-quoted script file instead of embedding literal backticks in a double-quoted command.

### Metadata
- Reproducible: yes
- Related Files: docs/AIREVIEW-PLAN-*.md

### Resolution
- **Resolved**: 2026-07-14T19:35:00+08:00
- **Commit/PR**: n/a
- **Notes**: Rebuilt the pattern from `[char]96`; validation then completed and found only two missing master-plan heading keywords.

---

## [ERR-20260714-007] apply_patch after IDEA table reformat

**Logged**: 2026-07-14T19:40:00+08:00
**Priority**: low
**Status**: resolved
**Area**: docs

### Summary
A multi-file patch used pre-format Markdown table rows and failed after IDEA aligned the table columns.

### Error
```
apply_patch verification failed: Failed to find expected lines
```

### Context
- IDEA had reformatted plan tables by inserting alignment spaces.
- The patch matched prose but failed on exact table-row whitespace, so the whole patch was rejected.

### Suggested Fix
After formatting, re-read exact table rows or patch stable prose anchors separately from table insertions.

### Metadata
- Reproducible: yes
- Related Files: docs/AIREVIEW-PLAN-010-领域事件SSE与恢复.md, docs/AIREVIEW-PLAN-011-人工审核报告与通知.md
- Recurrence-Count: 2
- Last-Seen: 2026-07-14

### Resolution
- **Resolved**: 2026-07-14T19:40:00+08:00
- **Commit/PR**: n/a
- **Notes**: Read the formatted rows and reapplied scoped patches. The same whitespace-sensitive mismatch recurred once on PLAN-002 and was fixed with an exact formatted-row anchor.

---
## [ERR-20260810-004] idea-mcp-run-tests-unavailable

**Logged**: 2026-08-10T00:00:00+08:00
**Priority**: medium
**Status**: pending
**Area**: tests

### Summary
IDEA MCP HTTP 会话可用，但服务端未暴露文档中列出的 `run_tests` 工具。

### Error
```
Tool run_tests not found
```

### Context
- 通过 `http://127.0.0.1:64342/stream` 初始化成功后调用 `tools/call`。
- 目标为 PLAN-023 后端聚焦测试。
- IDEA MCP 的文件搜索和读取工具仍可用。

### Suggested Fix
先通过 `tools/list` 探测当前 IDEA MCP 实际工具集；若没有 `run_tests`，使用其 `execute_terminal_command` 或 `build_project` 完成测试与构建验证。

### Metadata
- Reproducible: yes
- Related Files: docs/AIREVIEW-PLAN-023-评审入口与公开对话体验收口.md
- See Also: ERR-20260810-002

### Resolution
- **Resolved**: 2026-08-10T16:06:11+08:00
- **Commit/PR**: n/a
- **Notes**: 使用当前服务实际暴露的 `execute_terminal_command`，直接调用 Surefire 聚焦测试目标完成验证。

---

## [ERR-20260810-005] maven-jdk-trust-and-version-mismatch

**Logged**: 2026-08-10T16:04:45+08:00
**Priority**: medium
**Status**: resolved
**Area**: tests

### Summary
默认 Java 17 的 Maven TLS 信任链失败；IDEA JBR 可下载依赖但版本 25 不满足项目 Java 21 Enforcer。

### Error
```
PKIX path building failed
Detected JDK version 25.0.3 is not in the allowed range [21,22)
```

### Context
- Maven 首次需要下载 `spring-boot-starter-mail:4.0.7`。
- 默认 Java 17 无法建立 Maven Central TLS 信任链。
- IDEA JBR 25 成功补齐依赖，但项目只允许 Java 21。

### Suggested Fix
先用具有有效信任库的 JBR 补齐缺失依赖，再将 `JAVA_HOME` 指向 IDEA 项目 SDK `D:\Tool\Java21` 执行 Maven 测试。

### Metadata
- Reproducible: yes
- Related Files: pom.xml
- See Also: ERR-20260810-004

### Resolution
- **Resolved**: 2026-08-10T16:06:11+08:00
- **Commit/PR**: n/a
- **Notes**: 使用 Azul Java 21 运行 Surefire，13 个 PLAN-023 #2/#3 聚焦测试全部通过。

---
## [ERR-20260810-006] PowerShell regex quoting broke a read-only rg command

**Logged**: 2026-08-10T16:07:00+08:00
**Priority**: low
**Status**: resolved
**Area**: frontend

### Summary
PowerShell parsed a double-quoted ripgrep regex containing escaped quotes as an array expression instead of passing it to rg.

### Error
```text
Array index expression is missing or not valid.
The string is missing the terminator: ".
```

### Context
- Attempted to pipe a complex `rg -o` class-attribute regex into `Select-String`.
- No file was changed and the check was non-destructive.

### Suggested Fix
Use a single-quoted PowerShell argument or simpler direct `rg -n` patterns instead of embedding backslash-escaped quotes in a double-quoted command string.

### Metadata
- Reproducible: yes
- Related Files: frontend/src/views/ReviewLiveView.vue

### Resolution
- **Resolved**: 2026-08-10T16:07:00+08:00
- **Notes**: Replaced the command with direct targeted searches.

---
## [ERR-20260810-007] rg did not expand Windows path wildcards

**Logged**: 2026-08-10T16:11:00+08:00
**Priority**: low
**Status**: resolved
**Area**: frontend

### Summary
Passing `frontend/src/components/*.vue` directly to rg on Windows produced an invalid path error because the wildcard was not expanded.

### Error
```text
文件名、目录名或卷标语法不正确。 (os error 123)
```

### Context
- The preceding `git status` and explicit-file searches completed and returned the required evidence.
- The failure only affected an optional annotation inventory search.

### Suggested Fix
Search the directory and use `--glob '*.vue'`, or enumerate explicit paths instead of embedding a wildcard in the path argument.

### Metadata
- Reproducible: yes
- Related Files: frontend/src/components
- See Also: ERR-20260810-006

### Resolution
- **Resolved**: 2026-08-10T16:11:00+08:00
- **Notes**: Used already collected explicit-file status and build evidence for handoff.

---

## [ERR-20260810-008] Playwright could not spawn installed Chrome

**Logged**: 2026-08-10T16:33:00+08:00
**Priority**: medium
**Status**: resolved
**Area**: tests

### Summary
Playwright discovered all E2E cases but the environment denied launching the installed Chrome executable with `spawn EACCES`.

### Context
- `PLAYWRIGHT_CHANNEL=chrome npx playwright test` discovered 11 tests.
- Every case failed before page navigation at `browserType.launch`.
- The same local Vite page was reachable through the Codex in-app browser.

### Suggested Fix
Keep Playwright channel selection configurable for developer machines, and use the in-app browser for non-mutating visual verification when local process policy blocks browser launch.

### Metadata
- Reproducible: yes
- Related Files: frontend/playwright.config.js, frontend/tests/review-workbench.e2e.js

### Resolution
- **Resolved**: 2026-08-10T16:35:00+08:00
- **Notes**: `playwright.config.js` now accepts `PLAYWRIGHT_EXECUTABLE_PATH`; using the cached Chromium headless shell completed the full 13-case suite. A later `playwright install chromium` attempt also stalled because the requested revision was unavailable, so E2E must not depend on a just-in-time browser download in this environment.

---

## [ERR-20260811-009] Invoke-WebRequest could not call IDEA MCP stream endpoint

**Logged**: 2026-08-11T12:10:00+08:00
**Priority**: medium
**Status**: resolved
**Area**: tooling

### Summary
PowerShell `Invoke-WebRequest` raised a `NullReferenceException` when calling the IDEA MCP Streamable HTTP endpoint, even though the endpoint was available.

### Resolution
Use `System.Net.Http.HttpClient`, perform MCP `initialize`, preserve the returned `mcp-session-id`, send `notifications/initialized`, and then call `tools/call`. On older PowerShell, load `System.Net.Http` explicitly before constructing `HttpClient`.

### Metadata
- Reproducible: yes
- Related Files: E:\plus\ECC\.agents\skills\idea-mcp-http\SKILL.md

---

## [ERR-20260811-010] IDEA terminal inherited Java 8 for a Java 21 project

**Logged**: 2026-08-11T12:10:00+08:00
**Priority**: medium
**Status**: resolved
**Area**: build

### Summary
Long Maven verification launched from the IDEA terminal inherited Java 8 and also hit the terminal tool's 60-second limit, while the project requires Java 21.

### Resolution
Use IDEA MCP `build_project` for the IDE compilation signal. For the longer full Maven suite, explicitly point task-local `JAVA_HOME` and `Path` to `D:\Tool\Java21` without changing global environment variables.

### Metadata
- Reproducible: yes
- Related Files: pom.xml, mvnw.cmd

---
