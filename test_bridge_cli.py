import ast
import pathlib
import unittest


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


def parse_version(value):
    return tuple(int(part) for part in value.split("."))


if __name__ == "__main__":
    unittest.main()
