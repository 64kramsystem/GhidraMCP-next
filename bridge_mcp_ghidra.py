# /// script
# requires-python = ">=3.10"
# dependencies = [
#     "requests>=2,<3",
#     "mcp>=1.8.0,<2",
# ]
# ///

import sys
import requests
import argparse
import logging
from urllib.parse import urljoin

from mcp.server.fastmcp import FastMCP

DEFAULT_GHIDRA_SERVER = "http://127.0.0.1:8080/"

logger = logging.getLogger(__name__)

mcp = FastMCP("ghidra-mcp")

# Initialize ghidra_server_url with default value
ghidra_server_url = DEFAULT_GHIDRA_SERVER

def safe_get(endpoint: str, params: dict = None) -> list:
    """
    Perform a GET request with optional query parameters.
    """
    if params is None:
        params = {}

    url = urljoin(ghidra_server_url, endpoint)

    try:
        response = requests.get(url, params=params, timeout=5)
        response.encoding = 'utf-8'
        if response.ok:
            return response.text.splitlines()
        else:
            return [f"Error {response.status_code}: {response.text.strip()}"]
    except Exception as e:
        return [f"Request failed: {str(e)}"]

def safe_get_json(
    endpoint: str,
    fallback_endpoint: str,
    params: dict = None,
    data_key: str = None,
    fallback_transform = None,
):
    """
    Prefer a JSON envelope endpoint and fall back to a legacy text endpoint.
    """
    if params is None:
        params = {}

    url = urljoin(ghidra_server_url, endpoint)

    try:
        response = requests.get(url, params=params, timeout=5)
        response.encoding = 'utf-8'
        if response.ok:
            envelope = response.json()
            if envelope.get("ok"):
                data = envelope.get("data", {})
                return data.get(data_key, []) if data_key else data

            error = envelope.get("error", {})
            code = error.get("code", "unknown_error")
            message = error.get("message", response.text.strip())
            return [f"Error {code}: {message}"]
    except Exception:
        pass

    fallback = safe_get(fallback_endpoint, params)
    return fallback_transform(fallback) if fallback_transform else fallback

def safe_post(endpoint: str, data: dict | str) -> str:
    try:
        url = urljoin(ghidra_server_url, endpoint)
        if isinstance(data, dict):
            response = requests.post(url, data=data, timeout=5)
        else:
            response = requests.post(url, data=data.encode("utf-8"), timeout=5)
        response.encoding = 'utf-8'
        if response.ok:
            return response.text.strip()
        else:
            return f"Error {response.status_code}: {response.text.strip()}"
    except Exception as e:
        return f"Request failed: {str(e)}"

def parse_legacy_function_list(lines: list) -> list:
    functions = []
    for line in lines:
        if " at " not in line:
            continue
        name, address = line.rsplit(" at ", 1)
        functions.append({"name": name, "address": address})
    return functions or lines

def parse_legacy_function_record(lines: list):
    record = {
        "name": "",
        "namespace": "",
        "entry": "",
        "body_start": "",
        "body_end": "",
        "signature": "",
    }
    for line in lines:
        if line.startswith("Function: "):
            name_and_address = line.removeprefix("Function: ")
            if " at " in name_and_address:
                name, entry = name_and_address.rsplit(" at ", 1)
                record["name"] = name
                record["entry"] = entry
        elif line.startswith("Signature: "):
            record["signature"] = line.removeprefix("Signature: ")
        elif line.startswith("Entry: "):
            record["entry"] = line.removeprefix("Entry: ")
        elif line.startswith("Body: "):
            body = line.removeprefix("Body: ")
            if " - " in body:
                record["body_start"], record["body_end"] = body.split(" - ", 1)

    return record if record["name"] or record["entry"] else "\n".join(lines)

def empty_function_record(name: str = "", entry: str = "") -> dict:
    return {
        "name": name,
        "namespace": "",
        "entry": entry,
        "body_start": "",
        "body_end": "",
        "signature": "",
    }

def xref_record(
    from_address: str = "",
    to_address: str = "",
    reference_type: str = "",
    from_function_name: str = "",
    to_function_name: str = "",
) -> dict:
    return {
        "from_address": from_address,
        "to_address": to_address,
        "reference_type": reference_type,
        "from_function": empty_function_record(from_function_name),
        "to_function": empty_function_record(to_function_name),
    }

def split_legacy_xref_line(line: str, prefix: str):
    if not line.startswith(prefix):
        return None

    body = line.removeprefix(prefix).strip()
    reference_type = ""
    if body.endswith("]") and " [" in body:
        body, reference_type = body.rsplit(" [", 1)
        reference_type = reference_type[:-1]
    return body, reference_type

def parse_legacy_xrefs_to(lines: list, target_address: str) -> list:
    xrefs = []
    for line in lines:
        parsed = split_legacy_xref_line(line, "From ")
        if parsed is None:
            continue

        body, reference_type = parsed
        from_function_name = ""
        if " in " in body:
            from_address, from_function_name = body.split(" in ", 1)
        else:
            from_address = body
        xrefs.append(xref_record(from_address, target_address, reference_type, from_function_name, ""))
    return xrefs or lines

def parse_legacy_xrefs_from(lines: list, source_address: str) -> list:
    xrefs = []
    for line in lines:
        parsed = split_legacy_xref_line(line, "To ")
        if parsed is None:
            continue

        body, reference_type = parsed
        to_function_name = ""
        if " to function " in body:
            to_address, to_function_name = body.split(" to function ", 1)
        elif " to data " in body:
            to_address, _ = body.split(" to data ", 1)
        else:
            to_address = body
        xrefs.append(xref_record(source_address, to_address, reference_type, "", to_function_name))
    return xrefs or lines

@mcp.tool()
def list_methods(offset: int = 0, limit: int = 100) -> list:
    """
    List all function names in the program with pagination.
    """
    return safe_get("methods", {"offset": offset, "limit": limit})

@mcp.tool()
def list_classes(offset: int = 0, limit: int = 100) -> list:
    """
    List all namespace/class names in the program with pagination.
    """
    return safe_get("classes", {"offset": offset, "limit": limit})

@mcp.tool()
def decompile_function(name: str) -> str:
    """
    Decompile a specific function by name and return the decompiled C code.
    """
    return safe_post("decompile", name)

@mcp.tool()
def rename_function(old_name: str, new_name: str) -> str:
    """
    Rename a function by its current name to a new user-defined name.
    """
    return safe_post("renameFunction", {"oldName": old_name, "newName": new_name})

@mcp.tool()
def rename_data(address: str, new_name: str) -> str:
    """
    Rename a data label at the specified address.
    """
    return safe_post("renameData", {"address": address, "newName": new_name})

@mcp.tool()
def list_segments(offset: int = 0, limit: int = 100) -> list:
    """
    List all memory segments in the program with pagination.
    """
    return safe_get("segments", {"offset": offset, "limit": limit})

@mcp.tool()
def list_imports(offset: int = 0, limit: int = 100) -> list:
    """
    List imported symbols in the program with pagination.
    """
    return safe_get("imports", {"offset": offset, "limit": limit})

@mcp.tool()
def list_exports(offset: int = 0, limit: int = 100) -> list:
    """
    List exported functions/symbols with pagination.
    """
    return safe_get("exports", {"offset": offset, "limit": limit})

@mcp.tool()
def list_namespaces(offset: int = 0, limit: int = 100) -> list:
    """
    List all non-global namespaces in the program with pagination.
    """
    return safe_get("namespaces", {"offset": offset, "limit": limit})

@mcp.tool()
def list_data_items(offset: int = 0, limit: int = 100) -> list:
    """
    List defined data labels and their values with pagination.
    """
    return safe_get("data", {"offset": offset, "limit": limit})

@mcp.tool()
def search_functions_by_name(query: str, offset: int = 0, limit: int = 100) -> list:
    """
    Search for functions whose name contains the given substring.
    """
    if not query:
        return ["Error: query string is required"]
    return safe_get("searchFunctions", {"query": query, "offset": offset, "limit": limit})

@mcp.tool()
def rename_variable(function_name: str, old_name: str, new_name: str) -> str:
    """
    Rename a local variable within a function.
    """
    return safe_post("renameVariable", {
        "functionName": function_name,
        "oldName": old_name,
        "newName": new_name
    })

@mcp.tool()
def get_function_by_address(address: str) -> dict | str:
    """
    Get a function by its address.
    """
    return safe_get_json(
        "api/v1/get_function_by_address",
        "get_function_by_address",
        {"address": address},
        data_key="function",
        fallback_transform=parse_legacy_function_record)

@mcp.tool()
def get_current_address() -> str:
    """
    Get the address currently selected by the user.
    """
    return "\n".join(safe_get("get_current_address"))

@mcp.tool()
def get_current_function() -> str:
    """
    Get the function currently selected by the user.
    """
    return "\n".join(safe_get("get_current_function"))

@mcp.tool()
def list_functions() -> list:
    """
    List all functions in the database.
    """
    return safe_get_json(
        "api/v1/list_functions",
        "list_functions",
        data_key="functions",
        fallback_transform=parse_legacy_function_list)

@mcp.tool()
def decompile_function_by_address(address: str) -> str:
    """
    Decompile a function at the given address.
    """
    return safe_get_json(
        "api/v1/decompile_function",
        "decompile_function",
        {"address": address},
        data_key="decompile",
        fallback_transform=lambda lines: "\n".join(lines))

@mcp.tool()
def disassemble_function(address: str) -> list:
    """
    Get assembly code (address: instruction; comment) for a function.
    """
    return safe_get("disassemble_function", {"address": address})

@mcp.tool()
def set_decompiler_comment(address: str, comment: str) -> str:
    """
    Set a comment for a given address in the function pseudocode.
    """
    return safe_post("set_decompiler_comment", {"address": address, "comment": comment})

@mcp.tool()
def set_disassembly_comment(address: str, comment: str) -> str:
    """
    Set a comment for a given address in the function disassembly.
    """
    return safe_post("set_disassembly_comment", {"address": address, "comment": comment})

@mcp.tool()
def rename_function_by_address(function_address: str, new_name: str) -> str:
    """
    Rename a function by its address.
    """
    return safe_post("rename_function_by_address", {"function_address": function_address, "new_name": new_name})

@mcp.tool()
def set_function_prototype(function_address: str, prototype: str) -> str:
    """
    Set a function's prototype.
    """
    return safe_post("set_function_prototype", {"function_address": function_address, "prototype": prototype})

@mcp.tool()
def set_local_variable_type(function_address: str, variable_name: str, new_type: str) -> str:
    """
    Set a local variable's type.
    """
    return safe_post("set_local_variable_type", {"function_address": function_address, "variable_name": variable_name, "new_type": new_type})

@mcp.tool()
def get_xrefs_to(address: str, offset: int = 0, limit: int = 100) -> list:
    """
    Get all references to the specified address (xref to).
    
    Args:
        address: Target address in hex format (e.g. "0x1400010a0")
        offset: Pagination offset (default: 0)
        limit: Maximum number of references to return (default: 100)
        
    Returns:
        List of references to the specified address
    """
    return safe_get_json(
        "api/v1/get_xrefs_to",
        "xrefs_to",
        {"address": address, "offset": offset, "limit": limit},
        data_key="xrefs",
        fallback_transform=lambda lines: parse_legacy_xrefs_to(lines, address))

@mcp.tool()
def get_xrefs_from(address: str, offset: int = 0, limit: int = 100) -> list:
    """
    Get all references from the specified address (xref from).
    
    Args:
        address: Source address in hex format (e.g. "0x1400010a0")
        offset: Pagination offset (default: 0)
        limit: Maximum number of references to return (default: 100)
        
    Returns:
        List of references from the specified address
    """
    return safe_get_json(
        "api/v1/get_xrefs_from",
        "xrefs_from",
        {"address": address, "offset": offset, "limit": limit},
        data_key="xrefs",
        fallback_transform=lambda lines: parse_legacy_xrefs_from(lines, address))

@mcp.tool()
def get_function_xrefs(name: str, offset: int = 0, limit: int = 100) -> list:
    """
    Get all references to the specified function by name.
    
    Args:
        name: Function name to search for
        offset: Pagination offset (default: 0)
        limit: Maximum number of references to return (default: 100)
        
    Returns:
        List of references to the specified function
    """
    return safe_get("function_xrefs", {"name": name, "offset": offset, "limit": limit})

@mcp.tool()
def list_strings(offset: int = 0, limit: int = 2000, filter: str = None) -> list:
    """
    List all defined strings in the program with their addresses.
    
    Args:
        offset: Pagination offset (default: 0)
        limit: Maximum number of strings to return (default: 2000)
        filter: Optional filter to match within string content
        
    Returns:
        List of strings with their addresses
    """
    params = {"offset": offset, "limit": limit}
    if filter:
        params["filter"] = filter
    return safe_get("strings", params)

def main():
    parser = argparse.ArgumentParser(description="MCP server for Ghidra")
    parser.add_argument("--ghidra-server", type=str, default=DEFAULT_GHIDRA_SERVER,
                        help=f"Ghidra server URL, default: {DEFAULT_GHIDRA_SERVER}")
    parser.add_argument("--mcp-host", type=str, default="127.0.0.1",
                        help="Host to run MCP server on for HTTP transports, default: 127.0.0.1")
    parser.add_argument("--mcp-port", type=int,
                        help="Port to run MCP server on for HTTP transports, default: 8081")
    parser.add_argument("--transport", type=str, default="stdio", choices=["stdio", "sse", "streamable-http"],
                        help="Transport protocol for MCP, default: stdio")
    args = parser.parse_args()
    
    # Use the global variable to ensure it's properly updated
    global ghidra_server_url
    if args.ghidra_server:
        ghidra_server_url = args.ghidra_server
    
    if args.transport in ("sse", "streamable-http"):
        try:
            # Set up logging
            log_level = logging.INFO
            logging.basicConfig(level=log_level)
            logging.getLogger().setLevel(log_level)

            # Configure MCP settings
            mcp.settings.log_level = "INFO"
            if args.mcp_host:
                mcp.settings.host = args.mcp_host
            else:
                mcp.settings.host = "127.0.0.1"

            if args.mcp_port:
                mcp.settings.port = args.mcp_port
            else:
                mcp.settings.port = 8081

            logger.info(f"Connecting to Ghidra server at {ghidra_server_url}")
            endpoint = "/sse" if args.transport == "sse" else "/mcp"
            logger.info(f"Starting MCP server on http://{mcp.settings.host}:{mcp.settings.port}{endpoint}")
            logger.info(f"Using transport: {args.transport}")

            mcp.run(transport=args.transport)
        except KeyboardInterrupt:
            logger.info("Server stopped by user")
    else:
        mcp.run()
        
if __name__ == "__main__":
    main()
