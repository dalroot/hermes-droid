package com.hermesdroid.ui

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.hermesdroid.model.LogLevel
import com.hermesdroid.model.NodeLogEntry
import com.hermesdroid.model.TaskEntry
import com.hermesdroid.model.TelegramStatus
import com.hermesdroid.modules.HermesAccessibilityService
import com.hermesdroid.modules.NotificationListener
import com.hermesdroid.service.HermesNodeService
import com.hermesdroid.ui.theme.HermesDroidTheme
import java.lang.ref.WeakReference
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : ComponentActivity() {

    companion object {
        private var currentActivity: WeakReference<MainActivity>? = null

        fun applyKeepScreenOn(enabled: Boolean) {
            currentActivity?.get()?.let { act ->
                act.runOnUiThread {
                    if (enabled) {
                        act.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    } else {
                        act.window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    }
                }
            }
        }
    }

    private var onPermissionsResult: (() -> Unit)? = null

    private val requestPermissionsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        onPermissionsResult?.invoke()
    }

    override fun onResume() {
        super.onResume()
        currentActivity = WeakReference(this)
        applyKeepScreenOn(HermesNodeService.screenStayAwake.value)
        onPermissionsResult?.invoke()
    }

    override fun onDestroy() {
        super.onDestroy()
        if (currentActivity?.get() == this) {
            currentActivity = null
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        currentActivity = WeakReference(this)
        applyKeepScreenOn(HermesNodeService.screenStayAwake.value)

        // Auto start service on launch
        val serviceIntent = Intent(this, HermesNodeService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }

        setContent {
            HermesDroidTheme(darkTheme = true) {
                var permUpdateKey by remember { mutableStateOf(0) }
                onPermissionsResult = { permUpdateKey++ }

                MainAppScreen(
                    permUpdateKey = permUpdateKey,
                    onRequestPermissions = { requestAllPermissions() },
                    onRequestSpecificPermissions = { permissions -> requestSpecificPermissions(permissions) },
                    onOpenNotificationAccess = { openNotificationAccessSettings() },
                    onOpenAccessibilityAccess = { openAccessibilitySettings() },
                    onOpenBatteryOptimization = { openBatteryOptimizationSettings() },
                    onOpenMiuiAutostart = { openMiuiAutostartSettings() },
                    onOpenInstallUnknownApps = { openInstallUnknownAppsSettings() }
                )
            }
        }
    }

    private fun requestSpecificPermissions(permissions: Array<String>) {
        requestPermissionsLauncher.launch(permissions)
    }

    private fun openMiuiAutostartSettings() {
        try {
            val intent = Intent()
            intent.component = android.content.ComponentName(
                "com.miui.securitycenter",
                "com.miui.permcenter.autostart.AutoStartManagementActivity"
            )
            startActivity(intent)
        } catch (_: Exception) {
            try {
                val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.parse("package:$packageName")
                }
                startActivity(intent)
            } catch (_: Exception) {}
        }
    }

    private fun requestAllPermissions() {
        val permissions = mutableListOf(
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.READ_SMS,
            Manifest.permission.SEND_SMS,
            Manifest.permission.RECEIVE_SMS,
            Manifest.permission.READ_CALL_LOG,
            Manifest.permission.CALL_PHONE,
            Manifest.permission.READ_CONTACTS,
            Manifest.permission.WRITE_CONTACTS,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
            permissions.add(Manifest.permission.READ_MEDIA_IMAGES)
            permissions.add(Manifest.permission.READ_MEDIA_VIDEO)
        } else {
            permissions.add(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
        requestPermissionsLauncher.launch(permissions.toTypedArray())
    }

    private fun openInstallUnknownAppsSettings() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                    data = Uri.parse("package:$packageName")
                }
                startActivity(intent)
            } catch (_: Exception) {
                startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES))
            }
        }
    }

    private fun openNotificationAccessSettings() {
        try {
            startActivity(Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS"))
        } catch (e: Exception) {
            Toast.makeText(this, "تنظیمات اعلان باز نشد: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun openAccessibilitySettings() {
        try {
            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                val serviceArg = "$packageName/${HermesAccessibilityService::class.java.name}"
                val extraFragmentArgKey = ":settings:fragment_args_key"
                val extraShowFragmentArguments = ":settings:show_fragment_args"
                val bundle = Bundle().apply {
                    putString(extraFragmentArgKey, serviceArg)
                }
                putExtra(extraFragmentArgKey, serviceArg)
                putExtra(extraShowFragmentArguments, bundle)
            }
            startActivity(intent)
            Toast.makeText(this, "روی Downloaded apps سپس Hermes Droid کلیک کنید", Toast.LENGTH_LONG).show()
        } catch (_: Exception) {
            try {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            } catch (e: Exception) {
                Toast.makeText(this, "تنظیمات دسترس‌پذیری باز نشد: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun openBatteryOptimizationSettings() {
        val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
            data = Uri.parse("package:$packageName")
        }
        try {
            startActivity(intent)
        } catch (e: Exception) {
            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainAppScreen(
    permUpdateKey: Int,
    onRequestPermissions: () -> Unit,
    onRequestSpecificPermissions: (Array<String>) -> Unit,
    onOpenNotificationAccess: () -> Unit,
    onOpenAccessibilityAccess: () -> Unit,
    onOpenBatteryOptimization: () -> Unit,
    onOpenMiuiAutostart: () -> Unit,
    onOpenInstallUnknownApps: () -> Unit
) {
    val context = LocalContext.current
    var selectedTab by remember { mutableStateOf(0) }
    val isRunning by HermesNodeService.isRunning.collectAsState()
    val token by HermesNodeService.pairingToken.collectAsState()
    val pairingStatus by HermesNodeService.pairingStatus.collectAsState()
    val logs by HermesNodeService.logs.collectAsState()
    val tasks by HermesNodeService.tasks.collectAsState()
    val telegramStatus by HermesNodeService.telegramStatus.collectAsState()
    val telegramToken by HermesNodeService.telegramToken.collectAsState()
    val mqttStatus by HermesNodeService.mqttStatus.collectAsState()
    val mqttBrokerHost by HermesNodeService.mqttBrokerHost.collectAsState()
    val mqttBrokerPort by HermesNodeService.mqttBrokerPort.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color(0xFF12141A)
                ),
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(10.dp)
                                .clip(CircleShape)
                                .background(if (isRunning) Color(0xFF00E676) else Color(0xFFFF5252))
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Hermes Droid", fontWeight = FontWeight.Bold, fontSize = 20.sp)
                        Spacer(modifier = Modifier.width(8.dp))
                        Surface(
                            shape = RoundedCornerShape(4.dp),
                            color = if (isRunning) Color(0x2200E676) else Color(0x22FF5252)
                        ) {
                            Text(
                                text = if (isRunning) "ACTIVE NODE" else "STOPPED",
                                color = if (isRunning) Color(0xFF00E676) else Color(0xFFFF5252),
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                    }
                },
                actions = {
                    val stayAwake by HermesNodeService.screenStayAwake.collectAsState()
                    IconButton(onClick = {
                        val next = !stayAwake
                        HermesNodeService.setScreenStayAwake(context, next)
                        Toast.makeText(
                            context,
                            if (next) "Screen Stay-Awake: ENABLED" else "Screen Stay-Awake: DISABLED",
                            Toast.LENGTH_SHORT
                        ).show()
                    }) {
                        Icon(
                            imageVector = if (stayAwake) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                            contentDescription = "Toggle Screen Awake",
                            tint = if (stayAwake) Color(0xFFFFD54F) else Color(0xFF6B7280)
                        )
                    }

                    IconButton(onClick = {
                        val intent = Intent(context, HermesNodeService::class.java)
                        if (isRunning) {
                            intent.action = "ACTION_STOP_SERVICE"
                            context.startService(intent)
                        } else {
                            intent.action = "ACTION_START_SERVICE"
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                                context.startForegroundService(intent)
                            } else {
                                context.startService(intent)
                            }
                        }
                    }) {
                        Icon(
                            imageVector = if (isRunning) Icons.Default.Stop else Icons.Default.PlayArrow,
                            contentDescription = "Toggle Service",
                            tint = if (isRunning) Color(0xFFFF5252) else Color(0xFF00E676)
                        )
                    }
                }
            )
        },
        bottomBar = {
            NavigationBar(
                containerColor = Color(0xFF12141A)
            ) {
                NavigationBarItem(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    icon = { Icon(Icons.Default.Dashboard, contentDescription = "Dashboard") },
                    label = { Text("Status") }
                )
                NavigationBarItem(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    icon = {
                        BadgedBox(badge = {
                            if (tasks.isNotEmpty()) {
                                Badge { Text(tasks.size.toString()) }
                            }
                        }) {
                            Icon(Icons.Default.Task, contentDescription = "Tasks")
                        }
                    },
                    label = { Text("Tasks") }
                )
                NavigationBarItem(
                    selected = selectedTab == 2,
                    onClick = { selectedTab = 2 },
                    icon = { Icon(Icons.Default.Security, contentDescription = "Permissions") },
                    label = { Text("Access") }
                )
                NavigationBarItem(
                    selected = selectedTab == 3,
                    onClick = { selectedTab = 3 },
                    icon = {
                        Box {
                            Icon(Icons.Default.Send, contentDescription = "Telegram")
                            if (telegramStatus.state == "CONNECTED") {
                                Box(
                                    modifier = Modifier
                                        .size(6.dp)
                                        .clip(CircleShape)
                                        .background(Color(0xFF00E676))
                                        .align(Alignment.TopEnd)
                                )
                            }
                        }
                    },
                    label = { Text("Relay") }
                )
                NavigationBarItem(
                    selected = selectedTab == 4,
                    onClick = { selectedTab = 4 },
                    icon = { Icon(Icons.Default.Terminal, contentDescription = "Logs") },
                    label = { Text("Logs") }
                )
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .background(Color(0xFF0A0C10))
        ) {
            // Live Status Sub-Banner
            StatusSubBanner(isRunning, mqttStatus, telegramStatus)

            Box(modifier = Modifier.fillMaxSize()) {
                when (selectedTab) {
                    0 -> DashboardTab(
                        isRunning = isRunning,
                        pairingStatus = pairingStatus,
                        mqttStatus = mqttStatus,
                        telegramStatus = telegramStatus,
                        mqttBrokerHost = mqttBrokerHost,
                        mqttBrokerPort = mqttBrokerPort
                    )
                    1 -> TasksTab(tasks)
                    2 -> PermissionsTab(
                        permUpdateKey,
                        onRequestPermissions,
                        onRequestSpecificPermissions,
                        onOpenNotificationAccess,
                        onOpenAccessibilityAccess,
                        onOpenBatteryOptimization,
                        onOpenMiuiAutostart,
                        onOpenInstallUnknownApps
                    )
                    3 -> RelayTab(mqttBrokerHost, mqttBrokerPort, mqttStatus, telegramToken, telegramStatus)
                    4 -> LogsTab(logs)
                }
            }
        }
    }
}

@Composable
fun StatusSubBanner(
    isRunning: Boolean,
    mqttStatus: com.hermesdroid.model.MqttStatus,
    telegramStatus: TelegramStatus
) {
    Surface(
        color = Color(0xFF161922),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .padding(horizontal = 16.dp, vertical = 8.dp)
                .fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // MQTT Broker Status
            Row(verticalAlignment = Alignment.CenterVertically) {
                val (mColor, mText) = when (mqttStatus.state) {
                    "CONNECTED" -> Color(0xFF00E676) to "MQTT Online"
                    "CONNECTING" -> Color(0xFFFFD54F) to "MQTT Connecting..."
                    "ERROR" -> Color(0xFFFF5252) to "MQTT Error"
                    else -> Color.Gray to "MQTT Off"
                }
                Box(modifier = Modifier.size(7.dp).clip(CircleShape).background(mColor))
                Spacer(modifier = Modifier.width(5.dp))
                Text(mText, fontSize = 12.sp, color = mColor, fontWeight = FontWeight.SemiBold)
            }

            // Telegram Relay Status
            Row(verticalAlignment = Alignment.CenterVertically) {
                val (tColor, tText) = when (telegramStatus.state) {
                    "CONNECTED" -> Color(0xFF10B981) to ("@" + (telegramStatus.botUsername ?: "bot"))
                    "CONNECTING" -> Color(0xFFF59E0B) to "Connecting..."
                    "ERROR" -> Color(0xFF9CA3AF) to "Telegram (No VPN)"
                    else -> Color.Gray to "Telegram Off"
                }
                Box(modifier = Modifier.size(7.dp).clip(CircleShape).background(tColor))
                Spacer(modifier = Modifier.width(5.dp))
                Text(tText, fontSize = 12.sp, color = tColor, fontWeight = FontWeight.Normal)
            }
        }
    }
}

@Composable
fun DashboardTab(
    isRunning: Boolean,
    pairingStatus: com.hermesdroid.model.PairingStatus,
    mqttStatus: com.hermesdroid.model.MqttStatus,
    telegramStatus: TelegramStatus,
    mqttBrokerHost: String,
    mqttBrokerPort: Int
) {
    val context = LocalContext.current
    val isPaired = pairingStatus.state == "PAIRED"
    val code = pairingStatus.code ?: "HERMES01"

    var pingResult by remember { mutableStateOf<String?>(null) }
    var isPinging by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // 1. Identity & Node Health
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0xFF111827)),
                shape = RoundedCornerShape(12.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text("Hermes Droid", fontWeight = FontWeight.Bold, fontSize = 18.sp, color = Color.White)
                            Text("Physical Companion Node for Hermes AI", fontSize = 12.sp, color = Color(0xFF9CA3AF))
                        }
                        Surface(
                            shape = RoundedCornerShape(4.dp),
                            color = if (isRunning) Color(0x2210B981) else Color(0x22EF4444)
                        ) {
                            Text(
                                text = if (isRunning) "NODE ACTIVE" else "STOPPED",
                                color = if (isRunning) Color(0xFF10B981) else Color(0xFFEF4444),
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))
                    HorizontalDivider(color = Color(0xFF1F2937))
                    Spacer(modifier = Modifier.height(12.dp))

                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        MetricBadge("Engine", "Kotlin Native")
                        MetricBadge("Notification", if (NotificationListener.isConnected()) "Active" else "Denied")
                        MetricBadge("Accessibility", if (HermesAccessibilityService.isConnected()) "Active" else "Off")
                    }
                }
            }
        }

        // 2. Communication Channels Status (MQTT Primary & Telegram Secondary)
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0xFF111827)),
                shape = RoundedCornerShape(12.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Connection Channels", fontWeight = FontWeight.Bold, fontSize = 14.sp, color = Color.White)
                    Spacer(modifier = Modifier.height(10.dp))

                    // MQTT Bridge (Primary)
                    Surface(
                        color = Color(0xFF161F30),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column {
                                Text("MQTT Bridge (Primary)", fontWeight = FontWeight.SemiBold, fontSize = 13.sp, color = Color.White)
                                Text("$mqttBrokerHost:$mqttBrokerPort", fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = Color(0xFF9CA3AF))
                            }
                            val (mColor, mText) = when (mqttStatus.state) {
                                "CONNECTED" -> Color(0xFF10B981) to "CONNECTED"
                                "CONNECTING" -> Color(0xFFF59E0B) to "CONNECTING"
                                "ERROR" -> Color(0xFFEF4444) to "ERROR"
                                else -> Color.Gray to "DISCONNECTED"
                            }
                            Surface(
                                shape = RoundedCornerShape(4.dp),
                                color = mColor.copy(alpha = 0.15f)
                            ) {
                                Text(
                                    text = mText,
                                    color = mColor,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    // Telegram Gateway (Secondary Relay)
                    Surface(
                        color = Color(0xFF161F30),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column {
                                Text("Telegram Relay (Secondary)", fontWeight = FontWeight.SemiBold, fontSize = 13.sp, color = Color.White)
                                val subText = if (telegramStatus.state == "CONNECTED") {
                                    "@" + (telegramStatus.botUsername ?: "bot")
                                } else {
                                    "Optional backup channel"
                                }
                                Text(subText, fontSize = 11.sp, color = Color(0xFF9CA3AF))
                            }
                            val (tColor, tText) = when (telegramStatus.state) {
                                "CONNECTED" -> Color(0xFF10B981) to "CONNECTED"
                                "CONNECTING" -> Color(0xFFF59E0B) to "CONNECTING"
                                "ERROR" -> Color(0xFF9CA3AF) to "NO VPN"
                                else -> Color.Gray to "INACTIVE"
                            }
                            Surface(
                                shape = RoundedCornerShape(4.dp),
                                color = tColor.copy(alpha = 0.15f)
                            ) {
                                Text(
                                    text = tText,
                                    color = tColor,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                                )
                            }
                        }
                    }
                }
            }
        }

        // 3. Hermes Pairing Status
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = if (isPaired) Color(0xFF0F2318) else Color(0xFF231C10)),
                shape = RoundedCornerShape(12.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(10.dp)
                                    .clip(CircleShape)
                                    .background(if (isPaired) Color(0xFF10B981) else Color(0xFFF59E0B))
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                if (isPaired) "Paired with Hermes" else "Hermes Pairing Required",
                                fontWeight = FontWeight.Bold,
                                fontSize = 15.sp,
                                color = Color.White
                            )
                        }

                        if (isPaired) {
                            IconButton(onClick = {
                                HermesNodeService.resetPairing(context)
                                Toast.makeText(context, "Pairing reset", Toast.LENGTH_SHORT).show()
                            }) {
                                Icon(Icons.Default.Refresh, contentDescription = "Reset", tint = Color.Gray)
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(6.dp))

                    if (isPaired) {
                        Text(
                            text = "This device is verified and authorized by ${pairingStatus.approver ?: "Hermes Gateway"}.",
                            fontSize = 12.sp,
                            color = Color(0xFFA7F3D0)
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Device ID: ${pairingStatus.deviceId}",
                                fontFamily = FontFamily.Monospace,
                                fontSize = 11.sp,
                                color = Color(0xFF9CA3AF)
                            )
                            IconButton(
                                onClick = {
                                    val clip = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                    clip.setPrimaryClip(ClipData.newPlainText("device-id", pairingStatus.deviceId))
                                    Toast.makeText(context, "Device ID copied!", Toast.LENGTH_SHORT).show()
                                },
                                modifier = Modifier.size(24.dp)
                            ) {
                                Icon(Icons.Default.ContentCopy, contentDescription = "Copy ID", tint = Color(0xFF38BDF8), modifier = Modifier.size(16.dp))
                            }
                        }
                    } else {
                        Text(
                            text = "Run this command on your Hermes host to pair:",
                            fontSize = 12.sp,
                            color = Color(0xFFFDE68A)
                        )

                        Spacer(modifier = Modifier.height(8.dp))

                        Surface(
                            color = Color(0xFF0B0F17),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = code,
                                        fontFamily = FontFamily.Monospace,
                                        fontWeight = FontWeight.Bold,
                                        letterSpacing = 4.sp,
                                        fontSize = 22.sp,
                                        color = Color(0xFF10B981)
                                    )
                                    IconButton(onClick = {
                                        val clip = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                        clip.setPrimaryClip(ClipData.newPlainText("hermes-pair", "hermes pairing approve android $code"))
                                        Toast.makeText(context, "Copied command to clipboard!", Toast.LENGTH_SHORT).show()
                                    }) {
                                        Icon(Icons.Default.ContentCopy, contentDescription = "Copy", tint = Color(0xFF38BDF8))
                                    }
                                }
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = "hermes pairing approve android $code",
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 11.sp,
                                    color = Color(0xFF38BDF8)
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(6.dp))
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                            OutlinedButton(
                                onClick = {
                                    HermesNodeService.resetPairing(context)
                                    Toast.makeText(context, "New pairing code generated", Toast.LENGTH_SHORT).show()
                                },
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
                            ) {
                                Text("New Code", fontSize = 11.sp)
                            }
                        }
                    }
                }
            }
        }

        // Screen Stay-Awake Quick Control
        item {
            val stayAwake by HermesNodeService.screenStayAwake.collectAsState()
            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0xFF161F30)),
                shape = RoundedCornerShape(12.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(14.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.weight(1f)
                    ) {
                        Surface(
                            shape = CircleShape,
                            color = if (stayAwake) Color(0x33FFD54F) else Color(0x229E9E9E),
                            modifier = Modifier.size(38.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = if (stayAwake) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                                    contentDescription = null,
                                    tint = if (stayAwake) Color(0xFFFFD54F) else Color(0xFF9E9E9E),
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                            Text(
                                "Keep Screen Awake",
                                fontWeight = FontWeight.Bold,
                                fontSize = 14.sp,
                                color = Color.White
                            )
                            Text(
                                if (stayAwake) "Screen stays ON during dev & tests" else "System sleep/lock timeout applies",
                                fontSize = 11.sp,
                                color = if (stayAwake) Color(0xFFFFD54F) else Color(0xFF9CA3AF)
                            )
                        }
                    }
                    Switch(
                        checked = stayAwake,
                        onCheckedChange = { next ->
                            HermesNodeService.setScreenStayAwake(context, next)
                        },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Color(0xFFFFD54F),
                            checkedTrackColor = Color(0x66FFD54F),
                            uncheckedThumbColor = Color(0xFF9CA3AF),
                            uncheckedTrackColor = Color(0xFF1F2937)
                        )
                    )
                }
            }
        }

        // 4. Real Live Ping & Latency Test (Server / Laptop Connectivity)
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0xFF111827)),
                shape = RoundedCornerShape(12.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Server Connectivity Test", fontWeight = FontWeight.Bold, fontSize = 14.sp, color = Color.White)
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        "Performs a live TCP roundtrip ping to the MQTT broker to measure actual connection latency.",
                        fontSize = 12.sp,
                        color = Color(0xFF9CA3AF)
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    Button(
                        onClick = {
                            if (isPinging) return@Button
                            scope.launch(Dispatchers.IO) {
                                isPinging = true
                                val start = System.currentTimeMillis()
                                try {
                                    val socket = java.net.Socket()
                                    socket.connect(java.net.InetSocketAddress(mqttBrokerHost, mqttBrokerPort), 4000)
                                    val elapsed = System.currentTimeMillis() - start
                                    socket.close()
                                    withContext(Dispatchers.Main) {
                                        pingResult = "✓ Connected to $mqttBrokerHost:$mqttBrokerPort in ${elapsed}ms"
                                        isPinging = false
                                    }
                                } catch (e: Exception) {
                                    withContext(Dispatchers.Main) {
                                        pingResult = "✗ Ping failed: ${e.message}"
                                        isPinging = false
                                    }
                                }
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0284C7)),
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !isPinging
                    ) {
                        if (isPinging) {
                            CircularProgressIndicator(
                                color = Color.White,
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Testing Ping...", fontWeight = FontWeight.Bold)
                        } else {
                            Icon(Icons.Default.Speed, contentDescription = null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Ping MQTT Broker", fontWeight = FontWeight.Bold)
                        }
                    }

                    if (pingResult != null) {
                        Spacer(modifier = Modifier.height(10.dp))
                        val isSuccess = pingResult!!.startsWith("✓")
                        Surface(
                            color = if (isSuccess) Color(0x2210B981) else Color(0x22EF4444),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = pingResult!!,
                                color = if (isSuccess) Color(0xFF10B981) else Color(0xFFEF4444),
                                fontFamily = FontFamily.Monospace,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.padding(10.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun MetricBadge(label: String, value: String) {
    Column {
        Text(label, fontSize = 11.sp, color = Color.Gray)
        Text(value, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
    }
}

@Composable
fun ActionChip(label: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Surface(
        color = Color(0xFF1E2330),
        shape = RoundedCornerShape(8.dp),
        modifier = modifier.clickable { onClick() }
    ) {
        Box(modifier = Modifier.padding(12.dp), contentAlignment = Alignment.Center) {
            Text(label, fontSize = 13.sp, fontWeight = FontWeight.Medium, color = Color(0xFFECEFF1))
        }
    }
}

@Composable
fun TasksTab(tasks: List<TaskEntry>) {
    val timeFormat = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }

    if (tasks.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Default.TaskAlt, contentDescription = null, tint = Color.Gray, modifier = Modifier.size(48.dp))
                Spacer(modifier = Modifier.height(8.dp))
                Text("No tasks executed yet", color = Color.Gray, fontSize = 14.sp)
                Text("Tasks triggered via HTTP or Telegram will appear here", color = Color(0xFF546E7A), fontSize = 12.sp)
            }
        }
    } else {
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(tasks) { task ->
                Card(
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF161922)),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                val isSuccess = task.status == "SUCCESS"
                                Box(
                                    modifier = Modifier
                                        .size(8.dp)
                                        .clip(CircleShape)
                                        .background(if (isSuccess) Color(0xFF00E676) else Color(0xFFFF5252))
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(task.title, fontWeight = FontWeight.Bold, fontSize = 14.sp, color = Color.White)
                            }
                            Text(
                                text = "${task.durationMs}ms",
                                fontFamily = FontFamily.Monospace,
                                fontSize = 12.sp,
                                color = Color(0xFF81D4FA)
                            )
                        }

                        Spacer(modifier = Modifier.height(6.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("Source: ${task.source}", fontSize = 12.sp, color = Color(0xFF90A4AE))
                            Text(timeFormat.format(Date(task.timestamp)), fontSize = 11.sp, color = Color.Gray)
                        }

                        if (task.details.isNotEmpty()) {
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = task.details,
                                fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace,
                                color = Color(0xFF607D8B),
                                maxLines = 2
                            )
                        }
                    }
                }
            }
        }
    }
}

data class PermissionItem(
    val id: String,
    val title: String,
    val subtitle: String,
    val icon: androidx.compose.ui.graphics.vector.ImageVector,
    val isGranted: Boolean,
    val onClick: () -> Unit
)

@Composable
fun PermissionsTab(
    permUpdateKey: Int,
    onRequestPermissions: () -> Unit,
    onRequestSpecificPermissions: (Array<String>) -> Unit,
    onOpenNotificationAccess: () -> Unit,
    onOpenAccessibilityAccess: () -> Unit,
    onOpenBatteryOptimization: () -> Unit,
    onOpenMiuiAutostart: () -> Unit,
    onOpenInstallUnknownApps: () -> Unit
) {
    val context = LocalContext.current

    val isAccessibilityGranted = remember(permUpdateKey) {
        HermesAccessibilityService.isConnected() || HermesAccessibilityService.isServiceEnabled(context)
    }
    val isNotificationGranted = remember(permUpdateKey) {
        NotificationListener.isConnected() || NotificationListener.isNotificationAccessGranted(context)
    }
    val isBatteryExempt = remember(permUpdateKey) {
        val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            pm?.isIgnoringBatteryOptimizations(context.packageName) == true
        } else true
    }
    val isApkInstallAllowed = remember(permUpdateKey) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.packageManager.canRequestPackageInstalls()
        } else true
    }
    val isCameraGranted = remember(permUpdateKey) {
        ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
    }
    val isMicGranted = remember(permUpdateKey) {
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
    }
    val isLocationGranted = remember(permUpdateKey) {
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
    }
    val isSmsGranted = remember(permUpdateKey) {
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_SMS) == PackageManager.PERMISSION_GRANTED
    }
    val isContactsGranted = remember(permUpdateKey) {
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED
    }
    val isCallLogGranted = remember(permUpdateKey) {
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALL_LOG) == PackageManager.PERMISSION_GRANTED
    }
    val isStorageGranted = remember(permUpdateKey) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.READ_MEDIA_IMAGES) == PackageManager.PERMISSION_GRANTED
        } else {
            ContextCompat.checkSelfPermission(context, Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
        }
    }

    val permissionItems = remember(permUpdateKey, isAccessibilityGranted, isNotificationGranted, isBatteryExempt, isApkInstallAllowed, isCameraGranted, isMicGranted, isLocationGranted, isSmsGranted, isContactsGranted, isCallLogGranted, isStorageGranted) {
        listOf(
            PermissionItem(
                id = "accessibility",
                title = "Screen & UI Actuator (Accessibility)",
                subtitle = "Autonomous clicks, gestures & auto-install",
                icon = Icons.Default.TouchApp,
                isGranted = isAccessibilityGranted,
                onClick = onOpenAccessibilityAccess
            ),
            PermissionItem(
                id = "battery",
                title = "24/7 Battery Survival (Ignore Optimization)",
                subtitle = "Prevents MIUI / Android from sleeping the background node",
                icon = Icons.Default.BatteryChargingFull,
                isGranted = isBatteryExempt,
                onClick = onOpenBatteryOptimization
            ),
            PermissionItem(
                id = "notification",
                title = "Notification Mirror & Auto-Reply",
                subtitle = "Intercept inbound notifications & verification OTPs",
                icon = Icons.Default.Notifications,
                isGranted = isNotificationGranted,
                onClick = onOpenNotificationAccess
            ),
            PermissionItem(
                id = "apk_install",
                title = "Wireless Remote APK Install",
                subtitle = "Allows autonomous self-updating over MQTT broker",
                icon = Icons.Default.SystemUpdate,
                isGranted = isApkInstallAllowed,
                onClick = onOpenInstallUnknownApps
            ),
            PermissionItem(
                id = "camera",
                title = "Camera & Environment Vision",
                subtitle = "Capture real-time photos and surroundings",
                icon = Icons.Default.CameraAlt,
                isGranted = isCameraGranted,
                onClick = {
                    if (isCameraGranted) {
                        Toast.makeText(context, "دسترسی دوربین فعال است", Toast.LENGTH_SHORT).show()
                    } else {
                        onRequestSpecificPermissions(arrayOf(Manifest.permission.CAMERA))
                    }
                }
            ),
            PermissionItem(
                id = "mic",
                title = "Microphone & Voice Recording",
                subtitle = "Record audio clips & acoustic monitoring",
                icon = Icons.Default.Mic,
                isGranted = isMicGranted,
                onClick = {
                    if (isMicGranted) {
                        Toast.makeText(context, "دسترسی میکروفون فعال است", Toast.LENGTH_SHORT).show()
                    } else {
                        onRequestSpecificPermissions(arrayOf(Manifest.permission.RECORD_AUDIO))
                    }
                }
            ),
            PermissionItem(
                id = "location",
                title = "GPS Physical Geolocation",
                subtitle = "Precise coordinates & telemetry mapping",
                icon = Icons.Default.LocationOn,
                isGranted = isLocationGranted,
                onClick = {
                    if (isLocationGranted) {
                        Toast.makeText(context, "دسترسی موقعیت مکانی فعال است", Toast.LENGTH_SHORT).show()
                    } else {
                        onRequestSpecificPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
                    }
                }
            ),
            PermissionItem(
                id = "sms",
                title = "SMS Messages (Read & Send)",
                subtitle = "Read SMS bank OTPs and send autonomous alerts",
                icon = Icons.Default.Sms,
                isGranted = isSmsGranted,
                onClick = {
                    if (isSmsGranted) {
                        Toast.makeText(context, "دسترسی پیامک فعال است", Toast.LENGTH_SHORT).show()
                    } else {
                        onRequestSpecificPermissions(arrayOf(Manifest.permission.READ_SMS, Manifest.permission.SEND_SMS, Manifest.permission.RECEIVE_SMS))
                    }
                }
            ),
            PermissionItem(
                id = "contacts",
                title = "Contacts & Address Book",
                subtitle = "Query device contacts & directory",
                icon = Icons.Default.Contacts,
                isGranted = isContactsGranted,
                onClick = {
                    if (isContactsGranted) {
                        Toast.makeText(context, "دسترسی مخاطبین فعال است", Toast.LENGTH_SHORT).show()
                    } else {
                        onRequestSpecificPermissions(arrayOf(Manifest.permission.READ_CONTACTS, Manifest.permission.WRITE_CONTACTS))
                    }
                }
            ),
            PermissionItem(
                id = "calls",
                title = "Call Logs & Phone Dialer",
                subtitle = "Read telephony call records and dialer",
                icon = Icons.Default.Phone,
                isGranted = isCallLogGranted,
                onClick = {
                    if (isCallLogGranted) {
                        Toast.makeText(context, "دسترسی تماس فعال است", Toast.LENGTH_SHORT).show()
                    } else {
                        onRequestSpecificPermissions(arrayOf(Manifest.permission.READ_CALL_LOG, Manifest.permission.CALL_PHONE))
                    }
                }
            ),
            PermissionItem(
                id = "storage",
                title = "Media Gallery & File Storage",
                subtitle = "Read saved pictures, downloads & files",
                icon = Icons.Default.PhotoLibrary,
                isGranted = isStorageGranted,
                onClick = {
                    if (isStorageGranted) {
                        Toast.makeText(context, "دسترسی فایل و گالری فعال است", Toast.LENGTH_SHORT).show()
                    } else {
                        val perms = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO)
                        } else {
                            arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
                        }
                        onRequestSpecificPermissions(perms)
                    }
                }
            ),
            PermissionItem(
                id = "miui_autostart",
                title = "Xiaomi Autostart & App Lock",
                subtitle = "Enable auto-boot & lock Hermes Droid in recents",
                icon = Icons.Default.PowerSettingsNew,
                isGranted = true,
                onClick = onOpenMiuiAutostart
            )
        )
    }

    val allGranted = remember(permissionItems) {
        permissionItems.filter { it.id != "miui_autostart" }.all { it.isGranted }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // Status Master Card
        item {
            if (allGranted) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF0A2618)),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Surface(
                            color = Color(0x2210B981),
                            shape = CircleShape,
                            modifier = Modifier.size(42.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    Icons.Default.CheckCircle,
                                    contentDescription = null,
                                    tint = Color(0xFF10B981),
                                    modifier = Modifier.size(24.dp)
                                )
                            }
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                            Text(
                                "All Capabilities Authorized",
                                fontWeight = FontWeight.Bold,
                                fontSize = 15.sp,
                                color = Color(0xFF34D399)
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                "Sensory, Accessibility & System engines are ready for full autonomy.",
                                fontSize = 11.sp,
                                color = Color(0xFFA7F3D0)
                            )
                        }
                    }
                }
            } else {
                Card(
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF0F1E2E)),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Security, contentDescription = null, tint = Color(0xFF38BDF8), modifier = Modifier.size(22.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Permissions & Hardware Access", fontWeight = FontWeight.Bold, fontSize = 15.sp, color = Color.White)
                        }
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            "Hermes Droid requires access to sensors and system services. Click any item to configure directly.",
                            fontSize = 12.sp,
                            color = Color(0xFF94A3B8)
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Button(
                            onClick = onRequestPermissions,
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0284C7)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Default.DoneAll, contentDescription = null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Grant All Standard Dialogs", fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }

        item {
            Text(
                text = "Capabilities & Hardware Sensors",
                fontWeight = FontWeight.SemiBold,
                fontSize = 13.sp,
                color = Color(0xFF9CA3AF)
            )
        }

        items(permissionItems) { item ->
            Surface(
                color = Color(0xFF161922),
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { item.onClick() }
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Surface(
                        color = if (item.isGranted) Color(0x2210B981) else Color(0x22F59E0B),
                        shape = CircleShape,
                        modifier = Modifier.size(36.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = item.icon,
                                contentDescription = null,
                                tint = if (item.isGranted) Color(0xFF10B981) else Color(0xFFF59E0B),
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(item.title, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, color = Color.White)
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(item.subtitle, fontSize = 11.sp, color = Color(0xFF9CA3AF))
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Surface(
                        shape = RoundedCornerShape(4.dp),
                        color = if (item.isGranted) Color(0x2210B981) else Color(0x22F59E0B)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = if (item.isGranted) "GRANTED" else "SETUP",
                                color = if (item.isGranted) Color(0xFF10B981) else Color(0xFFF59E0B),
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold
                            )
                            if (!item.isGranted) {
                                Spacer(modifier = Modifier.width(2.dp))
                                Icon(
                                    imageVector = Icons.Default.ChevronRight,
                                    contentDescription = null,
                                    tint = Color(0xFFF59E0B),
                                    modifier = Modifier.size(12.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun RelayTab(
    mqttHost: String,
    mqttPort: Int,
    mqttStatus: com.hermesdroid.model.MqttStatus,
    telegramToken: String,
    telegramStatus: TelegramStatus
) {
    var hostInput by remember(mqttHost) { mutableStateOf(mqttHost) }
    var portInput by remember(mqttPort) { mutableStateOf(mqttPort.toString()) }
    var botTokenInput by remember(telegramToken) { mutableStateOf(telegramToken) }
    val context = LocalContext.current

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // Dedicated Private MQTT Broker Card
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0xFF161922)),
                shape = RoundedCornerShape(12.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Dedicated Private MQTT", fontWeight = FontWeight.Bold, fontSize = 16.sp, color = Color.White)
                        val statusColor = when (mqttStatus.state) {
                            "CONNECTED" -> Color(0xFF00E676)
                            "CONNECTING" -> Color(0xFFFFD54F)
                            "ERROR" -> Color(0xFFFF5252)
                            else -> Color.Gray
                        }
                        Surface(
                            shape = RoundedCornerShape(4.dp),
                            color = statusColor.copy(alpha = 0.15f)
                        ) {
                            Text(
                                text = mqttStatus.state,
                                color = statusColor,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        "Self-hosted Mosquitto broker on your private server. No payload limits.",
                        fontSize = 12.sp,
                        color = Color(0xFF90A4AE)
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = hostInput,
                            onValueChange = { hostInput = it },
                            label = { Text("Broker Host / IP") },
                            modifier = Modifier.weight(2.5f),
                            singleLine = true,
                            textStyle = androidx.compose.ui.text.TextStyle(fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                        )

                        OutlinedTextField(
                            value = portInput,
                            onValueChange = { portInput = it },
                            label = { Text("Port") },
                            modifier = Modifier.weight(1f),
                            singleLine = true,
                            textStyle = androidx.compose.ui.text.TextStyle(fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                        )
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    Button(
                        onClick = {
                            val p = portInput.toIntOrNull() ?: 1883
                            HermesNodeService.updateMqttConfig(context, hostInput, p)
                            Toast.makeText(context, "Settings saved! Reconnecting...", Toast.LENGTH_SHORT).show()
                        },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0288D1))
                    ) {
                        Icon(Icons.Default.CloudSync, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Save & Reconnect MQTT")
                    }

                    if (mqttStatus.error != null) {
                        Spacer(modifier = Modifier.height(6.dp))
                        Text("Error: ${mqttStatus.error}", color = Color(0xFFFF5252), fontSize = 11.sp)
                    }
                }
            }
        }

        // Telegram Serverless Relay Card
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0xFF161922)),
                shape = RoundedCornerShape(12.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Telegram Serverless Relay", fontWeight = FontWeight.Bold, fontSize = 16.sp, color = Color.White)
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        "Allows remote command execution and APK updates from Telegram.",
                        fontSize = 12.sp,
                        color = Color(0xFF90A4AE)
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    OutlinedTextField(
                        value = botTokenInput,
                        onValueChange = { botTokenInput = it },
                        label = { Text("Bot API Token") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        textStyle = androidx.compose.ui.text.TextStyle(fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    Button(
                        onClick = {
                            HermesNodeService.setTelegramToken(context, botTokenInput)
                            Toast.makeText(context, "Telegram Token saved!", Toast.LENGTH_SHORT).show()
                        },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00897B))
                    ) {
                        Text("Save & Reconnect Telegram")
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    Surface(
                        color = Color(0xFF0F1117),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(10.dp)) {
                            Text("Relay Status: ${telegramStatus.state}", fontWeight = FontWeight.Bold, fontSize = 12.sp, color = Color.White)
                            if (telegramStatus.botUsername != null) {
                                Text("Bot: @${telegramStatus.botUsername}", fontSize = 12.sp, color = Color(0xFF00E676))
                            }
                            if (telegramStatus.error != null) {
                                Text("Error: ${telegramStatus.error}", fontSize = 11.sp, color = Color(0xFFFF5252))
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun LogsTab(logs: List<NodeLogEntry>) {
    var filterTag by remember { mutableStateOf("ALL") }
    val timeFormat = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }
    val context = LocalContext.current

    val filtered = if (filterTag == "ALL") logs else logs.filter { it.tag.equals(filterTag, true) }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                modifier = Modifier.weight(1f),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                listOf("ALL", "MQTT", "TELEGRAM", "SERVER", "SERVICE").forEach { tag ->
                    FilterChip(
                        selected = filterTag == tag,
                        onClick = { filterTag = tag },
                        label = { Text(tag, fontSize = 10.sp) }
                    )
                }
            }

            IconButton(onClick = {
                val allText = logs.joinToString("\n") { "[${timeFormat.format(Date(it.timestamp))}] [${it.tag}] ${it.message}" }
                val clip = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clip.setPrimaryClip(ClipData.newPlainText("logs", allText))
                Toast.makeText(context, "Logs copied!", Toast.LENGTH_SHORT).show()
            }) {
                Icon(Icons.Default.ContentCopy, contentDescription = "Copy", tint = Color.Gray, modifier = Modifier.size(18.dp))
            }

            IconButton(onClick = {
                HermesNodeService.clearLogs()
            }) {
                Icon(Icons.Default.DeleteOutline, contentDescription = "Clear", tint = Color(0xFFFF5252), modifier = Modifier.size(18.dp))
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            items(filtered) { entry ->
                Surface(
                    color = Color(0xFF161922),
                    shape = RoundedCornerShape(6.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = timeFormat.format(Date(entry.timestamp)),
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace,
                            color = Color.Gray
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Surface(
                            shape = RoundedCornerShape(3.dp),
                            color = when (entry.tag) {
                                "MQTT" -> Color(0xFF00695C)
                                "TELEGRAM" -> Color(0xFF004D40)
                                "SERVER" -> Color(0xFF0D47A1)
                                "TASK" -> Color(0xFF1B5E20)
                                else -> Color(0xFF263238)
                            }
                        ) {
                            Text(
                                text = entry.tag,
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.White,
                                modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = entry.message,
                            fontSize = 11.sp,
                            color = Color(0xFFECEFF1),
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }
        }
    }
}
