"""Standard library unittest suite for hermes-droid plugin."""

import json
import unittest
from unittest.mock import MagicMock, patch

from plugin import _TOOLS, register
from plugin.bridge import AndroidBridge
from plugin.handlers import (
    handle_adb_shell,
    handle_contacts_search,
    handle_device_info,
    handle_device_logs,
    handle_radio_silence,
    handle_read_sms,
    handle_send_sms,
)


class MockHermesContext:
    def __init__(self):
        self.tools = {}
        self.hooks = {}

    def register_tool(self, name, toolset, schema, handler, emoji=None):
        self.tools[name] = {
            "toolset": toolset,
            "schema": schema,
            "handler": handler,
            "emoji": emoji,
        }

    def register_hook(self, name, handler):
        self.hooks[name] = handler


class TestHermesDroidPlugin(unittest.TestCase):
    def test_plugin_registration(self):
        ctx = MockHermesContext()
        register(ctx)

        self.assertEqual(len(ctx.tools), 18)
        self.assertIn("android_device_info", ctx.tools)
        self.assertIn("android_read_sms", ctx.tools)
        self.assertIn("android_contacts_search", ctx.tools)
        self.assertIn("android_device_logs", ctx.tools)
        self.assertIn("android_radio_silence", ctx.tools)
        self.assertIn("android_camera_snapshot", ctx.tools)
        self.assertIn("on_session_end", ctx.hooks)

    def test_handle_contacts_search(self):
        with patch("plugin.handlers.get_bridge") as mock_get_bridge:
            mock_bridge = MagicMock(spec=AndroidBridge)
            mock_bridge.send_request.return_value = {
                "success": True,
                "data": [{"name": "Ali", "number": "+989120000000"}],
            }
            mock_get_bridge.return_value = mock_bridge

            result = handle_contacts_search({"query": "Ali"})
            data = json.loads(result)
            self.assertEqual(data[0]["name"], "Ali")

    def test_handle_device_logs(self):
        with patch("plugin.handlers.get_bridge") as mock_get_bridge:
            mock_bridge = MagicMock(spec=AndroidBridge)
            mock_bridge.send_request.return_value = {
                "success": True,
                "data": {"buffer": "system", "logs": ["booting", "connected"]},
            }
            mock_get_bridge.return_value = mock_bridge

            result = handle_device_logs({"buffer": "system", "lines": 2})
            data = json.loads(result)
            self.assertEqual(data["buffer"], "system")

    def test_handle_radio_silence(self):
        with patch("plugin.handlers.get_bridge") as mock_get_bridge:
            mock_bridge = MagicMock(spec=AndroidBridge)
            mock_bridge.send_request.return_value = {"success": True, "radio_silence": True}
            mock_get_bridge.return_value = mock_bridge

            result = handle_radio_silence({"enable": True})
            data = json.loads(result)
            self.assertTrue(data["radio_silence"])


if __name__ == "__main__":
    unittest.main()
