# Errors

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
