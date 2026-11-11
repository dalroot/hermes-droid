package com.hermesdroid.service

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import com.hermesdroid.model.MqttStatus
import com.hermesdroid.model.NodeLogEntry
import com.hermesdroid.model.PairingStatus
import com.hermesdroid.model.TaskEntry
import com.hermesdroid.model.TelegramStatus
import com.hermesdroid.modules.DeviceModule
import com.hermesdroid.ui.MainActivity
import java.security.SecureRandom

class HermesNodeService : Service() {

    private var server: EmbeddedHttpServer? = null
    private var telegramClient: TelegramRelayClient? = null
    private var mqttClient: MqttRelayClient? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var screenWakeLock: PowerManager.WakeLock? = null
    private var userRequestedStop: Boolean = false

    override fun onCreate() {
        super.onCreate()
        loadPreferences()
        createNotificationChannel()
        acquireWakeLock()
        startServer()
        startTelegram()
        startMqtt()
        _isRunning.value = true
        addLog("SERVICE", "Hermes Droid Node Service started")
    }

    private fun loadPreferences() {
        val prefs = getSharedPreferences("hermes_prefs", Context.MODE_PRIVATE)
        val host = prefs.getString("mqtt_host", "127.0.0.1") ?: "127.0.0.1"
        val port = prefs.getInt("mqtt_port", 1883)
        _mqttBrokerHost.value = host
        _mqttBrokerPort.value = port
        val stayAwake = prefs.getBoolean("screen_stay_awake", false)
        _screenStayAwake.value = stayAwake
        if (stayAwake) {
            acquireScreenWakeLock()
        }
        val savedToken = prefs.getString("telegram_token", null)
        val defaultToken = try { com.hermesdroid.BuildConfig.DEFAULT_TELEGRAM_TOKEN } catch (_: Exception) { "" }
        val token = when {
            !savedToken.isNullOrBlank() -> savedToken
            defaultToken.isNotBlank() -> defaultToken
            else -> ""
        }
        _telegramToken.value = token
        _telegramAuthorizedChatId.value = prefs.getLong("telegram_authorized_chat_id", -1L)
        loadPairingState(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "ACTION_STOP_SERVICE") {
            userRequestedStop = true
            getSharedPreferences("hermes_prefs", Context.MODE_PRIVATE)
                .edit().putBoolean("service_enabled", false).apply()
            addLog("SERVICE", "Manual stop requested by user.")
            stopSelf()
            return START_NOT_STICKY
        }

        userRequestedStop = false
        getSharedPreferences("hermes_prefs", Context.MODE_PRIVATE)
            .edit().putBoolean("service_enabled", true).apply()

        if (intent?.action == "ACTION_RESTART_MQTT") {
            restartMqtt()
            return START_STICKY
        }

        if (intent?.action == "ACTION_KEEP_SCREEN_ON") {
            acquireScreenWakeLock()
            return START_STICKY
        }

        if (intent?.action == "ACTION_CLEAR_SCREEN_ON") {
            releaseScreenWakeLock()
            return START_STICKY
        }

        val notification = createNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            var fgsType = ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC or
                          ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                fgsType = fgsType or ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
            }
            startForeground(NOTIFICATION_ID, notification, fgsType)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        return START_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        val prefs = getSharedPreferences("hermes_prefs", Context.MODE_PRIVATE)
        val shouldRun = prefs.getBoolean("service_enabled", true)
        if (shouldRun && !userRequestedStop) {
            addLog("SERVICE", "App swiped from recents. Scheduling persistent instant restart...")
            val restartIntent = Intent(applicationContext, BootReceiver::class.java).apply {
                action = "com.hermesdroid.RESTART_SERVICE"
            }
            val pendingIntent = PendingIntent.getBroadcast(
                applicationContext,
                101,
                restartIntent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            val alarmManager = getSystemService(Context.ALARM_SERVICE) as AlarmManager
            try {
                alarmManager.setExactAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    System.currentTimeMillis() + 1000,
                    pendingIntent
                )
            } catch (e: Exception) {
                alarmManager.set(AlarmManager.RTC_WAKEUP, System.currentTimeMillis() + 1000, pendingIntent)
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        val prefs = getSharedPreferences("hermes_prefs", Context.MODE_PRIVATE)
        val shouldRun = prefs.getBoolean("service_enabled", true)
        if (shouldRun && !userRequestedStop) {
            val restartIntent = Intent(applicationContext, BootReceiver::class.java).apply {
                action = "com.hermesdroid.RESTART_SERVICE"
            }
            val pendingIntent = PendingIntent.getBroadcast(
                applicationContext,
                102,
                restartIntent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            val alarmManager = getSystemService(Context.ALARM_SERVICE) as AlarmManager
            try {
                alarmManager.setAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    System.currentTimeMillis() + 1500,
                    pendingIntent
                )
            } catch (_: Exception) {}
        }
        stopServer()
        stopTelegram()
        stopMqtt()
        releaseWakeLock()
        releaseScreenWakeLock()
        _isRunning.value = false
        addLog("SERVICE", "Hermes Droid Node Service stopped")
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startMqtt() {
        if (mqttClient == null) {
            mqttClient = MqttRelayClient(
                this,
                brokerHostProvider = { _mqttBrokerHost.value },
                brokerPortProvider = { _mqttBrokerPort.value }
            )
            mqttClient?.start()
            addLog("MQTT", "MQTT Relay initiated -> ${_mqttBrokerHost.value}:${_mqttBrokerPort.value}")
        }
    }

    private fun stopMqtt() {
        mqttClient?.stop()
        mqttClient = null
    }

    private fun restartMqtt() {
        stopMqtt()
        startMqtt()
    }

    private fun startTelegram() {
        if (telegramClient == null) {
            telegramClient = TelegramRelayClient(this) { _telegramToken.value }
            telegramClient?.start()
            addLog("TELEGRAM", "Telegram Relay Client initiated")
        }
    }

    private fun stopTelegram() {
        telegramClient?.stop()
        telegramClient = null
    }

    private fun startServer() {
        if (server == null) {
            server = EmbeddedHttpServer(this, port = 8899) {
                _pairingToken.value
            }
            try {
                server?.start()
                addLog("SERVER", "Embedded HTTP server listening on :8899")
            } catch (e: Exception) {
                addLog("SERVER", "Failed to start server: ${e.message}")
            }
        }
    }

    private fun stopServer() {
        server?.stop()
        server = null
    }

    private fun acquireWakeLock() {
        try {
            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "HermesDroid::NodeWakeLock").apply {
                setReferenceCounted(false)
            }
            wakeLock?.acquire(24 * 60 * 60 * 1000L) // 24 hours max
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.let {
            if (it.isHeld) it.release()
        }
        wakeLock = null
    }

    @Suppress("DEPRECATION")
    private fun acquireScreenWakeLock() {
        try {
            if (screenWakeLock == null) {
                val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
                screenWakeLock = powerManager.newWakeLock(
                    PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
                    "HermesDroid::ScreenStayAwakeLock"
                ).apply {
                    setReferenceCounted(false)
                }
            }
            if (screenWakeLock?.isHeld == false) {
                screenWakeLock?.acquire()
                addLog("HARDWARE", "Screen stay-awake acquired (Display forced ON)")
            }
        } catch (e: Exception) {
            addLog("ERROR", "Failed to acquire screen wake lock: ${e.message}")
        }
    }

    private fun releaseScreenWakeLock() {
        try {
            if (screenWakeLock?.isHeld == true) {
                screenWakeLock?.release()
                addLog("HARDWARE", "Screen stay-awake released")
            }
            screenWakeLock = null
        } catch (_: Exception) {}
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Hermes Droid Active Node",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Keeps Hermes Droid Node active for autonomous agent requests"
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(): android.app.Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Hermes Droid Node Active")
            .setContentText("Listening on port 8899 (Autonomous Physical Node)")
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setOngoing(true)
            .setContentIntent(pendingIntent)
            .build()
    }

    companion object {
        const val CHANNEL_ID = "hermes_droid_node_channel"
        const val NOTIFICATION_ID = 8899

        private val _screenStayAwake = MutableStateFlow(false)
        val screenStayAwake: StateFlow<Boolean> = _screenStayAwake.asStateFlow()

        fun setScreenStayAwake(context: Context, enabled: Boolean) {
            _screenStayAwake.value = enabled
            context.getSharedPreferences("hermes_prefs", Context.MODE_PRIVATE)
                .edit().putBoolean("screen_stay_awake", enabled).apply()

            MainActivity.applyKeepScreenOn(enabled)

            val intent = Intent(context, HermesNodeService::class.java).apply {
                action = if (enabled) "ACTION_KEEP_SCREEN_ON" else "ACTION_CLEAR_SCREEN_ON"
            }
            context.startService(intent)
            addLog("HARDWARE", "Stay-awake toggled: $enabled")
        }

        private val _isRunning = MutableStateFlow(false)
        val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

        private val _pairingToken = MutableStateFlow(generateRandomToken())
        val pairingToken: StateFlow<String> = _pairingToken.asStateFlow()

        private val _mqttBrokerHost = MutableStateFlow("127.0.0.1")
        val mqttBrokerHost: StateFlow<String> = _mqttBrokerHost.asStateFlow()

        private val _mqttBrokerPort = MutableStateFlow(1883)
        val mqttBrokerPort: StateFlow<Int> = _mqttBrokerPort.asStateFlow()

        private val _mqttStatus = MutableStateFlow(MqttStatus(brokerHost = "127.0.0.1", brokerPort = 1883))
        val mqttStatus: StateFlow<MqttStatus> = _mqttStatus.asStateFlow()

        fun setMqttStatus(status: MqttStatus) {
            _mqttStatus.value = status
        }

        fun updateMqttConfig(context: Context, host: String, port: Int) {
            val cleanHost = host.trim()
            _mqttBrokerHost.value = cleanHost
            _mqttBrokerPort.value = port
            val prefs = context.getSharedPreferences("hermes_prefs", Context.MODE_PRIVATE)
            prefs.edit().putString("mqtt_host", cleanHost).putInt("mqtt_port", port).apply()
            addLog("MQTT", "Broker settings saved -> $cleanHost:$port")
            
            // Reconnect service if active
            val intent = Intent(context, HermesNodeService::class.java).apply {
                action = "ACTION_RESTART_MQTT"
            }
            context.startService(intent)
        }

        private val _logs = MutableStateFlow<List<NodeLogEntry>>(emptyList())
        val logs: StateFlow<List<NodeLogEntry>> = _logs.asStateFlow()

        fun clearLogs() {
            _logs.value = emptyList()
        }

        private val _tasks = MutableStateFlow<List<TaskEntry>>(emptyList())
        val tasks: StateFlow<List<TaskEntry>> = _tasks.asStateFlow()

        private val _telegramToken = MutableStateFlow(
            try { com.hermesdroid.BuildConfig.DEFAULT_TELEGRAM_TOKEN } catch (_: Exception) { "" }
        )
        val telegramToken: StateFlow<String> = _telegramToken.asStateFlow()

        private val _telegramAuthorizedChatId = MutableStateFlow(-1L)
        val telegramAuthorizedChatId: StateFlow<Long> = _telegramAuthorizedChatId.asStateFlow()

        private val _telegramStatus = MutableStateFlow(TelegramStatus())
        val telegramStatus: StateFlow<TelegramStatus> = _telegramStatus.asStateFlow()

        fun setTelegramAuthorizedChatId(context: Context, chatId: Long, username: String = "") {
            _telegramAuthorizedChatId.value = chatId
            context.getSharedPreferences("hermes_prefs", Context.MODE_PRIVATE)
                .edit()
                .putLong("telegram_authorized_chat_id", chatId)
                .putString("telegram_authorized_user", username)
                .apply()
            addLog("TELEGRAM", "Authorized chat ID updated: $chatId (@$username)")
        }

        fun unpairTelegram(context: Context) {
            _telegramAuthorizedChatId.value = -1L
            context.getSharedPreferences("hermes_prefs", Context.MODE_PRIVATE)
                .edit()
                .remove("telegram_authorized_chat_id")
                .remove("telegram_authorized_user")
                .apply()
            resetPairing(context)
            addLog("TELEGRAM", "Telegram administrator unpaired and pairing reset")
        }

        fun setTelegramToken(context: Context?, token: String) {
            val clean = token.trim()
            _telegramToken.value = clean
            context?.getSharedPreferences("hermes_prefs", Context.MODE_PRIVATE)
                ?.edit()?.putString("telegram_token", clean)?.apply()
            addLog("TELEGRAM", "Telegram bot token updated")
        }

        fun setTelegramToken(token: String) {
            setTelegramToken(null, token)
        }

        fun setTelegramStatus(status: TelegramStatus) {
            _telegramStatus.value = status
        }

        fun recordTask(title: String, source: String, status: String, durationMs: Long, details: String = "") {
            val task = TaskEntry(
                title = title,
                source = source,
                status = status,
                durationMs = durationMs,
                details = details
            )
            val current = _tasks.value.toMutableList()
            current.add(0, task)
            if (current.size > 50) current.removeAt(current.size - 1)
            _tasks.value = current
            addLog("TASK", "[$status] $title ($durationMs ms) via $source")
        }

        fun generateRandomToken(): String {
            val chars = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
            val random = SecureRandom()
            val sb = StringBuilder(8)
            for (i in 0 until 8) {
                sb.append(chars[random.nextInt(chars.length)])
            }
            return sb.toString()
        }

        private val _pairingStatus = MutableStateFlow(PairingStatus())
        val pairingStatus: StateFlow<PairingStatus> = _pairingStatus.asStateFlow()

        const val OTP_TTL_MS = 10 * 60 * 1000L // 10 minutes
        const val MAX_FAILED_ATTEMPTS = 3
        const val LOCKOUT_DURATION_MS = 5 * 60 * 1000L // 5 minutes

        fun isPairingLockedOut(): Long {
            val status = _pairingStatus.value
            val now = System.currentTimeMillis()
            return if (status.lockoutUntil > now) status.lockoutUntil - now else 0L
        }

        fun isOtpExpired(): Boolean {
            val status = _pairingStatus.value
            if (status.state == "PAIRED") return false
            val now = System.currentTimeMillis()
            return (now - status.createdAt) > OTP_TTL_MS
        }

        fun renewExpiredOtp(context: Context): String {
            val newCode = generateRandomToken()
            val now = System.currentTimeMillis()
            _pairingToken.value = newCode
            val prefs = context.getSharedPreferences("hermes_prefs", Context.MODE_PRIVATE)
            prefs.edit()
                .putString("pairing_code", newCode)
                .putLong("pairing_created_at", now)
                .putInt("failed_attempts", 0)
                .apply()
            val deviceId = try { DeviceModule(context).getDeviceId() } catch (_: Exception) { "unknown_node" }
            _pairingStatus.value = _pairingStatus.value.copy(
                state = "PENDING",
                code = newCode,
                deviceId = deviceId,
                createdAt = now,
                expiresAt = now + OTP_TTL_MS,
                failedAttempts = 0
            )
            addLog("PAIRING", "Expired OTP renewed: $newCode (valid for 10m)")
            return newCode
        }

        fun recordFailedPairingAttempt(context: Context): Int {
            val prefs = context.getSharedPreferences("hermes_prefs", Context.MODE_PRIVATE)
            val now = System.currentTimeMillis()
            val currentAttempts = prefs.getInt("failed_attempts", 0) + 1
            if (currentAttempts >= MAX_FAILED_ATTEMPTS) {
                val lockoutUntil = now + LOCKOUT_DURATION_MS
                val newCode = generateRandomToken()
                _pairingToken.value = newCode
                prefs.edit()
                    .putLong("lockout_until", lockoutUntil)
                    .putInt("failed_attempts", 0)
                    .putString("pairing_code", newCode)
                    .putLong("pairing_created_at", now)
                    .apply()
                val deviceId = try { DeviceModule(context).getDeviceId() } catch (_: Exception) { "unknown_node" }
                _pairingStatus.value = _pairingStatus.value.copy(
                    code = newCode,
                    deviceId = deviceId,
                    createdAt = now,
                    expiresAt = now + OTP_TTL_MS,
                    failedAttempts = currentAttempts,
                    lockoutUntil = lockoutUntil
                )
                addLog("SECURITY", "Brute-force alert: 3 failed attempts. Pairing locked for 5m. OTP burned and regenerated.")
                return currentAttempts
            } else {
                prefs.edit().putInt("failed_attempts", currentAttempts).apply()
                _pairingStatus.value = _pairingStatus.value.copy(failedAttempts = currentAttempts)
                addLog("SECURITY", "Invalid pairing attempt ($currentAttempts/$MAX_FAILED_ATTEMPTS)")
                return currentAttempts
            }
        }

        fun loadPairingState(context: Context) {
            val prefs = context.getSharedPreferences("hermes_prefs", Context.MODE_PRIVATE)
            val isPaired = prefs.getBoolean("is_paired", false)
            val deviceId = try { DeviceModule(context).getDeviceId() } catch (_: Exception) { "unknown_node" }
            val now = System.currentTimeMillis()
            var code = prefs.getString("pairing_code", null)
            var createdAt = prefs.getLong("pairing_created_at", 0L)
            val lockoutUntil = prefs.getLong("lockout_until", 0L)
            val failedAttempts = prefs.getInt("failed_attempts", 0)

            if (!isPaired && (code.isNullOrBlank() || createdAt == 0L || (now - createdAt) > OTP_TTL_MS)) {
                code = generateRandomToken()
                createdAt = now
                prefs.edit()
                    .putString("pairing_code", code)
                    .putLong("pairing_created_at", createdAt)
                    .putInt("failed_attempts", 0)
                    .apply()
            }
            _pairingToken.value = code ?: ""
            val pairedAt = prefs.getLong("paired_at", 0L)
            val approver = prefs.getString("approver", null)
            _pairingStatus.value = PairingStatus(
                state = if (isPaired) "PAIRED" else "PENDING",
                code = code,
                deviceId = deviceId,
                pairedAt = pairedAt,
                approver = approver,
                createdAt = createdAt,
                expiresAt = if (isPaired) 0L else createdAt + OTP_TTL_MS,
                failedAttempts = failedAttempts,
                lockoutUntil = lockoutUntil
            )
        }

        fun setPairingApproved(context: Context, approver: String = "hermes-gateway") {
            val prefs = context.getSharedPreferences("hermes_prefs", Context.MODE_PRIVATE)
            val now = System.currentTimeMillis()
            prefs.edit()
                .putBoolean("is_paired", true)
                .putLong("paired_at", now)
                .putString("approver", approver)
                .putInt("failed_attempts", 0)
                .apply()
            _pairingStatus.value = _pairingStatus.value.copy(
                state = "PAIRED",
                pairedAt = now,
                approver = approver,
                failedAttempts = 0
            )
            addLog("PAIRING", "Device successfully paired with $approver!")
        }

        fun resetPairing(context: Context) {
            val newCode = generateRandomToken()
            val now = System.currentTimeMillis()
            _pairingToken.value = newCode
            val prefs = context.getSharedPreferences("hermes_prefs", Context.MODE_PRIVATE)
            prefs.edit()
                .putBoolean("is_paired", false)
                .putString("pairing_code", newCode)
                .putLong("pairing_created_at", now)
                .putInt("failed_attempts", 0)
                .remove("paired_at")
                .remove("approver")
                .apply()
            val deviceId = try { DeviceModule(context).getDeviceId() } catch (_: Exception) { "unknown_node" }
            _pairingStatus.value = PairingStatus(
                state = "PENDING",
                code = newCode,
                deviceId = deviceId,
                createdAt = now,
                expiresAt = now + OTP_TTL_MS,
                failedAttempts = 0,
                lockoutUntil = 0L
            )
            addLog("PAIRING", "Pairing reset. New code generated: $newCode")
        }

        fun regenerateToken() {
            _pairingToken.value = generateRandomToken()
            addLog("AUTH", "New pairing token generated")
        }

        fun addLog(tag: String, message: String) {
            android.util.Log.i("HermesDroid", "[$tag] $message")
            val entry = NodeLogEntry(tag = tag, message = message)
            val current = _logs.value.toMutableList()
            current.add(0, entry)
            if (current.size > 100) current.removeAt(current.size - 1)
            _logs.value = current
        }
    }
}
