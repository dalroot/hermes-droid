package org.nous.hermesdroid.modules

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.os.Bundle
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import org.json.JSONArray
import org.json.JSONObject
import org.nous.hermesdroid.service.HermesNodeService

class HermesAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        HermesNodeService.addLog("SERVICE", "HermesAccessibilityService connected & active")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            val pkg = event.packageName?.toString() ?: ""
            if (pkg.contains("packageinstaller") || pkg.contains("settings") || pkg.contains("security")) {
                clickByText("Update")
                clickByText("Install")
                clickByText("به‌روزرسانی")
                clickByText("نصب")
            }
        }
    }

    override fun onInterrupt() {
        if (instance == this) instance = null
    }

    override fun onDestroy() {
        super.onDestroy()
        if (instance == this) instance = null
    }

    companion object {
        private var instance: HermesAccessibilityService? = null

        fun isConnected(): Boolean = instance != null

        fun isServiceEnabled(context: android.content.Context): Boolean {
            if (instance != null) return true
            return try {
                val enabledServices = android.provider.Settings.Secure.getString(
                    context.contentResolver,
                    android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
                ) ?: ""
                val expected = "${context.packageName}/${HermesAccessibilityService::class.java.name}"
                enabledServices.contains(expected)
            } catch (_: Exception) {
                false
            }
        }

        fun clickCoordinate(x: Float, y: Float): Boolean {
            val service = instance ?: return false
            val path = Path().apply { moveTo(x, y) }
            val gesture = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, 50))
                .build()

            return service.dispatchGesture(gesture, null, null)
        }

        fun clickByText(targetText: String): Boolean {
            val service = instance ?: return false
            val root = service.rootInActiveWindow ?: return false
            val nodes = root.findAccessibilityNodeInfosByText(targetText)
            for (node in nodes) {
                if (node.isClickable && node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                    return true
                }
                var parent = node.parent
                while (parent != null) {
                    if (parent.isClickable && parent.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                        return true
                    }
                    parent = parent.parent
                }
            }
            return false
        }

        fun inputText(text: String): Boolean {
            val service = instance ?: return false
            val root = service.rootInActiveWindow ?: return false
            val focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: return false

            val arguments = Bundle()
            arguments.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
            return focused.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
        }

        fun dumpHierarchy(): JSONObject {
            val result = JSONObject()
            val service = instance
            if (service == null) {
                result.put("error", "Accessibility Service is not enabled.")
                return result
            }

            val root = service.rootInActiveWindow
            if (root == null) {
                result.put("error", "Active window is not accessible or screen is turned off.")
                return result
            }

            val elements = JSONArray()
            fun traverse(node: AccessibilityNodeInfo) {
                val text = node.text?.toString()
                val desc = node.contentDescription?.toString()
                val viewId = node.viewIdResourceName

                if (!text.isNullOrBlank() || !desc.isNullOrBlank() || node.isClickable) {
                    val rect = Rect()
                    node.getBoundsInScreen(rect)
                    val item = JSONObject().apply {
                        if (!text.isNullOrBlank()) put("text", text)
                        if (!desc.isNullOrBlank()) put("desc", desc)
                        if (!viewId.isNullOrBlank()) put("id", viewId)
                        put("clickable", node.isClickable)
                        put("bounds", "[${rect.left},${rect.top}][${rect.right},${rect.bottom}]")
                    }
                    elements.put(item)
                }

                for (i in 0 until node.childCount) {
                    node.getChild(i)?.let { traverse(it) }
                }
            }

            traverse(root)
            result.put("count", elements.length())
            result.put("elements", elements)
            return result
        }

        fun pressHome(): Boolean {
            return instance?.performGlobalAction(GLOBAL_ACTION_HOME) ?: false
        }

        fun pressBack(): Boolean {
            return instance?.performGlobalAction(GLOBAL_ACTION_BACK) ?: false
        }

        fun openNotifications(): Boolean {
            return instance?.performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS) ?: false
        }

        fun openQuickSettings(): Boolean {
            return instance?.performGlobalAction(GLOBAL_ACTION_QUICK_SETTINGS) ?: false
        }

        fun takeScreenshot(): Boolean {
            return if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                instance?.performGlobalAction(GLOBAL_ACTION_TAKE_SCREENSHOT) ?: false
            } else false
        }

        suspend fun captureScreenshotBitmap(): Result<android.graphics.Bitmap> =
            kotlinx.coroutines.suspendCancellableCoroutine { cont ->
                val service = instance
                if (service == null) {
                    cont.resume(Result.failure(IllegalStateException("Accessibility Service is not enabled."))) { }
                    return@suspendCancellableCoroutine
                }

                // If screen is off/locked, temporarily wake the display
                val pm = service.getSystemService(android.content.Context.POWER_SERVICE) as? android.os.PowerManager
                var wakeLock: android.os.PowerManager.WakeLock? = null
                if (pm != null && !pm.isInteractive) {
                    try {
                        @Suppress("DEPRECATION")
                        wakeLock = pm.newWakeLock(
                            android.os.PowerManager.SCREEN_BRIGHT_WAKE_LOCK or android.os.PowerManager.ACQUIRE_CAUSES_WAKEUP,
                            "hermes:screen_wake"
                        )
                        wakeLock.acquire(3000)
                    } catch (_: Exception) {}
                }

                cont.invokeOnCancellation {
                    try { if (wakeLock?.isHeld == true) wakeLock.release() } catch (_: Exception) {}
                }

                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                    service.takeScreenshot(
                        android.view.Display.DEFAULT_DISPLAY,
                        service.mainExecutor,
                        object : AccessibilityService.TakeScreenshotCallback {
                            override fun onSuccess(screenshotResult: AccessibilityService.ScreenshotResult) {
                                try {
                                    val hardwareBuffer = screenshotResult.hardwareBuffer
                                    val colorSpace = screenshotResult.colorSpace
                                    val hwBitmap = android.graphics.Bitmap.wrapHardwareBuffer(hardwareBuffer, colorSpace)
                                    hardwareBuffer.close()
                                    if (hwBitmap != null) {
                                        val softBitmap = hwBitmap.copy(android.graphics.Bitmap.Config.ARGB_8888, false)
                                        hwBitmap.recycle()
                                        cont.resume(Result.success(softBitmap)) { }
                                    } else {
                                        cont.resume(Result.failure(IllegalStateException("Bitmap wrapping failed."))) { }
                                    }
                                } catch (e: Exception) {
                                    cont.resume(Result.failure(e)) { }
                                } finally {
                                    try { if (wakeLock?.isHeld == true) wakeLock.release() } catch (_: Exception) {}
                                }
                            }

                            override fun onFailure(errorCode: Int) {
                                try { if (wakeLock?.isHeld == true) wakeLock.release() } catch (_: Exception) {}
                                cont.resume(Result.failure(IllegalStateException("Screenshot failed code $errorCode"))) { }
                            }
                        }
                    )
                } else {
                    try { if (wakeLock?.isHeld == true) wakeLock.release() } catch (_: Exception) {}
                    cont.resume(Result.failure(UnsupportedOperationException("Android 11+ (API 30+) required for in-memory screenshot."))) { }
                }
            }

        fun swipe(startX: Float, startY: Float, endX: Float, endY: Float, durationMs: Long = 300): Boolean {
            val service = instance ?: return false
            val path = Path().apply {
                moveTo(startX, startY)
                lineTo(endX, endY)
            }
            val gesture = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, durationMs))
                .build()

            return service.dispatchGesture(gesture, null, null)
        }
    }
}
