package com.conix.assistant.notif

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

class KoriNotificationListener : NotificationListenerService() {
    override fun onListenerConnected() { connected = true }
    override fun onListenerDisconnected() { connected = false }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        val n = sbn ?: return
        if (n.packageName == packageName || n.isOngoing) return
        val ex = n.notification.extras
        val title = ex.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: ""
        val text = ex.getCharSequence(Notification.EXTRA_TEXT)?.toString() ?: ""
        if (title.isEmpty() && text.isEmpty()) return
        val app = try {
            packageManager.getApplicationLabel(packageManager.getApplicationInfo(n.packageName, 0)).toString()
        } catch (e: Exception) { n.packageName }
        synchronized(recent) {
            recent.add(0, "$app: $title - $text")
            while (recent.size > 30) recent.removeAt(recent.size - 1)
        }
    }

    companion object {
        private val recent = ArrayList<String>()
        @Volatile var connected = false

        fun summary(max: Int = 5): String {
            if (!connected) return "Notification access is not enabled. Turn it on in Kori Settings."
            synchronized(recent) {
                if (recent.isEmpty()) return "No recent notifications."
                return recent.take(max).joinToString("\n")
            }
        }
    }
}
