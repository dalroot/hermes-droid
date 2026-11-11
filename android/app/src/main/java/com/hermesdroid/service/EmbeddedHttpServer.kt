package com.hermesdroid.service

import android.content.Context
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import fi.iki.elonen.NanoHTTPD
import org.json.JSONArray
import org.json.JSONObject
import com.hermesdroid.modules.*

class EmbeddedHttpServer(
    private val context: Context,
    port: Int = 8899,
    private val securityTokenProvider: () -> String
) : NanoHTTPD(port) {

    private val telephonyModule = TelephonyModule(context)
    private val deviceModule = DeviceModule(context)
    private val terminalModule = TerminalModule(context)
    private val contactsModule = ContactsModule(context)
    private val mediaModule = MediaModule(context)
    private val locationModule = LocationModule(context)
    private val radioSilenceModule = RadioSilenceModule(context)
    private val packageInstallerModule = PackageInstallerModule(context)
    private val audioModule = AudioModule(context)

    override fun serve(session: IHTTPSession): Response {
        val startTime = System.currentTimeMillis()
        val uri = session.uri
        val method = session.method
        val headers = session.headers

        // CORS preflight
        if (method == Method.OPTIONS) {
            val response = newFixedLengthResponse(Response.Status.OK, "text/plain", "")
            addCorsHeaders(response)
            return response
        }

        // Strict Header-only Token validation (Fail-closed)
        val expectedToken = securityTokenProvider().trim()
        val incomingToken = headers["x-hermes-token"]?.trim() ?: ""
        val tokenValid = expectedToken.isNotEmpty() && incomingToken.isNotEmpty() &&
            java.security.MessageDigest.isEqual(
                incomingToken.toByteArray(Charsets.UTF_8),
                expectedToken.toByteArray(Charsets.UTF_8)
            )

        if (!tokenValid) {
            val err = JSONObject().apply {
                put("success", false)
                put("error", "Unauthorized: missing or invalid X-Hermes-Token header")
            }
            val response = newFixedLengthResponse(Response.Status.UNAUTHORIZED, "application/json", err.toString())
            addCorsHeaders(response)
            return response
        }

        // Live Logcat & DevTools Browser Dashboard (requires valid token)
        if (uri == "/logs" || uri == "/") {
            val html = renderDashboardHtml()
            val response = newFixedLengthResponse(Response.Status.OK, "text/html; charset=UTF-8", html)
            addCorsHeaders(response)
            return response
        }

        val jsonBody = extractJsonBody(session)

        val resultJson = try {
            when (uri) {
                "/api/v1/ping" -> {
                    JSONObject().apply {
                        put("success", true)
                        put("status", "online")
                        put("node", "Hermes Droid Android Node")
                        put("version", "0.1.0")
                        put("notification_listener_active", NotificationListener.isConnected())
                        put("accessibility_active", HermesAccessibilityService.isConnected())
                    }
                }
                "/api/v1/device/info" -> {
                    val status = deviceModule.getDeviceStatus()
                    JSONObject().apply {
                        put("success", true)
                        put("data", JSONObject().apply {
                            put("model", status.model)
                            put("manufacturer", status.manufacturer)
                            put("android_version", status.androidVersion)
                            put("api_level", status.apiLevel)
                            put("battery_level", status.batteryLevel)
                            put("is_charging", status.isCharging)
                            put("wifi_ssid", status.wifiSsid)
                            put("ip_address", status.ipAddress)
                            put("total_ram_mb", status.totalRamMb)
                            put("free_ram_mb", status.freeRamMb)
                            put("uptime_seconds", status.uptimeSeconds)
                        })
                    }
                }
                "/api/v1/telephony/sms/read" -> {
                    val limit = jsonBody.optInt("limit", 5)
                    val sender = jsonBody.optString("sender", null)
                    val query = jsonBody.optString("query", null)
                    val smsList = telephonyModule.readSms(limit, sender, query)
                    val jsonArray = JSONArray()
                    for (sms in smsList) {
                        jsonArray.put(JSONObject().apply {
                            put("id", sms.id)
                            put("sender", sms.sender)
                            put("body", sms.body)
                            put("timestamp", sms.timestamp)
                            put("is_read", sms.isRead)
                        })
                    }
                    JSONObject().apply {
                        put("success", true)
                        put("data", jsonArray)
                    }
                }
                "/api/v1/telephony/sms/send" -> {
                    val recipient = jsonBody.optString("recipient")
                    val message = jsonBody.optString("message")
                    val sent = telephonyModule.sendSms(recipient, message)
                    JSONObject().apply {
                        put("success", sent)
                        if (!sent) put("error", "Failed to dispatch SMS through SmsManager")
                    }
                }
                "/api/v1/notifications/list" -> {
                    val pkg = jsonBody.optString("package_name", null)
                    val limit = jsonBody.optInt("limit", 15)
                    val notifs = NotificationListener.getNotifications(pkg, limit)
                    val jsonArray = JSONArray()
                    for (n in notifs) {
                        jsonArray.put(JSONObject().apply {
                            put("key", n.key)
                            put("package_name", n.packageName)
                            put("app_name", n.appName)
                            put("title", n.title)
                            put("text", n.text)
                            put("post_time", n.postTime)
                            put("can_reply", n.canReply)
                        })
                    }
                    JSONObject().apply {
                        put("success", true)
                        put("data", jsonArray)
                    }
                }
                "/api/v1/notifications/reply" -> {
                    val key = jsonBody.optString("notification_key")
                    val text = jsonBody.optString("reply_text")
                    val replied = NotificationListener.sendReply(key, text)
                    JSONObject().apply {
                        put("success", replied)
                        if (!replied) put("error", "Notification not found or does not support inline reply")
                    }
                }
                "/api/v1/telephony/call" -> {
                    val phone = jsonBody.optString("phone_number")
                    val direct = jsonBody.optBoolean("direct", false)
                    val success = telephonyModule.makeCall(phone, direct)
                    JSONObject().apply {
                        put("success", success)
                    }
                }
                "/api/v1/contacts/search" -> {
                    val query = jsonBody.optString("query", "")
                    val limit = jsonBody.optInt("limit", 10)
                    val contacts = contactsModule.searchContacts(query, limit)
                    JSONObject().apply {
                        put("success", true)
                        put("data", contacts)
                    }
                }
                "/api/v1/media/photos" -> {
                    val limit = jsonBody.optInt("limit", 5)
                    val b64 = jsonBody.optBoolean("include_base64", false)
                    val photos = mediaModule.getRecentPhotos(limit, b64)
                    JSONObject().apply {
                        put("success", true)
                        put("data", photos)
                    }
                }
                "/api/v1/device/logs" -> {
                    val buffer = jsonBody.optString("buffer", "system")
                    val lines = jsonBody.optInt("lines", 50)
                    val rawLogs = terminalModule.executeShell("logcat -b $buffer -d -t $lines")
                    JSONObject().apply {
                        put("success", rawLogs.exitCode == 0)
                        put("data", JSONObject().apply {
                            put("buffer", buffer)
                            put("lines", lines)
                            put("logs", rawLogs.stdout)
                        })
                    }
                }
                "/api/v1/device/radio_silence" -> {
                    val enable = jsonBody.optBoolean("enable", true)
                    radioSilenceModule.setRadioSilence(enable)
                }
                "/api/v1/device/location" -> {
                    val loc = locationModule.getCurrentLocation()
                    JSONObject().apply {
                        put("success", !loc.has("error"))
                        put("data", loc)
                    }
                }
                "/api/v1/device/alarm" -> {
                    val duration = jsonBody.optLong("duration_seconds", 5)
                    try {
                        val alarmUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
                        val ringtone = RingtoneManager.getRingtone(context, alarmUri)
                        ringtone.play()

                        val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                            vibrator.vibrate(VibrationEffect.createOneShot(duration * 1000, VibrationEffect.DEFAULT_AMPLITUDE))
                        } else {
                            vibrator.vibrate(duration * 1000)
                        }

                        Thread {
                            Thread.sleep(duration * 1000)
                            ringtone.stop()
                        }.start()

                        JSONObject().apply {
                            put("success", true)
                            put("status", "alarm_sounding")
                            put("duration_seconds", duration)
                        }
                    } catch (e: Exception) {
                        JSONObject().apply {
                            put("success", false)
                            put("error", e.message)
                        }
                    }
                }
                "/api/v1/terminal/exec" -> {
                    val cmd = jsonBody.optString("command")
                    val timeout = jsonBody.optLong("timeout", 15)
                    val res = terminalModule.executeShell(cmd, timeout)
                    JSONObject().apply {
                        put("success", res.exitCode == 0)
                        put("data", JSONObject().apply {
                            put("stdout", res.stdout)
                            put("stderr", res.stderr)
                            put("exit_code", res.exitCode)
                            put("duration_ms", res.durationMs)
                        })
                    }
                }
                "/api/v1/device/setting" -> {
                    val setting = jsonBody.optString("setting")
                    val value = jsonBody.optString("value")
                    val success = when (setting) {
                        "flashlight" -> deviceModule.setFlashlight(value.equals("on", true))
                        "ringer_mode" -> deviceModule.setRingerMode(value)
                        else -> false
                    }
                    JSONObject().apply {
                        put("success", success)
                    }
                }
                "/api/v1/device/clipboard" -> {
                    val action = jsonBody.optString("action")
                    if (action == "get") {
                        val text = deviceModule.getClipboard()
                        JSONObject().apply {
                            put("success", true)
                            put("text", text)
                        }
                    } else {
                        val text = jsonBody.optString("text")
                        val set = deviceModule.setClipboard(text)
                        JSONObject().apply {
                            put("success", set)
                        }
                    }
                }
                "/api/v1/system/logcat" -> {
                    val lines = session.parms["lines"]?.toIntOrNull() ?: jsonBody.optInt("lines", 50)
                    val tag = session.parms["tag"] ?: jsonBody.optString("tag", "")
                    val cmd = if (tag.isNotBlank()) "logcat -d -s $tag -t $lines" else "logcat -d -t $lines"
                    val res = terminalModule.executeShell(cmd, timeoutSeconds = 5)
                    JSONObject().apply {
                        put("success", true)
                        put("lines", lines)
                        put("tag", tag)
                        put("output", res.stdout.ifBlank { res.stderr })
                    }
                }
                "/api/v1/install" -> {
                    val files = HashMap<String, String>()
                    session.parseBody(files)
                    var targetFile: java.io.File? = null
                    for ((k, tmpPath) in files) {
                        val tmp = java.io.File(tmpPath)
                        if (tmp.exists() && tmp.length() > 0) {
                            val apk = java.io.File(context.cacheDir, "remote_install_${System.currentTimeMillis()}.apk")
                            tmp.copyTo(apk, overwrite = true)
                            targetFile = apk
                            break
                        }
                    }
                    if (targetFile != null) {
                        val res = packageInstallerModule.installApk(targetFile)
                        JSONObject().apply {
                            put("success", res.isSuccess)
                            put("message", res.getOrNull() ?: res.exceptionOrNull()?.message)
                        }
                    } else {
                        JSONObject().apply {
                            put("success", false)
                            put("error", "No APK file uploaded in request body.")
                        }
                    }
                }
                "/api/v1/accessibility/ui" -> {
                    HermesAccessibilityService.dumpHierarchy().apply {
                        put("success", !has("error"))
                    }
                }
                "/api/v1/accessibility/click" -> {
                    val x = jsonBody.optDouble("x", -1.0)
                    val y = jsonBody.optDouble("y", -1.0)
                    val text = jsonBody.optString("text", "")
                    val ok = if (x >= 0 && y >= 0) {
                        HermesAccessibilityService.clickCoordinate(x.toFloat(), y.toFloat())
                    } else if (text.isNotBlank()) {
                        HermesAccessibilityService.clickByText(text)
                    } else false
                    JSONObject().apply {
                        put("success", ok)
                    }
                }
                else -> {
                    JSONObject().apply {
                        put("success", false)
                        put("error", "Unknown endpoint: $uri")
                    }
                }
            }
        } catch (e: Exception) {
            JSONObject().apply {
                put("success", false)
                put("error", e.message ?: "Internal error")
            }
        }

        val elapsed = System.currentTimeMillis() - startTime
        val isSuccess = resultJson.optBoolean("success", true)
        val shortName = uri.removePrefix("/api/v1/").ifEmpty { uri }
        HermesNodeService.recordTask(
            title = shortName,
            source = "HTTP",
            status = if (isSuccess) "SUCCESS" else "FAILED",
            durationMs = elapsed,
            details = uri
        )

        val response = newFixedLengthResponse(Response.Status.OK, "application/json", resultJson.toString())
        addCorsHeaders(response)
        return response
    }

    private fun extractJsonBody(session: IHTTPSession): JSONObject {
        val map = HashMap<String, String>()
        try {
            session.parseBody(map)
            val postData = map["postData"]
            if (!postData.isNullOrBlank()) {
                return JSONObject(postData)
            }
        } catch (e: Exception) {
            // ignore
        }
        return JSONObject()
    }

    private fun addCorsHeaders(response: Response) {
        response.addHeader("Access-Control-Allow-Origin", "*")
        response.addHeader("Access-Control-Allow-Methods", "GET, POST, OPTIONS")
        response.addHeader("Access-Control-Allow-Headers", "Content-Type, X-Hermes-Token")
    }

    private fun renderDashboardHtml(): String {
        val profile = deviceModule.getDeviceProfile()
        return """
<!DOCTYPE html>
<html lang="en">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>Hermes Droid | Wireless DevTools</title>
    <style>
        * { box-sizing: border-box; margin: 0; padding: 0; }
        body { font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, monospace; background: #0d1117; color: #c9d1d9; padding: 20px; }
        header { display: flex; justify-content: space-between; align-items: center; border-bottom: 1px solid #30363d; padding-bottom: 15px; margin-bottom: 20px; }
        h1 { font-size: 20px; color: #58a6ff; display: flex; align-items: center; gap: 8px; }
        .badge { background: #238636; color: #fff; padding: 3px 8px; border-radius: 12px; font-size: 12px; }
        .grid { display: grid; grid-template-columns: 1fr 340px; gap: 20px; }
        @media(max-width: 900px) { .grid { grid-template-columns: 1fr; } }
        .panel { background: #161b22; border: 1px solid #30363d; border-radius: 8px; padding: 15px; }
        .panel-title { font-weight: 600; font-size: 14px; margin-bottom: 10px; color: #8b949e; text-transform: uppercase; letter-spacing: 0.5px; }
        #logcat { height: 500px; background: #010409; border: 1px solid #21262d; border-radius: 6px; padding: 12px; overflow-y: auto; font-family: Consolas, monospace; font-size: 12px; line-height: 1.5; white-space: pre-wrap; word-break: break-all; }
        .controls { display: flex; gap: 10px; margin-bottom: 12px; }
        input[type="text"] { background: #0d1117; border: 1px solid #30363d; color: #c9d1d9; padding: 6px 12px; border-radius: 6px; flex: 1; font-size: 13px; }
        button { background: #21262d; border: 1px solid #30363d; color: #c9d1d9; padding: 6px 14px; border-radius: 6px; cursor: pointer; font-size: 13px; font-weight: 500; transition: 0.2s; }
        button:hover { background: #30363d; color: #fff; }
        .btn-primary { background: #238636; border-color: #2ea043; color: #fff; }
        .btn-primary:hover { background: #2ea043; }
        .drop-box { border: 2px dashed #30363d; border-radius: 8px; padding: 25px 15px; text-align: center; margin-bottom: 15px; cursor: pointer; transition: 0.2s; }
        .drop-box:hover { border-color: #58a6ff; background: #0d1117; }
        .action-list { display: flex; flex-direction: column; gap: 8px; }
        .action-list button { width: 100%; text-align: left; }
        .status-msg { font-size: 12px; margin-top: 8px; color: #3fb950; min-height: 18px; }
    </style>
</head>
<body>
    <header>
        <h1>⚡ Hermes Droid DevBridge <span class="badge">ONLINE</span></h1>
        <div style="font-size: 13px; color: #8b949e;">Node: <strong>$profile</strong></div>
    </header>

    <div class="grid">
        <div class="panel">
            <div class="panel-title">📡 Real-Time Logcat Stream</div>
            <div class="controls">
                <input type="text" id="filterInput" placeholder="Filter by Tag or Keyword (e.g. ActivityTaskManager, MyApp)...">
                <button id="pauseBtn" onclick="togglePause()">Pause</button>
                <button onclick="fetchLogs()">Refresh</button>
            </div>
            <div id="logcat">Connecting to device log stream...</div>
        </div>

        <div>
            <div class="panel" style="margin-bottom: 20px;">
                <div class="panel-title">📦 Wireless APK Deployment</div>
                <div class="drop-box" onclick="document.getElementById('apkFile').click()">
                    <div style="font-size: 28px; margin-bottom: 6px;">📲</div>
                    <div style="font-weight: 500;">Click to select APK</div>
                    <div style="font-size: 11px; color: #8b949e; margin-top: 4px;">Installs directly without USB or cables</div>
                    <input type="file" id="apkFile" accept=".apk" style="display:none;" onchange="handleFileSelect(this)">
                </div>
                <button class="btn-primary" style="width: 100%;" onclick="uploadApk()">Deploy APK to Device</button>
                <div id="uploadStatus" class="status-msg"></div>
            </div>

            <div class="panel">
                <div class="panel-title">🎮 Remote Actuators</div>
                <div class="action-list">
                    <button onclick="triggerApi('/api/v1/accessibility/ui')">📋 Dump UI Hierarchy</button>
                    <button onclick="triggerApi('/api/v1/device/info')">📱 Inspect Device Diagnostics</button>
                    <button onclick="triggerApi('/api/v1/telephony/sms/read')">💬 Read Recent SMS / OTP</button>
                </div>
                <div id="actionStatus" class="status-msg"></div>
            </div>
        </div>
    </div>

    <script>
        let isPaused = false;
        let lastOutput = '';

        function togglePause() {
            isPaused = !isPaused;
            document.getElementById('pauseBtn').innerText = isPaused ? 'Resume' : 'Pause';
        }

        async function fetchLogs() {
            if (isPaused) return;
            const filter = document.getElementById('filterInput').value.trim();
            try {
                const url = '/api/v1/system/logcat?lines=100' + (filter ? '&tag=' + encodeURIComponent(filter) : '');
                const res = await fetch(url);
                const data = await res.json();
                if (data.output && data.output !== lastOutput) {
                    lastOutput = data.output;
                    const el = document.getElementById('logcat');
                    el.innerText = data.output;
                    el.scrollTop = el.scrollHeight;
                }
            } catch (e) {
                // Ignore transient connection delays
            }
        }

        setInterval(fetchLogs, 2000);
        fetchLogs();

        let selectedFile = null;
        function handleFileSelect(input) {
            if (input.files && input.files[0]) {
                selectedFile = input.files[0];
                document.getElementById('uploadStatus').innerText = 'Selected: ' + selectedFile.name + ' (' + (selectedFile.size / (1024*1024)).toFixed(1) + ' MB)';
            }
        }

        async function uploadApk() {
            if (!selectedFile) {
                alert('Please select an APK file first.');
                return;
            }
            const status = document.getElementById('uploadStatus');
            status.innerText = 'Uploading APK to node...';
            const formData = new FormData();
            formData.append('file', selectedFile);
            try {
                const res = await fetch('/api/v1/install', { method: 'POST', body: formData });
                const json = await res.json();
                status.innerText = json.success ? '✅ ' + (json.message || 'Installation initiated on phone!') : '❌ ' + (json.error || 'Failed');
            } catch (e) {
                status.innerText = '❌ Error: ' + e.message;
            }
        }

        async function triggerApi(endpoint) {
            const status = document.getElementById('actionStatus');
            status.innerText = 'Requesting ' + endpoint + '...';
            try {
                const res = await fetch(endpoint);
                const json = await res.json();
                status.innerText = '✅ Success! Result logged in console.';
                console.log(json);
                alert(JSON.stringify(json, null, 2).substring(0, 500));
            } catch (e) {
                status.innerText = '❌ Failed: ' + e.message;
            }
        }
    </script>
</body>
</html>
        """.trimIndent()
    }
}
