package com.conix.assistant

import android.app.Application
import com.conix.assistant.dev.CrashLog

class KoriApp : Application() {
    override fun onCreate() {
        super.onCreate()
        CrashLog.install(this)
        KoriBus.messages.restore(com.conix.assistant.core.ChatStore.init(this))
    }
}
