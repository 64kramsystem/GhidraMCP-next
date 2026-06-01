# GhidraMCP-next Improvement Notes

Date: 2026-06-01

This note reviews `GhidraMCP-next` against current Ghidra/MCP-style projects and
the related improvement notes in `/Users/saverio/Desktop/IMPROVEMENTS.md`.

No code changes are proposed as a rewrite. The current architecture - a Ghidra
Java plugin exposing local HTTP endpoints plus a Python FastMCP bridge - is
small and easy to ship. The highest-value work is to harden the boundary,
improve the API shape, and expose more agent-friendly context.

## Current Project Baseline

The repo is intentionally compact:

- `src/main/java/com/lauriewired/GhidraMCPPlugin.java` contains the plugin,
  embedded HTTP server, endpoint registration, Ghidra API logic, serialization,
  transactions, and utility parsing. It is currently about 1,650 lines.
- `bridge_mcp_ghidra.py` is a thin FastMCP bridge that maps MCP tools to the
  Java plugin's HTTP endpoints.
- `test_endpoints.sh`, `test/HeadlessMCPServer.java`, and `test/gui-smoke/run.sh`
  provide endpoint and packaged-plugin coverage.
- The README documents Ghidra 12.1 packaging, release matching, and the current
  upstream-derived client setup.

The project already has useful CI and GUI smoke coverage. That is a strong base
for incremental design improvements.

## Similar Projects Reviewed

### LaurieWired/GhidraMCP

The upstream project is the direct ancestor. It uses the same broad split:
Ghidra plugin plus Python bridge. This fork has already improved Ghidra 12
compatibility, release matching, and test coverage.

Reference:

- https://github.com/LaurieWired/GhidraMCP

### 13bm/GhidraMCP

This project keeps a bridge, but changes the transport between bridge and
Ghidra to length-prefixed JSON-RPC over TCP. It also adds settings, API-key
authentication, localhost-only binding, automatic bridge launch, multi-instance
routing, async decompilation, retries, pagination, and a larger tool surface.

Useful ideas for this repo:

- Localhost-only binding should be the default.
- Optional authentication is worth adding if remote binding is ever allowed.
- Health/version endpoints help setup and troubleshooting.
- Multi-instance routing is a later-stage improvement, not a first pass.
- Async decompilation is useful for large functions, but can wait until the
  core protocol is structured.

Reference:

- https://github.com/13bm/GhidraMCP

### cyberkaida/reverse-engineering-assistant (ReVa)

ReVa's most relevant design choice is not simply having more tools. Its tools
are shaped for LLM use: small, tolerant of imperfect inputs, and enriched with
context such as namespaces and xrefs to guide exploration without bloating every
response.

Useful ideas for this repo:

- Add higher-level inspection tools that return bounded context bundles.
- Prefer agent-facing output that helps the next decision, not only raw Ghidra
  listings.
- Support both interactive GUI use and headless automation as explicit modes.

Reference:

- https://github.com/cyberkaida/reverse-engineering-assistant

### bethington/ghidra-mcp

This is a larger "production-ready" implementation with many tools, stricter
setup scripts, headless support, batch operations, configurable timeouts, and
security hardening. Not all of that scope belongs here, but the hardening and
setup ergonomics are directly applicable.

Useful ideas for this repo:

- Script-like high-risk operations should be explicit and default-off if added.
- Setup preflight and health checks reduce support burden.
- Batch operations and timeouts are useful once results are structured.
- Troubleshooting docs should include connection, port, analysis, and plugin
  enablement checks.

Reference:

- https://github.com/bethington/ghidra-mcp

### symgraph/GhidrAssistMCP

GhidrAssistMCP is a native Ghidra MCP server with GUI configuration, tool
management, and documented headless mode. The headless design is especially
relevant because this repo currently keeps a separate headless test server that
can drift from the real plugin.

Useful ideas for this repo:

- Add a real headless entry point that uses the same endpoint/service layer as
  the GUI plugin.
- Expose server settings in a small Ghidra UI panel or menu later.
- Allow users to enable or disable groups of tools once the surface grows.

Reference:

- https://github.com/symgraph/GhidrAssistMCP

### themixednuts/GhidraMCP

This project embeds the MCP server directly in Ghidra and exposes tools,
resources, prompts, structured responses, opaque cursors, and resumable large
outputs. It is a useful reference for modern MCP feature shape.

Useful ideas for this repo:

- Native MCP in Ghidra is a possible future direction.
- MCP resources are a good fit for stable program views such as functions,
  strings, imports, exports, listing, and decompile output.
- Large outputs should use cursors or resumable reads rather than oversized
  single responses.

Reference:

- https://github.com/themixednuts/GhidraMCP

### clearbluejar/pyghidra-mcp

This is Python-first and optimized for headless and project-wide workflows. It
is less directly applicable to this plugin, but useful as a reference for
automation and mode selection.

Useful ideas for this repo:

- Separate "interactive Ghidra GUI" and "headless/project automation" stories.
- Consider semantic search only after core structured output and stable
  headless use exist.

Reference:

- https://github.com/clearbluejar/pyghidra-mcp

## MCP Protocol Notes

The current bridge supports `stdio` and legacy `sse`:

- `bridge_mcp_ghidra.py` exposes `--transport` with choices `stdio` and `sse`.

Current MCP guidance favors Streamable HTTP over the older HTTP+SSE transport.
The Python SDK supports:

```python
mcp.run(transport="streamable-http")
```

The protocol also supports structured tool results and output schemas. For this
project, that matters more than adding many more tools, because it lets agents
consume function lists, xrefs, symbols, and mutation results reliably.

References:

- https://modelcontextprotocol.io/specification/2025-06-18/basic/transports
- https://modelcontextprotocol.io/specification/2025-06-18/server/tools
- https://github.com/modelcontextprotocol/python-sdk

## Applicable Ideas From Desktop IMPROVEMENTS.md

The VICE/TraceRMI-specific recommendations do not directly apply here:

- TraceRMI launcher conventions
- VICE Binary Monitor Protocol validation
- C64 memory banks
- PRG import/rebase helpers

The general design principles do apply:

- Serialize or otherwise control execution at the Ghidra boundary.
- Harden protocol parsing and return typed results.
- Make setup easier and more explicit.
- Make logging configurable.
- Add clear localhost and remote-access security guidance.
- Avoid broad rewrites before smaller boundary improvements are in place.

Reference:

- `/Users/saverio/Desktop/IMPROVEMENTS.md`

## Priority Recommendations

### 1. Harden The Network Boundary

Current code creates the server with:

```java
server = HttpServer.create(new InetSocketAddress(port), 0);
```

That constructor binds to the wildcard address. The README says the MCP bridge
defaults to `127.0.0.1`, but the Java plugin itself should enforce the safe
default.

Recommended changes:

- Bind the Java HTTP server to `127.0.0.1` by default.
- Add a `Bind Host` or `Localhost Only` option in `GhidraMCP HTTP Server`.
- Reject non-local binds unless the user explicitly opts in.
- Add optional token auth for any non-local bind.
- Validate `Origin` and `Host` headers for HTTP clients.
- Add README guidance that remote access should be used only on trusted
  networks or through SSH forwarding.

Why:

- MCP tools can modify the Ghidra database.
- Local HTTP servers are exposed to DNS rebinding risks if browser-originated
  requests can reach them.
- Similar projects have moved toward localhost-only defaults and auth.

### 2. Add Health, Version, And Capability Endpoints

Current tests infer availability through `/list_functions`. That is useful, but
not enough for user setup or MCP client diagnostics.

Recommended endpoints:

- `/health`: server alive, current program loaded or not, program name if loaded.
- `/version`: plugin version, Ghidra version, Java version, supported API version.
- `/capabilities`: supported endpoint groups and whether write tools are enabled.

Why:

- Users can distinguish "plugin not running" from "no program open".
- The Python bridge can fail fast with actionable errors.
- Future clients can adapt to API changes.

### 3. Move From Plain Text To JSON Envelopes

Current Java endpoints mostly return newline-delimited text or human-readable
error strings. The Python bridge then returns either split lines or raw text.

Recommended response envelope:

```json
{
  "ok": true,
  "data": {},
  "warnings": [],
  "meta": {
    "offset": 0,
    "limit": 100,
    "next_offset": 100
  }
}
```

For errors:

```json
{
  "ok": false,
  "error": {
    "code": "function_not_found",
    "message": "No function found at or containing address 00401000"
  }
}
```

Recommended implementation approach:

- Keep legacy text endpoints for compatibility at first.
- Add `/api/v1/...` JSON endpoints or content-negotiate via `Accept:
  application/json`.
- Update the Python bridge to prefer JSON and fall back to text for older
  plugin builds.

Why:

- MCP tools can return structured content and output schemas.
- Tests can assert exact fields instead of substring matches.
- Agents do not have to parse ad hoc strings.

### 4. Consolidate Around Address-First Tools

The code currently exposes old name-based tools and newer address-based tools:

- `/decompile` by name
- `/renameFunction` by name
- `/renameVariable` by function name
- `/decompile_function` by address
- `/rename_function_by_address` by address

Recommended API direction:

- Keep name-based endpoints as compatibility wrappers.
- Prefer address-first MCP-visible tools.
- Add an explicit function lookup tool that resolves names to candidate
  addresses when names are ambiguous.
- Return namespace, entry point, body range, signature, and source type in
  function records.

Why:

- Function names are not unique enough in real Ghidra projects.
- Address-first APIs are easier for agents to chain after xref and listing
  operations.

### 5. Add Agent-Friendly Inspection Bundles

Today, an agent must call several separate tools to understand a function:

- `get_function_by_address`
- `decompile_function_by_address`
- `disassemble_function`
- `get_xrefs_to`
- `get_xrefs_from`

Recommended new tool:

```text
inspect_function(address, include_decompile=true, include_disassembly=false,
                 max_xrefs=50, max_lines=200)
```

Suggested response fields:

- Function identity: name, namespace, entry, body range, signature.
- Decompile text, optionally bounded.
- Incoming and outgoing xrefs with function context.
- Called functions and referenced strings where cheap to compute.
- Current comments and labels near the entry point.
- Hints for next actions, for example "large function, use disassembly window"
  or "ambiguous thunk/import".

Why:

- ReVa's strongest design idea is returning compact, decision-guiding context.
- It reduces context churn and tool-call overhead.
- It can be bounded for large binaries.

### 6. Add A Transaction And Error-Handling Layer

Several write paths repeat transaction and Swing-thread code. Some paths return
vague success/failure messages:

- `renameDataAtAddress` returns "Rename data attempted" even when errors occur.
- Some transactions commit with `true` after caught exceptions.
- Prototype setting writes a plate comment before applying the signature.

Recommended changes:

- Add a shared `runRead` and `runWriteTransaction` helper.
- Return `OperationResult<T>` with `success`, `value`, `errorCode`, and
  `message`.
- Commit only on success.
- Avoid side effects that are not the requested mutation, such as adding a
  "Setting prototype" plate comment.
- Include exception class and status message in logs, not in normal user-facing
  output unless useful.

Why:

- Ghidra database mutations need consistent transaction semantics.
- Agents need to know whether a mutation actually happened.
- Shared wrappers make later refactors safer.

### 7. Modernize The MCP Bridge

Recommended bridge changes:

- Add `--transport streamable-http` and keep `stdio`.
- Keep `sse` only as deprecated compatibility.
- Initialize FastMCP with JSON-friendly settings where supported.
- Add configurable HTTP request timeout.
- Add a health check at startup for HTTP transports.
- Use structured MCP results once Java returns JSON.

Why:

- Streamable HTTP is the current MCP direction.
- Some clients increasingly expect HTTP MCP URLs rather than a separate local
  bridge process.
- Better startup errors will reduce setup friction.

### 8. Split The Java Plugin By Responsibility

The plugin class currently mixes:

- Plugin lifecycle and options
- HTTP server setup
- Endpoint registration
- Parameter parsing
- Response serialization
- Ghidra read services
- Ghidra write services
- Decompiler helpers
- Data type resolution

Recommended split:

- `GhidraMCPPlugin`: plugin lifecycle and service wiring.
- `HttpServerController`: bind/start/stop, context registration.
- `RequestParser`: query/form/JSON parsing.
- `ResponseWriter`: text and JSON response writing.
- `ProgramService`: current program, functions, symbols, xrefs.
- `DecompilerService`: decompile, high-function variable helpers.
- `MutationService`: rename, comments, prototypes, types.
- `DataTypeService`: type resolution and search.

Why:

- The current class is still manageable but already large enough to slow safe
  edits.
- Tests can target service behavior without driving HTTP for every case.
- Headless and GUI modes can share services.

### 9. Reduce Headless Test Drift

`test/HeadlessMCPServer.java` reimplements a subset of the real plugin's
endpoints. That is useful today, but it can drift from the actual server logic.

Recommended direction:

- Extract shared services first.
- Make the headless test server call the same service layer as the plugin.
- Later, provide a real headless entry point similar to GhidrAssistMCP's
  documented `analyzeHeadless` server mode.

Why:

- Tests should verify production logic, not a simplified duplicate.
- A real headless mode would be useful for automation and CI.

### 10. Improve Build And Setup Ergonomics

Current source builds require manually copying Ghidra jars into `lib/`.

Recommended changes:

- Add a small setup script or Maven profile that locates `GHIDRA_HOME` and
  installs/copies required jars.
- Add a preflight command that checks Java, Ghidra version, required jars, and
  Python requirements.
- Document a quick `uv run --script bridge_mcp_ghidra.py` path if that remains
  the recommended bridge launch.
- Add troubleshooting entries for port conflicts, no program loaded, plugin not
  enabled, and analysis not complete.

Why:

- Other projects reduce setup friction with preflight and deploy helpers.
- This repo already has CI automation; local setup should use the same knowledge.

## Not Recommended Yet

### Do Not Immediately Rewrite As A Native MCP Server

A native in-Ghidra MCP endpoint is attractive and similar projects show it can
work. It would remove the Python bridge for HTTP clients and simplify some
configuration. However, it is a larger change than needed for the next step.

Recommended stance:

- First stabilize the local HTTP API with JSON, security, health checks, and
  better tool shape.
- Then decide whether to keep the bridge, embed MCP natively, or support both.

### Do Not Chase Tool Count

Projects advertising 70, 200, or more tools are useful references, but tool
count is not the main gap here. The current project would benefit more from
better primitives, structured outputs, and an `inspect_function` bundle than
from quickly adding dozens of thin wrappers.

### Do Not Add Script Execution By Default

Script execution can be useful, but it is high risk. If added later:

- It should be disabled by default.
- It should require explicit configuration.
- It should be limited to known script roots.
- It should be covered by clear security documentation.

## Suggested Implementation Order

1. Bind to localhost by default and add health/version endpoints.
2. Add JSON response envelopes while keeping legacy text compatibility.
3. Update the Python bridge to consume JSON and support `streamable-http`.
4. Add `inspect_function(address)` as the first richer agent-facing tool.
5. Add shared transaction/error helpers and fix vague write results.
6. Split the Java plugin into lifecycle, HTTP, parsing, services, and mutation
   helpers.
7. Make headless tests call the shared service layer.
8. Add setup/preflight tooling and expand troubleshooting docs.
9. Consider native MCP-in-Ghidra only after the API boundary is stable.

## Highest-Value First Patch Set

A focused first PR could be:

- Bind `HttpServer` to `127.0.0.1`.
- Add `/health` and `/version`.
- Add `--transport streamable-http` to `bridge_mcp_ghidra.py`.
- Add README security/setup notes.
- Add tests for health/version and localhost configuration.

This would improve safety and compatibility without changing the core tool
behavior.
