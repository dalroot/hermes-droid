"""Tool handlers for hermes-droid tools."""

from __future__ import annotations

import json
import os
import re
import subprocess
import tempfile
import time
from typing import Any, Dict, Optional, Tuple

from .bridge import get_bridge, get_clean_adb_env, get_plugin_data_dir

STATE_CHANGING_TOOLS = frozenset({
    "android_adb_shell",
    "android_send_sms",
    "android_call_phone",
    "android_install_apk",
    "android_reply_notification",
    "android_radio_silence",
    "android_toggle_setting",
    "android_device_alarm",
})


def _parse_bounded_int(val: Any, min_val: int, max_val: int, param_name: str) -> Tuple[Optional[int], Optional[str]]:
    """Parse and strictly validate an integer parameter, rejecting booleans, floats, and out-of-bounds values."""
    if isinstance(val, bool):
        return None, f"Parameter '{param_name}' must be an integer, not a boolean."
    if isinstance(val, float):
        return None, f"Parameter '{param_name}' must be an integer, not a float."
    try:
        if isinstance(val, str):
            val_str = val.strip()
            if not (val_str.isdigit() or (val_str.startswith("-") and val_str[1:].isdigit())):
                return None, f"Parameter '{param_name}' must be a valid integer."
            parsed = int(val_str)
        elif isinstance(val, int):
            parsed = val
        else:
            return None, f"Parameter '{param_name}' must be a valid integer."
    except (ValueError, TypeError):
        return None, f"Parameter '{param_name}' must be a valid integer."

    if not (min_val <= parsed <= max_val):
        return None, f"Parameter '{param_name}' must be an integer between {min_val} and {max_val} (got {parsed})."

    return parsed, None


def is_unattended_or_cron(ctx: Any = None) -> bool:
    """Check if the current execution is running inside an unattended or cron session."""
    if ctx is not None and hasattr(ctx, "current_cron_execution") and callable(ctx.current_cron_execution):
        try:
            if ctx.current_cron_execution() is not None:
                return True
        except Exception:
            pass

    cron_env = str(os.environ.get("HERMES_CRON_SESSION", "")).strip().lower()
    if cron_env in ("1", "true", "yes", "t", "y"):
        return True

    sq_env = str(os.environ.get("HERMES_SINGLE_QUERY_SESSION", "")).strip().lower()
    if sq_env in ("1", "true", "yes", "t", "y"):
        return True

    return False


def is_state_changing_tool(tool_name: str, args: Optional[Dict[str, Any]] = None) -> bool:
    """Determine whether an Android tool call alters physical device, telecom, or system state."""
    if tool_name in STATE_CHANGING_TOOLS:
        return True
    if tool_name == "android_clipboard_sync":
        args_dict = args if isinstance(args, dict) else {}
        action = str(args_dict.get("action", "")).strip().lower()
        text = args_dict.get("text")
        if action == "set" or (text is not None and str(text) != ""):
            return True
    return False


def _cron_refusal_json(tool_name: str) -> str:
    """Standardized fail-closed error payload for state-changing operations in cron/unattended runs."""
    return json.dumps({
        "success": False,
        "error": f"State-changing tool '{tool_name}' is refused in unattended or cron execution without an interactive user.",
    }, ensure_ascii=False, indent=2)


def handle_device_info(params: Dict[str, Any], **kwargs) -> str:
    """Fetch live Android device information."""
    bridge = get_bridge()
    res = bridge.send_request("api/v1/device/info")
    if res.get("success"):
        return json.dumps(res.get("data", res), ensure_ascii=False, indent=2)

    # Fallback to ADB getprop / dumpsys battery
    adb_stat = bridge.exec_adb_command(["shell", "dumpsys", "battery"])
    adb_model = bridge.exec_adb_command(["shell", "getprop", "ro.product.model"])
    adb_os = bridge.exec_adb_command(["shell", "getprop", "ro.build.version.release"])

    if adb_stat.get("success"):
        data = {
            "source": "adb_fallback",
            "model": adb_model.get("stdout", "Unknown"),
            "android_version": adb_os.get("stdout", "Unknown"),
            "battery_dump": adb_stat.get("stdout", ""),
        }
        return json.dumps(data, ensure_ascii=False, indent=2)

    return json.dumps({
        "error": "Failed to connect to Hermes Droid companion app or ADB.",
        "details": res.get("error", "No route to host")
    }, indent=2)


def handle_read_sms(params: Dict[str, Any], **kwargs) -> str:
    """Read SMS messages from Android device."""
    raw_limit = params.get("limit", 5)
    limit, err = _parse_bounded_int(raw_limit, 1, 50, "limit")
    if err:
        return json.dumps({"error": err}, indent=2)

    bridge = get_bridge()
    payload = {
        "limit": limit,
        "sender": params.get("sender"),
        "query": params.get("query"),
    }
    res = bridge.send_request("api/v1/telephony/sms/read", payload)
    if res.get("success"):
        return json.dumps(res.get("data", res), ensure_ascii=False, indent=2)

    # Fallback to Termux SMS API if running locally on Termux
    termux_sms = bridge.exec_adb_command(["shell", "termux-sms-list", "-l", str(limit)])
    if termux_sms.get("success") and termux_sms.get("stdout"):
        return termux_sms["stdout"]

    return json.dumps({
        "error": "Could not read SMS. Make sure SMS permission is granted in Hermes Droid companion app.",
        "details": res.get("error")
    }, indent=2)


def handle_send_sms(params: Dict[str, Any], **kwargs) -> str:
    """Send SMS to recipient."""
    if is_unattended_or_cron():
        return _cron_refusal_json("android_send_sms")
    recipient = params.get("recipient")
    message = params.get("message")
    if not recipient or not message:
        return json.dumps({"error": "recipient and message are required"})

    bridge = get_bridge()
    res = bridge.send_request("api/v1/telephony/sms/send", {
        "recipient": recipient,
        "message": message,
    })
    if res.get("success"):
        return json.dumps({"status": "sent", "recipient": recipient, "timestamp": time.time()})

    # ADB intent fallback
    adb_intent = bridge.exec_adb_command([
        "shell", "service", "call", "isms", "7", "i32", "0", "s16", "com.android.mms",
        "s16", recipient, "s16", "null", "s16", message, "s16", "null", "s16", "null"
    ])
    if adb_intent.get("success"):
        return json.dumps({"status": "dispatched_via_adb", "recipient": recipient})

    return json.dumps({"error": "Failed to send SMS.", "details": res.get("error")})


def handle_get_notifications(params: Dict[str, Any], **kwargs) -> str:
    """Get active incoming notifications."""
    bridge = get_bridge()
    payload = {
        "package_name": params.get("package_name"),
        "limit": params.get("limit", 10),
    }
    res = bridge.send_request("api/v1/notifications/list", payload)
    if res.get("success"):
        return json.dumps(res.get("data", res), ensure_ascii=False, indent=2)

    # Fallback to dumpsys notification
    adb_notif = bridge.exec_adb_command(["shell", "dumpsys", "notification", "--noredact"])
    if adb_notif.get("success"):
        # Truncate and return sample
        lines = [line.strip() for line in adb_notif["stdout"].splitlines() if "tickerText" in line or "pkg=" in line]
        return json.dumps({"source": "adb_dumpsys", "notifications": lines[:params.get("limit", 10)]}, indent=2)

    return json.dumps({"error": "Failed to fetch notifications. Ensure Notification Listener permission is active in Hermes Droid."})


def handle_reply_notification(params: Dict[str, Any], **kwargs) -> str:
    """Send inline reply to active notification."""
    if is_unattended_or_cron():
        return _cron_refusal_json("android_reply_notification")
    bridge = get_bridge()
    res = bridge.send_request("api/v1/notifications/reply", {
        "notification_key": params.get("notification_key"),
        "reply_text": params.get("reply_text"),
    })
    return json.dumps(res, ensure_ascii=False, indent=2)


def handle_call_phone(params: Dict[str, Any], **kwargs) -> str:
    """Place phone call or open dialer."""
    if is_unattended_or_cron():
        return _cron_refusal_json("android_call_phone")
    phone_number = params.get("phone_number")
    direct = params.get("direct", False)
    bridge = get_bridge()

    res = bridge.send_request("api/v1/telephony/call", {
        "phone_number": phone_number,
        "direct": direct,
    })
    if res.get("success"):
        return json.dumps(res, indent=2)

    # Fallback to ADB intent
    action = "android.intent.action.CALL" if direct else "android.intent.action.DIAL"
    adb_call = bridge.exec_adb_command(["shell", "am", "start", "-a", action, "-d", f"tel:{phone_number}"])
    return json.dumps({
        "status": "initiated",
        "phone_number": phone_number,
        "direct": direct,
        "adb_result": adb_call.get("stdout")
    }, indent=2)


def handle_adb_shell(params: Dict[str, Any], **kwargs) -> str:
    """Execute shell command on device."""
    if is_unattended_or_cron():
        return _cron_refusal_json("android_adb_shell")
    command = params.get("command")
    timeout = params.get("timeout_seconds", 15)
    bridge = get_bridge()

    # 1. Try companion app shell API
    res = bridge.send_request("api/v1/terminal/exec", {"command": command, "timeout": timeout}, timeout=timeout + 2)
    if res.get("success"):
        return json.dumps(res.get("data", res), ensure_ascii=False, indent=2)

    # 2. Try direct ADB shell
    adb_res = bridge.exec_adb_command(["shell", command], timeout=timeout)
    return json.dumps(adb_res, ensure_ascii=False, indent=2)


def handle_install_apk(params: Dict[str, Any], **kwargs) -> str:
    """Install APK package onto device wirelessly over MQTT or ADB fallback."""
    if is_unattended_or_cron():
        return _cron_refusal_json("android_install_apk")
    import base64
    import hashlib

    apk_path = params.get("apk_path")
    reinstall = params.get("reinstall", True)
    bridge = get_bridge()

    if not apk_path:
        return json.dumps({"success": False, "error": "apk_path is required"})

    # 1. Remote URL case
    raw_path = str(apk_path).strip()
    if raw_path.startswith("http://") or raw_path.startswith("https://"):
        sha256 = params.get("sha256", "")
        mqtt_res = bridge.send_command("install_apk", {
            "url": raw_path,
            "sha256": sha256,
        }, timeout=60.0)
        if mqtt_res.get("success"):
            return json.dumps(mqtt_res, ensure_ascii=False, indent=2)
        return json.dumps(mqtt_res, indent=2)

    # 2. Local file path -> enforce .apk extension and path containment inside allowed directory (Finding #4)
    if not raw_path.lower().endswith(".apk"):
        return json.dumps({
            "success": False,
            "error": f"Security policy violation: file '{raw_path}' must have a '.apk' extension.",
        })

    # Determine allowed directory: user-configured allowed_apk_dir or plugin data directory / apks
    configured_dir = getattr(bridge, "allowed_apk_dir", "")
    if configured_dir and str(configured_dir).strip():
        allowed_dir = os.path.realpath(os.path.abspath(os.path.expanduser(str(configured_dir).strip())))
    else:
        allowed_dir = os.path.realpath(os.path.join(get_plugin_data_dir(), "apks"))
    os.makedirs(allowed_dir, mode=0o700, exist_ok=True)

    resolved_apk = os.path.realpath(os.path.abspath(os.path.expanduser(raw_path)))

    try:
        is_contained = (resolved_apk != allowed_dir) and (os.path.commonpath([resolved_apk, allowed_dir]) == allowed_dir)
    except ValueError:
        is_contained = False

    if not is_contained:
        return json.dumps({
            "success": False,
            "error": (
                f"Path traversal security violation: APK path '{raw_path}' resolves to '{resolved_apk}', "
                f"which is outside the allowed directory '{allowed_dir}'."
            ),
        })

    if not os.path.isfile(resolved_apk):
        return json.dumps({"success": False, "error": f"Local APK file not found at '{resolved_apk}'."})

    mqtt_err = None
    try:
        with open(resolved_apk, "rb") as f:
            content = f.read()
        file_sha256 = hashlib.sha256(content).hexdigest()
        b64_content = base64.b64encode(content).decode("ascii")

        mqtt_res = bridge.send_command("install_apk", {
            "apk_b64": b64_content,
            "sha256": file_sha256,
            "reinstall": reinstall,
        }, timeout=60.0)

        if mqtt_res.get("success"):
            return json.dumps(mqtt_res, ensure_ascii=False, indent=2)
        mqtt_err = mqtt_res.get("error", "MQTT install failed")
    except Exception as e:
        mqtt_err = str(e)

    # Secondary ADB fallback if USB cable is physically attached
    adb_args = ["install"]
    if reinstall:
        adb_args.append("-r")
    adb_args.append(resolved_apk)
    res = bridge.exec_adb_command(adb_args, timeout=60)
    if res.get("success"):
        return json.dumps(res, indent=2)

    return json.dumps({
        "success": False,
        "error": "Failed to install APK wirelessly and ADB fallback was unavailable.",
        "mqtt_details": mqtt_err,
        "adb_details": res.get("error"),
    }, indent=2)


def handle_toggle_setting(params: Dict[str, Any], **kwargs) -> str:
    """Control hardware feature / setting."""
    if is_unattended_or_cron():
        return _cron_refusal_json("android_toggle_setting")
    setting = params.get("setting")
    value = params.get("value")
    bridge = get_bridge()

    res = bridge.send_request("api/v1/device/setting", {"setting": setting, "value": value})
    if res.get("success"):
        return json.dumps(res, indent=2)

    # ADB fallbacks for common settings
    if setting == "screen_awake":
        val_code = "7" if value == "on" else "0"
        bridge.exec_adb_command(["shell", "svc", "power", "stayon", "true" if value == "on" else "false"])
        return json.dumps({"status": "updated_via_adb", "setting": setting, "value": value})

    return json.dumps({"error": f"Setting {setting} could not be updated.", "details": res.get("error")})


def handle_clipboard_sync(params: Dict[str, Any], **kwargs) -> str:
    """Read or set clipboard text."""
    action = params.get("action")
    text = params.get("text", "")
    if (str(action).strip().lower() == "set" or (text is not None and str(text) != "")) and is_unattended_or_cron():
        return _cron_refusal_json("android_clipboard_sync")
    bridge = get_bridge()

    res = bridge.send_request("api/v1/device/clipboard", {"action": action, "text": text})
    return json.dumps(res, ensure_ascii=False, indent=2)


def handle_take_screenshot(params: Dict[str, Any], **kwargs) -> str:
    """Capture screen and download (confined strictly to plugin data directory)."""
    media_dir = os.path.realpath(os.path.join(get_plugin_data_dir(), "media"))
    os.makedirs(media_dir, mode=0o700, exist_ok=True)

    raw_dest = params.get("destination_path")
    if raw_dest and str(raw_dest).strip():
        filename = os.path.basename(str(raw_dest).strip())
        if not filename.lower().endswith(".png"):
            filename = f"{filename}.png"
    else:
        filename = f"screenshot_{int(time.time())}.png"

    dest = os.path.realpath(os.path.join(media_dir, filename))
    if os.path.commonpath([dest, media_dir]) != media_dir:
        dest = os.path.join(media_dir, f"screenshot_{int(time.time())}.png")

    bridge = get_bridge()

    # 1. Try companion app screenshot
    res = bridge.send_request("api/v1/device/screenshot")
    if res.get("success") and res.get("image_base64"):
        import base64
        with open(dest, "wb") as f:
            f.write(base64.b64decode(res["image_base64"]))
        return json.dumps({"status": "saved", "path": dest})

    # 2. Try ADB exec-out screencap
    if bridge.adb_bin:
        try:
            cmd = [bridge.adb_bin]
            if bridge.device_id:
                cmd.extend(["-s", bridge.device_id])
            cmd.extend(["exec-out", "screencap", "-p"])
            with open(dest, "wb") as f:
                subprocess.run(cmd, stdout=f, timeout=10, env=get_clean_adb_env())
            if os.path.exists(dest) and os.path.getsize(dest) > 0:
                return json.dumps({"status": "saved_via_adb", "path": dest, "size_bytes": os.path.getsize(dest)})
        except Exception as e:
            return json.dumps({"error": f"ADB screenshot failed: {e}"})

    return json.dumps({"error": "Failed to capture screenshot."})


def handle_contacts_search(params: Dict[str, Any], **kwargs) -> str:
    """Search address book contacts safely without SQL or shell command injection."""
    raw_query = params.get("query", "")
    query = str(raw_query).strip() if raw_query is not None else ""
    if not query:
        return json.dumps({
            "success": False,
            "error": "Search query cannot be empty.",
        })
    if len(query) > 100:
        return json.dumps({
            "success": False,
            "error": "Search query exceeds maximum length of 100 characters.",
        })

    raw_limit = params.get("limit", 10)
    limit, limit_err = _parse_bounded_int(raw_limit, 1, 50, "limit")
    if limit_err:
        limit = 10  # fallback safely to default for contacts

    bridge = get_bridge()

    # Layer 1: Reject quotes, shell metacharacters, and dangerous SQL injection syntax
    shell_and_sql_forbidden = (
        "'", '"', ";", "\\", "$", "`", "|", "&", ">", "<", "\n", "\r",
        "(", ")", "{", "}", "*", "?", "~", "!", "^"
    )
    if any(char in query for char in shell_and_sql_forbidden) or "--" in query or "/*" in query or "*/" in query:
        return json.dumps({
            "success": False,
            "error": "Invalid search query: shell metacharacters, quotes, semicolons, backslashes, and SQL comment markers are forbidden in contacts search.",
        })

    # Layer 2: SQL keyword rejection
    upper_query = f" {query.upper()} "
    dangerous_keywords = (" UNION ", " SELECT ", " DROP ", " DELETE ", " UPDATE ", " INSERT ", " ALTER ", " EXEC ")
    if any(kw in upper_query for kw in dangerous_keywords):
        return json.dumps({
            "success": False,
            "error": "Invalid search query: SQL statement keywords are forbidden in contacts search.",
        })

    # Layer 3: Whitelist pattern - allow only Unicode word characters, spaces, and safe punctuation (.-_@+)
    if not re.match(r"^[\w\s.\-@+]+$", query, re.UNICODE):
        return json.dumps({
            "success": False,
            "error": "Invalid search query: only alphanumeric characters, spaces, and safe punctuation (.-_@+) are permitted.",
        })

    safe_query = query.replace("'", "''")

    res = bridge.send_request("api/v1/contacts/search", {"query": query, "limit": limit})
    if res.get("success"):
        return json.dumps(res.get("data", res), ensure_ascii=False, indent=2)

    # ADB content query fallback
    adb_res = bridge.exec_adb_command([
        "shell", "content", "query", "--uri", "content://com.android.contacts/data/phones",
        "--projection", "display_name:data1",
        "--where", f"display_name LIKE '%{safe_query}%'"
    ])
    if adb_res.get("success"):
        return json.dumps({"source": "adb_contacts", "raw": adb_res.get("stdout")}, indent=2)

    return json.dumps({"error": "Failed to search contacts.", "details": res.get("error")})


def handle_media_recent_photos(params: Dict[str, Any], **kwargs) -> str:
    """Fetch recent photos metadata/base64."""
    limit = params.get("limit", 5)
    include_b64 = params.get("include_base64", False)
    bridge = get_bridge()

    res = bridge.send_request("api/v1/media/photos", {"limit": limit, "include_base64": include_b64})
    if res.get("success"):
        return json.dumps(res.get("data", res), ensure_ascii=False, indent=2)

    return json.dumps({"error": "Failed to query media storage.", "details": res.get("error")})


def handle_device_logs(params: Dict[str, Any], **kwargs) -> str:
    """Retrieve specialized device logs wirelessly over MQTT or ADB fallback."""
    raw_buffer = params.get("buffer", "system")
    buffer = str(raw_buffer).strip().lower() if raw_buffer is not None else "system"
    allowed_buffers = {"main", "system", "radio", "crash", "dumpsys"}
    if buffer not in allowed_buffers:
        return json.dumps({
            "success": False,
            "error": f"Invalid log buffer '{raw_buffer}'. Must be one of: {', '.join(sorted(allowed_buffers))}.",
        })

    raw_lines = params.get("lines", 50)
    lines, err = _parse_bounded_int(raw_lines, 1, 2000, "lines")
    if err:
        return json.dumps({"success": False, "error": err})

    bridge = get_bridge()

    res = bridge.send_request("api/v1/device/logs", {"buffer": buffer, "lines": lines})
    if res.get("success"):
        return json.dumps(res.get("data", res), ensure_ascii=False, indent=2)

    # Secondary ADB direct logcat / dumpsys fallback if USB attached
    if buffer == "dumpsys":
        adb_res = bridge.exec_adb_command(["shell", "dumpsys", "battery"])
    else:
        adb_res = bridge.exec_adb_command(["shell", "logcat", "-b", buffer, "-d", "-t", str(lines)])

    if adb_res.get("success"):
        return json.dumps({"source": "adb_logcat", "buffer": buffer, "content": adb_res.get("stdout")}, indent=2)

    return json.dumps({
        "success": False,
        "error": "Failed to retrieve logs wirelessly over MQTT and ADB cable fallback was not connected.",
        "mqtt_details": res.get("error"),
        "hint": "Ensure the phone is online and paired on the Hermes Droid MQTT network. No USB cable is needed in wireless mode."
    }, indent=2)


def handle_radio_silence(params: Dict[str, Any], **kwargs) -> str:
    """Toggle full radio silence."""
    if is_unattended_or_cron():
        return _cron_refusal_json("android_radio_silence")
    enable = params.get("enable", True)
    bridge = get_bridge()

    res = bridge.send_request("api/v1/device/radio_silence", {"enable": enable})
    if res.get("success"):
        return json.dumps(res, indent=2)

    # ADB fallback: svc wifi/data disable + airplane mode
    if enable:
        bridge.exec_adb_command(["shell", "cmd", "connectivity", "airplane-mode", "enable"])
        bridge.exec_adb_command(["shell", "svc", "wifi", "disable"])
        bridge.exec_adb_command(["shell", "svc", "data", "disable"])
        bridge.exec_adb_command(["shell", "svc", "bluetooth", "disable"])
        return json.dumps({"status": "radio_silence_enabled_via_adb"})
    else:
        bridge.exec_adb_command(["shell", "cmd", "connectivity", "airplane-mode", "disable"])
        bridge.exec_adb_command(["shell", "svc", "wifi", "enable"])
        bridge.exec_adb_command(["shell", "svc", "data", "enable"])
        return json.dumps({"status": "radio_silence_disabled_via_adb"})


def handle_camera_snapshot(params: Dict[str, Any], **kwargs) -> str:
    """Take instant camera photo (confined strictly to plugin data directory)."""
    media_dir = os.path.realpath(os.path.join(get_plugin_data_dir(), "media"))
    os.makedirs(media_dir, mode=0o700, exist_ok=True)

    camera = params.get("camera", "back")
    raw_dest = params.get("destination_path")
    if raw_dest and str(raw_dest).strip():
        filename = os.path.basename(str(raw_dest).strip())
        if not (filename.lower().endswith(".jpg") or filename.lower().endswith(".jpeg") or filename.lower().endswith(".png")):
            filename = f"{filename}.jpg"
    else:
        filename = f"camera_{int(time.time())}.jpg"

    dest = os.path.realpath(os.path.join(media_dir, filename))
    if os.path.commonpath([dest, media_dir]) != media_dir:
        dest = os.path.join(media_dir, f"camera_{int(time.time())}.jpg")

    bridge = get_bridge()

    res = bridge.send_request("api/v1/device/camera_snapshot", {"camera": camera})
    if res.get("success") and res.get("image_base64"):
        import base64
        with open(dest, "wb") as f:
            f.write(base64.b64decode(res["image_base64"]))
        return json.dumps({"status": "captured", "path": dest, "camera": camera})

    return json.dumps({"error": "Failed to capture camera snapshot.", "details": res.get("error")})


def handle_location_get(params: Dict[str, Any], **kwargs) -> str:
    """Fetch GPS location coordinates."""
    bridge = get_bridge()
    res = bridge.send_request("api/v1/device/location")
    if res.get("success"):
        return json.dumps(res.get("data", res), indent=2)

    # ADB dumpsys location fallback
    adb_loc = bridge.exec_adb_command(["shell", "dumpsys", "location"])
    if adb_loc.get("success"):
        lines = [l.strip() for l in adb_loc["stdout"].splitlines() if "last location" in l.lower() or "gps" in l.lower()]
        return json.dumps({"source": "adb_dumpsys_location", "data": lines[:10]}, indent=2)

    return json.dumps({"error": "Failed to query GPS location.", "details": res.get("error")})


def handle_device_alarm(params: Dict[str, Any], **kwargs) -> str:
    """Sound device buzzer / siren."""
    if is_unattended_or_cron():
        return _cron_refusal_json("android_device_alarm")
    duration = params.get("duration_seconds", 10)
    bridge = get_bridge()
    res = bridge.send_request("api/v1/device/alarm", {"duration_seconds": duration})
    return json.dumps(res, indent=2)
