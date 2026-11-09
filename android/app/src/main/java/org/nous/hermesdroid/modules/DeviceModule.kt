package org.nous.hermesdroid.modules

import android.app.ActivityManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Build
import android.os.SystemClock
import org.nous.hermesdroid.model.DeviceStatus
import java.net.Inet4Address
import java.net.NetworkInterface

class DeviceModule(private val context: Context) {

    fun getDeviceStatus(): DeviceStatus {
        val batteryStatus = getBatteryStatus()
        val memInfo = getMemoryInfo()
        val networkInfo = getNetworkInfo()

        return DeviceStatus(
            model = Build.MODEL,
            manufacturer = Build.MANUFACTURER,
            androidVersion = Build.VERSION.RELEASE,
            apiLevel = Build.VERSION.SDK_INT,
            batteryLevel = batteryStatus.first,
            isCharging = batteryStatus.second,
            wifiSsid = networkInfo.first,
            ipAddress = networkInfo.second,
            totalRamMb = memInfo.first,
            freeRamMb = memInfo.second,
            uptimeSeconds = SystemClock.elapsedRealtime() / 1000
        )
    }

    private fun getBatteryStatus(): Pair<Int, Boolean> {
        val ifilter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        val batteryStatus = context.registerReceiver(null, ifilter)
        val level = batteryStatus?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = batteryStatus?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        val batteryPct = if (level >= 0 && scale > 0) (level * 100 / scale) else 0

        val status = batteryStatus?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                status == BatteryManager.BATTERY_STATUS_FULL

        return Pair(batteryPct, isCharging)
    }

    private fun getMemoryInfo(): Pair<Long, Long> {
        val actManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memInfo = ActivityManager.MemoryInfo()
        actManager.getMemoryInfo(memInfo)
        val totalMb = memInfo.totalMem / (1024 * 1024)
        val freeMb = memInfo.availMem / (1024 * 1024)
        return Pair(totalMb, freeMb)
    }

    private fun getNetworkInfo(): Pair<String?, String?> {
        var ip: String? = null
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            for (intf in interfaces) {
                val addrs = intf.inetAddresses
                for (addr in addrs) {
                    if (!addr.isLoopbackAddress && addr is Inet4Address) {
                        ip = addr.hostAddress
                        break
                    }
                }
                if (ip != null) break
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        var ssid: String? = null
        try {
            val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            val info = wifiManager.connectionInfo
            if (info != null && info.ssid != null && info.ssid != "<unknown ssid>") {
                ssid = info.ssid.replace("\"", "")
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        return Pair(ssid, ip)
    }

    fun setFlashlight(enabled: Boolean): Boolean {
        return try {
            val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val cameraId = cameraManager.cameraIdList.firstOrNull { id ->
                val chars = cameraManager.getCameraCharacteristics(id)
                chars.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            } ?: return false

            cameraManager.setTorchMode(cameraId, enabled)
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    fun getClipboard(): String {
        return try {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = clipboard.primaryClip
            if (clip != null && clip.itemCount > 0) {
                clip.getItemAt(0).text?.toString() ?: ""
            } else ""
        } catch (e: Exception) {
            ""
        }
    }

    fun setClipboard(text: String): Boolean {
        return try {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = ClipData.newPlainText("Hermes", text)
            clipboard.setPrimaryClip(clip)
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    fun setRingerMode(mode: String): Boolean {
        return try {
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            when (mode.lowercase()) {
                "silent" -> audioManager.ringerMode = AudioManager.RINGER_MODE_SILENT
                "vibrate" -> audioManager.ringerMode = AudioManager.RINGER_MODE_VIBRATE
                "normal" -> audioManager.ringerMode = AudioManager.RINGER_MODE_NORMAL
                else -> return false
            }
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    fun getDeviceId(): String {
        return try {
            val androidId = android.provider.Settings.Secure.getString(
                context.contentResolver,
                android.provider.Settings.Secure.ANDROID_ID
            )
            androidId ?: "UNKNOWN_DEVICE"
        } catch (e: Exception) {
            "ERROR_ID"
        }
    }

    fun getDeviceProfile(): String {
        val devId = getDeviceId().take(6).uppercase()
        val model = Build.MODEL
        val manufacturer = Build.MANUFACTURER
        return "[$manufacturer $model | #$devId]"
    }

    fun getKernelInfo(): String {
        return try {
            val versionFile = java.io.File("/proc/version")
            if (versionFile.exists()) {
                versionFile.readText().trim()
            } else {
                System.getProperty("os.version") ?: "Unknown kernel"
            }
        } catch (e: Exception) {
            "Kernel info error: ${e.message}"
        }
    }

    fun getInstalledApps(limit: Int = 30): List<Pair<String, String>> {
        val pm = context.packageManager
        val apps = pm.getInstalledApplications(android.content.pm.PackageManager.GET_META_DATA)
        val filtered = apps.filter { (it.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) == 0 }
            .map { app ->
                val label = pm.getApplicationLabel(app).toString()
                label to app.packageName
            }
            .sortedBy { it.first.lowercase() }
        return filtered.take(limit)
    }

    fun getRecentLogcat(lines: Int = 25): String {
        return try {
            val process = Runtime.getRuntime().exec(arrayOf("logcat", "-d", "-t", lines.toString()))
            val reader = java.io.BufferedReader(java.io.InputStreamReader(process.inputStream))
            val sb = StringBuilder()
            var line: String? = reader.readLine()
            while (line != null) {
                sb.append(line).append("\n")
                line = reader.readLine()
            }
            process.waitFor()
            sb.toString().ifEmpty { "No recent logcat lines available." }
        } catch (e: Exception) {
            "Logcat error: ${e.message}"
        }
    }
}
