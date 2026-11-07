package com.conix.assistant.notif

import android.app.Notification
import android.app.RemoteInput
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.conix.assistant.core.Prefs

class KoriNotificationListener : NotificationListenerService() {
    private val prefs by lazy { Prefs(applicationContext) }

    override fun onListenerConnected() { connected = true }
    override fun onListenerDisconnected() { connected = false }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        val n = sbn ?: return
        val pkg = n.packageName
        if (pkg == packageName || n.isOngoing) return
        if (pkg in prefs.blockedApps) return
        val ex = n.notification.extras
        val title = ex.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: ""
        val text = ex.getCharSequence(Notification.EXTRA_TEXT)?.toString() ?: ""
        if (title.isEmpty() && text.isEmpty()) return
        val app = try {
            packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
        } catch (e: Exception) { pkg }
        synchronized(recent) {
            recent.add(0, "$app: $title - $text")
            while (recent.size > 30) recent.removeAt(recent.size - 1)
        }
        if (pkg == "com.whatsapp" && (n.notification.flags and Notification.FLAG_GROUP_SUMMARY) == 0) {
            var act: Notification.Action? = null
            val acts = n.notification.actions
            if (acts != null) {
                for (a in acts) {
                    val ri = a.remoteInputs
                    if (ri != null && ri.isNotEmpty()) { act = a; break }
                }
            }
            synchronized(waRecent) {
                waRecent.add(0, WaMsg(title, text, act))
                while (waRecent.size > 15) waRecent.removeAt(waRecent.size - 1)
            }
            onWhatsApp?.invoke(title, text)
        }
    }

    class WaMsg(val sender: String, val text: String, val action: Notification.Action?)

    companion object {
        private val recent = ArrayList<String>()
        private val waRecent = ArrayList<WaMsg>()
        @Volatile var connected = false
        @Volatile var onWhatsApp: ((String, String) -> Unit)? = null

        fun summary(max: Int = 5): String {
            if (!connected) return "Notification access is not enabled. Turn it on in Kori Settings."
            synchronized(recent) {
                if (recent.isEmpty()) return "No recent notifications."
                return recent.take(max).joinToString("\n")
            }
        }

        fun waSummary(max: Int = 5): String {
            if (!connected) return "Notification access is not enabled. Turn it on in Kori Settings."
            synchronized(waRecent) {
                if (waRecent.isEmpty()) return "No new WhatsApp messages."
                return waRecent.take(max).joinToString("\n") { "${it.sender}: ${it.text}" }
            }
        }

        fun lastWaSender(): String? = synchronized(waRecent) { waRecent.firstOrNull()?.sender }

        /** Replies through WhatsApp's own notification Reply action (no screen needed). */
        fun reply(ctx: Context, sender: String, text: String): Boolean {
            val msg = synchronized(waRecent) {
                waRecent.firstOrNull { it.action != null && (sender.isBlank() || it.sender.lowercase().contains(sender.lowercase())) }
            } ?: return false
            val action = msg.action ?: return false
            val ri = action.remoteInputs ?: return false
            val intent = Intent()
            val bundle = Bundle()
            for (r in ri) bundle.putCharSequence(r.resultKey, text)
            RemoteInput.addResultsToIntent(ri, intent, bundle)
            return try { action.actionIntent.send(ctx, 0, intent); true } catch (e: Exception) { false }
        }
    }
}
