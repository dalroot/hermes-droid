package org.nous.hermesdroid.service

import android.content.Context
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import org.nous.hermesdroid.model.TelegramStatus
import org.nous.hermesdroid.modules.AudioModule
import org.nous.hermesdroid.modules.ContactsModule
import org.nous.hermesdroid.modules.DeviceModule
import org.nous.hermesdroid.modules.HermesAccessibilityService
import org.nous.hermesdroid.modules.LocationModule
import org.nous.hermesdroid.modules.MediaModule
import org.nous.hermesdroid.modules.NotificationListener
import org.nous.hermesdroid.modules.PackageInstallerModule
import org.nous.hermesdroid.modules.TelephonyModule
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

class TelegramRelayClient(
    private val context: Context,
    private val tokenProvider: () -> String
) {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var job: Job? = null

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    private val deviceModule = DeviceModule(context)
    private val telephonyModule = TelephonyModule(context)
    private val mediaModule = MediaModule(context)
    private val contactsModule = ContactsModule(context)
    private val locationModule = LocationModule(context)
    private val terminalModule = org.nous.hermesdroid.modules.TerminalModule(context)
    private val packageInstallerModule = PackageInstallerModule(context)
    private val audioModule = AudioModule(context)

    private var lastUpdateId = 0L

    fun start() {
        if (job?.isActive == true) return
        job = scope.launch {
            pollLoop()
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        HermesNodeService.setTelegramStatus(TelegramStatus(state = "STOPPED"))
    }

    private suspend fun pollLoop() {
        while (scope.isActive) {
            val token = tokenProvider().trim()
            if (token.isEmpty()) {
                HermesNodeService.setTelegramStatus(
                    TelegramStatus(state = "NO_TOKEN", error = "No Telegram Bot Token provided")
                )
                delay(5000)
                continue
            }

            HermesNodeService.setTelegramStatus(TelegramStatus(state = "CONNECTING"))

            val botUser = fetchGetMe(token)
            if (botUser == null) {
                HermesNodeService.addLog("TELEGRAM", "getMe failed - check network / VPN")
                HermesNodeService.setTelegramStatus(
                    TelegramStatus(state = "ERROR", error = "Failed to connect to Telegram API (Check VPN / Proxy)")
                )
                delay(6000)
                continue
            }

            HermesNodeService.setTelegramStatus(
                TelegramStatus(state = "CONNECTED", botUsername = botUser, lastSeenMs = System.currentTimeMillis())
            )
            HermesNodeService.addLog("TELEGRAM", "Connected to Telegram as @$botUser")

            while (scope.isActive) {
                try {
                    val url = "https://api.telegram.org/bot$token/getUpdates?offset=${lastUpdateId + 1}&timeout=20"
                    val request = Request.Builder()
                        .url(url)
                        .header("User-Agent", "HermesDroid/0.2.0")
                        .build()

                    val response = client.newCall(request).execute()
                    val body = response.body?.string() ?: ""
                    response.close()

                    if (!response.isSuccessful) {
                        HermesNodeService.setTelegramStatus(
                            TelegramStatus(state = "ERROR", error = "HTTP ${response.code}: $body", botUsername = botUser)
                        )
                        delay(4000)
                        break
                    }

                    val json = JSONObject(body)
                    if (json.optBoolean("ok")) {
                        val results = json.optJSONArray("result") ?: JSONArray()
                        for (i in 0 until results.length()) {
                            val update = results.getJSONObject(i)
                            val updateId = update.optLong("update_id")
                            if (updateId > lastUpdateId) {
                                lastUpdateId = updateId
                            }
                            handleUpdate(token, update)
                        }
                        HermesNodeService.setTelegramStatus(
                            TelegramStatus(state = "CONNECTED", botUsername = botUser, lastSeenMs = System.currentTimeMillis())
                        )
                    } else {
                        val desc = json.optString("description")
                        HermesNodeService.addLog("TELEGRAM", "Error response: $desc")
                        delay(4000)
                    }
                } catch (e: Exception) {
                    HermesNodeService.addLog("TELEGRAM", "Polling exception: ${e.message}")
                    HermesNodeService.setTelegramStatus(
                        TelegramStatus(state = "ERROR", error = e.message ?: "Connection lost", botUsername = botUser)
                    )
                    delay(5000)
                    break
                }
            }
        }
    }

    private fun fetchGetMe(token: String): String? {
        return try {
            val req = Request.Builder()
                .url("https://api.telegram.org/bot$token/getMe")
                .header("User-Agent", "HermesDroid/0.2.0")
                .build()
            val res = client.newCall(req).execute()
            val body = res.body?.string() ?: ""
            res.close()
            if (res.isSuccessful) {
                val json = JSONObject(body)
                if (json.optBoolean("ok")) {
                    json.getJSONObject("result").optString("username")
                } else null
            } else null
        } catch (e: Exception) {
            null
        }
    }

    private fun handleUpdate(token: String, update: JSONObject) {
        val message = update.optJSONObject("message") ?: return
        val chat = message.optJSONObject("chat") ?: return
        val chatId = chat.optLong("id")
        val rawText = message.optString("text", "").trim()
        val fromUser = message.optJSONObject("from")?.optString("username", "Unknown") ?: "Unknown"

        val authorizedChatId = HermesNodeService.telegramAuthorizedChatId.value
        val startTime = System.currentTimeMillis()
        val profile = deviceModule.getDeviceProfile()

        val parts = rawText.split("\\s+".toRegex())
        val command = parts.firstOrNull()?.lowercase() ?: ""
        val argument = if (parts.size > 1) parts.drop(1).joinToString(" ") else ""

        // Pairing command: /pair <code> or /auth <code>
        if (command == "/pair" || command == "/auth") {
            val lockoutRemaining = HermesNodeService.isPairingLockedOut()
            if (lockoutRemaining > 0L) {
                val sec = (lockoutRemaining / 1000) + 1
                sendTelegramMessage(
                    token,
                    chatId,
                    "⏳ *Pairing Temporarily Locked Out*\nToo many invalid attempts. Please wait $sec seconds before trying again."
                )
                return
            }

            if (HermesNodeService.isOtpExpired()) {
                val newCode = HermesNodeService.renewExpiredOtp(context)
                sendTelegramMessage(
                    token,
                    chatId,
                    "⚠️ *Pairing Code Expired*\nOTP codes expire after 10 minutes for security. A new code has been generated on the device screen."
                )
                return
            }

            val inputCode = argument.trim().uppercase()
            val activeCode = HermesNodeService.pairingStatus.value.code
            if (inputCode.isNotEmpty() && inputCode.equals(activeCode, ignoreCase = true)) {
                HermesNodeService.setPairingApproved(context, "@$fromUser")
                HermesNodeService.setTelegramAuthorizedChatId(context, chatId, fromUser)
                val successMsg = """
                    ✅ *Hermes Droid Pairing Successful!*
                    
                    • *Device:* $profile
                    • *Authorized Chat ID:* `$chatId`
                    • *Administrator:* @$fromUser
                    • *Status:* Authenticated
                    
                    You now have full administrative privileges to control this physical node.
                    Send /help to view available commands.
                """.trimIndent()
                sendTelegramMessage(token, chatId, successMsg)
                HermesNodeService.recordTask("Pair Telegram: @$fromUser", "TELEGRAM", "SUCCESS", System.currentTimeMillis() - startTime)
                return
            } else {
                val attempts = HermesNodeService.recordFailedPairingAttempt(context)
                if (attempts >= HermesNodeService.MAX_FAILED_ATTEMPTS) {
                    sendTelegramMessage(
                        token,
                        chatId,
                        "❌ *Pairing Blocked (3/3 Failed Attempts)*\nPairing is locked for 5 minutes. The compromised code was burned and a new code generated on the device screen."
                    )
                } else {
                    sendTelegramMessage(
                        token,
                        chatId,
                        "❌ *Invalid Pairing Code (Attempt $attempts/3)*\nUsage: `/pair <8-CHAR-CODE>`\nEnter the OTP code displayed on the companion app screen (Status tab)."
                    )
                }
                return
            }
        }

        // Unpair command for current administrator
        if (command == "/unpair" && chatId == authorizedChatId) {
            HermesNodeService.unpairTelegram(context)
            sendTelegramMessage(
                token,
                chatId,
                "🔒 *Telegram Administrator Unpaired*\nThis chat has been revoked. The node is now locked until paired again."
            )
            return
        }

        // Authorization Gate
        if (authorizedChatId != -1L && chatId != authorizedChatId) {
            val deniedMsg = """
                ⛔ *Access Restricted: Unauthorized User*
                
                This Hermes Droid physical node is secured by an administrator.
                Your Telegram Chat ID (`$chatId`) is not authorized to control this device.
                
                If you are the owner, send `/pair <8-CHAR-CODE>` with the code shown on the companion app screen.
            """.trimIndent()
            sendTelegramMessage(token, chatId, deniedMsg)
            HermesNodeService.addLog("SECURITY", "Blocked unauthorized Telegram access attempt from @$fromUser (Chat ID: $chatId)")
            return
        } else if (authorizedChatId == -1L) {
            val lockedMsg = """
                🔒 *Hermes Droid Node Locked*
                
                No administrator is currently paired with this physical node.
                To unlock and pair this device, send:
                `/pair <8-CHAR-CODE>`
                
                The 8-character OTP pairing code can be found on the companion app screen (Status tab).
            """.trimIndent()
            sendTelegramMessage(token, chatId, lockedMsg)
            HermesNodeService.addLog("SECURITY", "Prompted @$fromUser (Chat ID: $chatId) for initial pairing code")
            return
        }

        // Autonomous APK Installation Trigger on receiving .apk files (ONLY if authorized!)
        val document = message.optJSONObject("document")
        if (document != null) {
            val fileName = document.optString("file_name", "")
            val fileId = document.optString("file_id")
            if (fileName.endsWith(".apk", ignoreCase = true)) {
                scope.launch(Dispatchers.IO) {
                    sendTelegramMessage(token, chatId, "⏳ *Downloading APK `$fileName` to node cache...*")
                    val apkFile = downloadTelegramFile(token, fileId, fileName)
                    if (apkFile != null && apkFile.exists()) {
                        val res = packageInstallerModule.installApk(apkFile)
                        if (res.isSuccess) {
                            sendTelegramMessage(
                                token,
                                chatId,
                                "🚀 *Autonomous APK Installation Initiated!*\n\n• Package: `${apkFile.name}`\n• Size: `${apkFile.length() / (1024 * 1024)} MB`\n\n_Hermes Accessibility is automatically confirming the install prompt!_"
                            )
                            HermesNodeService.recordTask("Install APK: ${apkFile.name}", "TELEGRAM", "SUCCESS", 0)
                        } else {
                            sendTelegramMessage(token, chatId, "⚠️ Install Error: ${res.exceptionOrNull()?.message}")
                        }
                    } else {
                        sendTelegramMessage(token, chatId, "❌ Failed to download APK from Telegram servers.")
                    }
                }
                return
            }
        }

        HermesNodeService.addLog("TELEGRAM", "Received '$rawText' from @$fromUser")

        scope.launch(Dispatchers.IO) {
            if (rawText.startsWith("{") && rawText.endsWith("}")) {
                try {
                    val cmd = JSONObject(rawText)
                    val action = cmd.optString("action")
                    val result = JSONObject().apply {
                        put("device", profile as Any)
                        put("action", action)
                        put("status", "executed")
                        put("timestamp", System.currentTimeMillis())
                    }
                    sendTelegramMessage(token, chatId, "```json\n${result.toString(2)}\n```")
                    HermesNodeService.recordTask("JSON Action: $action", "TELEGRAM", "SUCCESS", System.currentTimeMillis() - startTime)
                } catch (e: Exception) {
                    sendTelegramMessage(token, chatId, "JSON parse error: ${e.message}")
                }
                return@launch
            }

            when (command) {
                "/start", "/help" -> {
                    val help = """
                        🤖 *$profile Autonomous Node*
                        
                        *📸 Camera & Media:*
                        • /cam_back - Real live back camera shutter
                        • /cam_front - Real live front camera selfie
                        • /gallery - Latest photo saved in gallery
                        
                        *💬 Messages & Social:*
                        • /directs - Recent Instagram DMs, WhatsApp & Telegram
                        • /sms - Recent SMS messages
                        • /contacts [query] - Search contacts book
                        
                        *📍 Sensors & System:*
                        • /location - Active GPS coordinates & map link
                        • /battery - Battery health & charging
                        • /sys - Kernel version & hardware status
                        • /apps - List installed user applications
                        • /logcat - Recent system logcat
                        • /ping - Test node roundtrip latency

                        *💻 Shell & Device Actuators:*
                        • /sh <cmd> - Execute terminal / shell command
                        • /tap <x> <y> - Simulate tap at screen coordinate
                        • /type <text> - Type text into focused input
                        • /home - Press Android Home button
                        • /back - Press Android Back button
                        • /quick_settings - Pull down Quick Settings
                        • /screenshot - Capture device screenshot
                    """.trimIndent()
                    sendTelegramMessage(token, chatId, help)
                    HermesNodeService.recordTask("Help / Start", "TELEGRAM", "SUCCESS", System.currentTimeMillis() - startTime)
                }

                "/cam_back", "/photo", "/camera" -> {
                    sendTelegramMessage(token, chatId, "📸 *Capturing live photo from back camera lens...*")
                    val result = mediaModule.captureLivePhoto(useFrontCamera = false)
                    result.fold(
                        onSuccess = { bytes ->
                            sendTelegramPhoto(token, chatId, bytes, "📸 $profile Live Back Camera Snapshot")
                            HermesNodeService.recordTask("Live Camera (Back)", "TELEGRAM", "SUCCESS", System.currentTimeMillis() - startTime, "${bytes.size} bytes")
                        },
                        onFailure = { err ->
                            sendTelegramMessage(token, chatId, "❌ *Camera Capture Failed*\nError: ${err.message}")
                            HermesNodeService.recordTask("Live Camera (Back)", "TELEGRAM", "FAILED", System.currentTimeMillis() - startTime, err.message ?: "")
                        }
                    )
                }

                "/cam_front", "/selfie" -> {
                    sendTelegramMessage(token, chatId, "🤳 *Capturing live selfie from front camera lens...*")
                    val result = mediaModule.captureLivePhoto(useFrontCamera = true)
                    result.fold(
                        onSuccess = { bytes ->
                            sendTelegramPhoto(token, chatId, bytes, "🤳 $profile Live Front Selfie Snapshot")
                            HermesNodeService.recordTask("Live Camera (Front)", "TELEGRAM", "SUCCESS", System.currentTimeMillis() - startTime, "${bytes.size} bytes")
                        },
                        onFailure = { err ->
                            sendTelegramMessage(token, chatId, "❌ *Front Camera Failed*\nError: ${err.message}")
                            HermesNodeService.recordTask("Live Camera (Front)", "TELEGRAM", "FAILED", System.currentTimeMillis() - startTime, err.message ?: "")
                        }
                    )
                }

                "/gallery" -> {
                    val photos = mediaModule.getRecentPhotos(1, includeBase64 = true)
                    if (photos.length() > 0) {
                        val p = photos.getJSONObject(0)
                        val base64 = p.optString("base64_jpeg")
                        if (base64.isNotEmpty()) {
                            val bytes = android.util.Base64.decode(base64, android.util.Base64.NO_WRAP)
                            sendTelegramPhoto(token, chatId, bytes, "🖼️ $profile Gallery: ${p.optString("name")}")
                            HermesNodeService.recordTask("Gallery Photo", "TELEGRAM", "SUCCESS", System.currentTimeMillis() - startTime, p.optString("name"))
                        } else {
                            sendTelegramMessage(token, chatId, "❌ Failed to read photo bytes from gallery.")
                        }
                    } else {
                        sendTelegramMessage(token, chatId, "⚠️ No photos found in device gallery.")
                    }
                }

                "/directs", "/notifications" -> {
                    if (!NotificationListener.isConnected()) {
                        sendTelegramMessage(
                            token,
                            chatId,
                            "⚠️ *Notification Listener is Disabled!*\nTo read Instagram DMs, WhatsApp, and Telegram messages, please open Hermes Droid app -> tap 'Access' tab -> tap 'Notification Listener' and enable it."
                        )
                        HermesNodeService.recordTask("Read Directs", "TELEGRAM", "FAILED", System.currentTimeMillis() - startTime, "Permission disabled")
                        return@launch
                    }

                    val notifs = NotificationListener.getNotifications(limit = 10)
                    if (notifs.isEmpty()) {
                        sendTelegramMessage(token, chatId, "📭 $profile No recent notifications or directs captured yet.")
                    } else {
                        val sb = StringBuilder("📬 *$profile Recent Directs & Notifications (${notifs.size})*\n\n")
                        val timeFmt = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
                        for (n in notifs) {
                            val timeStr = timeFmt.format(java.util.Date(n.postTime))
                            sb.append("• *${n.appName}* (`$timeStr`)\n")
                            sb.append("  *From:* ${n.title}\n")
                            sb.append("  *Text:* ${n.text}\n")
                            if (n.canReply) sb.append("  _Can Reply: Yes_\n")
                            sb.append("\n")
                        }
                        sendTelegramMessage(token, chatId, sb.toString())
                        HermesNodeService.recordTask("Read Directs", "TELEGRAM", "SUCCESS", System.currentTimeMillis() - startTime, "${notifs.size} items")
                    }
                }

                "/contacts" -> {
                    val contacts = contactsModule.searchContacts(query = argument, limit = 15)
                    if (contacts.length() == 0) {
                        sendTelegramMessage(token, chatId, "👥 $profile No contacts found matching '$argument'.")
                    } else {
                        val firstObj = contacts.getJSONObject(0)
                        if (firstObj.has("error")) {
                            sendTelegramMessage(token, chatId, "❌ *Contacts Error*\n${firstObj.getString("error")}")
                            HermesNodeService.recordTask("Contacts", "TELEGRAM", "FAILED", System.currentTimeMillis() - startTime, firstObj.getString("error"))
                            return@launch
                        }

                        val sb = StringBuilder("👥 *$profile Contacts (${contacts.length()})*\n\n")
                        for (i in 0 until contacts.length()) {
                            val c = contacts.getJSONObject(i)
                            val name = c.optString("name", "Unnamed")
                            val num = c.optString("number", "No Number")
                            sb.append("• *$name*: `$num`\n")
                        }
                        sendTelegramMessage(token, chatId, sb.toString())
                        HermesNodeService.recordTask("Contacts", "TELEGRAM", "SUCCESS", System.currentTimeMillis() - startTime, "${contacts.length()} contacts")
                    }
                }

                "/sms" -> {
                    try {
                        val smsList = telephonyModule.readSms(limit = 5)
                        if (smsList.isEmpty()) {
                            sendTelegramMessage(token, chatId, "💬 $profile Inbox is empty. No SMS messages found on device.")
                        } else {
                            val sb = StringBuilder("💬 *$profile Recent SMS Messages (${smsList.size})*\n\n")
                            for (item in smsList) {
                                sb.append("• *From:* `${item.sender}`\n")
                                sb.append("  ${item.body}\n\n")
                            }
                            sendTelegramMessage(token, chatId, sb.toString())
                        }
                        HermesNodeService.recordTask("Read SMS", "TELEGRAM", "SUCCESS", System.currentTimeMillis() - startTime, "${smsList.size} msgs")
                    } catch (e: Exception) {
                        sendTelegramMessage(token, chatId, "❌ *SMS Read Error*\n${e.message}")
                    }
                }

                "/location", "/gps" -> {
                    val loc = locationModule.getCurrentLocation()
                    if (loc.has("latitude")) {
                        val lat = loc.optDouble("latitude")
                        val lon = loc.optDouble("longitude")
                        val acc = loc.optDouble("accuracy_meters")
                        val src = loc.optString("source")
                        val msg = """
                            📍 *$profile GPS Location Fix*
                            • *Latitude:* `$lat`
                            • *Longitude:* `$lon`
                            • *Accuracy:* `±${acc.toInt()}m` ($src)
                            🗺️ *Google Maps:* https://maps.google.com/?q=$lat,$lon
                        """.trimIndent()
                        sendTelegramMessage(token, chatId, msg)
                        HermesNodeService.recordTask("GPS Location", "TELEGRAM", "SUCCESS", System.currentTimeMillis() - startTime, "$lat,$lon")
                    } else {
                        val err = loc.optString("error", "Unknown GPS error.")
                        sendTelegramMessage(token, chatId, "❌ *Location Error*\n$err")
                        HermesNodeService.recordTask("GPS Location", "TELEGRAM", "FAILED", System.currentTimeMillis() - startTime, err)
                    }
                }

                "/sys", "/kernel", "/battery" -> {
                    val status = deviceModule.getDeviceStatus()
                    val kernel = deviceModule.getKernelInfo()
                    val devId = deviceModule.getDeviceId()
                    val report = """
                        📱 *$profile Full System Diagnostics*
                        • *Model:* ${status.manufacturer} ${status.model}
                        • *Android:* ${status.androidVersion} (API ${status.apiLevel})
                        • *Device ID:* `$devId`
                        • *Kernel:* `$kernel`
                        • *Battery:* ${status.batteryLevel}% (Charging: ${status.isCharging})
                        • *RAM Free:* ${status.freeRamMb} MB / ${status.totalRamMb} MB
                        • *Local IP:* `${status.ipAddress ?: "N/A"}`
                        • *Uptime:* ${status.uptimeSeconds / 60} minutes
                    """.trimIndent()
                    sendTelegramMessage(token, chatId, report)
                    HermesNodeService.recordTask("System Diagnostics", "TELEGRAM", "SUCCESS", System.currentTimeMillis() - startTime, "${status.batteryLevel}%")
                }

                "/apps" -> {
                    val apps = deviceModule.getInstalledApps(30)
                    val sb = StringBuilder("📦 *$profile Installed Apps (${apps.size})*\n\n")
                    for ((label, pkg) in apps) {
                        sb.append("• *$label*: `$pkg`\n")
                    }
                    sendTelegramMessage(token, chatId, sb.toString())
                    HermesNodeService.recordTask("List Apps", "TELEGRAM", "SUCCESS", System.currentTimeMillis() - startTime, "${apps.size} apps")
                }

                "/logcat" -> {
                    val logs = deviceModule.getRecentLogcat(25)
                    sendTelegramMessage(token, chatId, "📋 *$profile System Logcat (Last 25 lines):*\n```\n$logs\n```")
                    HermesNodeService.recordTask("Dump Logcat", "TELEGRAM", "SUCCESS", System.currentTimeMillis() - startTime)
                }

                "/ping" -> {
                    val elapsed = System.currentTimeMillis() - startTime
                    sendTelegramMessage(token, chatId, "🏓 Pong! $profile is active. Roundtrip: ${elapsed}ms.")
                    HermesNodeService.recordTask("Ping", "TELEGRAM", "SUCCESS", elapsed)
                }

                "/sh", "/shell", "/exec" -> {
                    if (argument.isBlank()) {
                        sendTelegramMessage(token, chatId, "⚠️ Usage: `/sh <command>`\nExample: `/sh ls -la /sdcard` or `/sh uname -a`")
                    } else {
                        val res = terminalModule.executeShell(argument, timeoutSeconds = 20)
                        val out = res.stdout.ifEmpty { "(no stdout)" }
                        val err = if (res.stderr.isNotEmpty()) "\n*Stderr:*\n```\n${res.stderr}\n```" else ""
                        val msg = """
                            💻 *$profile Shell Result*
                            *Exit:* `${res.exitCode}` | *Time:* `${res.durationMs}ms`
                            *Stdout:*
                            ```
                            $out
                            ```$err
                        """.trimIndent()
                        sendTelegramMessage(token, chatId, msg)
                        HermesNodeService.recordTask("Shell: $argument", "TELEGRAM", if (res.exitCode == 0) "SUCCESS" else "FAILED", res.durationMs, "Exit ${res.exitCode}")
                    }
                }

                "/tap" -> {
                    val xy = argument.split("\\s+".toRegex())
                    if (xy.size >= 2) {
                        val x = xy[0].toFloatOrNull() ?: 500f
                        val y = xy[1].toFloatOrNull() ?: 500f
                        val ok = org.nous.hermesdroid.modules.HermesAccessibilityService.clickCoordinate(x, y)
                        sendTelegramMessage(token, chatId, if (ok) "👆 Clicked ($x, $y) successfully." else "⚠️ Accessibility service disabled or failed.")
                        HermesNodeService.recordTask("Tap ($x, $y)", "TELEGRAM", if (ok) "SUCCESS" else "FAILED", System.currentTimeMillis() - startTime)
                    } else {
                        sendTelegramMessage(token, chatId, "Usage: `/tap <x> <y>` (e.g. `/tap 540 960`)")
                    }
                }

                "/type" -> {
                    val ok = org.nous.hermesdroid.modules.HermesAccessibilityService.inputText(argument)
                    sendTelegramMessage(token, chatId, if (ok) "⌨️ Typed '$argument' into focused field." else "⚠️ Failed to type text. Ensure an input field is focused.")
                    HermesNodeService.recordTask("Type text", "TELEGRAM", if (ok) "SUCCESS" else "FAILED", System.currentTimeMillis() - startTime)
                }

                "/home" -> {
                    val ok = org.nous.hermesdroid.modules.HermesAccessibilityService.pressHome()
                    sendTelegramMessage(token, chatId, if (ok) "🏠 Pressed HOME button." else "⚠️ Accessibility disabled.")
                }

                "/back" -> {
                    val ok = org.nous.hermesdroid.modules.HermesAccessibilityService.pressBack()
                    sendTelegramMessage(token, chatId, if (ok) "◀️ Pressed BACK button." else "⚠️ Accessibility disabled.")
                }

                "/quick_settings" -> {
                    val ok = org.nous.hermesdroid.modules.HermesAccessibilityService.openQuickSettings()
                    sendTelegramMessage(token, chatId, if (ok) "⚙️ Opened Quick Settings." else "⚠️ Accessibility disabled.")
                }

                "/screenshot" -> {
                    val ok = org.nous.hermesdroid.modules.HermesAccessibilityService.takeScreenshot()
                    sendTelegramMessage(token, chatId, if (ok) "📸 System screenshot captured." else "⚠️ Screenshot requires Android 9+ and Accessibility.")
                }

                "/record" -> {
                    val sec = argument.toIntOrNull() ?: 5
                    sendTelegramMessage(token, chatId, "🎙️ *Recording $sec seconds of ambient audio...*")
                    val recResult = audioModule.recordAudio(sec)
                    recResult.fold(
                        onSuccess = { audioFile ->
                            sendTelegramAudio(token, chatId, audioFile, "🎙️ $profile Ambient Audio Snapshot ($sec sec)")
                            audioFile.delete()
                            HermesNodeService.recordTask("Record Audio", "TELEGRAM", "SUCCESS", System.currentTimeMillis() - startTime)
                        },
                        onFailure = { err ->
                            sendTelegramMessage(token, chatId, "❌ Audio Record Error: ${err.message}")
                            HermesNodeService.recordTask("Record Audio", "TELEGRAM", "FAILED", System.currentTimeMillis() - startTime)
                        }
                    )
                }

                "/call" -> {
                    if (argument.isBlank()) {
                        sendTelegramMessage(token, chatId, "⚠️ Usage: `/call <phone_number>`")
                    } else {
                        val num = argument.trim()
                        val ok = telephonyModule.makeCall(num, direct = true)
                        sendTelegramMessage(token, chatId, if (ok) "📞 Initiated phone call to `$num` via SIM." else "❌ Failed to initiate call.")
                    }
                }

                "/sms", "/send_sms" -> {
                    val smsParts = argument.split("\\s+".toRegex(), limit = 2)
                    if (smsParts.size < 2) {
                        sendTelegramMessage(token, chatId, "⚠️ Usage: `/sms <phone_number> <message>`")
                    } else {
                        val num = smsParts[0]
                        val text = smsParts[1]
                        val ok = telephonyModule.sendSms(num, text)
                        sendTelegramMessage(token, chatId, if (ok) "✉️ Cellular SMS successfully sent to `$num`:\n\"$text\"" else "❌ Failed to send SMS.")
                    }
                }

                "/open_app" -> {
                    val pkg = argument.trim().ifEmpty { "com.instagram.android" }
                    val intent = context.packageManager.getLaunchIntentForPackage(pkg)
                    if (intent != null) {
                        intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                        context.startActivity(intent)
                        sendTelegramMessage(token, chatId, "🚀 Successfully launched `$pkg`.")
                    } else {
                        sendTelegramMessage(token, chatId, "❌ Package `$pkg` not found on this device.")
                    }
                }

                "/ui_dump" -> {
                    val tree = HermesAccessibilityService.dumpHierarchy()
                    val str = tree.toString(2)
                    val truncated = if (str.length > 3500) str.take(3500) + "\n... (truncated)" else str
                    sendTelegramMessage(token, chatId, "📋 *UI Element Hierarchy:*\n```json\n$truncated\n```")
                }

                "/click" -> {
                    val coords = argument.split("\\s+".toRegex())
                    if (coords.size == 2 && coords[0].toFloatOrNull() != null && coords[1].toFloatOrNull() != null) {
                        val ok = HermesAccessibilityService.clickCoordinate(coords[0].toFloat(), coords[1].toFloat())
                        sendTelegramMessage(token, chatId, if (ok) "👆 Tapped coordinate (${coords[0]}, ${coords[1]})" else "⚠️ Accessibility service disabled.")
                    } else if (argument.isNotBlank()) {
                        val ok = HermesAccessibilityService.clickByText(argument.trim())
                        sendTelegramMessage(token, chatId, if (ok) "👆 Clicked on node with text \"${argument.trim()}\"" else "⚠️ Node with text \"${argument.trim()}\" not found or not clickable.")
                    } else {
                        sendTelegramMessage(token, chatId, "Usage: `/click <x> <y>` or `/click <text>`")
                    }
                }

                "/logcat" -> {
                    val lParts = argument.split("\\s+".toRegex())
                    val tag = if (lParts.isNotEmpty() && lParts[0].isNotBlank() && lParts[0].toIntOrNull() == null) lParts[0] else null
                    val lines = lParts.firstOrNull { it.toIntOrNull() != null }?.toIntOrNull() ?: 25
                    val cmd = if (tag != null) "logcat -d -s $tag -t $lines" else "logcat -d -t $lines"
                    val res = terminalModule.executeShell(cmd, timeoutSeconds = 5)
                    val out = res.stdout.ifBlank { res.stderr }.takeLast(3800)
                    sendTelegramMessage(token, chatId, "📋 *Logcat Output ($lines lines)*:\n```\n$out\n```")
                }

                else -> {
                    sendTelegramMessage(token, chatId, "❓ Unknown command: '$rawText'. Send /help for available commands on $profile.")
                }
            }
        }
    }

    private fun sendTelegramMessage(token: String, chatId: Long, text: String) {
        scope.launch(Dispatchers.IO) {
            try {
                val form = FormBody.Builder()
                    .add("chat_id", chatId.toString())
                    .add("text", text)
                    .add("parse_mode", "Markdown")
                    .build()
                val req = Request.Builder()
                    .url("https://api.telegram.org/bot$token/sendMessage")
                    .post(form)
                    .build()
                client.newCall(req).execute().close()
            } catch (e: Exception) {
                HermesNodeService.addLog("TELEGRAM", "Failed to send message: ${e.message}")
            }
        }
    }

    private fun sendTelegramPhoto(token: String, chatId: Long, bytes: ByteArray, caption: String) {
        scope.launch(Dispatchers.IO) {
            try {
                val body = MultipartBody.Builder()
                    .setType(MultipartBody.FORM)
                    .addFormDataPart("chat_id", chatId.toString())
                    .addFormDataPart("caption", caption)
                    .addFormDataPart(
                        "photo",
                        "photo.jpg",
                        bytes.toRequestBody("image/jpeg".toMediaType())
                    )
                    .build()
                val req = Request.Builder()
                    .url("https://api.telegram.org/bot$token/sendPhoto")
                    .post(body)
                    .build()
                client.newCall(req).execute().close()
                HermesNodeService.addLog("TELEGRAM", "Photo delivered successfully to chat $chatId")
            } catch (e: Exception) {
                HermesNodeService.addLog("TELEGRAM", "Failed to upload photo: ${e.message}")
            }
        }
    }

    private fun sendTelegramAudio(token: String, chatId: Long, file: File, caption: String) {
        scope.launch(Dispatchers.IO) {
            try {
                val body = MultipartBody.Builder()
                    .setType(MultipartBody.FORM)
                    .addFormDataPart("chat_id", chatId.toString())
                    .addFormDataPart("caption", caption)
                    .addFormDataPart(
                        "voice",
                        file.name,
                        file.readBytes().toRequestBody("audio/mp4".toMediaType())
                    )
                    .build()
                val req = Request.Builder()
                    .url("https://api.telegram.org/bot$token/sendVoice")
                    .post(body)
                    .build()
                client.newCall(req).execute().close()
                HermesNodeService.addLog("TELEGRAM", "Audio voice delivered to chat $chatId")
            } catch (e: Exception) {
                HermesNodeService.addLog("TELEGRAM", "Failed to upload audio: ${e.message}")
            }
        }
    }

    private fun downloadTelegramFile(token: String, fileId: String, fileName: String): File? {
        return try {
            val getFileReq = Request.Builder()
                .url("https://api.telegram.org/bot$token/getFile?file_id=$fileId")
                .build()
            val getFileRes = client.newCall(getFileReq).execute()
            val getFileBody = getFileRes.body?.string() ?: ""
            getFileRes.close()
            val filePath = JSONObject(getFileBody).optJSONObject("result")?.optString("file_path") ?: return null

            val downloadReq = Request.Builder()
                .url("https://api.telegram.org/file/bot$token/$filePath")
                .build()
            val downloadRes = client.newCall(downloadReq).execute()
            if (!downloadRes.isSuccessful) return null

            val targetFile = File(context.cacheDir, fileName)
            downloadRes.body?.byteStream()?.use { input ->
                FileOutputStream(targetFile).use { output ->
                    input.copyTo(output)
                }
            }
            downloadRes.close()
            targetFile
        } catch (e: Exception) {
            HermesNodeService.addLog("TELEGRAM", "Download file error: ${e.message}")
            null
        }
    }
}
