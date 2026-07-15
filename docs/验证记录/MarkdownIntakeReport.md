# PLAN-005 Markdown Intake Verification

> Date: 2026-07-15
> Command: `./mvnw.cmd clean verify`
> Result: **PASS** — 55 tests run, 52 passed, 3 Docker-dependent MySQL tests skipped; application JAR packaged successfully.

## Verified capabilities

| Capability | Evidence | Result |
|---|---|---|
| Multipart API and response fields | `ReviewCommandControllerTests` | 202 response, review/attempt/hash/status URL and idempotent `reused` flag verified |
| Stable RFC 9457 errors | `ReviewCommandControllerTests` | 400 missing part/parameter, 409 cancellation, 413 size limit, 422 invalid Markdown and safe 500 response verified |
| File validation | `MarkdownRequirementValidatorTests` | Empty input, non-`.md` extension, unsafe name, malformed UTF-8, NUL/control bytes rejected |
| Streaming normalization and hashes | `MarkdownRequirementValidatorTests` | Original SHA-256, normalized SHA-256, newline/NFC normalization and 1 MiB single-line input verified |
| Deterministic parsing | `MarkdownRequirementParserTests` | Headings, line numbers, links, tables, code blocks, Chinese/English injection markers and cancellation verified |
| Immutable workspace snapshot | `ReviewIntakeServiceTests` | Raw file, normalized file and JSON manifest are atomically published as one `input/` directory |
| Idempotency and cancellation | `ReviewIntakeServiceTests` | Concurrent matching submissions produce one snapshot; cancellation publishes no final snapshot |

## Confirmed behavior

- A zero-byte upload is rejected with `EMPTY_MARKDOWN` / HTTP 422.
- Uploaded filenames never influence the workspace destination; final files use controlled names beneath `reviews/{reviewId}/attempt-{n}/input/`.
- The implementation has no application-level business size cap. Deployment multipart limits remain responsible for triggering HTTP 413.
- Prompt-injection-like text is stored only as a detection marker and is never interpreted as an instruction.

## Pending verification

- MyBatis/Flyway transactional persistence of the review request and `requirement_snapshot` data.
- Database-backed idempotency/claim, rollback, and cross-filesystem/database orphan recovery.
- Actual multipart size-limit behavior and MySQL migration tests in a Docker-enabled environment.
- Production resource metrics and deployment-specific multipart limit configuration.
