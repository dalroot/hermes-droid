---
name: hermes-droid
description: Autonomous physical testbed & mobile companion node for Hermes Agent over wireless MQTT. Controls hardware, captures UI, runs on-device shell commands, captures crash logs, deploys APKs, and handles SMS/notifications without USB cables.
version: 0.1.0
author: dalroot
license: MIT
platforms: [linux, macos, windows, android]
metadata:
  hermes:
    tags: [android, mobile, testbed, hardware, telemetry, wireless]
    category: hardware
    requires_toolsets: [android]
---

# Hermes Droid — Autonomous Android Testbed & Companion Node

Hermes Droid bridges Hermes Agent to a paired physical Android smartphone or testbed device wirelessly over an MQTT broker network (with mandatory TLS encryption and authentication enforced for all non-loopback connections).

> **CRITICAL ARCHITECTURE PRINCIPLE (ZERO-CABLE PHILOSOPHY):**
> You are directly paired with an autonomous Android device running the Hermes Droid companion app.
> **DO NOT require, request, or suggest USB cables or local host ADB commands.**
> All operations (logs, shell commands, screenshots, APK installation, UI interactions, hardware sensors) are executed over the wireless MQTT mesh directly by the on-device companion app.

### Transport Security & Encryption Policy
- **Local Loopback (`127.0.0.1` / `localhost`):** Standard unencrypted TCP (port 1883) or local TLS (port 8883) is permitted for local development.
- **Remote / Non-Loopback Brokers:** TLS encryption (`tls_enabled: true`, invoking `client.tls_set()`) and username/password authentication (`HERMES_DROID_MQTT_USER` and `HERMES_DROID_MQTT_PASSWORD`) are strictly enforced. Unencrypted or unauthenticated connections to remote brokers are rejected fail-closed.

---

## When to Use This Skill

Activate and use this skill whenever the user asks to:
1. **Inspect Device Telemetry:** Check phone battery level, charging status, available RAM, Wi-Fi connectivity, or hardware model.
2. **Execute On-Device Shell Commands:** Run system queries, check installed packages (`pm list packages`), or query system properties (`getprop`).
3. **Capture Logs & Crash Diagnostics:** Fetch `logcat` buffers (`system`, `crash`, `main`, `radio`) or `dumpsys` diagnostics after running an app.
4. **Deploy & QA Mobile Apps (APK):** Wirelessly install APKs onto the target device and verify launch.
5. **UI Automation & Inspection:** Take screenshots of the phone screen, dump the accessibility view hierarchy, or simulate taps/swipes.
6. **Read/Send SMS & OTPs:** Read incoming 2FA verification codes, bank OTP alerts, or send SMS.
7. **Read & Reply to Notifications:** Inspect messaging notifications (Telegram, WhatsApp, SMS) and send inline replies without opening the app.
8. **Hardware Actuators & Alerts:** Toggle flashlight, adjust volume/ringer, sound an audio beacon/alarm, or activate radio silence kill-switch.

---

## Available Tools Reference

### 1. Telemetry & Hardware Control
- `android_device_info`: Returns battery percentage, charging state, RAM, storage, and device model.
- `android_toggle_setting`: Controls device features:
  - `setting: "flashlight"` (`value: "on"` / `"off"`)
  - `setting: "screen_awake"` (`value: "on"` / `"off"`)
  - `setting: "ringer_mode"` (`value: "normal"` / `"vibrate"` / `"silent"`)
  - `setting: "volume_level"` (`value: "0-100"`)
- `android_device_alarm`: Triggers a high-volume audible alert beacon on the phone to locate the device or alert operators.
- `android_location_get`: Fetches current GPS and network coordinates (latitude, longitude, accuracy).
- `android_radio_silence`: Privacy/emergency kill-switch to instantaneously cut all wireless communications (`enable: true`).

### 2. Logcat & Diagnostics (Wireless)
- `android_device_logs`: Fetches recent device logs directly from the Android logger.
  - `buffer: "system"` (default) — system events and framework messages.
  - `buffer: "crash"` — fatal exceptions and uncaught application crashes.
  - `buffer: "main"` — general application logs.
  - `buffer: "radio"` — telephony, SIM, and cellular network events.
  - `buffer: "dumpsys"` — hardware diagnostic dump (battery, power, memory).
  - `lines`: Number of lines to retrieve (default: 50).

### 3. On-Device Shell Execution
- `android_adb_shell`: Executes shell commands inside the Android runtime environment.
  - Examples:
    - `command: "dumpsys battery"`
    - `command: "pm list packages -3"` (list user-installed third-party apps)
    - `command: "getprop ro.product.model"`
    - `command: "am start -n <package>/<activity>"` (launch an app)

### 4. Wireless App Deployment (APK Install)
- `android_install_apk`: Wirelessly deploys an application onto the device.
  - `apk_path`: Local path on host to the `.apk` file (transferred via MQTT base64) OR a direct HTTPS download URL.
  - `reinstall`: Replace existing installation if present (default: `true`).

### 5. UI Capture & Vision
- `android_take_screenshot`: Takes a screenshot of the active device display and saves it locally on the host machine.
- `android_camera_snapshot`: Takes a live photo with the physical front or back camera (`camera: "back"` or `"front"`).
- `android_media_recent_photos`: Queries recent gallery photos and can return a base64 thumbnail for vision LLM inspection.

### 6. Messaging, OTPs & Contacts
- `android_read_sms`: Retrieves incoming text messages. Ideal for OTP codes.
  - `query`: Keyword to filter (e.g. "code", "bank", "verification").
  - `limit`: Number of messages (default: 5).
- `android_send_sms`: Sends an SMS via the cellular network (`recipient`, `message`).
- `android_get_notifications`: Lists active notifications.
  - `package_name`: Filter by app (e.g., `org.telegram.messenger`, `com.whatsapp`).
- `android_reply_notification`: Sends an inline reply to a notification (`notification_key`, `reply_text`).
- `android_contacts_search`: Finds contacts in the address book by name or phone query.
- `android_clipboard_sync`: Reads (`action: "get"`) or sets (`action: "set"`, `text: "..."`) the device clipboard.

---

## Best Practices & Workflows

1. **Always trust wireless MQTT:** Never assume the device needs an ADB cable. If a command times out, inform the user that the phone's companion app may be backgrounded or reconnecting to MQTT.
2. **Mobile QA Deployment Workflow:**
   - Step 1: Use `android_device_info` to ensure device is online and battery is sufficient.
   - Step 2: Deploy APK using `android_install_apk`.
   - Step 3: Launch app using `android_adb_shell` with `am start`.
   - Step 4: Verify visually with `android_take_screenshot`.
   - Step 5: Check for crashes with `android_device_logs(buffer="crash")`.
3. **OTP & 2FA Retrieval Workflow:**
   - Call `android_read_sms(query="code", limit=3)` to extract 2FA codes immediately.
