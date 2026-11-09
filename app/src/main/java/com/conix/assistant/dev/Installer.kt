package com.conix.assistant.dev

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.provider.Settings
import com.conix.assistant.ChatLine
import com.conix.assistant.KoriBus
import java.io.File

object Installer {
    fun canInstall(ctx: Context) = ctx.packageManager.canRequestPackageInstalls()

    fun install(ctx: Context, apk: File): String {
        if (!canInstall(ctx)) {
            ctx.startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + ctx.packageName))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            return "Allow \"Install unknown apps\" for Kori, then tap Install again."
        }
        val pi = ctx.packageManager.packageInstaller
        val id = pi.createSession(PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL))
        pi.openSession(id).use { s ->
            apk.inputStream().use { ins ->
                s.openWrite("kori.apk", 0, apk.length()).use { out ->
                    ins.copyTo(out)
                    s.fsync(out)
                }
            }
            val status = PendingIntent.getBroadcast(
                ctx, id, Intent(ctx, InstallReceiver::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
            )
            s.commit(status.intentSender)
        }
        return "Installing... confirm on the screen."
    }
}

class InstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val st = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, -1)
        if (st == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            val confirm = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
            else @Suppress("DEPRECATION") intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
            confirm?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (confirm != null) context.startActivity(confirm)
        } else if (st != PackageInstaller.STATUS_SUCCESS) {
            KoriBus.messages.add(ChatLine("conix", "Install result: " + (intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "unknown")))
        }
    }
}
