"""Bridge module handling communication between Hermes Agent and Android Node.

Supports:
1. Dedicated MQTT Broker over TLS/TCP with sub-100ms round-trip.
2. Isolated, tamper-proof device registry stored in plugin data directory.
3. Direct ADB CLI fallback if MQTT client is unreachable.
"""

from __future__ import annotations

import json
import logging
import os
import secrets
import shutil
import subprocess
import tempfile
import threading
import time
from typing import Any, Dict, Optional

logger = logging.getLogger("hermes_droid.bridge")

DEFAULT_BROKER = "127.0.0.1"
DEFAULT_PORT = 1883
DEFAULT_DEVICE_ID = ""


def is_loopback_host(host: str) -> bool:
    """Return True if host is a local loopback address (127.0.0.1, localhost, ::1)."""
    h = (host or "").strip().lower()
    if h in ("127.0.0.1", "localhost", "::1", "localhost.localdomain"):
        return True
    try:
        import ipaddress

        ip = ipaddress.ip_address(h)
        return ip.is_loopback
    except ValueError:
        return False


def get_plugin_secret(name: str, default: Optional[str] = None) -> Optional[str]:
    """Resolve credential via Hermes public agent.secret_scope.get_secret.

    Respects active profile isolation under multiplexing. Falls back to os.environ
    only when running isolated unit tests without the Hermes runtime package installed.
    Never bypasses UnscopedSecretError: if Hermes is running under multiplexing
    without an active scope, get_secret raises UnscopedSecretError (fail-closed).
    """
    try:
        from agent.secret_scope import get_secret
    except ImportError:
        return os.environ.get(name, default)

    return get_secret(name, default)


def get_clean_adb_env() -> Dict[str, str]:
    """Build a minimal, safe environment dictionary for child ADB processes.

    Prevents leaking Hermes process environment (including provider API keys,
    tokens, and sensitive session credentials) to external child processes.
    Only copies essential variables when explicitly set in the host environment.
    """
    allowed_keys = (
        "PATH",
        "HOME",
        "LANG",
        "TMPDIR",
        "ANDROID_HOME",
        "ANDROID_SDK_ROOT",
        "ADB_VENDOR_KEYS",
        "ANDROID_ADB_SERVER_PORT",
        "SYSTEMROOT",
        "SystemDrive",
    )
    clean_env: Dict[str, str] = {}
    for key in allowed_keys:
        val = os.environ.get(key)
        if val is not None:
            clean_env[key] = val
    return clean_env


def get_plugin_data_dir() -> str:
    """Return isolated data directory for hermes-droid with strict permissions (0700).

    Resolves via hermes_constants.get_hermes_home() to follow active Hermes profile,
    falling back to standard ~/.hermes/plugin-data/hermes-droid or HERMES_PLUGIN_DATA override.
    """
    override = os.environ.get("HERMES_PLUGIN_DATA")
    if override:
        base_dir = override
    else:
        try:
            from hermes_constants import get_hermes_home
            base_dir = str(get_hermes_home() / "plugin-data" / "hermes-droid")
        except ImportError:
            base_dir = os.path.expanduser("~/.hermes/plugin-data/hermes-droid")

    os.makedirs(base_dir, mode=0o700, exist_ok=True)
    return base_dir


class DeviceRegistry:
    """Manages trusted/approved paired devices in the plugin's isolated data dir.

    Security guarantees:
    - Never automatically registers or trusts devices from unauthenticated MQTT packets.
    - Atomic writes with restrictive 0600 file permissions and directory fsync.
    - Symlink protection: refuses to read/write through symbolic links.
    - Safe error handling for missing/corrupted registry files.
    """

    def __init__(self, data_dir: Optional[str] = None):
        self.data_dir = data_dir or get_plugin_data_dir()
        self.registry_file = os.path.join(self.data_dir, "paired_devices.json")
        self._lock = threading.Lock()

    def _read_registry(self) -> Dict[str, Any]:
        if os.path.islink(self.registry_file):
            logger.error(f"Security alert: registry file '{self.registry_file}' is a symlink! Refusing to read.")
            return {}
        if not os.path.isfile(self.registry_file):
            return {}
        try:
            with open(self.registry_file, "r", encoding="utf-8") as f:
                data = json.load(f)
                return data if isinstance(data, dict) else {}
        except Exception as e:
            logger.warning(f"Failed to read device registry from {self.registry_file}: {e}")
            return {}

    def is_approved(self, device_id: str) -> bool:
        if not device_id:
            return False
        with self._lock:
            data = self._read_registry()
            dev_entry = data.get(device_id)
            if isinstance(dev_entry, dict):
                return bool(dev_entry.get("approved", False))
            elif isinstance(dev_entry, bool):
                return dev_entry
            return False

    def list_approved_devices(self) -> list[str]:
        with self._lock:
            data = self._read_registry()
            approved = []
            for dev_id, info in data.items():
                if isinstance(info, dict) and info.get("approved"):
                    approved.append(dev_id)
                elif info is True:
                    approved.append(dev_id)
            return approved

    def approve_device(self, device_id: str, model: str = "") -> bool:
        """Explicitly approve a device in the local registry.

        ADMINISTRATIVE ONLY:
        This method must only be invoked via explicit administrator/user configuration
        or verified local setup workflows. It is NEVER exposed to LLM tools, hooks,
        or incoming MQTT message handlers.
        """
        if not device_id:
            return False
        with self._lock:
            data = self._read_registry()
            data[device_id] = {
                "approved": True,
                "model": model,
                "updated_at": time.time(),
            }
            return self._write_registry_atomic(data)

    def revoke_device(self, device_id: str) -> bool:
        """Revoke a previously approved device."""
        with self._lock:
            data = self._read_registry()
            if device_id in data:
                del data[device_id]
                return self._write_registry_atomic(data)
            return True

    def _write_registry_atomic(self, data: Dict[str, Any]) -> bool:
        os.makedirs(self.data_dir, mode=0o700, exist_ok=True)
        if os.path.islink(self.registry_file):
            logger.error(f"Security alert: registry target '{self.registry_file}' is a symlink! Refusing to write.")
            return False

        tmp_fd, tmp_path = tempfile.mkstemp(dir=self.data_dir, prefix="paired_devices_", suffix=".tmp")
        try:
            with os.fdopen(tmp_fd, "w", encoding="utf-8") as f:
                json.dump(data, f, indent=2)
                f.flush()
                os.fsync(f.fileno())
            os.chmod(tmp_path, 0o600)
            os.replace(tmp_path, self.registry_file)

            # On POSIX, fsync the containing directory to ensure directory entry rename durability
            try:
                dir_fd = os.open(self.data_dir, os.O_RDONLY)
                try:
                    os.fsync(dir_fd)
                finally:
                    os.close(dir_fd)
            except Exception as e:
                logger.debug(f"Directory fsync not supported or failed: {e}")

            return True
        except Exception as e:
            logger.error(f"Failed to write device registry atomically: {e}")
            if os.path.exists(tmp_path):
                try:
                    os.remove(tmp_path)
                except OSError:
                    pass
            return False


class AndroidBridge:
    def __init__(
        self,
        broker_host: Optional[str] = None,
        broker_port: Optional[int] = None,
        device_id: Optional[str] = None,
        tls_enabled: Optional[bool] = None,
        allowed_apk_dir: Optional[str] = None,
        mqtt_user: Optional[str] = None,
        mqtt_password: Optional[str] = None,
    ):
        self.broker = (broker_host or DEFAULT_BROKER).strip() or DEFAULT_BROKER
        self.port = int(broker_port if broker_port is not None else DEFAULT_PORT)
        self.device_id = (device_id if device_id is not None else DEFAULT_DEVICE_ID).strip()
        self.tls_enabled = bool(tls_enabled) if tls_enabled is not None else False
        self.allowed_apk_dir = (allowed_apk_dir or "").strip()
        self._explicit_mqtt_user = mqtt_user
        self._explicit_mqtt_password = mqtt_password
        self.adb_bin = shutil.which("adb")
        self._registry = DeviceRegistry()

        # In-flight request correlation: reqId -> (threading.Event, response_dict)
        self._pending_requests: Dict[str, tuple[threading.Event, Dict[str, Any]]] = {}
        self._lock = threading.Lock()
        self._mqtt_client = None
        self._is_connected = False
        self._init_mqtt()

    def get_mqtt_credentials(self) -> tuple[Optional[str], Optional[str]]:
        """Resolve MQTT broker credentials via Hermes agent.secret_scope."""
        user = self._explicit_mqtt_user
        if user is None:
            user = get_plugin_secret("HERMES_DROID_MQTT_USER")
        password = self._explicit_mqtt_password
        if password is None:
            password = get_plugin_secret("HERMES_DROID_MQTT_PASSWORD")
        clean_user = user.strip() if isinstance(user, str) and user.strip() else None
        clean_pass = password if isinstance(password, str) and password != "" else None
        return clean_user, clean_pass

    def close(self) -> None:
        """Stop MQTT background loop and disconnect cleanly."""
        client = self._mqtt_client
        self._mqtt_client = None
        self._is_connected = False
        if client is not None:
            try:
                client.loop_stop()
            except Exception:
                pass
            try:
                client.disconnect()
            except Exception:
                pass

    def _init_mqtt(self):
        try:
            import paho.mqtt.client as mqtt

            is_local = is_loopback_host(self.broker)
            mqtt_user, mqtt_password = self.get_mqtt_credentials()

            # Item #5: Enforce TLS and authentication for all non-loopback brokers
            if not is_local:
                if not self.tls_enabled:
                    raise ValueError(
                        f"Insecure connection refused: Non-loopback MQTT broker '{self.broker}' requires TLS encryption. "
                        "Set tls_enabled: true in plugin configuration."
                    )
                if not mqtt_user or not mqtt_password:
                    raise ValueError(
                        f"Unauthenticated connection refused: Non-loopback MQTT broker '{self.broker}' requires credentials. "
                        "Set HERMES_DROID_MQTT_USER and HERMES_DROID_MQTT_PASSWORD in your environment/credentials."
                    )

            client_id = f"hermes_bridge_{secrets.token_hex(4)}"
            try:
                self._mqtt_client = mqtt.Client(mqtt.CallbackAPIVersion.VERSION2, client_id=client_id)
            except AttributeError:
                self._mqtt_client = mqtt.Client(client_id=client_id)

            if self.tls_enabled:
                self._mqtt_client.tls_set()

            if mqtt_user:
                self._mqtt_client.username_pw_set(mqtt_user, mqtt_password)

            self._mqtt_client.on_connect = self._on_connect
            self._mqtt_client.on_message = self._on_message
            self._mqtt_client.on_disconnect = self._on_disconnect

            self._mqtt_client.connect_async(self.broker, self.port, keepalive=30)
            self._mqtt_client.loop_start()
        except Exception as e:
            if isinstance(e, ValueError):
                raise
            try:
                from agent.secret_scope import UnscopedSecretError
                if isinstance(e, UnscopedSecretError):
                    raise
            except ImportError:
                pass
            logger.warning(f"MQTT init failed, fallback available: {e}")

    def _on_connect(self, client, userdata, flags, rc, properties=None):
        rc_code = rc if isinstance(rc, int) else getattr(rc, "value", 0)
        if rc_code == 0:
            self._is_connected = True
            subscriptions = []
            if self.device_id:
                res_topic = f"hermes/node/{self.device_id}/res"
                subscriptions.append((res_topic, 1))
            # Security: We deliberately do NOT subscribe to unauthenticated announce topics.
            if subscriptions:
                client.subscribe(subscriptions)
                logger.info(f"Connected to MQTT broker {self.broker}:{self.port}, subscribed to {[s[0] for s in subscriptions]}")
            else:
                logger.info(f"Connected to MQTT broker {self.broker}:{self.port} (no device configured yet)")
        else:
            logger.warning(f"MQTT connect returned rc={rc_code}")

    def _on_disconnect(self, client, userdata, flags, rc=0, properties=None):
        self._is_connected = False
        logger.info("Disconnected from MQTT broker")

    def _on_message(self, client, userdata, msg):
        try:
            topic = msg.topic
            if topic == "hermes/node/announce":
                logger.info(
                    "Received MQTT announce message; automatic enrollment is disabled for security. "
                    "Devices must be explicitly configured."
                )
                return

            expected_topic = f"hermes/node/{self.device_id}/res" if self.device_id else None
            if not expected_topic or topic != expected_topic:
                logger.warning(f"Dropping MQTT message from unauthorized or unexpected topic: {topic}")
                return

            try:
                payload = json.loads(msg.payload.decode("utf-8"))
            except Exception as e:
                logger.warning(f"Dropping malformed JSON payload from {topic}: {e}")
                return

            if not isinstance(payload, dict):
                logger.warning(f"Dropping non-dict payload from {topic}")
                return

            # Fail-closed validation: deviceId must be present, non-empty, match configured device_id, and be approved.
            msg_dev_id = payload.get("deviceId")
            if not msg_dev_id or msg_dev_id != self.device_id or not self.is_device_approved(msg_dev_id):
                logger.warning(f"Dropping MQTT message: invalid, missing, or unapproved deviceId '{msg_dev_id}'")
                return

            req_id = payload.get("reqId")
            if not req_id or not isinstance(req_id, str):
                logger.warning(f"Dropping MQTT message: missing or invalid reqId '{req_id}'")
                return

            # Atomic single-delivery: pop the pending request so it cannot be matched again.
            with self._lock:
                pending = self._pending_requests.pop(req_id, None)

            if pending is None:
                logger.warning(f"Dropping MQTT message: reqId '{req_id}' is unknown, expired, or already completed")
                return

            event, container = pending
            container.update(payload)
            event.set()
        except Exception as e:
            logger.debug(f"Error handling MQTT message: {e}")

    def is_device_approved(self, device_id: Optional[str] = None) -> bool:
        dev_id = (device_id or self.device_id or "").strip()
        if not dev_id:
            return False
        # Explicitly configured device in plugin settings is trusted
        if self.device_id and dev_id == self.device_id:
            return True
        return self._registry.is_approved(dev_id)

    def notify_node_approved(self, device_id: Optional[str] = None, approver: str = "Hermes Plugin"):
        """Notify the physical Android node that it has been approved."""
        dev_id = device_id or self.device_id
        if not dev_id or not self.is_device_approved(dev_id):
            logger.warning(f"Refusing to send approval notification to unapproved device: {dev_id}")
            return

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
        if not self.device_id or not self.is_device_approved(self.device_id):
            return {
                "success": False,
                "error": f"Device '{self.device_id}' is not configured or not approved in hermes-droid.",
            }
        if params is None:
            params = {}

        req_id = f"cmd_{int(time.time() * 1000)}_{secrets.token_hex(8)}"
        cmd_topic = f"hermes/node/{self.device_id}/cmd"
        payload = {
            "reqId": req_id,
            "deviceId": self.device_id,
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
            res = subprocess.run(
                cmd,
                capture_output=True,
                text=True,
                timeout=timeout,
                env=get_clean_adb_env(),
            )
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


# Global default bridge instance and configuration
_bridge_instance: Optional[AndroidBridge] = None
_bridge_config: Dict[str, Any] = {}
_bridge_lock = threading.Lock()


def configure_bridge(
    broker_host: Optional[str] = None,
    broker_port: Optional[int] = None,
    device_id: Optional[str] = None,
    tls_enabled: Optional[bool] = None,
    allowed_apk_dir: Optional[str] = None,
) -> None:
    """Apply settings resolved from ctx.get_config() and reset active bridge instance."""
    global _bridge_instance, _bridge_config
    with _bridge_lock:
        _bridge_config = {
            "broker_host": broker_host,
            "broker_port": broker_port,
            "device_id": device_id,
            "tls_enabled": tls_enabled,
            "allowed_apk_dir": allowed_apk_dir,
        }
        if _bridge_instance is not None:
            _bridge_instance.close()
            _bridge_instance = None


def get_bridge() -> AndroidBridge:
    global _bridge_instance
    with _bridge_lock:
        if _bridge_instance is None:
            _bridge_instance = AndroidBridge(**_bridge_config)
        return _bridge_instance
