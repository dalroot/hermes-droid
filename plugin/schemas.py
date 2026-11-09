"""JSON schemas for hermes-droid tools."""

DEVICE_INFO_SCHEMA = {
    "name": "android_device_info",
    "description": "Fetch live device status including battery level, charging state, network connectivity, Wi-Fi SSID, device model, Android version, and available memory.",
    "parameters": {
        "type": "object",
        "properties": {},
        "required": [],
    },
}

READ_SMS_SCHEMA = {
    "name": "android_read_sms",
    "description": "Read incoming SMS messages from the Android device. Can filter by sender/originating number, search query keywords, or limit the number of returned messages. Ideal for OTP verification codes and banking alerts.",
    "parameters": {
        "type": "object",
        "properties": {
            "limit": {
                "type": "integer",
                "description": "Maximum number of recent SMS messages to retrieve (default: 5, max: 50).",
                "default": 5,
            },
            "sender": {
                "type": "string",
                "description": "Optional phone number or sender ID to filter by.",
            },
            "query": {
                "type": "string",
                "description": "Optional keyword or search term to search within message bodies.",
            },
        },
        "required": [],
    },
}

SEND_SMS_SCHEMA = {
    "name": "android_send_sms",
    "description": "Send an SMS message to a specified recipient phone number through the Android phone's cellular network.",
    "parameters": {
        "type": "object",
        "properties": {
            "recipient": {
                "type": "string",
                "description": "The destination phone number (e.g., '+1234567890' or local format).",
            },
            "message": {
                "type": "string",
                "description": "The text content of the SMS message to send.",
            },
        },
        "required": ["recipient", "message"],
    },
}

GET_NOTIFICATIONS_SCHEMA = {
    "name": "android_get_notifications",
    "description": "Get recent active or incoming notifications from the Android device. Supports filtering by package name (e.g. org.telegram.messenger, com.whatsapp, com.instagram.android) or keyword query.",
    "parameters": {
        "type": "object",
        "properties": {
            "package_name": {
                "type": "string",
                "description": "Optional application package name to filter (e.g. 'org.telegram.messenger', 'com.whatsapp').",
            },
            "limit": {
                "type": "integer",
                "description": "Maximum number of notifications to retrieve (default: 10).",
                "default": 10,
            },
        },
        "required": [],
    },
}

REPLY_NOTIFICATION_SCHEMA = {
    "name": "android_reply_notification",
    "description": "Send an inline reply directly to an active messaging notification (e.g. Telegram, WhatsApp, Slack, Signal) using Android RemoteInput without launching the app.",
    "parameters": {
        "type": "object",
        "properties": {
            "notification_key": {
                "type": "string",
                "description": "The unique notification key returned by android_get_notifications.",
            },
            "reply_text": {
                "type": "string",
                "description": "The text reply to send back to the chat.",
            },
        },
        "required": ["notification_key", "reply_text"],
    },
}

CALL_PHONE_SCHEMA = {
    "name": "android_call_phone",
    "description": "Place a phone call to a given phone number, or prepare the dialer on the Android device.",
    "parameters": {
        "type": "object",
        "properties": {
            "phone_number": {
                "type": "string",
                "description": "Phone number to call.",
            },
            "direct": {
                "type": "boolean",
                "description": "True to initiate call immediately (requires CALL_PHONE permission), False to open dialer with number filled.",
                "default": False,
            },
        },
        "required": ["phone_number"],
    },
}

ADB_SHELL_SCHEMA = {
    "name": "android_adb_shell",
    "description": "Execute a shell command on the Android device via local companion server or ADB bridge. Supports commands like 'dumpsys', 'pm list packages', 'input tap', 'input text', 'getprop', etc.",
    "parameters": {
        "type": "object",
        "properties": {
            "command": {
                "type": "string",
                "description": "The shell command to execute on the Android device.",
            },
            "timeout_seconds": {
                "type": "integer",
                "description": "Execution timeout in seconds (default: 15).",
                "default": 15,
            },
        },
        "required": ["command"],
    },
}

INSTALL_APK_SCHEMA = {
    "name": "android_install_apk",
    "description": "Install an APK package onto the Android device from a local file path on host or a direct URL.",
    "parameters": {
        "type": "object",
        "properties": {
            "apk_path": {
                "type": "string",
                "description": "Local filesystem path to .apk file or HTTP/HTTPS download URL.",
            },
            "reinstall": {
                "type": "boolean",
                "description": "Whether to reinstall / replace existing application if present.",
                "default": True,
            },
        },
        "required": ["apk_path"],
    },
}

TOGGLE_SETTING_SCHEMA = {
    "name": "android_toggle_setting",
    "description": "Control hardware features and system settings on the phone (flashlight, volume, ringer mode, screen awake).",
    "parameters": {
        "type": "object",
        "properties": {
            "setting": {
                "type": "string",
                "enum": ["flashlight", "ringer_mode", "volume_level", "screen_awake"],
                "description": "The setting or hardware actuator to control.",
            },
            "value": {
                "type": "string",
                "description": "The desired value: 'on'/'off' for flashlight, 'normal'/'vibrate'/'silent' for ringer_mode, '0-100' for volume.",
            },
        },
        "required": ["setting", "value"],
    },
}

CLIPBOARD_SYNC_SCHEMA = {
    "name": "android_clipboard_sync",
    "description": "Read from or write to the Android device system clipboard.",
    "parameters": {
        "type": "object",
        "properties": {
            "action": {
                "type": "string",
                "enum": ["get", "set"],
                "description": "'get' to read clipboard text, 'set' to set clipboard text.",
            },
            "text": {
                "type": "string",
                "description": "The text to set on clipboard (required if action is 'set').",
            },
        },
        "required": ["action"],
    },
}

TAKE_SCREENSHOT_SCHEMA = {
    "name": "android_take_screenshot",
    "description": "Capture the current screen of the Android phone and save it locally on host.",
    "parameters": {
        "type": "object",
        "properties": {
            "destination_path": {
                "type": "string",
                "description": "Local path where the screenshot PNG should be saved. Defaults to temp directory.",
            },
        },
        "required": [],
    },
}


CONTACTS_SEARCH_SCHEMA = {
    "name": "android_contacts_search",
    "description": "Search address book contacts by name or phone number on the Android device.",
    "parameters": {
        "type": "object",
        "properties": {
            "query": {
                "type": "string",
                "description": "The search term (name, phone number, or email).",
            },
            "limit": {
                "type": "integer",
                "description": "Maximum number of contacts to return (default: 10).",
                "default": 10,
            },
        },
        "required": ["query"],
    },
}

MEDIA_RECENT_PHOTOS_SCHEMA = {
    "name": "android_media_recent_photos",
    "description": "Retrieve metadata and optional Base64 thumbnail or file paths of recent photos taken or downloaded on the phone.",
    "parameters": {
        "type": "object",
        "properties": {
            "limit": {
                "type": "integer",
                "description": "Number of recent photos to query (default: 5).",
                "default": 5,
            },
            "include_base64": {
                "type": "boolean",
                "description": "Whether to return the latest photo as base64 for vision LLM analysis.",
                "default": False,
            },
        },
        "required": [],
    },
}

DEVICE_LOGS_SCHEMA = {
    "name": "android_device_logs",
    "description": "Extract specialized device logs (system, radio, crash, main) or dumpsys diagnostics for deep debugging.",
    "parameters": {
        "type": "object",
        "properties": {
            "buffer": {
                "type": "string",
                "enum": ["main", "system", "radio", "crash", "dumpsys"],
                "description": "Log buffer to query: 'system' for OS events, 'radio' for cellular/modem, 'crash' for fatal app crashes, 'dumpsys' for battery/process diagnostics.",
                "default": "system",
            },
            "lines": {
                "type": "integer",
                "description": "Number of recent log lines to capture (default: 50).",
                "default": 50,
            },
        },
        "required": [],
    },
}

RADIO_SILENCE_SCHEMA = {
    "name": "android_radio_silence",
    "description": "Emergency or privacy kill-switch: instantaneously cut all wireless emissions (enable Airplane Mode, disable Wi-Fi, Bluetooth, Cellular Data, and GPS).",
    "parameters": {
        "type": "object",
        "properties": {
            "enable": {
                "type": "boolean",
                "description": "True to activate radio silence (cut emissions), False to restore connectivity.",
            },
        },
        "required": ["enable"],
    },
}

CAMERA_SNAPSHOT_SCHEMA = {
    "name": "android_camera_snapshot",
    "description": "Take an instantaneous photo using the device's front or back camera and download it locally for vision inspection.",
    "parameters": {
        "type": "object",
        "properties": {
            "camera": {
                "type": "string",
                "enum": ["back", "front"],
                "description": "Camera to use: 'back' (default) or 'front' (selfie).",
                "default": "back",
            },
            "destination_path": {
                "type": "string",
                "description": "Optional local filepath to save the captured image.",
            },
        },
        "required": [],
    },
}

LOCATION_GET_SCHEMA = {
    "name": "android_location_get",
    "description": "Retrieve current GPS and network location coordinates (latitude, longitude, altitude, accuracy) of the phone.",
    "parameters": {
        "type": "object",
        "properties": {},
        "required": [],
    },
}

DEVICE_ALARM_SCHEMA = {
    "name": "android_device_alarm",
    "description": "Trigger an audible alarm siren and vibration on the device to locate it ('Find My Phone'), even if muted.",
    "parameters": {
        "type": "object",
        "properties": {
            "duration_seconds": {
                "type": "integer",
                "description": "Duration in seconds to sound the buzzer (default: 10).",
                "default": 10,
            },
        },
        "required": [],
    },
}
