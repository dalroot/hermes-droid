<p align="center">
  <a href="https://nousresearch.com" target="_blank">
    <picture>
      <source media="(prefers-color-scheme: dark)" srcset="docs/assets/img/nous-stamp-dark.svg">
      <source media="(prefers-color-scheme: light)" srcset="docs/assets/img/nous-stamp.svg">
      <img src="docs/assets/img/nous-stamp.svg" width="160" alt="Nous Research Seal - Rebellion to Tyrants is Obedience to God">
    </picture>
  </a>
</p>

<p align="center">
  <em>"Rebellion to tyrants is obedience to God."</em><br>
  <strong>Nous Research Hermes Agent Hackathon 2026 Submission</strong>
</p>

# 📱 Hermes Droid

> **On-Device Physical Testbed & Autonomous QA Companion for Hermes Agent**  
> *Transform physical Android devices (tested on Xiaomi Redmi Note 8 / Android 11) into automated hardware testbeds, mobile QA workstations, and telemetry nodes for Hermes Agent.*

[![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)
[![Platform: Android](https://img.shields.io/badge/Platform-Android%208.0%2B-green.svg)](https://developer.android.com)
[![Hermes Agent](https://img.shields.io/badge/Hermes%20Agent-%3E%3D0.19.0-purple.svg)](https://github.com/NousResearch/hermes-agent)
[![Defense: Verified](https://img.shields.io/badge/Defense--in--Depth-v0.6.0%20Verified-blue.svg)](docs/SECURITY_AUDIT.md)
[![Landing Page](https://img.shields.io/badge/Landing%20Page-Live%20Demo-0015ff.svg)](https://dalroot.github.io/hermes-droid/)
[![CI Status](https://github.com/dalroot/hermes-droid/actions/workflows/ci.yml/badge.svg)](https://github.com/dalroot/hermes-droid/actions)

---

## ⚡ Overview

While software emulators simulate basic Android runtime logic, they fail to replicate vendor-specific GPU drivers, real thermal throttling, physical touch latency, cellular radio handoffs, or battery degradation under load. 

**Hermes Droid** bridges this gap by enabling Hermes Agent to control and inspect real physical hardware:

1. **Remote Physical Device Testing:** Control a physical test device across isolated networks without USB cables or requiring physical presence beside the phone.
2. **Live Visual QA & Diagnostics:** Stream real-time Logcat buffers (crash stack traces, ANRs), capture display framebuffers (1080x2340 on tested hardware) for visual regression verification, and inspect hardware vitals (battery, thermals, RAM).
3. **Wireless Dev-Cycle (OTA Package Deployments):** Deploy freshly compiled APK builds over the network with mandatory SHA-256 integrity verification, signature matching, and PackageInstaller initiation — eliminating manual USB sideloading.
4. **Developer ADB & Shell Interface:** Execute direct system shell commands, package management tasks, and dumpsys inspect routines through authenticated agent tool-calls.

---

## 🏛️ Nous Research Vision: The Physical Actuation Paradigm

> *"Embodied Intelligence on Bare-Metal Silicon"*  
> Hermes Agent was conceived to think, remember, and reason across digital environments. **Hermes Droid** completes the circle by extending sovereign open intelligence into the physical domain — transforming real Android hardware into autonomous sensory testbeds.

<p align="center">
  <img src="docs/assets/img/mission-duo-1.webp" width="48%" alt="Nous Research Cognition - The Cloud Mind" />
  &nbsp;
  <img src="docs/assets/img/mission-duo-2.webp" width="48%" alt="Hermes Droid Embodiment - Physical Silicon Actuation" />
</p>

<p align="center">
  <sub><strong>Left:</strong> Sovereign Cloud & Desktop Cognition (Nous Hermes Agent) &nbsp;|&nbsp; <strong>Right:</strong> Autonomous Embodied Actuation on Physical Hardware (Hermes Droid)</sub>
</p>

---

## 🏗️ Architecture & Dual Connection Modes

Hermes Droid supports two operational modes depending on deployment topology:

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                       Hermes Agent / Host Workstation                       │
│              Plugin: hermes-droid (plugin.yaml, bridge.py)                  │
└───────────────────────┬─────────────────────────────┬───────────────────────┘
                        │                             │
    Mode A: Direct LAN  │                             │ Mode B: Remote Wireless
    (USB ADB / LAN)     │                             │ (MQTT Relay Backbone)
                        ▼                             ▼
              ┌───────────────────┐         ┌───────────────────┐
              │  HTTP Port 8899   │         │  MQTT Broker      │
              │  (X-Hermes-Token) │         │  (Port 1883/8883) │
              └─────────┬─────────┘         └─────────┬─────────┘
                        │                             │
                        └──────────────┬──────────────┘
                                       │
┌──────────────────────────────────────▼──────────────────────────────────────┐
│                  Hermes Droid Android Node (Kotlin Native)                  │
│                                                                             │
│  ┌─────────────────────────┐  ┌──────────────────────────────────────────┐  │
│  │ Mobile QA & Diagnostics │  │ Hardware Controls & Telemetry            │  │
│  │ • Live Logcat & Dumpsys │  │ • Camera & Audio Diagnostics             │  │
│  │ • Framebuffer Captures  │  │ • Battery, Thermal, RAM & Storage        │  │
│  │ • UI Touch & Shell Exec │  │ • Cellular Modem (SMS & Radio State)     │  │
│  └─────────────────────────┘  └──────────────────────────────────────────┘  │
│  ┌───────────────────────────────────────────────────────────────────────┐  │
│  │ Core Services & Reliability Engine                                    │  │
│  │ • Stay-Awake Architecture (Service WakeLock + Window Keep-Screen-On)  │  │
│  │ • Cryptographic OTA Pipeline (Mandatory SHA-256 & Cert Pre-flight)    │  │
│  │ • Display OTP Pairing Gate with Brute-Force Rate Limiting             │  │
│  └───────────────────────────────────────────────────────────────────────┘  │
└─────────────────────────────────────────────────────────────────────────────┘
```

### Mode A: Direct LAN Mode (or ADB Forward)
- Direct HTTP requests to the embedded Android daemon on port `8899`.
- Listens on network interfaces; authenticated strictly via the `X-Hermes-Token` header.
- Recommended for local test benches or via `adb forward tcp:8899 tcp:8899`.

### Mode B: Remote Wireless Mode (MQTT Backbone)
- Cable-free operation across cellular or independent networks via MQTT broker.
- Out-of-band control via dedicated device topics (`hermes/node/<DEVICE_ID>/cmd`).
- Companion CLI utility `hdroid` for remote diagnostics, screenshot fetching, and OTA deployments.
- *Note:* Broker transport on port 1883 operates in plaintext without transport encryption; securing the broker with TLS (port 8883) and per-topic ACLs requires infrastructure-side configuration (see [Security Audit](docs/SECURITY_AUDIT.md)).

---

## 🛠️ Complete Suite of 18 Tools

| Tool | Icon | Category | Description |
| :--- | :---: | :---: | :--- |
| `android_device_info` | 📱 | System | Query battery level, charging status, Wi-Fi SSID, IP, RAM, and uptime. |
| `android_device_logs` | 📜 | Dev & QA | Extract real-time Logcat buffers, radio logs, crash stack-traces, or dumpsys. |
| `android_take_screenshot` | 📸 | Dev & QA | Capture high-resolution display framebuffer for visual UI verification. |
| `android_adb_shell` | 💻 | Dev & QA | Execute authenticated shell commands, package managers, and UI events. |
| `android_install_apk` | 📦 | Dev & QA | Wirelessly download and trigger package update with mandatory SHA-256 verification. |
| `android_toggle_setting` | ⚙️ | Hardware | Control flashlight, ringer mode (silent/vibrate/normal), and screen stay-awake lock. |
| `android_read_sms` | 💬 | Comms | Read cellular SMS with sender/keyword filtering for 2FA verification testing. |
| `android_send_sms` | 📤 | Comms | Dispatch SMS messages directly over the device's cellular modem. |
| `android_contacts_search` | 👥 | Comms | Search address book entries by name or phone number. |
| `android_call_phone` | 📞 | Comms | Initiate cellular phone calls or prep dialer for carrier testing. |
| `android_get_notifications` | 🔔 | Social | Inspect active system notifications across all installed messaging and banking apps. |
| `android_reply_notification` | ↩️ | Social | Reply inline to incoming notification intents without launching the host app. |
| `android_media_recent_photos` | 🖼️ | Media | Query device photo library with optional Base64 encoding for vision model evaluation. |
| `android_camera_snapshot` | 📷 | Media | Capture instantaneous photos using front or rear physical camera sensors. |
| `android_location_get` | 📍 | Sensors | Retrieve real-time GPS coordinates (latitude, longitude, altitude, speed). |
| `android_device_alarm` | 🚨 | Hardware | Sound audible locate siren and vibration patterns ('Find My Node'). |
| `android_radio_silence` | 🔕 | Hardware | Emergency kill-switch: cuts Airplane mode, Wi-Fi, Bluetooth, Cellular, and GPS. |
| `android_clipboard_sync` | 📋 | System | Read from and write to system clipboard buffer. |

---

## 🚀 Setup & Getting Started

### 1. Build and Run the Android Node
1. Compile the hardened Release APK:
   ```bash
   cd hermes-droid/android && ./gradlew assembleRelease
   ```
2. Install onto the device via ADB:
   ```bash
   adb install app/build/outputs/apk/release/app-release.apk
   ```
3. Open **Hermes Droid**, grant requested permissions in the Permissions tab, and tap **Start Service**.
4. The status screen will display the current **Node Status**, **Device ID**, and **8-character OTP Pairing Code**.

---

### 2. Configuration & Connection Setup

#### Option A: Local Network or USB ADB Forward
Set environment variables for direct HTTP communication:
```bash
# If using USB ADB tunnel:
adb forward tcp:8899 tcp:8899
export HERMES_DROID_HOST="127.0.0.1"
export HERMES_DROID_PORT="8899"
export HERMES_DROID_TOKEN="YOUR_NODE_TOKEN"

# Or if connecting over private LAN:
export HERMES_DROID_HOST="192.168.1.105"
export HERMES_DROID_PORT="8899"
export HERMES_DROID_TOKEN="YOUR_NODE_TOKEN"
```

#### Option B: Remote Wireless MQTT Backbone
Configure the MQTT broker parameters:
```bash
export HERMES_DROID_BROKER="broker.your-domain.com"   # or your private broker IP
export HERMES_DROID_PORT="1883"                       # or 8883 for TLS
export HERMES_DROID_DEVICE_ID="your_device_id_here"   # displayed on the app screen
```

---

## 💻 Developer CLI: `hdroid`

The companion CLI tool `hdroid` provides immediate command-line access to remote physical nodes over the MQTT backbone:

```bash
# 1. Healthcheck and status probe
hdroid ping
hdroid status

# 2. Extract live device Logcat logs
hdroid logcat -n 50

# 3. Capture real-time display screenshot
hdroid screenshot -o /tmp/screen_current.jpg

# 4. Toggle Screen Stay-Awake lock (Window FLAG + Service WakeLock)
hdroid awake on
hdroid awake off

# 5. Execute remote shell diagnostics
hdroid shell "dumpsys battery"
hdroid shell "pm list packages -3"

# 6. Wirelessly deploy updated APK with mandatory SHA-256 calculation
hdroid install hermes-droid/android/app/build/outputs/apk/release/app-release.apk
```

---

## 🔄 OTA Deployment Pipeline

Hermes Droid incorporates an over-the-air package update pipeline designed to simplify test deployment cycles:

1. **Pre-Flight Hash Computation:** `hdroid install` automatically computes the SHA-256 digest of the local APK binary.
2. **Secure HTTPS Transfer:** The package is fetched by the device over HTTPS from a whitelisted repository.
3. **Cryptographic Validation Gate:**
   - The device strictly enforces the `sha256` parameter; requests lacking a checksum are rejected immediately.
   - The SHA-256 digest is verified on the downloaded stream before PackageInstaller is invoked.
   - Package name (`org.nous.hermesdroid`) and cryptographic signing certificates are validated against the active application.
4. **Installer Initiation:** The app invokes Android's PackageInstaller session. Note: Depending on device OS policies, initial installation or updates may prompt the user or require Accessibility confirmation on the screen.

---

## 🔒 Security & Privacy Architecture

Hermes Droid implements defense-in-depth principles:

- **Zero-Secret Release Binary:** Release builds completely purge hardcoded tokens from APK bytecode (`classes.dex`). Tokens are managed dynamically in private runtime `SharedPreferences` (`Context.MODE_PRIVATE`).
- **Display OTP Pairing Gate:** Telegram and MQTT control sessions require physical display pairing with 10-minute expiration (`OTP_TTL_MS`) and automatic brute-force lockout after 3 incorrect attempts. Pairing state persists in private app storage.
- **Fail-Closed HTTP Authentication:** The embedded server rejects unauthenticated query-string parameters (`?token=...`) and requires constant-time `X-Hermes-Token` header verification.
- **Broker Infrastructure Scope (NOT VERIFIED):** Port 1883 on the development broker operates over plaintext TCP without mandatory TLS. Transport-layer encryption (port 8883) and per-device ACLs require server-side broker configuration, which is documented as outside the Android client scope.
- **Catalog CI Compliance:** The Python plugin passes all official Nous Research catalog checks under `hermes plugins validate`.
- **Full Security Verification Report:** Detailed vulnerability analyses and live negative test logs are documented in [docs/SECURITY_AUDIT.md](docs/SECURITY_AUDIT.md).

---

## 📄 Disclaimer

Hermes Droid is an independent open-source project created for the Nous Research Hermes Agent ecosystem. Hermes is a trademark of Nous Research.

---

## 📝 License

Distributed under the [MIT License](LICENSE).
