"""Configuration schema, ctx.get_config, and secret_scope tests for Item #2."""

import os
import sys
import types
import unittest
from unittest.mock import MagicMock, patch

from plugin import register
from plugin.bridge import AndroidBridge, configure_bridge, get_bridge


class MockConfigContext:
    def __init__(self, settings=None):
        self.settings = settings or {}
        self.tools = {}
        self.hooks = {}
        self.unload_callbacks = []

    def get_config(self, key, default=None):
        return self.settings.get(key, default)

    def register_tool(self, name, toolset, schema, handler, emoji=None):
        self.tools[name] = handler

    def register_hook(self, name, handler):
        self.hooks[name] = handler

    def on_unload(self, callback):
        self.unload_callbacks.append(callback)


class TestConfigAndSecrets(unittest.TestCase):
    def tearDown(self):
        configure_bridge()

    def test_no_direct_dotenv_parsing_in_bridge(self):
        """Catalog Rule 11: bridge.py must never read ~/.hermes/.env directly."""
        bridge_path = os.path.join(os.path.dirname(__file__), "..", "bridge.py")
        with open(bridge_path, "r", encoding="utf-8") as f:
            source = f.read()

        self.assertNotIn(".hermes/.env", source)
        self.assertNotIn("env_file", source)
        self.assertNotIn("_get_default_config", source)
        self.assertNotIn('os.environ.get("HERMES_DROID_BROKER"', source)
        self.assertNotIn('os.environ.get("HERMES_DROID_PORT"', source)
        self.assertNotIn('os.environ.get("HERMES_DROID_DEVICE_ID"', source)

    def test_register_reads_config_via_ctx_get_config(self):
        """Item #2: register(ctx) reads broker_host/port/device_id/tls_enabled via ctx.get_config."""
        ctx = MockConfigContext({
            "broker_host": "mqtt.example.local",
            "broker_port": 8883,
            "device_id": "pixel_test_99",
            "tls_enabled": True,
            "allowed_apk_dir": "/var/tmp/hermes_apks",
        })

        with patch.object(AndroidBridge, "_init_mqtt"):
            register(ctx)
            bridge = get_bridge()

            self.assertEqual(bridge.broker, "mqtt.example.local")
            self.assertEqual(bridge.port, 8883)
            self.assertEqual(bridge.device_id, "pixel_test_99")
            self.assertTrue(bridge.tls_enabled)
            self.assertEqual(bridge.allowed_apk_dir, "/var/tmp/hermes_apks")
            self.assertEqual(len(ctx.unload_callbacks), 1)

    def test_register_handles_invalid_port_safely(self):
        """Invalid port values fall back cleanly to 1883."""
        ctx = MockConfigContext({
            "broker_port": "invalid_port",
        })
        with patch.object(AndroidBridge, "_init_mqtt"):
            register(ctx)
            bridge = get_bridge()
            self.assertEqual(bridge.port, 1883)

    def test_get_mqtt_credentials_via_agent_secret_scope(self):
        """Item #2: Credentials are read via agent.secret_scope.get_secret, not bare os.environ."""
        mock_agent_pkg = types.ModuleType("agent")
        mock_secret_mod = types.ModuleType("agent.secret_scope")
        mock_secret_mod.get_secret = MagicMock(side_effect=lambda name, default=None: {
            "HERMES_DROID_MQTT_USER": "scoped_user",
            "HERMES_DROID_MQTT_PASSWORD": "scoped_password_123",
        }.get(name, default))

        with patch.dict(sys.modules, {"agent": mock_agent_pkg, "agent.secret_scope": mock_secret_mod}):
            with patch.object(AndroidBridge, "_init_mqtt"):
                bridge = AndroidBridge(broker_host="127.0.0.1", device_id="dev1")
                user, pwd = bridge.get_mqtt_credentials()

                self.assertEqual(user, "scoped_user")
                self.assertEqual(pwd, "scoped_password_123")
                mock_secret_mod.get_secret.assert_any_call("HERMES_DROID_MQTT_USER", None)
                mock_secret_mod.get_secret.assert_any_call("HERMES_DROID_MQTT_PASSWORD", None)

    def test_plugin_yaml_declares_config_schema_and_requires_env(self):
        """Verify plugin.yaml declares config_schema and requires_env fields cleanly."""
        manifest_path = os.path.join(os.path.dirname(__file__), "..", "plugin.yaml")
        with open(manifest_path, "r", encoding="utf-8") as f:
            content = f.read()

        for key in ("config_schema:", "broker_host:", "broker_port:", "device_id:", "tls_enabled:", "allowed_apk_dir:"):
            self.assertIn(key, content, f"Missing {key} in plugin.yaml")

        for env_key in ("requires_env:", "HERMES_DROID_MQTT_USER", "HERMES_DROID_MQTT_PASSWORD"):
            self.assertIn(env_key, content, f"Missing {env_key} in plugin.yaml")

    def test_strict_boolean_parsing_accepted_variants(self):
        """Strict bool parsing accepts native bools and exact true/false string variants."""
        from plugin import _parse_strict_bool

        self.assertTrue(_parse_strict_bool(True))
        self.assertFalse(_parse_strict_bool(False))
        self.assertTrue(_parse_strict_bool("true"))
        self.assertTrue(_parse_strict_bool("True"))
        self.assertTrue(_parse_strict_bool("TRUE"))
        self.assertTrue(_parse_strict_bool("  true  "))
        self.assertFalse(_parse_strict_bool("false"))
        self.assertFalse(_parse_strict_bool("False"))
        self.assertFalse(_parse_strict_bool("FALSE"))
        self.assertFalse(_parse_strict_bool("  false  "))
        self.assertFalse(_parse_strict_bool(None, default=False))
        self.assertTrue(_parse_strict_bool(None, default=True))

    def test_strict_boolean_parsing_rejects_invalid_values_fail_closed(self):
        """Invalid values must raise ValueError and never silently default or downgrade to False."""
        from plugin import _parse_strict_bool

        for invalid in ("maybe", "yes", "no", "1", "0", 1, 0, 123, ["true"], {"tls": True}):
            with self.subTest(invalid=invalid):
                with self.assertRaises(ValueError):
                    _parse_strict_bool(invalid)

        # Ensure register(ctx) fails closed on invalid tls_enabled value
        ctx = MockConfigContext({
            "tls_enabled": "maybe",
        })
        with self.assertRaises(ValueError):
            register(ctx)

    def test_unscoped_secret_error_is_not_bypassed_with_environ(self):
        """UnscopedSecretError in multiplex mode must NOT fall back to os.environ."""
        from plugin.bridge import get_plugin_secret

        class MockUnscopedSecretError(RuntimeError):
            pass

        mock_agent_pkg = types.ModuleType("agent")
        mock_secret_mod = types.ModuleType("agent.secret_scope")
        mock_secret_mod.UnscopedSecretError = MockUnscopedSecretError
        mock_secret_mod.get_secret = MagicMock(side_effect=MockUnscopedSecretError("Unscoped secret access"))

        with patch.dict(sys.modules, {"agent": mock_agent_pkg, "agent.secret_scope": mock_secret_mod}):
            with patch.dict(os.environ, {"HERMES_DROID_MQTT_USER": "leaked_env_user"}):
                # get_plugin_secret must propagate UnscopedSecretError and NEVER return leaked_env_user
                with self.assertRaises(MockUnscopedSecretError):
                    get_plugin_secret("HERMES_DROID_MQTT_USER")

                # AndroidBridge._init_mqtt must also re-raise UnscopedSecretError
                with self.assertRaises(MockUnscopedSecretError):
                    AndroidBridge(broker_host="127.0.0.1", device_id="dev1")

    def test_unload_lifecycle_stops_mqtt_and_clears_resources(self):
        """Unload callback must stop MQTT background thread, disconnect, and nullify bridge."""
        mock_mqtt_client = MagicMock()
        ctx = MockConfigContext({
            "broker_host": "127.0.0.1",
            "device_id": "test_device_lifecycle",
            "tls_enabled": False,
        })

        with patch("paho.mqtt.client.Client", return_value=mock_mqtt_client):
            register(ctx)
            bridge = get_bridge()
            self.assertIsNotNone(bridge)
            self.assertEqual(len(ctx.unload_callbacks), 1)

            # Invoke the registered unload callback
            ctx.unload_callbacks[0]()

            mock_mqtt_client.loop_stop.assert_called_once()
            mock_mqtt_client.disconnect.assert_called_once()
            self.assertIsNone(bridge._mqtt_client)
            self.assertFalse(bridge._is_connected)

            # Next get_bridge() must create a fresh unconfigured instance (old was cleared)
            from plugin import bridge as bridge_mod
            self.assertIsNone(bridge_mod._bridge_instance)
            self.assertIsNone(bridge_mod._bridge_config["broker_host"])

    def test_is_loopback_host(self):
        """Verify loopback host detection for IPv4, IPv6, and standard hostnames."""
        from plugin.bridge import is_loopback_host

        self.assertTrue(is_loopback_host("127.0.0.1"))
        self.assertTrue(is_loopback_host("127.0.0.99"))
        self.assertTrue(is_loopback_host("localhost"))
        self.assertTrue(is_loopback_host("LOCALHOST"))
        self.assertTrue(is_loopback_host("::1"))
        self.assertTrue(is_loopback_host("localhost.localdomain"))

        self.assertFalse(is_loopback_host("192.168.1.50"))
        self.assertFalse(is_loopback_host("10.0.0.1"))
        self.assertFalse(is_loopback_host("mqtt.example.com"))
        self.assertFalse(is_loopback_host("8.8.8.8"))
        self.assertFalse(is_loopback_host(""))

    def test_non_loopback_broker_requires_tls_in_register(self):
        """Item #5: Non-loopback broker without TLS is rejected fail-closed at registration."""
        ctx = MockConfigContext({
            "broker_host": "mqtt.remote.org",
            "tls_enabled": False,
        })
        with self.assertRaises(ValueError) as cm:
            register(ctx)
        self.assertIn("requires TLS", str(cm.exception))

    def test_non_loopback_broker_requires_credentials_in_bridge(self):
        """Item #5: Non-loopback broker without credentials raises ValueError."""
        with patch("paho.mqtt.client.Client"):
            with self.assertRaises(ValueError) as cm:
                AndroidBridge(
                    broker_host="mqtt.remote.org",
                    tls_enabled=True,
                    mqtt_user=None,
                    mqtt_password=None,
                )
            self.assertIn("requires credentials", str(cm.exception))

    def test_non_loopback_broker_with_tls_and_credentials_enforces_tls_set_and_auth(self):
        """Item #5: Non-loopback broker with TLS and credentials calls tls_set and username_pw_set."""
        mock_mqtt_client = MagicMock()
        with patch("paho.mqtt.client.Client", return_value=mock_mqtt_client):
            bridge = AndroidBridge(
                broker_host="mqtt.remote.org",
                tls_enabled=True,
                mqtt_user="valid_remote_user",
                mqtt_password="valid_remote_password",
            )
            mock_mqtt_client.tls_set.assert_called_once()
            mock_mqtt_client.username_pw_set.assert_called_once_with("valid_remote_user", "valid_remote_password")
            self.assertTrue(bridge.tls_enabled)

    def test_loopback_allows_plaintext_or_local_tls(self):
        """Item #5: Loopback allows plaintext TCP without credentials, and sets tls_set if enabled."""
        mock_mqtt_client = MagicMock()
        with patch("paho.mqtt.client.Client", return_value=mock_mqtt_client):
            # Plaintext loopback
            bridge_plain = AndroidBridge(broker_host="127.0.0.1", tls_enabled=False)
            mock_mqtt_client.tls_set.assert_not_called()

            # TLS on loopback
            mock_mqtt_client.reset_mock()
            bridge_tls = AndroidBridge(broker_host="127.0.0.1", tls_enabled=True)
            mock_mqtt_client.tls_set.assert_called_once()


if __name__ == "__main__":
    unittest.main()
