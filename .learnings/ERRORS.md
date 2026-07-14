# Errors

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
