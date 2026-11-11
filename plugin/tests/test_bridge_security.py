"""Security and trust verification tests for hermes-droid bridge and device registry."""

import json
import os
import stat
import tempfile
import threading
import time
import unittest
from unittest.mock import MagicMock, patch

from plugin.bridge import AndroidBridge, DeviceRegistry


class TestBridgeSecurity(unittest.TestCase):
    def setUp(self):
        self.temp_dir = tempfile.mkdtemp(prefix="hermes_droid_sec_test_")
        self.registry = DeviceRegistry(data_dir=self.temp_dir)

    def tearDown(self):
        import shutil
        shutil.rmtree(self.temp_dir, ignore_errors=True)

    def test_announce_never_enrolls_or_approves_device(self):
        """Security requirement: Unauthenticated MQTT announce must NEVER create or approve device records."""
        with patch.object(AndroidBridge, "_init_mqtt"):
            bridge = AndroidBridge(device_id="known_device_1")
            bridge._registry = self.registry

            # Simulate incoming unauthenticated MQTT announce message
            mock_msg = MagicMock()
            mock_msg.topic = "hermes/node/announce"
            mock_msg.payload = json.dumps({
                "deviceId": "rogue_device_999",
                "pairingCode": "123456",
                "model": "Malicious Phone",
            }).encode("utf-8")

            bridge._on_message(None, None, mock_msg)

            # Registry must be completely untouched and device must NOT be approved
            self.assertFalse(self.registry.is_approved("rogue_device_999"))
            self.assertFalse(bridge.is_device_approved("rogue_device_999"))
            self.assertEqual(len(self.registry.list_approved_devices()), 0)

    def test_unapproved_device_cannot_inject_command_response(self):
        """Security requirement: Unapproved device messages must be dropped and never wake pending commands."""
        with patch.object(AndroidBridge, "_init_mqtt"):
            bridge = AndroidBridge(device_id="legit_device_123")
            bridge._registry = self.registry

            req_id = "test_req_42"
            event = threading.Event()
            container = {}
            bridge._pending_requests[req_id] = (event, container)

            # 1. Attacker sends response from unexpected topic
            rogue_msg = MagicMock()
            rogue_msg.topic = "hermes/node/attacker_device/res"
            rogue_msg.payload = json.dumps({
                "reqId": req_id,
                "deviceId": "attacker_device",
                "success": True,
                "data": "injected",
            }).encode("utf-8")

            bridge._on_message(None, None, rogue_msg)
            self.assertFalse(event.is_set(), "Event was triggered by an unauthorized topic!")
            self.assertEqual(container, {}, "Container was modified by an unauthorized topic!")

            # 2. Attacker publishes to legit topic but with spoofed/unapproved deviceId
            spoofed_msg = MagicMock()
            spoofed_msg.topic = "hermes/node/legit_device_123/res"
            spoofed_msg.payload = json.dumps({
                "reqId": req_id,
                "deviceId": "unapproved_device_77",
                "success": True,
                "data": "spoofed",
            }).encode("utf-8")

            bridge._on_message(None, None, spoofed_msg)
            self.assertFalse(event.is_set(), "Event was triggered by an unapproved deviceId payload!")
            self.assertEqual(container, {}, "Container was modified by an unapproved deviceId payload!")

    def test_fail_closed_missing_device_id_in_payload(self):
        """Security requirement: Fail-closed validation. Missing deviceId in payload MUST be rejected."""
        with patch.object(AndroidBridge, "_init_mqtt"):
            bridge = AndroidBridge(device_id="legit_device_123")
            bridge._registry = self.registry

            req_id = "test_req_missing_dev"
            event = threading.Event()
            container = {}
            bridge._pending_requests[req_id] = (event, container)

            mock_msg = MagicMock()
            mock_msg.topic = "hermes/node/legit_device_123/res"
            # Missing "deviceId" key entirely
            mock_msg.payload = json.dumps({
                "reqId": req_id,
                "success": True,
                "data": "bypassed?",
            }).encode("utf-8")

            bridge._on_message(None, None, mock_msg)
            self.assertFalse(event.is_set(), "Fail-closed check failed: message with missing deviceId was accepted!")
            self.assertEqual(container, {})

    def test_fail_closed_mismatched_device_id_in_payload(self):
        """Security requirement: Mismatched deviceId in payload MUST be rejected."""
        with patch.object(AndroidBridge, "_init_mqtt"):
            bridge = AndroidBridge(device_id="legit_device_123")
            bridge._registry = self.registry

            req_id = "test_req_mismatch_dev"
            event = threading.Event()
            container = {}
            bridge._pending_requests[req_id] = (event, container)

            mock_msg = MagicMock()
            mock_msg.topic = "hermes/node/legit_device_123/res"
            mock_msg.payload = json.dumps({
                "reqId": req_id,
                "deviceId": "different_device_456",
                "success": True,
            }).encode("utf-8")

            bridge._on_message(None, None, mock_msg)
            self.assertFalse(event.is_set(), "Mismatched deviceId was accepted!")
            self.assertEqual(container, {})

    def test_forged_req_id_dropped(self):
        """Security requirement: Responses with unknown/forged reqId are dropped without exception."""
        with patch.object(AndroidBridge, "_init_mqtt"):
            bridge = AndroidBridge(device_id="legit_device_123")
            bridge._registry = self.registry

            mock_msg = MagicMock()
            mock_msg.topic = "hermes/node/legit_device_123/res"
            mock_msg.payload = json.dumps({
                "reqId": "forged_non_existent_req_999",
                "deviceId": "legit_device_123",
                "success": True,
            }).encode("utf-8")

            # Should not raise exception and nothing should be pending
            bridge._on_message(None, None, mock_msg)
            self.assertEqual(len(bridge._pending_requests), 0)

    def test_duplicate_response_dropped_and_single_delivery(self):
        """Security requirement: Single-delivery semantics. Duplicate responses are dropped."""
        with patch.object(AndroidBridge, "_init_mqtt"):
            bridge = AndroidBridge(device_id="legit_device_123")
            bridge._registry = self.registry

            req_id = "test_req_dup"
            event = threading.Event()
            container = {}
            bridge._pending_requests[req_id] = (event, container)

            # First response
            msg1 = MagicMock()
            msg1.topic = "hermes/node/legit_device_123/res"
            msg1.payload = json.dumps({
                "reqId": req_id,
                "deviceId": "legit_device_123",
                "success": True,
                "data": "initial_result",
            }).encode("utf-8")

            bridge._on_message(None, None, msg1)
            self.assertTrue(event.is_set())
            self.assertEqual(container.get("data"), "initial_result")
            self.assertNotIn(req_id, bridge._pending_requests)

            # Duplicate / replay response
            msg2 = MagicMock()
            msg2.topic = "hermes/node/legit_device_123/res"
            msg2.payload = json.dumps({
                "reqId": req_id,
                "deviceId": "legit_device_123",
                "success": True,
                "data": "overwritten_spoof",
            }).encode("utf-8")

            bridge._on_message(None, None, msg2)
            # Must remain the initial result and not be corrupted
            self.assertEqual(container.get("data"), "initial_result")

    def test_expired_response_dropped(self):
        """Security requirement: Responses arriving after request timeout/cleanup are dropped."""
        with patch.object(AndroidBridge, "_init_mqtt"):
            bridge = AndroidBridge(device_id="legit_device_123")
            bridge._registry = self.registry

            req_id = "test_req_expired"
            # Request already expired and cleaned up from pending_requests
            msg = MagicMock()
            msg.topic = "hermes/node/legit_device_123/res"
            msg.payload = json.dumps({
                "reqId": req_id,
                "deviceId": "legit_device_123",
                "success": True,
                "data": "late_data",
            }).encode("utf-8")

            bridge._on_message(None, None, msg)
            self.assertEqual(len(bridge._pending_requests), 0)

    def test_concurrent_request_isolation(self):
        """Security requirement: Concurrent requests have isolated correlation and cannot cross-deliver."""
        with patch.object(AndroidBridge, "_init_mqtt"):
            bridge = AndroidBridge(device_id="legit_device_123")
            bridge._registry = self.registry

            req1, req2 = "cmd_thread_1", "cmd_thread_2"
            ev1, ev2 = threading.Event(), threading.Event()
            cont1, cont2 = {}, {}

            bridge._pending_requests[req1] = (ev1, cont1)
            bridge._pending_requests[req2] = (ev2, cont2)

            # Deliver response for req2 first
            msg2 = MagicMock()
            msg2.topic = "hermes/node/legit_device_123/res"
            msg2.payload = json.dumps({
                "reqId": req2,
                "deviceId": "legit_device_123",
                "data": "from_worker_2",
            }).encode("utf-8")

            bridge._on_message(None, None, msg2)
            self.assertTrue(ev2.is_set())
            self.assertEqual(cont2.get("data"), "from_worker_2")
            self.assertFalse(ev1.is_set())
            self.assertEqual(cont1, {})

            # Deliver response for req1
            msg1 = MagicMock()
            msg1.topic = "hermes/node/legit_device_123/res"
            msg1.payload = json.dumps({
                "reqId": req1,
                "deviceId": "legit_device_123",
                "data": "from_worker_1",
            }).encode("utf-8")

            bridge._on_message(None, None, msg1)
            self.assertTrue(ev1.is_set())
            self.assertEqual(cont1.get("data"), "from_worker_1")

    def test_symlink_registry_protection(self):
        """Security requirement: Registry refuses to read or write through symlinks."""
        target_file = os.path.join(self.temp_dir, "real_target.json")
        with open(target_file, "w", encoding="utf-8") as f:
            f.write(json.dumps({"symlink_dev": {"approved": True}}))

        symlink_path = os.path.join(self.temp_dir, "symlink_paired.json")
        os.symlink(target_file, symlink_path)

        reg = DeviceRegistry(data_dir=self.temp_dir)
        reg.registry_file = symlink_path

        # Should refuse to read through symlink
        self.assertFalse(reg.is_approved("symlink_dev"))

        # Should refuse to write through symlink
        success = reg.approve_device("another_dev")
        self.assertFalse(success)

    def test_send_command_refuses_unapproved_or_empty_device(self):
        """Security requirement: Commands must fail immediately if device is not approved/configured."""
        with patch.object(AndroidBridge, "_init_mqtt"):
            bridge = AndroidBridge(device_id="")
            bridge._registry = self.registry

            res = bridge.send_command("ping")
            self.assertFalse(res.get("success"))
            self.assertIn("not configured or not approved", res.get("error", ""))

    def test_device_registry_atomic_write_and_permissions(self):
        """Security requirement: Registry file must use atomic writes and restrictive 0600 permissions."""
        success = self.registry.approve_device("device_abc", model="Pixel 7")
        self.assertTrue(success)
        self.assertTrue(self.registry.is_approved("device_abc"))

        # Verify permissions
        file_path = self.registry.registry_file
        self.assertTrue(os.path.isfile(file_path))
        file_mode = stat.S_IMODE(os.stat(file_path).st_mode)
        self.assertEqual(file_mode, 0o600, f"Expected 0600 permissions, got {oct(file_mode)}")

    def test_corrupted_registry_json_handling(self):
        """Security requirement: Corrupted or malformed registry files must be handled safely."""
        with open(self.registry.registry_file, "w", encoding="utf-8") as f:
            f.write("{invalid_json: true, broken")
        self.assertFalse(self.registry.is_approved("any_device"))

    def test_revoke_device(self):
        """Security requirement: Revoking a device immediately invalidates approval."""
        self.registry.approve_device("dev_to_revoke", model="Test Device")
        self.assertTrue(self.registry.is_approved("dev_to_revoke"))

        self.registry.revoke_device("dev_to_revoke")
        self.assertFalse(self.registry.is_approved("dev_to_revoke"))

    def test_unapproved_device_id_explicit_check(self):
        """Security requirement: Bridge with configured device_id A rejects unapproved device B."""
        with patch.object(AndroidBridge, "_init_mqtt"):
            bridge = AndroidBridge(device_id="configured_dev_A")
            bridge._registry = self.registry

            self.assertTrue(bridge.is_device_approved("configured_dev_A"))
            self.assertFalse(bridge.is_device_approved("random_dev_B"))

    def test_no_private_gateway_pairing_or_sys_path_injection(self):
        """Architecture requirement: No sys.path mutation or private gateway.pairing imports."""
        bridge_file = os.path.join(os.path.dirname(__file__), "..", "bridge.py")
        with open(bridge_file, "r", encoding="utf-8") as f:
            content = f.read()

        self.assertNotIn("gateway.pairing", content)
        self.assertNotIn("sys.path.insert", content)
        self.assertNotIn("_get_pairing_store", content)
        self.assertNotIn("PairingStore", content)


if __name__ == "__main__":
    unittest.main()
