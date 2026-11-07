package com.conix.assistant.core

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.conix.assistant.KoriBus
import com.conix.assistant.MainActivity
import com.conix.assistant.access.KoriAccessibility

object Notifs {
    const val CH_LISTEN = "kori_listening"
    const val CH_ALERT = "kori_alert"
    private var counter = 0

    fun ensure(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CH_LISTEN, "Kori listening", NotificationManager.IMPORTANCE_LOW))
        nm.createNotificationChannel(NotificationChannel(CH_ALERT, "Kori actions", NotificationManager.IMPORTANCE_HIGH))
    }

    /** Starts an activity. When the app is in the background Android may block it, so also post a tap-to-open notification. */
    fun launch(ctx: Context, i: Intent) {
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try { (KoriAccessibility.instance ?: ctx).startActivity(i) } catch (_: Exception) { }
        if (!KoriBus.appInForeground) tapToOpen(ctx, "Kori: tap to continue", i)
    }

    fun tapToOpen(ctx: Context, title: String, i: Intent) {
        ensure(ctx)
        val pi = PendingIntent.getActivity(
            ctx, (System.currentTimeMillis() % 100000).toInt(), i,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val n = Notification.Builder(ctx, CH_ALERT)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentIntent(pi)
            .setAutoCancel(true)
            .setTimeoutAfter(8000)
            .build()
        ctx.getSystemService(NotificationManager::class.java).notify(1000 + (counter++ % 50), n)
    }

    fun needsConfirmation(ctx: Context) {
        val i = Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        tapToOpen(ctx, "Kori needs your confirmation", i)
    }
}
