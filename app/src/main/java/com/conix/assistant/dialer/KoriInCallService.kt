package com.conix.assistant.dialer

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.graphics.drawable.Icon
import android.net.Uri
import android.provider.ContactsContract
import android.telecom.Call
import android.telecom.CallAudioState
import android.telecom.InCallService

class KoriInCallService : InCallService() {
    private var lastState = -1

    private val cb = object : Call.Callback() {
        override fun onStateChanged(call: Call, state: Int) { sync(call) }
        override fun onDetailsChanged(call: Call, details: Call.Details) { sync(call) }
    }

    override fun onCreate() { super.onCreate(); instance = this }
    override fun onDestroy() { instance = null; super.onDestroy() }

    override fun onCallAdded(call: Call) {
        call.registerCallback(cb)
        CallRegistry.call = call
        lastState = -1
        sync(call)
        if (call.state == Call.STATE_RINGING) {
            showIncomingNotification()
            CallRegistry.onRinging?.invoke(CallRegistry.number.ifBlank { null })
        }
        try {
            startActivity(Intent(this, DialerActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP))
        } catch (_: Exception) { }
    }

    override fun onCallRemoved(call: Call) {
        call.unregisterCallback(cb)
        if (CallRegistry.call === call) {
            CallRegistry.call = null
            CallRegistry.state = Call.STATE_DISCONNECTED
        }
        getSystemService(NotificationManager::class.java).cancel(CALL_NOTIF)
        CallRegistry.onIdle?.invoke()
    }

    override fun onCallAudioStateChanged(audioState: CallAudioState?) {
        if (audioState != null) {
            CallRegistry.speaker = audioState.route == CallAudioState.ROUTE_SPEAKER
            CallRegistry.muted = audioState.isMuted
        }
    }

    private fun sync(call: Call) {
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
