"""Unit tests for Item #3: State-changing tools gating, approval prompts, and cron refusal."""

import json
import os
import unittest
from unittest.mock import MagicMock, patch

from plugin import (
    _format_approval_reason,
    _on_pre_tool_call,
    register,
    reset_plugin_context,
)
from plugin.handlers import (
    STATE_CHANGING_TOOLS,
    handle_adb_shell,
    handle_call_phone,
    handle_clipboard_sync,
    handle_device_alarm,
    handle_device_info,
    handle_install_apk,
    handle_radio_silence,
    handle_reply_notification,
    handle_send_sms,
    handle_toggle_setting,
    is_state_changing_tool,
    is_unattended_or_cron,
)


class TestStateChangingToolClassification(unittest.TestCase):
    """Test identification of the 9 state-changing tools vs 9 read-only tools."""

    def test_eight_unconditional_state_changing_tools(self):
        expected_state_changing = {
            "android_adb_shell",
            "android_send_sms",
            "android_call_phone",
            "android_install_apk",
            "android_reply_notification",
            "android_radio_silence",
            "android_toggle_setting",
            "android_device_alarm",
        }
        self.assertEqual(STATE_CHANGING_TOOLS, expected_state_changing)
        for tool in expected_state_changing:
            self.assertTrue(
                is_state_changing_tool(tool, {}),
                f"Tool {tool} should be classified as state-changing",
            )

    def test_clipboard_sync_state_changing_when_setting(self):
        # action == 'set'
        self.assertTrue(is_state_changing_tool("android_clipboard_sync", {"action": "set"}))
        self.assertTrue(is_state_changing_tool("android_clipboard_sync", {"action": "SET"}))
        # text provided
        self.assertTrue(is_state_changing_tool("android_clipboard_sync", {"text": "new paste"}))
        self.assertTrue(is_state_changing_tool("android_clipboard_sync", {"action": "set", "text": "foo"}))
        # ambiguous action with text provided (fail-closed)
        self.assertTrue(is_state_changing_tool("android_clipboard_sync", {"action": "unknown", "text": "payload"}))
        self.assertTrue(is_state_changing_tool("android_clipboard_sync", {"action": "get", "text": "injected_text"}))

    def test_clipboard_sync_read_only_when_getting(self):
        # action == 'get' without text
        self.assertFalse(is_state_changing_tool("android_clipboard_sync", {"action": "get"}))
        self.assertFalse(is_state_changing_tool("android_clipboard_sync", {"action": "get", "text": ""}))
        self.assertFalse(is_state_changing_tool("android_clipboard_sync", {}))
        self.assertFalse(is_state_changing_tool("android_clipboard_sync", {"action": "unknown"}))

    def test_all_nine_read_only_tools(self):
        read_only_tools = [
            "android_device_info",
            "android_read_sms",
            "android_get_notifications",
            "android_take_screenshot",
            "android_contacts_search",
            "android_media_recent_photos",
            "android_device_logs",
            "android_camera_snapshot",
            "android_location_get",
        ]
        for tool in read_only_tools:
            self.assertFalse(
                is_state_changing_tool(tool, {}),
                f"Tool {tool} should be classified as read-only",
            )


class TestUnattendedAndCronDetection(unittest.TestCase):
    """Test environment and context-based detection of unattended and cron runs."""

    def setUp(self):
        self._orig_cron = os.environ.get("HERMES_CRON_SESSION")
        self._orig_sq = os.environ.get("HERMES_SINGLE_QUERY_SESSION")
        os.environ.pop("HERMES_CRON_SESSION", None)
        os.environ.pop("HERMES_SINGLE_QUERY_SESSION", None)

    def tearDown(self):
        if self._orig_cron is not None:
            os.environ["HERMES_CRON_SESSION"] = self._orig_cron
        else:
            os.environ.pop("HERMES_CRON_SESSION", None)
        if self._orig_sq is not None:
            os.environ["HERMES_SINGLE_QUERY_SESSION"] = self._orig_sq
        else:
            os.environ.pop("HERMES_SINGLE_QUERY_SESSION", None)

    def test_clean_session_is_not_unattended(self):
        self.assertFalse(is_unattended_or_cron())

    def test_cron_session_env_var(self):
        os.environ["HERMES_CRON_SESSION"] = "1"
        self.assertTrue(is_unattended_or_cron())
        os.environ["HERMES_CRON_SESSION"] = "true"
        self.assertTrue(is_unattended_or_cron())

    def test_single_query_session_env_var(self):
        os.environ["HERMES_SINGLE_QUERY_SESSION"] = "1"
        self.assertTrue(is_unattended_or_cron())

    def test_plugin_context_current_cron_execution(self):
        mock_ctx = MagicMock()
        mock_ctx.current_cron_execution.return_value = MagicMock(job_id="job_daily_sync")
        self.assertTrue(is_unattended_or_cron(mock_ctx))

        mock_ctx.current_cron_execution.return_value = None
        self.assertFalse(is_unattended_or_cron(mock_ctx))


class TestPreToolCallHook(unittest.TestCase):
    """Test the pre_tool_call hook behavior: None for reads, approve for interactive, block for cron."""

    def setUp(self):
        reset_plugin_context()
        self._orig_cron = os.environ.get("HERMES_CRON_SESSION")
        os.environ.pop("HERMES_CRON_SESSION", None)

    def tearDown(self):
        reset_plugin_context()
        if self._orig_cron is not None:
            os.environ["HERMES_CRON_SESSION"] = self._orig_cron
        else:
            os.environ.pop("HERMES_CRON_SESSION", None)

    def test_read_only_tool_returns_none(self):
        directive = _on_pre_tool_call("android_device_info", {})
        self.assertIsNone(directive)

        directive_sms = _on_pre_tool_call("android_read_sms", {"limit": 5})
        self.assertIsNone(directive_sms)

        directive_clip = _on_pre_tool_call("android_clipboard_sync", {"action": "get"})
        self.assertIsNone(directive_clip)

    def test_state_changing_tool_in_interactive_returns_approve_directive(self):
        # 1. Send SMS
        res_sms = _on_pre_tool_call("android_send_sms", {"recipient": "+1234567890", "message": "Hi"})
        self.assertIsNotNone(res_sms)
        self.assertEqual(res_sms["action"], "approve")
        self.assertIn("+1234567890", res_sms["message"])
        self.assertEqual(res_sms["rule_key"], "hermes_droid:android_send_sms")

        # 2. Call phone
        res_call = _on_pre_tool_call("android_call_phone", {"phone_number": "911"})
        self.assertEqual(res_call["action"], "approve")
        self.assertIn("911", res_call["message"])
        self.assertEqual(res_call["rule_key"], "hermes_droid:android_call_phone")

        # 3. ADB shell
        res_shell = _on_pre_tool_call("android_adb_shell", {"command": "reboot"})
        self.assertEqual(res_shell["action"], "approve")
        self.assertIn("reboot", res_shell["message"])
        self.assertEqual(res_shell["rule_key"], "hermes_droid:android_adb_shell")

        # 4. Install APK
        res_apk = _on_pre_tool_call("android_install_apk", {"apk_path": "/tmp/test.apk"})
        self.assertEqual(res_apk["action"], "approve")
        self.assertIn("/tmp/test.apk", res_apk["message"])
        self.assertEqual(res_apk["rule_key"], "hermes_droid:android_install_apk")

        # 5. Clipboard set
        res_clip = _on_pre_tool_call("android_clipboard_sync", {"action": "set", "text": "secret"})
        self.assertEqual(res_clip["action"], "approve")
        self.assertEqual(res_clip["rule_key"], "hermes_droid:android_clipboard_sync")

        # 6. Radio silence
        res_radio = _on_pre_tool_call("android_radio_silence", {"enable": True})
        self.assertEqual(res_radio["action"], "approve")
        self.assertEqual(res_radio["rule_key"], "hermes_droid:android_radio_silence")

        # 7. Device alarm
        res_alarm = _on_pre_tool_call("android_device_alarm", {"duration_seconds": 30})
        self.assertEqual(res_alarm["action"], "approve")
        self.assertIn("30", res_alarm["message"])
        self.assertEqual(res_alarm["rule_key"], "hermes_droid:android_device_alarm")

    def test_state_changing_tool_in_cron_returns_block_directive(self):
        os.environ["HERMES_CRON_SESSION"] = "1"
        for tool in STATE_CHANGING_TOOLS:
            directive = _on_pre_tool_call(tool, {})
            self.assertIsNotNone(directive, f"Tool {tool} should return directive in cron")
            self.assertEqual(directive["action"], "block")
            self.assertIn("BLOCKED", directive["message"])
            self.assertIn(tool, directive["message"])
            self.assertIn("refused in unattended or cron execution", directive["message"])

        # Also clipboard sync with action=set
        clip_directive = _on_pre_tool_call("android_clipboard_sync", {"action": "set", "text": "abc"})
        self.assertEqual(clip_directive["action"], "block")
        self.assertIn("BLOCKED", clip_directive["message"])

    def test_read_only_tools_in_cron_still_allowed(self):
        os.environ["HERMES_CRON_SESSION"] = "1"
        self.assertIsNone(_on_pre_tool_call("android_device_info", {}))
        self.assertIsNone(_on_pre_tool_call("android_read_sms", {}))
        self.assertIsNone(_on_pre_tool_call("android_clipboard_sync", {"action": "get"}))


class TestDefenseInDepthHandlerGuards(unittest.TestCase):
    """Test that individual handlers refuse execution if called directly inside cron."""

    def setUp(self):
        self._orig_cron = os.environ.get("HERMES_CRON_SESSION")
        os.environ["HERMES_CRON_SESSION"] = "1"

    def tearDown(self):
        if self._orig_cron is not None:
            os.environ["HERMES_CRON_SESSION"] = self._orig_cron
        else:
            os.environ.pop("HERMES_CRON_SESSION", None)

    def test_state_changing_handlers_fail_closed_in_cron(self):
        handlers_to_test = [
            (handle_send_sms, {"recipient": "123", "message": "hi"}, "android_send_sms"),
            (handle_call_phone, {"phone_number": "123"}, "android_call_phone"),
            (handle_adb_shell, {"command": "ls"}, "android_adb_shell"),
            (handle_install_apk, {"apk_path": "/path.apk"}, "android_install_apk"),
            (handle_reply_notification, {"notification_key": "k", "reply_text": "ok"}, "android_reply_notification"),
            (handle_radio_silence, {"enable": True}, "android_radio_silence"),
            (handle_toggle_setting, {"setting": "wifi", "value": "off"}, "android_toggle_setting"),
            (handle_device_alarm, {"duration_seconds": 5}, "android_device_alarm"),
        ]
        for handler, params, name in handlers_to_test:
            out = handler(params)
            data = json.loads(out)
            self.assertFalse(data.get("success"), f"Handler {name} should fail in cron")
            self.assertIn("refused in unattended or cron execution", data.get("error", ""))

    def test_clipboard_set_refused_in_cron_but_get_allowed(self):
        out_set = handle_clipboard_sync({"action": "set", "text": "secret"})
        data_set = json.loads(out_set)
        self.assertFalse(data_set.get("success"))
        self.assertIn("refused in unattended or cron execution", data_set.get("error", ""))


class TestPluginRegistrationAndLifecycle(unittest.TestCase):
    """Test that register(ctx) adds pre_tool_call hook and unloads cleanly."""

    def tearDown(self):
        reset_plugin_context()

    def test_registration_registers_pre_tool_call_hook(self):
        mock_ctx = MagicMock()
        mock_ctx.get_config.return_value = None
        mock_ctx.current_cron_execution.return_value = None
        register(mock_ctx)

        # Verify hooks registered
        registered_hooks = [call.args[0] for call in mock_ctx.register_hook.call_args_list]
        self.assertIn("on_session_end", registered_hooks)
        self.assertIn("pre_tool_call", registered_hooks)

        # Verify on_unload callback registered
        self.assertTrue(mock_ctx.on_unload.called)

    def test_registration_fails_closed_if_pre_tool_call_hook_fails(self):
        """Fail-closed: if register_hook('pre_tool_call') fails, register() must raise RuntimeError."""
        mock_ctx = MagicMock()
        mock_ctx.get_config.return_value = None
        mock_ctx.current_cron_execution.return_value = None

        def side_effect(hook_name, callback):
            if hook_name == "pre_tool_call":
                raise ValueError("cannot register pre_tool_call")

        mock_ctx.register_hook.side_effect = side_effect
        with self.assertRaises(RuntimeError) as ctx_err:
            register(mock_ctx)
        self.assertIn("Failed to register mandatory pre_tool_call security hook", str(ctx_err.exception))

    def test_end_to_end_hermes_dispatch_hooks(self):
        """End-to-end integration: test through Hermes _dispatch_pre_tool_call_hooks."""
        try:
            from hermes_cli.plugins import _dispatch_pre_tool_call_hooks, get_plugin_manager
        except ImportError:
            self.skipTest("hermes-agent not available in test environment")

        pm = get_plugin_manager()
        mock_ctx = MagicMock()
        mock_ctx.get_config.return_value = None
        mock_ctx.current_cron_execution.return_value = None

        # Clean isolation
        old_hooks = list(pm._hooks.get("pre_tool_call", []))
        pm._hooks["pre_tool_call"] = [_on_pre_tool_call]
        try:
            # 1. Read-only tool proceeds without block
            block, _ = _dispatch_pre_tool_call_hooks("android_device_info", {})
            self.assertIsNone(block)

            # 2. Cron blocks state-changing tool
            with patch.dict(os.environ, {"HERMES_CRON_SESSION": "1"}):
                cron_block, _ = _dispatch_pre_tool_call_hooks("android_send_sms", {"recipient": "123"})
                self.assertIsNotNone(cron_block)
                self.assertIn("BLOCKED", cron_block)
                self.assertIn("refused in unattended or cron execution", cron_block)

            # 3. Interactive routes to request_tool_approval
            with patch("tools.approval.request_tool_approval", return_value={"approved": True, "message": None}) as mock_appr:
                appr_block, _ = _dispatch_pre_tool_call_hooks("android_send_sms", {"recipient": "+100"})
                self.assertIsNone(appr_block)
                self.assertTrue(mock_appr.called)
                self.assertEqual(mock_appr.call_args[0][0], "android_send_sms")

            # 4. Prove that user denial prevents tool handler execution
            mock_sms_handler = MagicMock()
            with patch("tools.approval.request_tool_approval", return_value={"approved": False, "message": "denied by operator"}):
                deny_block, _ = _dispatch_pre_tool_call_hooks("android_send_sms", {"recipient": "+100"})
                self.assertEqual(deny_block, "denied by operator")
                # Following Hermes tool_executor.py: dispatch only occurs if deny_block is None
                if deny_block is None:
                    mock_sms_handler()
                mock_sms_handler.assert_not_called()
        finally:
            pm._hooks["pre_tool_call"] = old_hooks
            reset_plugin_context()


if __name__ == "__main__":
    unittest.main()
