package com.conix.assistant.dialer

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class CallActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            "answer" -> CallRegistry.answer()
            "decline" -> CallRegistry.reject()
        }
        context.getSystemService(NotificationManager::class.java).cancel(KoriInCallService.CALL_NOTIF)
    }
}
