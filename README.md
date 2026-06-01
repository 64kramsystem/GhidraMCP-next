# GhidraMCP-next

Fork of [LaurieWired/GhidraMCP](https://github.com/LaurieWired/GhidraMCP), updated for Ghidra 12, and with some improvements.

## Changes from upstream

- Java 21 / artifacts built for the latest exact Ghidra 12.1 release
- Replaced deprecated `CodeUnit` comment constants with `CommentType` enum
- Fixed `renameData` to work on addresses without defined data
- CI follows the latest Ghidra 12.1 release and publishes version-matched artifacts
- Integration test suite (runs headless in CI)

## Installation

Download the latest [release](https://github.com/64kramsystem/GhidraMCP-next/releases) ZIP, then in Ghidra:

1. File > Install Extensions > `+` > select the ZIP
2. Restart Ghidra
3. File > Configure > Developer > enable GhidraMCPPlugin

Each release ZIP targets the exact Ghidra version named in the release title; install the artifact matching your Ghidra build.

The plugin starts an HTTP server bound to `127.0.0.1:8080` by default
(configurable via Edit > Tool Options > GhidraMCP HTTP Server).

Keep the server bound to `127.0.0.1` unless you fully trust the network path.
The HTTP API can read and modify the open Ghidra program and does not yet
provide authentication for remote clients. Use SSH forwarding instead of a
public bind when connecting from another machine.

## MCP client setup

See the [upstream README](https://github.com/LaurieWired/GhidraMCP#mcp-clients) for Claude Desktop, Cline, and other MCP client configurations. Use `bridge_mcp_ghidra.py` from the release.

The bridge defaults to MCP `stdio`. It also accepts `--transport
streamable-http` for clients that connect to a local HTTP MCP server.

For setup checks, the plugin exposes legacy text endpoints at `/health` and
`/version`, plus JSON envelope equivalents at `/api/v1/health` and
`/api/v1/version`. Function listing is also available as structured JSON at
`/api/v1/list_functions`, `/api/v1/get_function_by_address`, and
`/api/v1/decompile_function`; xrefs are available at
`/api/v1/get_xrefs_to` and `/api/v1/get_xrefs_from`. The Python bridge prefers
those endpoints and falls back to legacy text endpoints for older plugin
builds. The bridge normalizes legacy `list_functions` fallback into the
structured `{"name", "address"}` shape, and legacy xref fallback into the
canonical structured xref shape with blank function-detail fields when older
text endpoints cannot provide them. Paginated JSON endpoints include
`meta.offset` and `meta.limit`; `meta.next_offset` is present only when another
page is available. JSON list endpoints are not paginated yet.

## Building from source

```bash
# Copy Ghidra JARs to lib/
mkdir -p lib
for jar in Base Decompiler Docking Generic Project SoftwareModeling Utility Gui; do
  cp "$GHIDRA_HOME"/Ghidra/*/lib/${jar}.jar lib/ 2>/dev/null ||
  cp "$GHIDRA_HOME"/Ghidra/*/*/lib/${jar}.jar lib/
done

mvn clean package assembly:single
```

Output: `target/GhidraMCP-next-1.0-SNAPSHOT.zip`

## Testing

```bash
bash test_endpoints.sh
```

Requires Ghidra running with the plugin enabled and a program open with at least one function. See the script header for setup with the included test binary (`test/fixtures/test_6502.bin`).
