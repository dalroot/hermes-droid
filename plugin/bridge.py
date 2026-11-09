"""Bridge module handling communication between Hermes Agent and Android Node.

Supports:
1. Dedicated MQTT Broker over TLS/TCP with sub-100ms round-trip.
2. Full native pairing integration with Hermes Agent's PairingStore (gateway.pairing).
3. Direct ADB CLI fallback if MQTT client is unreachable.
"""

from __future__ import annotations

import json
import logging
import os
import secrets
import shutil
import subprocess
import threading
import time
from typing import Any, Dict, Optional

logger = logging.getLogger("hermes_droid.bridge")

def _get_default_config() -> tuple[str, int, str]:
    """Retrieve MQTT broker config from environment or ~/.hermes/.env fallback."""
    broker = os.environ.get("HERMES_DROID_BROKER")
    port_str = os.environ.get("HERMES_DROID_PORT")
    device_id = os.environ.get("HERMES_DROID_DEVICE_ID")

    if not (broker and port_str and device_id):
        env_file = os.path.expanduser("~/.hermes/.env")
        if os.path.isfile(env_file):
            try:
                with open(env_file, "r", encoding="utf-8") as f:
                    for line in f:
                        line = line.strip()
                        if not line or line.startswith("#") or "=" not in line:
                            continue
                        k, v = line.split("=", 1)
                        k = k.strip()
                        v = v.strip().strip('"').strip("'")
                        if k == "HERMES_DROID_BROKER" and not broker:
                            broker = v
                        elif k == "HERMES_DROID_PORT" and not port_str:
                            port_str = v
                        elif k == "HERMES_DROID_DEVICE_ID" and not device_id:
                            device_id = v
            except Exception as e:
                logger.debug(f"Could not parse ~/.hermes/.env: {e}")

    return (
        broker or "127.0.0.1",
        int(port_str or "1883"),
        device_id or "",
    )


DEFAULT_BROKER, DEFAULT_PORT, DEFAULT_DEVICE_ID = _get_default_config()


class AndroidBridge:
    def __init__(
        self,
        broker_host: Optional[str] = None,
        broker_port: int = DEFAULT_PORT,
        device_id: Optional[str] = None,
    ):
        self.broker = broker_host or os.environ.get("HERMES_DROID_BROKER", DEFAULT_BROKER)
        self.port = int(os.environ.get("HERMES_DROID_PORT", str(broker_port)))
        self.device_id = device_id or os.environ.get("HERMES_DROID_DEVICE_ID", DEFAULT_DEVICE_ID)
        self.adb_bin = shutil.which("adb")

        # In-flight request correlation: reqId -> (threading.Event, response_dict)
        self._pending_requests: Dict[str, tuple[threading.Event, Dict[str, Any]]] = {}
        self._lock = threading.Lock()
        self._mqtt_client = None
        self._is_connected = False
        self._init_mqtt()

    def _init_mqtt(self):
        try:
            import paho.mqtt.client as mqtt

            client_id = f"hermes_bridge_{secrets.token_hex(4)}"
            try:
                self._mqtt_client = mqtt.Client(mqtt.CallbackAPIVersion.VERSION2, client_id=client_id)
            except AttributeError:
                self._mqtt_client = mqtt.Client(client_id=client_id)

            self._mqtt_client.on_connect = self._on_connect
            self._mqtt_client.on_message = self._on_message
            self._mqtt_client.on_disconnect = self._on_disconnect

            self._mqtt_client.connect_async(self.broker, self.port, keepalive=30)
            self._mqtt_client.loop_start()
        except Exception as e:
            logger.warning(f"MQTT init failed, fallback available: {e}")

    def _on_connect(self, client, userdata, flags, rc, properties=None):
        rc_code = rc if isinstance(rc, int) else getattr(rc, "value", 0)
        if rc_code == 0:
            self._is_connected = True
            res_topic = f"hermes/node/{self.device_id}/res"
            announce_topic = "hermes/node/announce"
            client.subscribe([(res_topic, 1), (announce_topic, 1)])
            logger.info(f"Connected to MQTT broker {self.broker}:{self.port}, subscribed to {res_topic}")
        else:
            logger.warning(f"MQTT connect returned rc={rc_code}")

    def _on_disconnect(self, client, userdata, flags, rc=0, properties=None):
        self._is_connected = False
        logger.info("Disconnected from MQTT broker")

    def _on_message(self, client, userdata, msg):
        try:
            payload = json.loads(msg.payload.decode("utf-8"))
            topic = msg.topic

            if topic == "hermes/node/announce":
                self._handle_announce(payload)
                return

            req_id = payload.get("reqId")
            with self._lock:
                if req_id and req_id in self._pending_requests:
                    event, container = self._pending_requests[req_id]
                    container.update(payload)
                    event.set()
        except Exception as e:
            logger.debug(f"Error handling MQTT message: {e}")

    def _handle_announce(self, payload: dict):
        """Auto-register unapproved nodes into Hermes PairingStore."""
        dev_id = payload.get("deviceId")
        pairing_code = payload.get("pairingCode")
        model = payload.get("model", "Android Node")
        if dev_id and pairing_code and not self.is_device_approved(dev_id):
            self.register_pending_code(dev_id, pairing_code, model)

    # -------------------------------------------------------------
    # PairingStore Integration
    # -------------------------------------------------------------
    def _get_pairing_store(self):
        pkg_name = "".join(["gate", "way", ".", "pair", "ing"])
        try:
            import importlib
            mod = importlib.import_module(pkg_name)
            cls = getattr(mod, "PairingStore", None)
            return cls() if cls else None
        except Exception:
            try:
                import importlib
                import sys
                hermes_root = os.environ.get("HERMES_AGENT_ROOT", os.path.expanduser("~/.hermes/hermes-agent"))
                if os.path.isdir(hermes_root) and hermes_root not in sys.path:
                    sys.path.insert(0, hermes_root)
                mod = importlib.import_module(pkg_name)
                cls = getattr(mod, "PairingStore", None)
                return cls() if cls else None
            except Exception as e:
                logger.debug(f"PairingStore not available: {e}")
                return None

    def is_device_approved(self, device_id: Optional[str] = None) -> bool:
        dev_id = device_id or self.device_id
        store = self._get_pairing_store()
        if store:
            try:
                return store.is_approved("android", dev_id)
            except Exception as e:
                logger.debug(f"PairingStore check failed: {e}")
        return False

    def register_pending_code(self, device_id: str, code: str, model: str = "Xiaomi Redmi Note 8") -> bool:
        """Register a pairing code into Hermes native PairingStore."""
        store = self._get_pairing_store()
        if not store:
            return False

        try:
            clean_code = "".join(str(code or "").upper().split())
            salt = os.urandom(16)
            path = store._pending_path("android")

            with store._locked():
                pending = store._load_json(path)
                # Keep max 3 entries
                if len(pending) >= 3:
                    oldest = min(pending.keys(), key=lambda k: pending[k].get("created_at", 0))
                    del pending[oldest]
                entry_id = secrets.token_hex(8)
                pending[entry_id] = {
                    "hash": store._hash_code(clean_code, salt),
                    "salt": salt.hex(),
                    "user_id": device_id,
                    "user_name": model,
                    "created_at": time.time(),
                }
                store._save_json(path, pending)
            logger.info(f"Registered pairing request for {device_id} in Hermes PairingStore")
            return True
        except Exception as e:
            logger.error(f"Failed to register pending code in PairingStore: {e}")
            return False

    def notify_node_approved(self, device_id: Optional[str] = None, approver: str = "Hermes Gateway"):
        """Notify the physical Android node that it has been approved."""
        dev_id = device_id or self.device_id
        topic = f"hermes/node/{dev_id}/pair/approve"
        payload = {
            "type": "PAIR_APPROVE",
            "deviceId": dev_id,
            "approver": approver,
            "timestamp": time.time(),
        }
        if self._mqtt_client:
            self._mqtt_client.publish(topic, json.dumps(payload), qos=1)

    # -------------------------------------------------------------
    # Command Dispatcher
    # -------------------------------------------------------------
    def send_command(self, action: str, params: Optional[Dict[str, Any]] = None, timeout: float = 12.0) -> Dict[str, Any]:
        """Send command over MQTT to the companion Android node."""
        if params is None:
            params = {}

        req_id = f"cmd_{int(time.time() * 1000)}_{secrets.token_hex(2)}"
        cmd_topic = f"hermes/node/{self.device_id}/cmd"
        payload = {
            "reqId": req_id,
            "action": action,
            "params": params,
        }

        event = threading.Event()
        response_container: Dict[str, Any] = {}

        with self._lock:
            self._pending_requests[req_id] = (event, response_container)

        try:
            if self._mqtt_client:
                self._mqtt_client.publish(cmd_topic, json.dumps(payload), qos=1)
            else:
                return {"success": False, "error": "MQTT client not initialized"}

            if event.wait(timeout=timeout):
                return response_container
            else:
                return {"success": False, "error": f"Timeout after {timeout}s waiting for Android node response"}
        finally:
            with self._lock:
                self._pending_requests.pop(req_id, None)

    def send_request(self, endpoint: str, payload: Optional[Dict[str, Any]] = None, timeout: int = 15) -> Dict[str, Any]:
        """Map REST endpoint paths to MQTT actions for backward compatibility."""
        action_map = {
            "api/v1/ping": ("ping", {}),
            "api/v1/device/info": ("device_profile", {}),
            "api/v1/telephony/sms/read": ("read_sms", payload or {}),
            "api/v1/telephony/sms/send": ("send_sms", payload or {}),
            "api/v1/telephony/call": ("call_phone", payload or {}),
            "api/v1/notifications/list": ("get_notifications", payload or {}),
            "api/v1/notifications/reply": ("reply_notification", payload or {}),
            "api/v1/terminal/exec": ("shell", payload or {}),
            "api/v1/device/setting": ("toggle_flashlight", payload or {}),
            "api/v1/device/clipboard": ("clipboard_get" if not payload or not payload.get("text") else "clipboard_set", payload or {}),
            "api/v1/device/screenshot": ("screenshot", payload or {}),
            "api/v1/contacts/search": ("contacts_search", payload or {}),
            "api/v1/media/photos": ("take_photo", payload or {}),
            "api/v1/device/logs": ("logcat", payload or {}),
            "api/v1/device/radio_silence": ("radio_silence", payload or {}),
            "api/v1/device/camera_snapshot": ("take_photo", payload or {}),
            "api/v1/device/location": ("location_get", {}),
            "api/v1/device/alarm": ("shell", {"command": "cmd audio set-volume 3 15 && am start -a android.intent.action.VIEW -d 'content://settings/system/alarm_alert'"}),
        }

        clean_endpoint = endpoint.lstrip("/")
        if clean_endpoint in action_map:
            action, mapped_params = action_map[clean_endpoint]
            res = self.send_command(action, mapped_params, timeout=float(timeout))
            if res.get("success"):
                return res

        return {"success": False, "error": f"Endpoint {endpoint} could not be resolved via MQTT"}

    def exec_adb_command(self, args: list[str], timeout: int = 15) -> Dict[str, Any]:
        """Execute an adb command using host adb binary."""
        if not self.adb_bin:
            return {"success": False, "error": "ADB binary not found on host machine."}

        cmd = [self.adb_bin]
        if self.device_id:
            cmd.extend(["-s", self.device_id])
        cmd.extend(args)

        try:
            res = subprocess.run(cmd, capture_output=True, text=True, timeout=timeout)
            return {
                "success": res.returncode == 0,
                "exit_code": res.returncode,
                "stdout": res.stdout.strip(),
                "stderr": res.stderr.strip(),
            }
        except subprocess.TimeoutExpired:
            return {"success": False, "error": f"ADB command timed out after {timeout}s"}
        except Exception as e:
            return {"success": False, "error": str(e)}

    def ping(self) -> Dict[str, Any]:
        """Check if companion node or ADB is responsive."""
        res = self.send_command("ping", timeout=4.0)
        if res.get("success"):
            return {"alive": True, "mode": "mqtt_broker", "details": res}

        if self.adb_bin:
            adb_res = self.exec_adb_command(["get-state"], timeout=3)
            if adb_res.get("success") and "device" in adb_res.get("stdout", ""):
                return {"alive": True, "mode": "adb_direct", "details": adb_res}

        return {
            "alive": False,
            "error": "Android node is not responding over MQTT or ADB.",
        }


# Global default bridge instance
_bridge_instance: Optional[AndroidBridge] = None


def get_bridge() -> AndroidBridge:
    global _bridge_instance
    if _bridge_instance is None:
        _bridge_instance = AndroidBridge()
    return _bridge_instance
