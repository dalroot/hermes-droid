"""Hermes Droid Plugin for Hermes Agent.

Exposes tools to interact with Android devices directly or via the Hermes Droid companion app.
"""

from __future__ import annotations

import logging
import os
from typing import Any

from .bridge import configure_bridge, get_bridge
from .handlers import (
    STATE_CHANGING_TOOLS,
    handle_adb_shell,
    handle_call_phone,
    handle_camera_snapshot,
    handle_clipboard_sync,
    handle_contacts_search,
    handle_device_alarm,
    handle_device_info,
    handle_device_logs,
    handle_get_notifications,
    handle_install_apk,
    handle_location_get,
    handle_media_recent_photos,
    handle_radio_silence,
    handle_read_sms,
    handle_reply_notification,
    handle_send_sms,
    handle_take_screenshot,
    handle_toggle_setting,
    is_state_changing_tool,
    is_unattended_or_cron,
)
from .schemas import (
    ADB_SHELL_SCHEMA,
    CALL_PHONE_SCHEMA,
    CAMERA_SNAPSHOT_SCHEMA,
    CLIPBOARD_SYNC_SCHEMA,
    CONTACTS_SEARCH_SCHEMA,
    DEVICE_ALARM_SCHEMA,
    DEVICE_INFO_SCHEMA,
    DEVICE_LOGS_SCHEMA,
    GET_NOTIFICATIONS_SCHEMA,
    INSTALL_APK_SCHEMA,
    LOCATION_GET_SCHEMA,
    MEDIA_RECENT_PHOTOS_SCHEMA,
    RADIO_SILENCE_SCHEMA,
    READ_SMS_SCHEMA,
    REPLY_NOTIFICATION_SCHEMA,
    SEND_SMS_SCHEMA,
    TAKE_SCREENSHOT_SCHEMA,
    TOGGLE_SETTING_SCHEMA,
)

logger = logging.getLogger("hermes_droid")

_TOOLS = (
    ("android_device_info", DEVICE_INFO_SCHEMA, handle_device_info, "📱"),
    ("android_read_sms", READ_SMS_SCHEMA, handle_read_sms, "💬"),
    ("android_send_sms", SEND_SMS_SCHEMA, handle_send_sms, "📤"),
    ("android_get_notifications", GET_NOTIFICATIONS_SCHEMA, handle_get_notifications, "🔔"),
    ("android_reply_notification", REPLY_NOTIFICATION_SCHEMA, handle_reply_notification, "↩️"),
    ("android_call_phone", CALL_PHONE_SCHEMA, handle_call_phone, "📞"),
    ("android_adb_shell", ADB_SHELL_SCHEMA, handle_adb_shell, "💻"),
    ("android_install_apk", INSTALL_APK_SCHEMA, handle_install_apk, "📦"),
    ("android_toggle_setting", TOGGLE_SETTING_SCHEMA, handle_toggle_setting, "⚙️"),
    ("android_clipboard_sync", CLIPBOARD_SYNC_SCHEMA, handle_clipboard_sync, "📋"),
    ("android_take_screenshot", TAKE_SCREENSHOT_SCHEMA, handle_take_screenshot, "📸"),
    ("android_contacts_search", CONTACTS_SEARCH_SCHEMA, handle_contacts_search, "👥"),
    ("android_media_recent_photos", MEDIA_RECENT_PHOTOS_SCHEMA, handle_media_recent_photos, "🖼️"),
    ("android_device_logs", DEVICE_LOGS_SCHEMA, handle_device_logs, "📜"),
    ("android_radio_silence", RADIO_SILENCE_SCHEMA, handle_radio_silence, "🔕"),
    ("android_camera_snapshot", CAMERA_SNAPSHOT_SCHEMA, handle_camera_snapshot, "📷"),
    ("android_location_get", LOCATION_GET_SCHEMA, handle_location_get, "📍"),
    ("android_device_alarm", DEVICE_ALARM_SCHEMA, handle_device_alarm, "🚨"),
)


def _read_ctx_config(ctx: Any, key: str, default: Any) -> Any:
    """Read plugin config safely using Hermes PluginContext.get_config()."""
    if hasattr(ctx, "get_config") and callable(ctx.get_config):
        val = ctx.get_config(key, default)
        return default if val is None else val
    return default


def _parse_strict_bool(val: Any, default: bool = False) -> bool:
    """Parse boolean configuration strictly and fail-closed.

    Accepts:
    - Native bool: True / False
    - Case-insensitive exact string literals: 'true' / 'false'
    - None (omitted): returns default

    Rejects:
    - Any other type or string (e.g. 'yes', 'no', '1', 'maybe', 123) with ValueError.
      Never silently defaults or coerces invalid values to False (prevents insecure TLS downgrade).
    """
    if val is None:
        return default
    if isinstance(val, bool):
        return val
    if isinstance(val, str):
        cleaned = val.strip().lower()
        if cleaned == "true":
            return True
        if cleaned == "false":
            return False
    raise ValueError(
        f"Invalid boolean value for config setting: {val!r}. "
        "Must be a boolean (true/false) or string 'true'/'false'."
    )


_active_plugin_context: Any = None


def reset_plugin_context() -> None:
    """Reset the active plugin context (used in cleanup and testing)."""
    global _active_plugin_context
    _active_plugin_context = None


def _format_approval_reason(tool_name: str, args: dict[str, Any]) -> str:
    """Format human-readable explanation for the Hermes approval prompt."""
    if tool_name == "android_send_sms":
        recipient = args.get("recipient", "<unknown>")
        return f"Send SMS message to recipient '{recipient}'."
    if tool_name == "android_call_phone":
        phone_number = args.get("phone_number", "<unknown>")
        return f"Initiate phone call to '{phone_number}'."
    if tool_name == "android_adb_shell":
        cmd = args.get("command", "")
        return f"Execute ADB shell command on device: {cmd!r}"
    if tool_name == "android_install_apk":
        apk = args.get("apk_path", "<unknown>")
        return f"Install APK package '{apk}' onto device."
    if tool_name == "android_reply_notification":
        return "Send automated reply to incoming notification."
    if tool_name == "android_radio_silence":
        enable = args.get("enable", True)
        return f"Change radio silence / airplane mode (enable={enable})."
    if tool_name == "android_toggle_setting":
        setting = args.get("setting", "<unknown>")
        val = args.get("value", "<unknown>")
        return f"Update device system setting '{setting}' to '{val}'."
    if tool_name == "android_device_alarm":
        duration = args.get("duration_seconds", 10)
        return f"Trigger device alarm / siren for {duration} seconds."
    if tool_name == "android_clipboard_sync":
        return "Write text to device system clipboard."
    return f"Execute state-changing Android operation '{tool_name}'."


def _on_pre_tool_call(
    tool_name: str = "",
    args: dict[str, Any] | None = None,
    **kwargs: Any,
) -> dict[str, str] | None:
    """Hermes lifecycle hook intercepting tool execution before dispatch.

    1. Read-only tools return None (allowed to proceed without human prompt).
    2. State-changing tools in unattended cron sessions return action: "block" (fail-closed refusal).
    3. State-changing tools in interactive sessions return action: "approve" (escalates to
       Hermes's native human-approval prompt).
    """
    args_dict = args if isinstance(args, dict) else {}
    if not is_state_changing_tool(tool_name, args_dict):
        return None

    if is_unattended_or_cron(_active_plugin_context):
        logger.warning(
            "Blocked state-changing tool '%s' during unattended or cron execution.",
            tool_name,
        )
        return {
            "action": "block",
            "message": (
                f"BLOCKED: Tool '{tool_name}' alters device state and is strictly refused "
                "in unattended or cron execution without an interactive user present."
            ),
        }

    reason = _format_approval_reason(tool_name, args_dict)
    return {
        "action": "approve",
        "message": reason,
        "rule_key": f"hermes_droid:{tool_name}",
    }


def _on_session_end(**kwargs: Any) -> None:
    """Cleanup hook called on session end."""
    logger.debug("hermes-droid session ended.")


def register(ctx: Any) -> None:
    """Standard registration entry point called by Hermes Agent plugin loader."""
    global _active_plugin_context
    _active_plugin_context = ctx

    broker_host = str(_read_ctx_config(ctx, "broker_host", "127.0.0.1")).strip() or "127.0.0.1"
    raw_port = _read_ctx_config(ctx, "broker_port", 1883)
    try:
        broker_port = int(raw_port)
    except (TypeError, ValueError):
        logger.warning("Invalid broker_port %r in plugin config; using default 1883", raw_port)
        broker_port = 1883
    device_id = str(_read_ctx_config(ctx, "device_id", "")).strip()
    raw_tls = _read_ctx_config(ctx, "tls_enabled", False)
    tls_enabled = _parse_strict_bool(raw_tls, default=False)
    allowed_apk_dir = str(_read_ctx_config(ctx, "allowed_apk_dir", "")).strip()

    from .bridge import is_loopback_host

    if not is_loopback_host(broker_host) and not tls_enabled:
        raise ValueError(
            f"Security policy violation: Non-loopback MQTT broker {broker_host!r} requires TLS encryption. "
            "Set tls_enabled: true in plugin configuration."
        )

    configure_bridge(
        broker_host=broker_host,
        broker_port=broker_port,
        device_id=device_id,
        tls_enabled=tls_enabled,
        allowed_apk_dir=allowed_apk_dir,
    )

    for name, schema, handler, emoji in _TOOLS:
        try:
            ctx.register_tool(
                name=name,
                toolset="android",
                schema=schema,
                handler=handler,
                emoji=emoji,
            )
        except TypeError:
            # Fallback if Hermes version does not accept emoji argument
            ctx.register_tool(
                name=name,
                toolset="android",
                schema=schema,
                handler=handler,
            )

    try:
        ctx.register_hook("on_session_end", _on_session_end)
    except Exception as e:
        logger.debug("Could not register on_session_end hook: %s", e)

    try:
        ctx.register_hook("pre_tool_call", _on_pre_tool_call)
    except Exception as e:
        raise RuntimeError(f"Failed to register mandatory pre_tool_call security hook: {e}") from e

    def _on_plugin_unload() -> None:
        global _active_plugin_context
        _active_plugin_context = None
        configure_bridge()

    if hasattr(ctx, "on_unload") and callable(ctx.on_unload):
        try:
            ctx.on_unload(_on_plugin_unload)
        except Exception as e:
            logger.debug("Could not register on_unload callback: %s", e)

    # Register hermes-droid skill documentation
    skill_md = os.path.join(os.path.dirname(__file__), "SKILL.md")
    if os.path.isfile(skill_md) and hasattr(ctx, "register_skill"):
        try:
            ctx.register_skill("hermes-droid", skill_md)
        except Exception as e:
            logger.debug("Could not register skill hermes-droid: %s", e)
