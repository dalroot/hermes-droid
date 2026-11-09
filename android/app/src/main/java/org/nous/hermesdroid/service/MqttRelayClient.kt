package org.nous.hermesdroid.service

import android.content.Context
import android.graphics.Bitmap
import android.util.Base64
import kotlinx.coroutines.*
import org.eclipse.paho.client.mqttv3.*
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence
import org.json.JSONArray
import org.json.JSONObject
import org.nous.hermesdroid.model.MqttStatus
import org.nous.hermesdroid.modules.*
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.net.URL
import javax.net.ssl.SSLContext

class MqttRelayClient(
    private val context: Context,
    private val brokerHostProvider: () -> String = { HermesNodeService.mqttBrokerHost.value },
    private val brokerPortProvider: () -> Int = { HermesNodeService.mqttBrokerPort.value }
) {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var client: MqttAsyncClient? = null
    private var isRunning = false

    private val deviceModule = DeviceModule(context)
    private val mediaModule = MediaModule(context)
    private val terminalModule = TerminalModule(context)
    private val audioModule = AudioModule(context)
    private val packageInstallerModule = PackageInstallerModule(context)
    private val telephonyModule = TelephonyModule(context)
    private val contactsModule = ContactsModule(context)
    private val locationModule = LocationModule(context)
    private val radioSilenceModule = RadioSilenceModule(context)

    private val deviceId: String
        get() = deviceModule.getDeviceId()

    private val cmdTopic: String
        get() = "hermes/node/$deviceId/cmd"

    private val resTopic: String
        get() = "hermes/node/$deviceId/res"

    private val pairApproveTopic: String
        get() = "hermes/node/$deviceId/pair/approve"

    private val announceTopic: String = "hermes/node/announce"

    fun start() {
        if (isRunning) return
        isRunning = true
        scope.launch {
            connectLoop()
        }
    }

    fun stop() {
        isRunning = false
        try {
            client?.disconnect()
            client?.close()
        } catch (_: Exception) {}
        client = null
        HermesNodeService.setMqttStatus(
            MqttStatus(state = "DISCONNECTED", brokerHost = brokerHostProvider(), brokerPort = brokerPortProvider())
        )
    }

    private suspend fun connectLoop() {
        while (isRunning) {
            val brokerHost = brokerHostProvider()
            val brokerPort = brokerPortProvider()
            val serverUri = if (brokerPort == 8883) "ssl://$brokerHost:$brokerPort" else "tcp://$brokerHost:$brokerPort"
            val clientId = "hermes_node_$deviceId"

            // Ensure any previous client is disconnected and closed before creating a new one
            try {
                client?.disconnectForcibly(1000, 1000)
                client?.close()
            } catch (_: Exception) {}
            client = null

            try {
                HermesNodeService.setMqttStatus(
                    MqttStatus(state = "CONNECTING", brokerHost = brokerHost, brokerPort = brokerPort)
                )
                HermesNodeService.addLog("MQTT", "Connecting to $serverUri as $clientId...")
                val mqttClient = MqttAsyncClient(serverUri, clientId, MemoryPersistence())
                client = mqttClient

                val options = MqttConnectOptions().apply {
                    isCleanSession = true
                    isAutomaticReconnect = true
                    connectionTimeout = 15
                    keepAliveInterval = 30
                    if (brokerPort == 8883) {
                        val sslContext = SSLContext.getInstance("TLSv1.2").apply {
                            init(null, null, null)
                        }
                        socketFactory = sslContext.socketFactory
                    }
                }

                mqttClient.setCallback(object : MqttCallbackExtended {
                    override fun connectComplete(reconnect: Boolean, serverURI: String?) {
                        HermesNodeService.setMqttStatus(
                            MqttStatus(
                                state = "CONNECTED",
                                brokerHost = brokerHost,
                                brokerPort = brokerPort,
                                lastSeenMs = System.currentTimeMillis()
                            )
                        )
                        HermesNodeService.addLog("MQTT", "Connected! Reconnect=$reconnect. Subscribing to $cmdTopic")
                        try {
                            mqttClient.subscribe(arrayOf(cmdTopic, pairApproveTopic), intArrayOf(1, 1))
                            sendAnnounce()
                        } catch (e: Exception) {
                            HermesNodeService.addLog("MQTT", "Subscribe error: ${e.message}")
                        }
                    }

                    override fun connectionLost(cause: Throwable?) {
                        HermesNodeService.setMqttStatus(
                            MqttStatus(
                                state = "ERROR",
                                brokerHost = brokerHost,
                                brokerPort = brokerPort,
                                error = cause?.message ?: "Connection lost"
                            )
                        )
                        HermesNodeService.addLog("MQTT", "Connection lost: ${cause?.message}. Waiting for Paho auto-reconnect...")
                    }

                    override fun messageArrived(topic: String?, message: MqttMessage?) {
                        if (message == null) return
                        val payload = String(message.payload)
                        if (topic == pairApproveTopic) {
                            scope.launch {
                                handlePairApprove(payload)
                            }
                        } else if (topic == cmdTopic) {
                            scope.launch {
                                handleCommand(payload)
                            }
                        }
                    }

                    override fun deliveryComplete(token: IMqttDeliveryToken?) {}
                })

                val token = mqttClient.connect(options)
                token.waitForCompletion(15000)

                // Maintain this connection as long as service runs.
                // Do NOT recreate clients when disconnected; Paho's isAutomaticReconnect handles it!
                while (isRunning) {
                    delay(10000)
                }

            } catch (e: Exception) {
                HermesNodeService.setMqttStatus(
                    MqttStatus(
                        state = "ERROR",
                        brokerHost = brokerHost,
                        brokerPort = brokerPort,
                        error = e.message ?: "Connect error"
                    )
                )
                HermesNodeService.addLog("MQTT", "MQTT Error: ${e.message}. Retrying in 6s...")
                try {
                    client?.disconnectForcibly(1000, 1000)
                    client?.close()
                } catch (_: Exception) {}
                client = null
                delay(6000)
            }
        }
    }

    private fun sendAnnounce() {
        try {
            val status = deviceModule.getDeviceStatus()
            val pairing = HermesNodeService.pairingStatus.value
            val announce = JSONObject().apply {
                put("type", "ANNOUNCE")
                put("deviceId", deviceId)
                put("model", status.model)
                put("manufacturer", status.manufacturer)
                put("android", status.androidVersion)
                put("battery", status.batteryLevel)
                put("isCharging", status.isCharging)
                put("isPaired", pairing.state == "PAIRED")
                // Note: pairingCode is deliberately excluded from public announce topic for security
                put("timestamp", System.currentTimeMillis())
            }
            publish(announceTopic, announce.toString())
        } catch (e: Exception) {
            HermesNodeService.addLog("MQTT", "Announce error: ${e.message}")
        }
    }

    private fun handlePairApprove(payload: String) {
        HermesNodeService.addLog("MQTT", "Pairing approval received: $payload")
        val json = try { JSONObject(payload) } catch (_: Exception) { JSONObject() }
        val approver = json.optString("approver", "Hermes Gateway")
        HermesNodeService.setPairingApproved(context, approver)

        val response = JSONObject().apply {
            put("type", "PAIR_CONFIRMED")
            put("deviceId", deviceId)
            put("status", "PAIRED")
            put("pairedAt", System.currentTimeMillis())
        }
        publish(resTopic, response.toString())
    }

    private suspend fun handleCommand(rawPayload: String) {
        val startTime = System.currentTimeMillis()
        HermesNodeService.addLog("MQTT", "Command received: $rawPayload")
        val json = try {
            JSONObject(rawPayload)
        } catch (_: Exception) {
            return
        }

        val reqId = json.optString("reqId", "req_${System.currentTimeMillis()}")
        val action = json.optString("action", "").lowercase().trim()
        val params = json.optJSONObject("params") ?: JSONObject()

        val response = JSONObject().apply {
            put("reqId", reqId)
            put("deviceId", deviceId)
            put("action", action)
        }

        try {
            when (action) {
                "ping" -> {
                    response.put("success", true)
                    response.put("status", "pong")
                    response.put("battery", deviceModule.getDeviceStatus().batteryLevel)
                    response.put("isPaired", HermesNodeService.pairingStatus.value.state == "PAIRED")
                }

                "pair_status", "pairing" -> {
                    val p = HermesNodeService.pairingStatus.value
                    response.put("success", true)
                    response.put("isPaired", p.state == "PAIRED")
                    response.put("pairingState", p.state)
                    response.put("pairingCode", p.code)
                    response.put("pairedAt", p.pairedAt)
                }

                "device_profile", "status" -> {
                    val p = deviceModule.getDeviceStatus()
                    response.put("success", true)
                    response.put("profile", JSONObject().apply {
                        put("model", p.model)
                        put("manufacturer", p.manufacturer)
                        put("android", p.androidVersion)
                        put("battery", p.batteryLevel)
                        put("isCharging", p.isCharging)
                        put("ramFreeMb", p.freeRamMb)
                        put("ramTotalMb", p.totalRamMb)
                        put("localIp", p.ipAddress ?: "unknown")
                        put("uptimeSeconds", p.uptimeSeconds)
                        put("isPaired", HermesNodeService.pairingStatus.value.state == "PAIRED")
                    })
                }

                "take_photo", "camera" -> {
                    val useFront = params.optBoolean("front", false)
                    val result = mediaModule.captureLivePhoto(useFront)
                    if (result.isSuccess) {
                        val bytes = result.getOrThrow()
                        response.put("success", true)
                        response.put("photo_b64", Base64.encodeToString(bytes, Base64.NO_WRAP))
                        response.put("bytes_length", bytes.size)
                    } else {
                        response.put("success", false)
                        response.put("error", result.exceptionOrNull()?.message)
                    }
                }

                "screenshot", "take_screenshot" -> {
                    val scResult = HermesAccessibilityService.captureScreenshotBitmap()
                    if (scResult.isSuccess) {
                        val bitmap = scResult.getOrThrow()
                        val stream = ByteArrayOutputStream()
                        bitmap.compress(Bitmap.CompressFormat.JPEG, 85, stream)
                        val bytes = stream.toByteArray()
                        bitmap.recycle()
                        response.put("success", true)
                        response.put("screenshot_b64", Base64.encodeToString(bytes, Base64.NO_WRAP))
                        response.put("bytes_length", bytes.size)
                    } else {
                        // Fallback: execute screencap command via shell
                        val tmpSc = File(context.cacheDir, "screen_${System.currentTimeMillis()}.png")
                        val shRes = terminalModule.executeShell("screencap -p ${tmpSc.absolutePath}")
                        if (tmpSc.exists() && tmpSc.length() > 0) {
                            val bytes = tmpSc.readBytes()
                            tmpSc.delete()
                            response.put("success", true)
                            response.put("screenshot_b64", Base64.encodeToString(bytes, Base64.NO_WRAP))
                            response.put("bytes_length", bytes.size)
                        } else {
                            response.put("success", false)
                            response.put("error", scResult.exceptionOrNull()?.message ?: shRes.stderr)
                        }
                    }
                }

                "record_audio", "audio" -> {
                    val seconds = params.optInt("seconds", 5)
                    val recResult = audioModule.recordAudio(seconds)
                    if (recResult.isSuccess) {
                        val audioFile = recResult.getOrThrow()
                        response.put("success", true)
                        response.put("audio_b64", Base64.encodeToString(audioFile.readBytes(), Base64.NO_WRAP))
                        audioFile.delete()
                    } else {
                        response.put("success", false)
                        response.put("error", recResult.exceptionOrNull()?.message ?: "Recording failed")
                    }
                }

                "logcat", "logs" -> {
                    val lines = params.optInt("lines", 100)
                    val logs = terminalModule.getRecentLogcat(lines)
                    response.put("success", true)
                    response.put("logs", logs)
                }

                "shell", "exec" -> {
                    val cmd = params.optString("command", "")
                    val res = terminalModule.executeShell(cmd)
                    response.put("success", res.exitCode == 0)
                    response.put("stdout", res.stdout)
                    response.put("stderr", res.stderr)
                    response.put("exitCode", res.exitCode)
                }

                "read_sms" -> {
                    val limit = params.optInt("limit", 20)
                    val senderFilter = params.optString("sender", "").ifEmpty { null }
                    val queryFilter = params.optString("query", "").ifEmpty { null }
                    val smsList = telephonyModule.readSms(limit, senderFilter, queryFilter)
                    val arr = JSONArray()
                    smsList.forEach { s ->
                        arr.put(JSONObject().apply {
                            put("id", s.id)
                            put("sender", s.sender)
                            put("body", s.body)
                            put("timestamp", s.timestamp)
                            put("isRead", s.isRead)
                        })
                    }
                    response.put("success", true)
                    response.put("count", smsList.size)
                    response.put("sms", arr)
                }

                "send_sms" -> {
                    val recipient = params.optString("recipient", "")
                    val message = params.optString("message", "")
                    if (recipient.isNotBlank() && message.isNotBlank()) {
                        val ok = telephonyModule.sendSms(recipient, message)
                        response.put("success", ok)
                    } else {
                        response.put("success", false)
                        response.put("error", "recipient and message are required")
                    }
                }

                "call_phone", "phone_call" -> {
                    val number = params.optString("number", "")
                    if (number.isNotBlank()) {
                        val ok = telephonyModule.makeCall(number)
                        response.put("success", ok)
                    } else {
                        response.put("success", false)
                        response.put("error", "phone number is required")
                    }
                }

                "get_notifications", "notifications" -> {
                    val limit = params.optInt("limit", 20)
                    val pkg = params.optString("package", "").ifEmpty { null }
                    val notifs = NotificationListener.getNotifications(pkg, limit)
                    val arr = JSONArray()
                    notifs.forEach { n ->
                        arr.put(JSONObject().apply {
                            put("key", n.key)
                            put("packageName", n.packageName)
                            put("appName", n.appName)
                            put("title", n.title)
                            put("text", n.text)
                            put("postTime", n.postTime)
                            put("canReply", n.canReply)
                        })
                    }
                    response.put("success", true)
                    response.put("count", notifs.size)
                    response.put("notifications", arr)
                }

                "reply_notification" -> {
                    val key = params.optString("key", "")
                    val replyText = params.optString("text", "")
                    if (key.isNotBlank() && replyText.isNotBlank()) {
                        val ok = NotificationListener.sendReply(key, replyText)
                        response.put("success", ok)
                    } else {
                        response.put("success", false)
                        response.put("error", "key and text are required")
                    }
                }

                "contacts_search", "contacts" -> {
                    val query = params.optString("query", "")
                    val limit = params.optInt("limit", 30)
                    val contactsArr = contactsModule.searchContacts(query, limit)
                    response.put("success", true)
                    response.put("contacts", contactsArr)
                }

                "location_get", "location" -> {
                    val loc = locationModule.getCurrentLocation()
                    response.put("success", !loc.has("error"))
                    response.put("location", loc)
                }

                "clipboard_get" -> {
                    val text = deviceModule.getClipboard()
                    response.put("success", true)
                    response.put("clipboard", text)
                }

                "clipboard_set" -> {
                    val text = params.optString("text", "")
                    val ok = deviceModule.setClipboard(text)
                    response.put("success", ok)
                }

                "toggle_flashlight" -> {
                    val enabled = params.optBoolean("enabled", true)
                    val ok = deviceModule.setFlashlight(enabled)
                    response.put("success", ok)
                }

                "radio_silence" -> {
                    val enable = params.optBoolean("enable", true)
                    val res = radioSilenceModule.setRadioSilence(enable)
                    response.put("success", res.optBoolean("success", true))
                    response.put("details", res)
                }

                "set_stay_awake" -> {
                    val enable = params.optBoolean("enabled", true)
                    HermesNodeService.setScreenStayAwake(context, enable)
                    response.put("success", true)
                    response.put("screenStayAwake", enable)
                }

                "ui_dump" -> {
                    val dump = HermesAccessibilityService.dumpHierarchy()
                    response.put("success", !dump.has("error"))
                    response.put("ui", dump)
                }

                "click" -> {
                    val x = params.optDouble("x", -1.0).toFloat()
                    val y = params.optDouble("y", -1.0).toFloat()
                    val text = params.optString("text", "")
                    val ok = if (x >= 0 && y >= 0) {
                        HermesAccessibilityService.clickCoordinate(x, y)
                    } else if (text.isNotEmpty()) {
                        HermesAccessibilityService.clickByText(text)
                    } else false
                    response.put("success", ok)
                }

                "input_text" -> {
                    val text = params.optString("text", "")
                    val ok = HermesAccessibilityService.inputText(text)
                    response.put("success", ok)
                }

                "swipe" -> {
                    val x1 = params.optDouble("x1", 0.0).toFloat()
                    val y1 = params.optDouble("y1", 0.0).toFloat()
                    val x2 = params.optDouble("x2", 0.0).toFloat()
                    val y2 = params.optDouble("y2", 0.0).toFloat()
                    val duration = params.optLong("duration", 300L)
                    val ok = HermesAccessibilityService.swipe(x1, y1, x2, y2, duration)
                    response.put("success", ok)
                }

                "press_home" -> {
                    response.put("success", HermesAccessibilityService.pressHome())
                }

                "press_back" -> {
                    response.put("success", HermesAccessibilityService.pressBack())
                }

                "open_notifications" -> {
                    response.put("success", HermesAccessibilityService.openNotifications())
                }

                "open_quick_settings" -> {
                    response.put("success", HermesAccessibilityService.openQuickSettings())
                }

                "install_apk" -> {
                    val apkB64 = params.optString("apk_b64", "")
                    val apkUrl = params.optString("url", "")
                    val expectedSha256 = params.optString("sha256", "").trim().lowercase()
                    if (expectedSha256.isEmpty()) {
                        response.put("success", false)
                        response.put("error", "Rejected: Mandatory 'sha256' parameter missing in install_apk request")
                    } else if (apkB64.isNotEmpty()) {
                        try {
                            val tmpApk = File(context.cacheDir, "update_${System.currentTimeMillis()}.apk")
                            val bytes = android.util.Base64.decode(apkB64, android.util.Base64.DEFAULT)
                            tmpApk.writeBytes(bytes)
                            HermesNodeService.addLog("TASK", "Received APK via MQTT (${bytes.size / 1024} KB). Verifying integrity...")
                            val installRes = verifyAndInstallApk(tmpApk, expectedSha256)
                            response.put("success", installRes.isSuccess)
                            if (installRes.isSuccess) {
                                response.put("message", installRes.getOrNull())
                            } else {
                                response.put("error", installRes.exceptionOrNull()?.message)
                            }
                        } catch (e: Exception) {
                            response.put("success", false)
                            response.put("error", "Failed to decode/save APK: ${e.message}")
                        }
                    } else if (apkUrl.isNotEmpty()) {
                        try {
                            if (!apkUrl.startsWith("https://", ignoreCase = true)) {
                                throw SecurityException("Insecure download rejected: URL must use HTTPS protocol")
                            }
                            val parsedUri = java.net.URI(apkUrl)
                            val host = parsedUri.host ?: ""
                            val allowedHosts = listOf("bot.cinemapluss.ir", "cinemapluss.ir", "github.com", "objects.githubusercontent.com", "raw.githubusercontent.com")
                            val isAllowed = allowedHosts.any { host.equals(it, ignoreCase = true) || host.endsWith(".$it", ignoreCase = true) }
                            if (!isAllowed) {
                                throw SecurityException("Untrusted download host rejected: $host is not in trusted repository whitelist")
                            }

                            val tmpApk = File(context.cacheDir, "update_${System.currentTimeMillis()}.apk")
                            withContext(Dispatchers.IO) {
                                val stream = URL(apkUrl).openStream()
                                val fos = FileOutputStream(tmpApk)
                                stream.copyTo(fos)
                                fos.close()
                                stream.close()
                            }

                            val installRes = verifyAndInstallApk(tmpApk, expectedSha256)
                            response.put("success", installRes.isSuccess)
                            if (installRes.isSuccess) {
                                response.put("message", installRes.getOrNull())
                            } else {
                                response.put("error", installRes.exceptionOrNull()?.message)
                            }
                        } catch (e: Exception) {
                            response.put("success", false)
                            response.put("error", e.message ?: "Download/install error")
                        }
                    } else {
                        response.put("success", false)
                        response.put("error", "Missing apk_b64 or url")
                    }
                }

                else -> {
                    response.put("success", false)
                    response.put("error", "Unknown action: $action")
                }
            }
        } catch (e: Exception) {
            response.put("success", false)
            response.put("error", e.message ?: "Execution error")
        }

        val duration = System.currentTimeMillis() - startTime
        HermesNodeService.recordTask(
            title = action,
            source = "MQTT",
            status = if (response.optBoolean("success")) "SUCCESS" else "FAILED",
            durationMs = duration
        )

        publish(resTopic, response.toString())
    }

    private fun publish(topic: String, messageStr: String) {
        try {
            val msg = MqttMessage(messageStr.toByteArray()).apply {
                qos = 1
            }
            client?.publish(topic, msg)
            HermesNodeService.addLog("MQTT", "Published response to $topic (${messageStr.length} chars)")
        } catch (e: Exception) {
            HermesNodeService.addLog("MQTT", "Publish error: ${e.message}")
        }
    }

    private fun verifyAndInstallApk(tmpApk: File, expectedSha256: String): Result<String> {
        try {
            if (expectedSha256.isBlank()) {
                throw SecurityException("Mandatory 'sha256' parameter missing in install_apk request")
            }

            val header = ByteArray(4)
            tmpApk.inputStream().use { it.read(header) }
            if (header[0] != 0x50.toByte() || header[1] != 0x4B.toByte() || header[2] != 0x03.toByte() || header[3] != 0x04.toByte()) {
                throw IllegalArgumentException("Downloaded file is not a valid APK package (invalid ZIP signature)")
            }

            val md = java.security.MessageDigest.getInstance("SHA-256")
            val actualSha256 = tmpApk.inputStream().use { input ->
                val buf = ByteArray(8192)
                var n: Int
                while (input.read(buf).also { n = it } > 0) {
                    md.update(buf, 0, n)
                }
                md.digest().joinToString("") { "%02x".format(it) }
            }
            if (!actualSha256.equals(expectedSha256, ignoreCase = true)) {
                throw SecurityException("SHA-256 integrity verification failed: expected $expectedSha256, actual $actualSha256")
            }

            val pm = context.packageManager
            val archiveInfo = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                pm.getPackageArchiveInfo(tmpApk.absolutePath, android.content.pm.PackageManager.PackageInfoFlags.of(android.content.pm.PackageManager.GET_SIGNING_CERTIFICATES.toLong()))
            } else {
                @Suppress("DEPRECATION")
                pm.getPackageArchiveInfo(tmpApk.absolutePath, android.content.pm.PackageManager.GET_SIGNATURES)
            }
            if (archiveInfo == null) {
                throw IllegalArgumentException("Downloaded file is not a parseable Android APK package")
            }
            if (archiveInfo.packageName != context.packageName) {
                throw SecurityException("Package name mismatch: Expected '${context.packageName}' but APK is '${archiveInfo.packageName}'")
            }

            val currentPkgInfo = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                pm.getPackageInfo(context.packageName, android.content.pm.PackageManager.PackageInfoFlags.of(android.content.pm.PackageManager.GET_SIGNING_CERTIFICATES.toLong()))
            } else {
                @Suppress("DEPRECATION")
                pm.getPackageInfo(context.packageName, android.content.pm.PackageManager.GET_SIGNATURES)
            }
            val currentSigs = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                currentPkgInfo.signingInfo?.apkContentsSigners
            } else {
                @Suppress("DEPRECATION")
                currentPkgInfo.signatures
            }
            val newSigs = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                archiveInfo.signingInfo?.apkContentsSigners
            } else {
                @Suppress("DEPRECATION")
                archiveInfo.signatures
            }
            if (currentSigs != null && newSigs != null && currentSigs.isNotEmpty() && newSigs.isNotEmpty()) {
                val certMd = java.security.MessageDigest.getInstance("SHA-256")
                val curCertHash = certMd.digest(currentSigs[0].toByteArray()).joinToString("") { "%02x".format(it) }
                val newCertMd = java.security.MessageDigest.getInstance("SHA-256")
                val newCertHash = newCertMd.digest(newSigs[0].toByteArray()).joinToString("") { "%02x".format(it) }
                if (curCertHash != newCertHash) {
                    throw SecurityException("Signing certificate mismatch: Expected cert $curCertHash but APK was signed with $newCertHash")
                }
            }

            return packageInstallerModule.installApk(tmpApk)
        } catch (e: Exception) {
            tmpApk.delete()
            return Result.failure(e)
        }
    }
}
