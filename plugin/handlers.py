"""Tool handlers for hermes-droid tools."""

from __future__ import annotations

import json
import os
import tempfile
import time
from typing import Any, Dict

from .bridge import get_bridge


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
    bridge = get_bridge()
    payload = {
        "limit": params.get("limit", 5),
        "sender": params.get("sender"),
        "query": params.get("query"),
    }
    res = bridge.send_request("api/v1/telephony/sms/read", payload)
    if res.get("success"):
        return json.dumps(res.get("data", res), ensure_ascii=False, indent=2)

    # Fallback to Termux SMS API if running locally on Termux
    termux_sms = bridge.exec_adb_command(["shell", "termux-sms-list", "-l", str(params.get("limit", 5))])
    if termux_sms.get("success") and termux_sms.get("stdout"):
        return termux_sms["stdout"]

    return json.dumps({
        "error": "Could not read SMS. Make sure SMS permission is granted in Hermes Droid companion app.",
        "details": res.get("error")
    }, indent=2)


def handle_send_sms(params: Dict[str, Any], **kwargs) -> str:
    """Send SMS to recipient."""
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
    bridge = get_bridge()
    res = bridge.send_request("api/v1/notifications/reply", {
        "notification_key": params.get("notification_key"),
        "reply_text": params.get("reply_text"),
    })
    return json.dumps(res, ensure_ascii=False, indent=2)


def handle_call_phone(params: Dict[str, Any], **kwargs) -> str:
    """Place phone call or open dialer."""
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
    import base64
    import hashlib

    apk_path = params.get("apk_path")
    reinstall = params.get("reinstall", True)
    bridge = get_bridge()

    if not apk_path:
        return json.dumps({"error": "apk_path is required"})

    # 1. Local file path -> calculate sha256 and base64 encode for wireless MQTT deployment
    if os.path.exists(apk_path):
        mqtt_err = None
        try:
            with open(apk_path, "rb") as f:
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
        adb_args.append(apk_path)
        res = bridge.exec_adb_command(adb_args, timeout=60)
        if res.get("success"):
            return json.dumps(res, indent=2)

        return json.dumps({
            "success": False,
            "error": "Failed to install APK wirelessly and ADB fallback was unavailable.",
            "mqtt_details": mqtt_err,
            "adb_details": res.get("error"),
        }, indent=2)

    # 2. Remote URL case
    if apk_path.startswith("http://") or apk_path.startswith("https://"):
        sha256 = params.get("sha256", "")
        mqtt_res = bridge.send_command("install_apk", {
            "url": apk_path,
            "sha256": sha256,
        }, timeout=60.0)
        if mqtt_res.get("success"):
            return json.dumps(mqtt_res, ensure_ascii=False, indent=2)
        return json.dumps(mqtt_res, indent=2)

    return json.dumps({"error": f"Local APK file not found at {apk_path}"})


def handle_toggle_setting(params: Dict[str, Any], **kwargs) -> str:
    """Control hardware feature / setting."""
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
    bridge = get_bridge()

    res = bridge.send_request("api/v1/device/clipboard", {"action": action, "text": text})
    return json.dumps(res, ensure_ascii=False, indent=2)


def handle_take_screenshot(params: Dict[str, Any], **kwargs) -> str:
    """Capture screen and download."""
    dest = params.get("destination_path") or os.path.join(tempfile.gettempdir(), f"screenshot_{int(time.time())}.png")
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
            with open(dest, "wb") as f:
                subprocess.run([bridge.adb_bin, "exec-out", "screencap", "-p"], stdout=f, timeout=10)
            if os.path.exists(dest) and os.path.getsize(dest) > 0:
                return json.dumps({"status": "saved_via_adb", "path": dest, "size_bytes": os.path.getsize(dest)})
        except Exception as e:
            return json.dumps({"error": f"ADB screenshot failed: {e}"})

    return json.dumps({"error": "Failed to capture screenshot."})


def handle_contacts_search(params: Dict[str, Any], **kwargs) -> str:
    """Search address book contacts."""
    query = params.get("query", "")
    limit = params.get("limit", 10)
    bridge = get_bridge()

    res = bridge.send_request("api/v1/contacts/search", {"query": query, "limit": limit})
    if res.get("success"):
        return json.dumps(res.get("data", res), ensure_ascii=False, indent=2)

    # ADB content query fallback
    adb_res = bridge.exec_adb_command([
        "shell", "content", "query", "--uri", "content://com.android.contacts/data/phones",
        "--projection", "display_name:data1",
        "--where", f"display_name LIKE '%{query}%'"
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
    buffer = params.get("buffer", "system")
    lines = params.get("lines", 50)
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
    """Take instant camera photo."""
    camera = params.get("camera", "back")
    dest = params.get("destination_path") or os.path.join(tempfile.gettempdir(), f"camera_{int(time.time())}.jpg")
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
    duration = params.get("duration_seconds", 10)
    bridge = get_bridge()
    res = bridge.send_request("api/v1/device/alarm", {"duration_seconds": duration})
    return json.dumps(res, indent=2)
