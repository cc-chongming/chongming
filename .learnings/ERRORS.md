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
