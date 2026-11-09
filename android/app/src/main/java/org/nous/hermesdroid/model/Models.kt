package org.nous.hermesdroid.model

data class ApiResponse<T>(
    val success: Boolean,
    val data: T? = null,
    val error: String? = null,
    val timestamp: Long = System.currentTimeMillis()
)

data class DeviceStatus(
    val model: String,
    val manufacturer: String,
    val androidVersion: String,
    val apiLevel: Int,
    val batteryLevel: Int,
    val isCharging: Boolean,
    val wifiSsid: String?,
    val ipAddress: String?,
    val totalRamMb: Long,
    val freeRamMb: Long,
    val uptimeSeconds: Long
)

data class SmsItem(
    val id: String,
    val sender: String,
    val body: String,
    val timestamp: Long,
    val isRead: Boolean
)

data class NotificationPayload(
    val key: String,
    val packageName: String,
    val appName: String,
    val title: String,
    val text: String,
    val postTime: Long,
    val canReply: Boolean
)

data class ShellExecutionResult(
    val stdout: String,
    val stderr: String,
    val exitCode: Int,
    val durationMs: Long
)

data class NodeLogEntry(
    val id: String = java.util.UUID.randomUUID().toString(),
    val tag: String,
    val message: String,
    val timestamp: Long = System.currentTimeMillis(),
    val level: LogLevel = LogLevel.INFO
)

enum class LogLevel {
    DEBUG, INFO, WARN, ERROR
}

data class TaskEntry(
    val id: String = java.util.UUID.randomUUID().toString(),
    val title: String,
    val source: String, // "HTTP", "TELEGRAM", "ADB"
    val status: String, // "SUCCESS", "FAILED"
    val durationMs: Long,
    val timestamp: Long = System.currentTimeMillis(),
    val details: String = ""
)

data class TelegramStatus(
    val state: String = "DISCONNECTED", // "DISCONNECTED", "CONNECTING", "CONNECTED", "ERROR"
    val lastSeenMs: Long = 0L,
    val error: String? = null,
    val botUsername: String? = null
)

data class MqttStatus(
    val state: String = "DISCONNECTED", // "DISCONNECTED", "CONNECTING", "CONNECTED", "ERROR"
    val brokerHost: String = "192.209.62.115",
    val brokerPort: Int = 1883,
    val lastSeenMs: Long = 0L,
    val error: String? = null,
    val latencyMs: Long = 0L
)

data class PairingStatus(
    val state: String = "UNPAIRED", // "UNPAIRED", "PENDING", "PAIRED"
    val code: String? = null,
    val deviceId: String = "",
    val pairedAt: Long = 0L,
    val approver: String? = null,
    val createdAt: Long = 0L,
    val expiresAt: Long = 0L,
    val failedAttempts: Int = 0,
    val lockoutUntil: Long = 0L
)

