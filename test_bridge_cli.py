import ast
import importlib.util
import pathlib
import sys
import types
import unittest
from unittest import mock


BRIDGE = pathlib.Path(__file__).with_name("bridge_mcp_ghidra.py")
REQUIREMENTS = pathlib.Path(__file__).with_name("requirements.txt")
MIN_STREAMABLE_HTTP_MCP_VERSION = (1, 8, 0)


class BridgeCliTest(unittest.TestCase):

    def setUp(self):
        self.source = BRIDGE.read_text(encoding="utf-8")
        self.tree = ast.parse(self.source)

    def test_transport_choices_include_streamable_http(self):
        transport_choices = None

        for node in ast.walk(self.tree):
            if not isinstance(node, ast.Call):
                continue
            if not node.args or not isinstance(node.args[0], ast.Constant):
                continue
            if node.args[0].value != "--transport":
                continue
            for keyword in node.keywords:
                if keyword.arg == "choices":
                    transport_choices = ast.literal_eval(keyword.value)

        self.assertIsNotNone(transport_choices)
        self.assertIn("stdio", transport_choices)
        self.assertIn("streamable-http", transport_choices)

    def test_streamable_http_transport_is_run_directly(self):
        self.assertIn('mcp.run(transport=args.transport)', self.source)

    def test_requirements_support_streamable_http_transport(self):
        requirements = REQUIREMENTS.read_text(encoding="utf-8")

        self.assertMcpRequirementSupportsStreamableHttp(requirements)

    def test_inline_script_metadata_supports_streamable_http_transport(self):
        self.assertMcpRequirementSupportsStreamableHttp(self.source)

    def assertMcpRequirementSupportsStreamableHttp(self, text):
        for line in text.splitlines():
            stripped = line.strip().removeprefix("#").strip().strip('"').rstrip(",")
            if stripped.startswith("mcp=="):
                version = parse_version(stripped.removeprefix("mcp=="))
                self.assertGreaterEqual(version, MIN_STREAMABLE_HTTP_MCP_VERSION)
                return
            if stripped.startswith("mcp>="):
                version = parse_version(stripped.removeprefix("mcp>=").split(",", 1)[0])
                self.assertGreaterEqual(version, MIN_STREAMABLE_HTTP_MCP_VERSION)
                return

        self.fail("mcp requirement not found")

    def test_list_functions_prefers_json_endpoint(self):
        bridge = load_bridge_module()
        response = FakeResponse(
            text='{"ok":true,"data":{"functions":[{"name":"main","address":"00000600"}]}}',
            json_data={"ok": True, "data": {"functions": [{"name": "main", "address": "00000600"}]}})
        calls = []

        def fake_get(url, params=None, timeout=None):
            calls.append((url, params, timeout))
            return response

        with mock.patch.object(bridge.requests, "get", side_effect=fake_get):
            self.assertEqual([{"name": "main", "address": "00000600"}], bridge.list_functions())

        self.assertEqual(1, len(calls))
        self.assertTrue(calls[0][0].endswith("/api/v1/list_functions"))

    def test_list_functions_falls_back_to_legacy_text_endpoint(self):
        bridge = load_bridge_module()
        responses = [
            FakeResponse(ok=False, status_code=404, text="not found"),
            FakeResponse(text="main at 00000600\nhelper at 0000060a\n"),
        ]
        calls = []

        def fake_get(url, params=None, timeout=None):
            calls.append((url, params, timeout))
            return responses.pop(0)

        with mock.patch.object(bridge.requests, "get", side_effect=fake_get):
            self.assertEqual([
                {"name": "main", "address": "00000600"},
                {"name": "helper", "address": "0000060a"},
            ], bridge.list_functions())

        self.assertEqual(2, len(calls))
        self.assertTrue(calls[0][0].endswith("/api/v1/list_functions"))
        self.assertTrue(calls[1][0].endswith("/list_functions"))

    def test_get_function_by_address_prefers_json_endpoint(self):
        bridge = load_bridge_module()
        function = {
            "name": "main",
            "namespace": "Global",
            "entry": "00000600",
            "body_start": "00000600",
            "body_end": "00000609",
            "signature": "void main(void)",
        }
        response = FakeResponse(
            json_data={"ok": True, "data": {"function": function}})
        calls = []

        def fake_get(url, params=None, timeout=None):
            calls.append((url, params, timeout))
            return response

        with mock.patch.object(bridge.requests, "get", side_effect=fake_get):
            self.assertEqual(function, bridge.get_function_by_address("00000600"))

        self.assertEqual(1, len(calls))
        self.assertTrue(calls[0][0].endswith("/api/v1/get_function_by_address"))

    def test_get_function_by_address_falls_back_to_legacy_text_as_dict(self):
        bridge = load_bridge_module()
        responses = [
            FakeResponse(ok=False, status_code=404, text="not found"),
            FakeResponse(text=(
                "Function: main at 00000600\n"
                "Signature: void main(void)\n"
                "Entry: 00000600\n"
                "Body: 00000600 - 00000609")),
        ]
        calls = []

        def fake_get(url, params=None, timeout=None):
            calls.append((url, params, timeout))
            return responses.pop(0)

        with mock.patch.object(bridge.requests, "get", side_effect=fake_get):
            self.assertEqual({
                "name": "main",
                "namespace": "",
                "entry": "00000600",
                "body_start": "00000600",
                "body_end": "00000609",
                "signature": "void main(void)",
            }, bridge.get_function_by_address("00000600"))

        self.assertEqual(2, len(calls))
        self.assertTrue(calls[0][0].endswith("/api/v1/get_function_by_address"))
        self.assertTrue(calls[1][0].endswith("/get_function_by_address"))

    def test_decompile_function_by_address_prefers_json_endpoint_text(self):
        bridge = load_bridge_module()
        response = FakeResponse(
            json_data={"ok": True, "data": {
                "function": {"name": "main", "entry": "00000600"},
                "decompile": "void main(void) {\n}\n"}})
        calls = []

        def fake_get(url, params=None, timeout=None):
            calls.append((url, params, timeout))
            return response

        with mock.patch.object(bridge.requests, "get", side_effect=fake_get):
            self.assertEqual("void main(void) {\n}\n", bridge.decompile_function_by_address("00000600"))

        self.assertEqual(1, len(calls))
        self.assertTrue(calls[0][0].endswith("/api/v1/decompile_function"))

    def test_decompile_function_by_address_falls_back_to_legacy_text(self):
        bridge = load_bridge_module()
        responses = [
            FakeResponse(ok=False, status_code=404, text="not found"),
            FakeResponse(text="void main(void) {\n}\n"),
        ]
        calls = []

        def fake_get(url, params=None, timeout=None):
            calls.append((url, params, timeout))
            return responses.pop(0)

        with mock.patch.object(bridge.requests, "get", side_effect=fake_get):
            self.assertEqual("void main(void) {\n}", bridge.decompile_function_by_address("00000600"))

        self.assertEqual(2, len(calls))
        self.assertTrue(calls[0][0].endswith("/api/v1/decompile_function"))
        self.assertTrue(calls[1][0].endswith("/decompile_function"))


def parse_version(value):
    return tuple(int(part) for part in value.split("."))


class FakeFastMCP:

    def __init__(self, *args, **kwargs):
        self.settings = types.SimpleNamespace()

    def tool(self):
        return lambda function: function

    def run(self, *args, **kwargs):
        return None


class FakeResponse:

    def __init__(self, ok=True, status_code=200, text="", json_data=None):
        self.ok = ok
        self.status_code = status_code
        self.text = text
        self._json_data = json_data
        self.encoding = None

    def json(self):
        if self._json_data is None:
            raise ValueError("No JSON body configured")
        return self._json_data


def load_bridge_module():
    module_name = "bridge_mcp_ghidra_under_test"
    sys.modules.pop(module_name, None)

    mcp_module = types.ModuleType("mcp")
    server_module = types.ModuleType("mcp.server")
    fastmcp_module = types.ModuleType("mcp.server.fastmcp")
    fastmcp_module.FastMCP = FakeFastMCP

    with mock.patch.dict(sys.modules, {
        "mcp": mcp_module,
        "mcp.server": server_module,
        "mcp.server.fastmcp": fastmcp_module,
    }):
        spec = importlib.util.spec_from_file_location(module_name, BRIDGE)
        module = importlib.util.module_from_spec(spec)
        sys.modules[module_name] = module
        spec.loader.exec_module(module)
        return module


if __name__ == "__main__":
    unittest.main()
