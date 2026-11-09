"""Tests for hermes-droid plugin registration and tools."""

import json
from unittest.mock import MagicMock, patch

from plugin import _TOOLS, register
from plugin.bridge import AndroidBridge
from plugin.handlers import handle_adb_shell, handle_device_info, handle_read_sms, handle_send_sms


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


def test_plugin_registration():
    ctx = MockHermesContext()
    register(ctx)

    assert len(ctx.tools) == len(_TOOLS)
    assert "android_device_info" in ctx.tools
    assert "android_read_sms" in ctx.tools
    assert "android_send_sms" in ctx.tools
    assert "android_get_notifications" in ctx.tools
    assert "android_adb_shell" in ctx.tools
    assert "on_session_end" in ctx.hooks


def test_handle_device_info():
    with patch("plugin.handlers.get_bridge") as mock_get_bridge:
        mock_bridge = MagicMock(spec=AndroidBridge)
        mock_bridge.send_request.return_value = {
            "success": True,
            "data": {
                "battery": 88,
                "charging": True,
                "model": "Pixel 8 Pro",
                "android_version": "14",
            },
        }
        mock_get_bridge.return_value = mock_bridge

        result = handle_device_info({})
        data = json.loads(result)
        assert data["battery"] == 88
        assert data["model"] == "Pixel 8 Pro"


def test_handle_read_sms():
    with patch("plugin.handlers.get_bridge") as mock_get_bridge:
        mock_bridge = MagicMock(spec=AndroidBridge)
        mock_bridge.send_request.return_value = {
            "success": True,
            "data": [
                {"sender": "BankAlert", "body": "Your OTP code is 492811", "timestamp": 1730800000}
            ],
        }
        mock_get_bridge.return_value = mock_bridge

        result = handle_read_sms({"limit": 1})
        data = json.loads(result)
        assert len(data) == 1
        assert "492811" in data[0]["body"]


def test_handle_send_sms():
    with patch("plugin.handlers.get_bridge") as mock_get_bridge:
        mock_bridge = MagicMock(spec=AndroidBridge)
        mock_bridge.send_request.return_value = {"success": True}
        mock_get_bridge.return_value = mock_bridge

        result = handle_send_sms({"recipient": "+1234567890", "message": "Hello from Hermes"})
        data = json.loads(result)
        assert data["status"] == "sent"
        assert data["recipient"] == "+1234567890"


def test_handle_adb_shell():
    with patch("plugin.handlers.get_bridge") as mock_get_bridge:
        mock_bridge = MagicMock(spec=AndroidBridge)
        mock_bridge.send_request.return_value = {
            "success": True,
            "data": {"stdout": "test_output\n", "exit_code": 0},
        }
        mock_get_bridge.return_value = mock_bridge

        result = handle_adb_shell({"command": "echo test_output"})
        data = json.loads(result)
        assert "test_output" in data["stdout"]
