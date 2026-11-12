# -*- coding: utf-8 -*-
"""Unit tests for Item #4 (Path traversal & file containment),

Item #6 (Subprocess import & bounded paho-mqtt), and Item #7 (Contacts SQL/query injection prevention).
"""

from __future__ import annotations

import base64
import json
import os
import shutil
import sys
import tempfile
import unittest
from pathlib import Path
from unittest.mock import MagicMock, patch

from plugin.bridge import configure_bridge, get_bridge, get_plugin_data_dir
from plugin.handlers import (
    handle_camera_snapshot,
    handle_contacts_search,
    handle_install_apk,
    handle_take_screenshot,
)


class TestApkContainmentAndSecurity(unittest.TestCase):
    """Test suite verifying Item #4: strict .apk validation and path containment."""

    def setUp(self):
        self.temp_dir = tempfile.mkdtemp(prefix="hermes_droid_apk_test_")
        self.orig_env = os.environ.get("HERMES_PLUGIN_DATA")
        os.environ["HERMES_PLUGIN_DATA"] = self.temp_dir
        self.allowed_apk_dir = os.path.join(self.temp_dir, "custom_apks")
        os.makedirs(self.allowed_apk_dir, exist_ok=True)
        configure_bridge(
            broker_host="127.0.0.1",
            broker_port=1883,
            device_id="test_device_001",
            allowed_apk_dir=self.allowed_apk_dir,
        )

    def tearDown(self):
        configure_bridge()
        if self.orig_env is not None:
            os.environ["HERMES_PLUGIN_DATA"] = self.orig_env
        else:
            os.environ.pop("HERMES_PLUGIN_DATA", None)
        shutil.rmtree(self.temp_dir, ignore_errors=True)

    def test_install_apk_missing_path(self):
        """Missing apk_path must return an error immediately."""
        res_str = handle_install_apk({})
        res = json.loads(res_str)
        self.assertFalse(res.get("success"))
        self.assertIn("apk_path is required", res.get("error", ""))

    def test_install_apk_remote_url_delegates(self):
        """Remote URLs (http/https) should delegate to MQTT bridge install_apk command."""
        bridge = get_bridge()
        with patch.object(bridge, "send_command", return_value={"success": True, "task_id": "job_123"}) as mock_cmd:
            res_str = handle_install_apk({
                "apk_path": "https://example.com/app-release.apk",
                "sha256": "fake_sha256",
            })
            res = json.loads(res_str)
            self.assertTrue(res.get("success"))
            mock_cmd.assert_called_once_with(
                "install_apk",
                {"url": "https://example.com/app-release.apk", "sha256": "fake_sha256"},
                timeout=60.0,
            )

    def test_install_apk_rejects_non_apk_extension(self):
        """Non-.apk files (e.g. /etc/passwd, malicious.sh) must be rejected fail-closed."""
        bad_files = [
            "/etc/passwd",
            os.path.join(self.allowed_apk_dir, "malicious.sh"),
            os.path.join(self.allowed_apk_dir, "payload.exe"),
            os.path.join(self.allowed_apk_dir, "libnative.so"),
            os.path.join(self.allowed_apk_dir, "archive.zip"),
        ]
        for bad_path in bad_files:
            res_str = handle_install_apk({"apk_path": bad_path})
            res = json.loads(res_str)
            self.assertFalse(res.get("success"))
            self.assertIn("must have a '.apk' extension", res.get("error", ""))

    def test_install_apk_rejects_traversal_outside_allowed_dir(self):
        """Path traversal attempting to escape allowed directory must be rejected."""
        # Create an apk file outside the allowed directory
        outside_apk = os.path.join(self.temp_dir, "outside_secret.apk")
        with open(outside_apk, "wb") as f:
            f.write(b"PK\x03\x04fakeapkdata")

        traversal_attempts = [
            f"{self.allowed_apk_dir}/../outside_secret.apk",
            outside_apk,
            "/tmp/outside_random.apk",
            f"{self.allowed_apk_dir}/../../../../etc/fake.apk",
        ]

        for attempt in traversal_attempts:
            res_str = handle_install_apk({"apk_path": attempt})
            res = json.loads(res_str)
            self.assertFalse(res.get("success"))
            self.assertIn("outside the allowed directory", res.get("error", ""))

    def test_install_apk_rejects_symlink_pointing_outside(self):
        """Symlinks inside the allowed directory pointing outside must be rejected."""
        outside_target = os.path.join(self.temp_dir, "target.apk")
        with open(outside_target, "wb") as f:
            f.write(b"PK\x03\x04evilapk")

        symlink_apk = os.path.join(self.allowed_apk_dir, "symlink_evil.apk")
        os.symlink(outside_target, symlink_apk)

        res_str = handle_install_apk({"apk_path": symlink_apk})
        res = json.loads(res_str)
        self.assertFalse(res.get("success"))
        self.assertIn("outside the allowed directory", res.get("error", ""))

    def test_install_apk_file_not_found_inside_allowed_dir(self):
        """A non-existent .apk inside the allowed directory returns not found."""
        non_existent = os.path.join(self.allowed_apk_dir, "missing.apk")
        res_str = handle_install_apk({"apk_path": non_existent})
        res = json.loads(res_str)
        self.assertFalse(res.get("success"))
        self.assertIn("Local APK file not found", res.get("error", ""))

    def test_install_apk_valid_file_in_allowed_dir_installs_via_mqtt(self):
        """A valid .apk file within the allowed directory is read, hashed, and published."""
        apk_content = b"PK\x03\x04android_package_test_payload"
        valid_apk = os.path.join(self.allowed_apk_dir, "good_app.apk")
        with open(valid_apk, "wb") as f:
            f.write(apk_content)

        bridge = get_bridge()
        with patch.object(bridge, "send_command", return_value={"success": True, "installed": True}) as mock_cmd:
            res_str = handle_install_apk({"apk_path": valid_apk, "reinstall": True})
            res = json.loads(res_str)
            self.assertTrue(res.get("success"))
            mock_cmd.assert_called_once()
            call_args = mock_cmd.call_args[0]
            self.assertEqual(call_args[0], "install_apk")
            payload = call_args[1]
            self.assertEqual(payload["apk_b64"], base64.b64encode(apk_content).decode("ascii"))
            self.assertTrue(payload["reinstall"])


class TestScreenshotAndCameraContainment(unittest.TestCase):
    """Test suite verifying Item #4: media downloads confined strictly to plugin data directory."""

    def setUp(self):
        self.temp_dir = tempfile.mkdtemp(prefix="hermes_droid_media_test_")
        self.orig_env = os.environ.get("HERMES_PLUGIN_DATA")
        os.environ["HERMES_PLUGIN_DATA"] = self.temp_dir
        configure_bridge(broker_host="127.0.0.1", broker_port=1883, device_id="test_device_002")
        self.media_dir = os.path.realpath(os.path.join(self.temp_dir, "media"))

    def tearDown(self):
        configure_bridge()
        if self.orig_env is not None:
            os.environ["HERMES_PLUGIN_DATA"] = self.orig_env
        else:
            os.environ.pop("HERMES_PLUGIN_DATA", None)
        shutil.rmtree(self.temp_dir, ignore_errors=True)

    def test_screenshot_default_path_confined_to_media_dir(self):
        """Taking screenshot without destination_path must save strictly inside plugin_data_dir/media/."""
        fake_b64 = base64.b64encode(b"\x89PNG\r\n\x1a\nfakeimage").decode("ascii")
        bridge = get_bridge()
        with patch.object(bridge, "send_request", return_value={"success": True, "image_base64": fake_b64}):
            res_str = handle_take_screenshot({})
            res = json.loads(res_str)
            self.assertEqual(res.get("status"), "saved")
            saved_path = res.get("path")
            self.assertTrue(os.path.exists(saved_path))
            self.assertEqual(os.path.commonpath([saved_path, self.media_dir]), self.media_dir)
            self.assertTrue(saved_path.endswith(".png"))

    def test_screenshot_path_traversal_sanitized_to_media_dir(self):
        """Model-supplied traversal paths like /etc/cron.d/hack.png are stripped to basename in media dir."""
        fake_b64 = base64.b64encode(b"\x89PNG\r\n\x1a\nscreenshot").decode("ascii")
        bridge = get_bridge()
        with patch.object(bridge, "send_request", return_value={"success": True, "image_base64": fake_b64}):
            traversal_dest = "../../../../etc/cron.d/exploit.png"
            res_str = handle_take_screenshot({"destination_path": traversal_dest})
            res = json.loads(res_str)
            self.assertEqual(res.get("status"), "saved")
            saved_path = res.get("path")
            # Must NOT be written to /etc/cron.d/
            self.assertEqual(saved_path, os.path.join(self.media_dir, "exploit.png"))
            self.assertTrue(os.path.exists(saved_path))
            self.assertEqual(os.path.commonpath([saved_path, self.media_dir]), self.media_dir)

    def test_screenshot_extension_sanitization(self):
        """Model destination_path without .png extension is automatically normalized."""
        fake_b64 = base64.b64encode(b"\x89PNG\r\n\x1a\nscreenshot").decode("ascii")
        bridge = get_bridge()
        with patch.object(bridge, "send_request", return_value={"success": True, "image_base64": fake_b64}):
            res_str = handle_take_screenshot({"destination_path": "screen_dump"})
            res = json.loads(res_str)
            saved_path = res.get("path")
            self.assertTrue(saved_path.endswith("screen_dump.png"))
            self.assertEqual(os.path.commonpath([saved_path, self.media_dir]), self.media_dir)

    def test_camera_snapshot_default_path_confined_to_media_dir(self):
        """Taking camera snapshot without destination_path must save inside plugin_data_dir/media/."""
        fake_b64 = base64.b64encode(b"\xff\xd8\xff\xe0fakejpeg").decode("ascii")
        bridge = get_bridge()
        with patch.object(bridge, "send_request", return_value={"success": True, "image_base64": fake_b64}):
            res_str = handle_camera_snapshot({"camera": "back"})
            res = json.loads(res_str)
            self.assertEqual(res.get("status"), "captured")
            saved_path = res.get("path")
            self.assertTrue(os.path.exists(saved_path))
            self.assertEqual(os.path.commonpath([saved_path, self.media_dir]), self.media_dir)
            self.assertTrue(saved_path.endswith(".jpg"))

    def test_camera_snapshot_path_traversal_sanitized_to_media_dir(self):
        """Model-supplied camera destination_path traversal is stripped to basename in media dir."""
        fake_b64 = base64.b64encode(b"\xff\xd8\xff\xe0camera").decode("ascii")
        bridge = get_bridge()
        with patch.object(bridge, "send_request", return_value={"success": True, "image_base64": fake_b64}):
            traversal_dest = "/var/www/html/shell.jpg"
            res_str = handle_camera_snapshot({"destination_path": traversal_dest, "camera": "front"})
            res = json.loads(res_str)
            self.assertEqual(res.get("status"), "captured")
            saved_path = res.get("path")
            self.assertEqual(saved_path, os.path.join(self.media_dir, "shell.jpg"))
            self.assertTrue(os.path.exists(saved_path))
            self.assertEqual(os.path.commonpath([saved_path, self.media_dir]), self.media_dir)


class TestContactsSearchInjectionPrevention(unittest.TestCase):
    """Test suite verifying Item #7: SQL and query injection prevention in contacts search."""

    def setUp(self):
        self.temp_dir = tempfile.mkdtemp(prefix="hermes_droid_contacts_test_")
        self.orig_env = os.environ.get("HERMES_PLUGIN_DATA")
        os.environ["HERMES_PLUGIN_DATA"] = self.temp_dir
        configure_bridge(broker_host="127.0.0.1", broker_port=1883, device_id="test_device_003")

    def tearDown(self):
        configure_bridge()
        if self.orig_env is not None:
            os.environ["HERMES_PLUGIN_DATA"] = self.orig_env
        else:
            os.environ.pop("HERMES_PLUGIN_DATA", None)
        shutil.rmtree(self.temp_dir, ignore_errors=True)

    def test_contacts_search_rejects_single_quotes(self):
        """Single quotes in contacts search query must be rejected fail-closed."""
        payloads = [
            "Robert'; DROP TABLE contacts; --",
            "O'Connor",
            "test' OR '1'='1",
        ]
        for query in payloads:
            res_str = handle_contacts_search({"query": query})
            res = json.loads(res_str)
            self.assertFalse(res.get("success"))
            self.assertIn("forbidden in contacts search", res.get("error", ""))

    def test_contacts_search_rejects_double_quotes_and_semicolons(self):
        """Double quotes and semicolons must be rejected."""
        payloads = [
            'test" OR 1=1',
            "John; ls -la",
            "admin; SELECT * FROM users",
        ]
        for query in payloads:
            res_str = handle_contacts_search({"query": query})
            res = json.loads(res_str)
            self.assertFalse(res.get("success"))
            self.assertIn("forbidden in contacts search", res.get("error", ""))

    def test_contacts_search_rejects_sql_comment_markers(self):
        """SQL comment characters (-- and /* */) must be rejected."""
        payloads = [
            "Alice -- comment",
            "Bob /* in-line comment */",
            "Charlie */ close comment",
        ]
        for query in payloads:
            res_str = handle_contacts_search({"query": query})
            res = json.loads(res_str)
            self.assertFalse(res.get("success"))
            self.assertIn("forbidden in contacts search", res.get("error", ""))

    def test_contacts_search_rejects_sql_statement_keywords(self):
        """SQL statement keywords (UNION, SELECT, DROP, DELETE) must be rejected case-insensitively."""
        payloads = [
            "Alice UNION SELECT name FROM users",
            "Bob union select password",
            "Charlie DROP table",
            "David delete from contacts",
            "Eve UPDATE users SET role=admin",
        ]
        for query in payloads:
            res_str = handle_contacts_search({"query": query})
            res = json.loads(res_str)
            self.assertFalse(res.get("success"))
            self.assertIn("SQL statement keywords are forbidden", res.get("error", ""))

    def test_contacts_search_safe_query_success_over_mqtt(self):
        """Normal safe search query passes through to MQTT bridge and returns data."""
        bridge = get_bridge()
        mock_contacts = [
            {"name": "Alice Smith", "phone": "+15551234567"},
            {"name": "Alice Jones", "phone": "+15557654321"},
        ]
        with patch.object(bridge, "send_request", return_value={"success": True, "data": mock_contacts}) as mock_send:
            res_str = handle_contacts_search({"query": "Alice", "limit": 5})
            res = json.loads(res_str)
            self.assertEqual(len(res), 2)
            mock_send.assert_called_once_with("api/v1/contacts/search", {"query": "Alice", "limit": 5})

    def test_contacts_search_adb_fallback_uses_safe_query(self):
        """When MQTT request fails, ADB content query fallback executes with safe query."""
        bridge = get_bridge()
        with patch.object(bridge, "send_request", return_value={"success": False, "error": "device offline"}):
            with patch.object(bridge, "exec_adb_command", return_value={"success": True, "stdout": "Row 0: Alice\n"}) as mock_adb:
                res_str = handle_contacts_search({"query": "Alice"})
                res = json.loads(res_str)
                self.assertEqual(res.get("source"), "adb_contacts")
                mock_adb.assert_called_once()
                cmd = mock_adb.call_args[0][0]
                self.assertIn("--where", cmd)
                where_idx = cmd.index("--where")
                self.assertEqual(cmd[where_idx + 1], "display_name LIKE '%Alice%'")

    def test_contacts_search_invalid_limit_handled_gracefully(self):
        """Non-integer limit is coerced to default 10 without exception."""
        bridge = get_bridge()
        with patch.object(bridge, "send_request", return_value={"success": True, "data": []}) as mock_send:
            res_str = handle_contacts_search({"query": "Dave", "limit": "invalid_number"})
            res = json.loads(res_str)
            self.assertEqual(res, [])
            mock_send.assert_called_once_with("api/v1/contacts/search", {"query": "Dave", "limit": 10})


class TestDependenciesAndSubprocessImport(unittest.TestCase):
    """Test suite verifying Item #6: import subprocess in handlers.py & bounded paho-mqtt in pyproject.toml."""

    def test_subprocess_imported_in_handlers(self):
        """handlers.py must have subprocess imported at module level."""
        import plugin.handlers as handlers
        self.assertTrue(hasattr(handlers, "subprocess"))
        self.assertIsNotNone(handlers.subprocess)

    def test_pyproject_toml_dependencies(self):
        """pyproject.toml must declare bounded paho-mqtt and omit requests/urllib3."""
        pyproject_path = Path(__file__).resolve().parent.parent / "pyproject.toml"
        self.assertTrue(pyproject_path.exists())
        content = pyproject_path.read_text(encoding="utf-8")

        self.assertIn("paho-mqtt>=2.0.0,<3.0.0", content)
        self.assertNotIn("requests>=", content)
        self.assertNotIn("urllib3>=", content)


class TestAdbEnvironmentSanitization(unittest.TestCase):
    """Test suite verifying minimal environment isolation for ADB subprocesses."""

    def test_clean_adb_env_excludes_sensitive_keys(self):
        """Sensitive process environment variables must NEVER leak into child ADB env."""
        from plugin.bridge import get_clean_adb_env

        sensitive_sample = {
            "OPENAI_API_KEY": "sk-proj-secret-12345",
            "ANTHROPIC_API_KEY": "sk-ant-secret-67890",
            "HERMES_AGENT_TOKEN": "token-xyz-secret",
            "AWS_SECRET_ACCESS_KEY": "aws-secret-test",
            "DATABASE_URL": "postgres://user:pass@localhost:5432/db",
        }
        with patch.dict(os.environ, sensitive_sample, clear=False):
            clean_env = get_clean_adb_env()
            for key in sensitive_sample:
                self.assertNotIn(key, clean_env, f"Sensitive key {key} leaked into child ADB env!")

    def test_clean_adb_env_includes_essential_keys_when_set(self):
        """Allowed ADB variables (PATH, ANDROID_HOME, etc.) are safely preserved when present."""
        from plugin.bridge import get_clean_adb_env

        safe_sample = {
            "PATH": "/usr/bin:/bin",
            "ANDROID_HOME": "/opt/android-sdk",
            "ANDROID_SDK_ROOT": "/opt/android-sdk",
            "TMPDIR": "/tmp/custom_tmp",
            "ADB_VENDOR_KEYS": "/home/user/.android/adbkey",
        }
        with patch.dict(os.environ, safe_sample, clear=False):
            clean_env = get_clean_adb_env()
            for key, val in safe_sample.items():
                self.assertEqual(clean_env.get(key), val)

    def test_bridge_exec_adb_command_passes_clean_env(self):
        """bridge.exec_adb_command must invoke subprocess.run with explicit clean env."""
        from plugin.bridge import AndroidBridge

        bridge = AndroidBridge(broker_host="127.0.0.1", broker_port=1883)
        bridge.adb_bin = "/usr/bin/adb"
        with patch("subprocess.run") as mock_run:
            mock_run.return_value = MagicMock(returncode=0, stdout="ok", stderr="")
            bridge.exec_adb_command(["get-state"])
            mock_run.assert_called_once()
            _, kwargs = mock_run.call_args
            self.assertIn("env", kwargs)
            passed_env = kwargs["env"]
            self.assertIsInstance(passed_env, dict)
            # Verify sensitive keys not present in kwargs["env"]
            self.assertNotIn("OPENAI_API_KEY", passed_env)

    def test_handle_take_screenshot_adb_screencap_passes_clean_env(self):
        """handle_take_screenshot ADB fallback must pass clean env to subprocess.run."""
        from plugin.handlers import handle_take_screenshot
        from plugin.bridge import get_bridge

        bridge = get_bridge()
        bridge.adb_bin = "/usr/bin/adb"
        with patch.object(bridge, "send_request", return_value={"success": False, "error": "offline"}):
            with patch("subprocess.run") as mock_run:
                mock_run.return_value = MagicMock(returncode=0)
                with patch("os.path.exists", return_value=True), patch("os.path.getsize", return_value=1024):
                    handle_take_screenshot({})
                    mock_run.assert_called_once()
                    _, kwargs = mock_run.call_args
                    self.assertIn("env", kwargs)
                    passed_env = kwargs["env"]
                    self.assertNotIn("OPENAI_API_KEY", passed_env)


class TestAdbShellModelArgumentValidation(unittest.TestCase):
    """Test suite verifying validation of model arguments reaching adb shell."""

    def test_read_sms_rejects_non_integer_limit(self):
        """Non-integer limit values (e.g. injected shell text) must be refused fail-closed."""
        from plugin.handlers import handle_read_sms
        from plugin.bridge import get_bridge

        bridge = get_bridge()
        with patch.object(bridge, "exec_adb_command") as mock_adb:
            res_str = handle_read_sms({"limit": "5; rm -rf /data/local/tmp; #"})
            res = json.loads(res_str)
            self.assertIn("error", res)
            self.assertIn("limit", res["error"].lower())
            mock_adb.assert_not_called()

    def test_read_sms_rejects_out_of_bounds_limit(self):
        """Limits outside [1, 50] must be refused fail-closed."""
        from plugin.handlers import handle_read_sms
        from plugin.bridge import get_bridge

        bridge = get_bridge()
        with patch.object(bridge, "exec_adb_command") as mock_adb:
            for invalid_val in (0, -1, 51, 1000):
                res_str = handle_read_sms({"limit": invalid_val})
                res = json.loads(res_str)
                self.assertIn("error", res)
                self.assertIn("between 1 and 50", res["error"])
                mock_adb.assert_not_called()

    def test_device_logs_rejects_invalid_buffer(self):
        """Invalid log buffer names (injected shell text) must be refused fail-closed."""
        from plugin.handlers import handle_device_logs
        from plugin.bridge import get_bridge

        bridge = get_bridge()
        with patch.object(bridge, "exec_adb_command") as mock_adb:
            res_str = handle_device_logs({"buffer": "system; reboot", "lines": 50})
            res = json.loads(res_str)
            self.assertFalse(res.get("success"))
            self.assertIn("Invalid log buffer", res.get("error", ""))
            mock_adb.assert_not_called()

    def test_device_logs_accepts_valid_buffers(self):
        """Valid buffer names (main, system, radio, crash, dumpsys) are allowed."""
        from plugin.handlers import handle_device_logs
        from plugin.bridge import get_bridge

        bridge = get_bridge()
        valid_buffers = ("main", "system", "radio", "crash", "dumpsys")
        for buf in valid_buffers:
            with patch.object(bridge, "send_request", return_value={"success": True, "data": []}):
                res_str = handle_device_logs({"buffer": buf, "lines": 50})
                res = json.loads(res_str)
                self.assertEqual(res, [])

    def test_device_logs_rejects_non_integer_or_out_of_bounds_lines(self):
        """Lines argument must be strictly an integer bounded between 1 and 2000."""
        from plugin.handlers import handle_device_logs
        from plugin.bridge import get_bridge

        bridge = get_bridge()
        with patch.object(bridge, "exec_adb_command") as mock_adb:
            for bad_lines in ("50; rm -rf /", 0, -5, 2001, 10000):
                res_str = handle_device_logs({"buffer": "system", "lines": bad_lines})
                res = json.loads(res_str)
                self.assertFalse(res.get("success"))
                self.assertIn("lines", res.get("error", "").lower())
                mock_adb.assert_not_called()

    def test_contacts_search_rejects_shell_metacharacters(self):
        """Shell metacharacters ($(), backticks, pipes, redirection) must be rejected."""
        from plugin.handlers import handle_contacts_search
        from plugin.bridge import get_bridge

        bridge = get_bridge()
        shell_payloads = [
            "Alice$(reboot)",
            "Bob`id`",
            "Charlie | reboot",
            "David & reboot",
            "Eve; rm -rf /",
            "Frank > /data/local/tmp/hack",
            "Grace < /dev/null",
            "Heidi\nreboot",
        ]
        with patch.object(bridge, "exec_adb_command") as mock_adb:
            for payload in shell_payloads:
                res_str = handle_contacts_search({"query": payload})
                res = json.loads(res_str)
                self.assertFalse(res.get("success"))
                self.assertIn("forbidden in contacts search", res.get("error", ""))
                mock_adb.assert_not_called()

    def test_exact_verification_matrix(self):
        """Verify the comprehensive security test matrix for Unicode names, injections, and edge types."""
        from plugin.handlers import handle_contacts_search, handle_read_sms, handle_device_logs
        from plugin.bridge import get_bridge

        bridge = get_bridge()

        # 1. Accepted Unicode and standard names
        for safe_name in ("علی رضایی", "Alice", "Ali Smith"):
            with patch.object(bridge, "send_request", return_value={"success": True, "data": []}):
                res_str = handle_contacts_search({"query": safe_name})
                res = json.loads(res_str)
                self.assertEqual(res, [], f"Expected safe acceptance for '{safe_name}'")

        # 2. Rejected injections in contacts
        for attack in ("' OR 1=1 --", "$(id)", "Alice`id`", "Bob\nreboot"):
            with patch.object(bridge, "exec_adb_command") as mock_adb:
                res_str = handle_contacts_search({"query": attack})
                res = json.loads(res_str)
                self.assertFalse(res.get("success"), f"Attack '{attack}' must be rejected!")
                mock_adb.assert_not_called()

        # 3. Rejected boolean and float values for limit in read_sms
        for bad_limit in (True, False, 12.7, "12.7", "True"):
            with patch.object(bridge, "exec_adb_command") as mock_adb:
                res_str = handle_read_sms({"limit": bad_limit})
                res = json.loads(res_str)
                self.assertIn("error", res, f"Limit {bad_limit!r} should be rejected!")
                mock_adb.assert_not_called()

        # 4. Rejected out of bounds (0 or 2001) and boolean for lines in device_logs
        for bad_lines in (0, 2001, True, False, 12.7):
            with patch.object(bridge, "exec_adb_command") as mock_adb:
                res_str = handle_device_logs({"buffer": "system", "lines": bad_lines})
                res = json.loads(res_str)
                self.assertFalse(res.get("success"), f"Lines {bad_lines!r} should be rejected!")
                mock_adb.assert_not_called()


if __name__ == "__main__":
    unittest.main()
