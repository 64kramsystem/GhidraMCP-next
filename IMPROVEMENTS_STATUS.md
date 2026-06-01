# IMPROVEMENTS.md — Implementation Status

Date: 2026-06-01
Branch: `WIP`

This tracks progress on the roadmap in `IMPROVEMENTS.md`. Work was driven by
Codex (implementation) with Claude reviewing each increment. Codex ran out of
capacity partway through the pagination increment, so the last increment is
committed in a partial/unreviewed state — see "In progress" below.

## How the work was done

Each increment was implemented by Codex, then independently reviewed and
re-verified by Claude (running `mvn test`, the Python bridge unit tests,
`bash -n test_endpoints.sh`, and a headless `javac` compile) before being
committed as a checkpoint. Legacy text endpoints were preserved throughout for
backward compatibility; new functionality lives under `/api/v1/...`.

## Done and committed

### Pre-existing (commit `15fed6e` and earlier)
- IMPROVEMENTS.md item 1 (network boundary): `HttpServer` binds to `127.0.0.1`
  by default with a configurable `Bind Host` option.
- Legacy text `/health` and `/version` endpoints.
- Bridge `--transport streamable-http` (item 7, partial).
- README security/setup notes; tests for health/version and localhost config.

### `5623fd6` — JSON envelope API layer (`/api/v1`)
Implements item 3 (JSON envelopes) and parts of items 4/5/7.
- `Json` + `HttpResponses` helpers: a single escaping/response path for all
  envelopes (`{ok, data, warnings, meta}` / error envelopes).
- `/api/v1/health`, `/api/v1/version`, `/api/v1/list_functions`,
  `/api/v1/get_function_by_address`, `/api/v1/decompile_function`.
- Rich function records: `name, namespace, entry, body_start, body_end,
  signature`.
- `ServerMetadata` builds every envelope through `Json` (single source of
  truth); legacy `listFunctions()` shares `getFunctionRecords()` with the JSON
  path.
- Bridge `safe_get_json`: prefers JSON, falls back to legacy text on old plugin
  builds, normalizes fallbacks into the canonical structured shape.
- `HeadlessMCPServerParityTest` compiles the headless harness and asserts its
  JSON output matches `ServerMetadata` (guards item 9 drift).

### `c1d4f7d` — xrefs JSON endpoints + cleanups
- `/api/v1/get_xrefs_to`, `/api/v1/get_xrefs_from` with nested
  `from_function`/`to_function` rich records; offset/limit slicing.
- Cleanup: legacy `decompileFunctionByAddress` now disposes `DecompInterface`
  in `finally` (was leaking).
- Cleanup: JSON lookup/decompile/xrefs return a precise `invalid_address` error
  code instead of a generic failure.

## In progress — UNCOMMITTED AT TIME OF WRITING (now committed as partial)

### Round 005 — envelope pagination metadata (item 3, `meta` part)
Status: **implemented and passing local tests, but incomplete and not yet
reviewed.** Codex was interrupted (out of capacity) before producing its
completion report, and one planned sub-task was not done.

Done in the working tree:
- `Json.envelope(data, offset, limit, nextOffset)` overload and a `meta(offset,
  limit, nextOffset)` builder: emits `offset`/`limit` always and `next_offset`
  only when more results exist. Non-paginated endpoints keep `meta` =
  `{api_version}` only.
- `Page<T>` helper + `paginateRecords` computing `nextOffset`.
- Both xref endpoints wired to report pagination meta.
- `ServerMetadata.buildXrefsJsonResponse` overloads; `JsonTest`,
  `ServerMetadataTest`, headless harness + parity guard, `test_endpoints.sh`,
  and README updated.
- Local verification at time of commit: `mvn test` 23 passing, Python bridge
  15 passing, `bash -n` OK, headless `javac` OK.

NOT done (remaining for this increment):
- **Python bridge not updated** to surface/forward `next_offset` for paginated
  tools (`bridge_mcp_ghidra.py` was not modified). Agents calling the bridge
  cannot yet page through xrefs.
- Not reviewed by Claude in depth (no Codex completion report to review
  against); treat as provisional until reviewed.

## Remaining roadmap (not started)

In the suggested order from `IMPROVEMENTS.md`:
- Finish the bridge side of pagination (forward `next_offset`).
- Continue JSON coverage to remaining read groups (disassembly, current
  address/function, strings, imports/exports).
- Item 6: shared transaction/error helpers (`runRead`/`runWriteTransaction`,
  `OperationResult<T>`); fix vague write results (e.g. "Rename data attempted").
- Item 5: `inspect_function(address)` bundle.
- Item 8: split `GhidraMCPPlugin` by responsibility.
- Item 9: real headless entry point sharing the service layer.
- Item 10: build/setup preflight tooling and troubleshooting docs.

## Known minor findings (noted, non-blocking)
- Bridge error-envelope path returns a list-shaped value even for `dict|str`-
  typed tools (degraded error path; does not crash callers).
- `from_function`/`to_function` are emitted as objects with empty-string fields
  (not `null`) when there is no containing function — consistent-shape choice.
