"""Hermes Droid Plugin for Hermes Agent.

Exposes tools to interact with Android devices directly or via the Hermes Droid companion app.
"""

from __future__ import annotations

import logging
import os
from typing import Any

from .bridge import get_bridge
from .handlers import (
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


def _on_session_end(**kwargs: Any) -> None:
    """Cleanup hook called on session end."""
    logger.debug("hermes-droid session ended.")


def register(ctx: Any) -> None:
    """Standard registration entry point called by Hermes Agent plugin loader."""
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

    # Register hermes-droid skill documentation
    skill_md = os.path.join(os.path.dirname(__file__), "SKILL.md")
    if os.path.isfile(skill_md) and hasattr(ctx, "register_skill"):
        try:
            ctx.register_skill("hermes-droid", skill_md)
        except Exception as e:
            logger.debug("Could not register skill hermes-droid: %s", e)
