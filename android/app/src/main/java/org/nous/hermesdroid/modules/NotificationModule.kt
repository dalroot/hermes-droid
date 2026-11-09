package org.nous.hermesdroid.modules

import android.app.Notification
import android.app.RemoteInput
import android.content.Intent
import android.os.Bundle
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import org.nous.hermesdroid.model.NotificationPayload
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedDeque

class NotificationListener : NotificationListenerService() {

    override fun onListenerConnected() {
        super.onListenerConnected()
        instance = this
        activeNotificationsCache.clear()
        try {
            activeNotifications.forEach { processStatusBarNotification(it) }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        if (instance == this) instance = null
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        super.onNotificationPosted(sbn)
        sbn?.let { processStatusBarNotification(it) }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        super.onNotificationRemoved(sbn)
        sbn?.let {
            activeNotificationsCache.remove(it.key)
        }
    }

    private fun processStatusBarNotification(sbn: StatusBarNotification) {
        val extras = sbn.notification.extras ?: return
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: ""
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()
            ?: extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString() ?: ""

        val pm = packageManager
        val appName = try {
            val appInfo = pm.getApplicationInfo(sbn.packageName, 0)
            pm.getApplicationLabel(appInfo).toString()
        } catch (e: Exception) {
            sbn.packageName
        }

        val canReply = findReplyAction(sbn.notification) != null

        val payload = NotificationPayload(
            key = sbn.key,
            packageName = sbn.packageName,
            appName = appName,
            title = title,
            text = text,
            postTime = sbn.postTime,
            canReply = canReply
        )

        activeNotificationsCache[sbn.key] = payload
        recentNotificationHistory.addFirst(payload)
        while (recentNotificationHistory.size > 200) {
            recentNotificationHistory.removeLast()
        }
    }

    companion object {
        private var instance: NotificationListener? = null
        private val activeNotificationsCache = ConcurrentHashMap<String, NotificationPayload>()
        private val recentNotificationHistory = ConcurrentLinkedDeque<NotificationPayload>()

        fun isConnected(): Boolean = instance != null

        fun isNotificationAccessGranted(context: android.content.Context): Boolean {
            if (instance != null) return true
            return try {
                val enabled = android.provider.Settings.Secure.getString(
                    context.contentResolver,
                    "enabled_notification_listeners"
                ) ?: ""
                enabled.contains(context.packageName)
            } catch (_: Exception) {
                false
            }
        }

        fun getNotifications(packageNameFilter: String? = null, limit: Int = 20): List<NotificationPayload> {
            val all = recentNotificationHistory.toList()
            val filtered = if (!packageNameFilter.isNullOrBlank()) {
                all.filter { it.packageName.equals(packageNameFilter, ignoreCase = true) }
            } else {
                all
            }
            return filtered.take(limit)
        }

        fun sendReply(notificationKey: String, replyText: String): Boolean {
            val listener = instance ?: return false
            val activeList = listener.activeNotifications ?: return false
            val target = activeList.firstOrNull { it.key == notificationKey } ?: return false

            val replyPair = findReplyAction(target.notification) ?: return false
            val action = replyPair.first
            val remoteInput = replyPair.second

            val bundle = Bundle()
            bundle.putCharSequence(remoteInput.resultKey, replyText)

            val intent = Intent()
            RemoteInput.addResultsToIntent(arrayOf(remoteInput), intent, bundle)

            return try {
                action.actionIntent.send(listener, 0, intent)
                true
            } catch (e: Exception) {
                e.printStackTrace()
                false
            }
        }

        private fun findReplyAction(notification: Notification): Pair<Notification.Action, RemoteInput>? {
            val actions = notification.actions ?: return null
            for (action in actions) {
                val remoteInputs = action.remoteInputs ?: continue
                for (input in remoteInputs) {
                    if (input.allowFreeFormInput) {
                        return Pair(action, input)
                    }
                }
            }
            return null
        }
    }
}
