package com.conix.assistant.dialer

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.graphics.drawable.Icon
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.ContactsContract
import android.telecom.Call
import android.telecom.CallAudioState
import android.telecom.InCallService
import com.conix.assistant.ChatLine
import com.conix.assistant.KoriBus
import com.conix.assistant.core.CallNotes
import com.conix.assistant.core.Prefs
import com.conix.assistant.core.SecretStore

class KoriInCallService : InCallService() {
    private var lastState = -1
    private val handler = Handler(Looper.getMainLooper())

    private val cb = object : Call.Callback() {
        override fun onStateChanged(call: Call, state: Int) { sync(call) }
        override fun onDetailsChanged(call: Call, details: Call.Details) { sync(call) }
    }

    override fun onCreate() { super.onCreate(); instance = this }
    override fun onDestroy() { instance = null; super.onDestroy() }

    override fun onCallAdded(call: Call) {
        call.registerCallback(cb)
        CallRegistry.calls.add(call)
        CallRegistry.callCount = CallRegistry.calls.size
        CallRegistry.call = call
        lastState = -1
        sync(call)
        if (call.state == Call.STATE_RINGING) {
            showIncomingNotification()
            if (!screenWithText(call)) CallRegistry.onRinging?.invoke(CallRegistry.number.ifBlank { null })
        }
        try {
            startActivity(Intent(this, DialerActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP))
        } catch (_: Exception) { }
    }

    override fun onCallRemoved(call: Call) {
        call.unregisterCallback(cb)
        CallRegistry.calls.remove(call)
        CallRegistry.callCount = CallRegistry.calls.size
        val next = CallRegistry.calls.lastOrNull()
        CallRegistry.call = next
        getSystemService(NotificationManager::class.java).cancel(CALL_NOTIF)
        if (next != null) {
            lastState = -1
            sync(next)
        } else {
            CallRegistry.state = Call.STATE_DISCONNECTED
            CallRegistry.onIdle?.invoke()
        }
    }

    override fun onCallAudioStateChanged(audioState: CallAudioState?) {
        if (audioState != null) {
            CallRegistry.speaker = audioState.route == CallAudioState.ROUTE_SPEAKER
            CallRegistry.muted = audioState.isMuted
        }
    }

    fun refresh(call: Call) { lastState = -1; sync(call) }

    private fun sync(call: Call) {
        if (call !== CallRegistry.call) return
        val details = call.details
        val num = details?.handle?.schemeSpecificPart ?: ""
        CallRegistry.state = call.state
        CallRegistry.number = num
        CallRegistry.name = details?.callerDisplayName?.toString()?.takeIf { it.isNotBlank() } ?: nameOf(num)
        CallRegistry.connectedAt = details?.connectTimeMillis ?: 0L
        if (call.state != lastState) {
            lastState = call.state
            if (call.state == Call.STATE_ACTIVE) {
                getSystemService(NotificationManager::class.java).cancel(CALL_NOTIF)
                CallRegistry.onActive?.invoke()
            }
        }
    }

    /**
     * Driving mode and Away mode in "text" style: decline the call and let the phone send an SMS.
     * No microphone, no speaker tricks, so it works on every phone. Returns true if it took over.
     */
    private fun screenWithText(call: Call): Boolean {
        val prefs = Prefs(this)
        if (prefs.callReply != "text" || prefs.automationPaused) return false
        val driving = prefs.driving
        val away = System.currentTimeMillis() < prefs.awayUntil
        if (!driving && !away) return false
        if (!driving && CallRegistry.number.isNotBlank() && AwayWatch.recentlyScreened(this, CallRegistry.number, 180_000L)) return false   // calling again = urgent: let it ring
        val delay = if (driving) 800L else prefs.awayDelaySec * 1000L
        handler.postDelayed({
            if (call.state == Call.STATE_RINGING) {
                val who = CallRegistry.name.ifBlank { CallRegistry.number.ifBlank { "Unknown" } }
                val num = CallRegistry.number
                val text = if (driving) prefs.drivingText else awayMessage(prefs)
                call.reject(true, text)
                if (!driving && num.isNotBlank()) AwayWatch.markScreened(this, num)
                if (num.isNotBlank()) AwayWatch.add(this, num, who)
                val note = if (driving) "Called while you were driving. I texted them." else "Called while you were away. I texted them."
                CallNotes.add(SecretStore(this), who, note, "")
                KoriBus.notesTick++
                KoriBus.messages.add(ChatLine("conix", "Call note - $who: $note"))
            }
        }, delay)
        return true
    }

    private fun awayMessage(prefs: Prefs): String {
        if (prefs.awayText.isNotBlank()) return prefs.awayText
        val owner = prefs.ownerName.ifBlank { "the owner" }
        return "Hi, main Kori hoon, $owner ka assistant. $owner abhi available nahi hain. Apna naam aur kaaran SMS kar dein, wo jaldi reply karenge. Urgent ho to 2 minute mein dobara call karein."
    }

    private fun nameOf(number: String): String {
        if (number.isBlank()) return ""
        return try {
            val uri = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(number))
            contentResolver.query(uri, arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME), null, null, null)?.use {
                if (it.moveToFirst()) it.getString(0) else ""
            } ?: ""
        } catch (e: Exception) { "" }
    }

    private fun showIncomingNotification() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CH, "Incoming calls", NotificationManager.IMPORTANCE_HIGH))
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, DialerActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        fun action(a: String, code: Int) = PendingIntent.getBroadcast(
            this, code, Intent(this, CallActionReceiver::class.java).setAction(a), PendingIntent.FLAG_IMMUTABLE
        )
        val who = CallRegistry.name.ifBlank { CallRegistry.number.ifBlank { "Unknown" } }
        val n = Notification.Builder(this, CH)
            .setSmallIcon(android.R.drawable.sym_call_incoming)
            .setContentTitle("Incoming call")
            .setContentText(who)
            .setCategory(Notification.CATEGORY_CALL)
            .setOngoing(true)
            .setContentIntent(open)
            .setFullScreenIntent(open, true)
            .addAction(Notification.Action.Builder(Icon.createWithResource(this, android.R.drawable.sym_action_call), "Answer", action("answer", 1)).build())
            .addAction(Notification.Action.Builder(Icon.createWithResource(this, android.R.drawable.ic_menu_close_clear_cancel), "Decline", action("decline", 2)).build())
            .build()
        nm.notify(CALL_NOTIF, n)
    }

    companion object {
        const val CALL_NOTIF = 4242
        private const val CH = "kori_calls"
        @Volatile var instance: KoriInCallService? = null
    }
}
