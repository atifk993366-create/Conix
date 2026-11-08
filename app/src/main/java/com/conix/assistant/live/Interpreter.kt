package com.conix.assistant.live

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.conix.assistant.AppLang
import com.conix.assistant.core.KoriCore
import com.conix.assistant.speech.Ears
import com.conix.assistant.speech.EarsFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** Listens continuously and translates what it hears (for calls on speaker or face-to-face). */
class Interpreter(private val ctx: Context, private val core: KoriCore) {
    private val handler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var ears: Ears? = null
    private var active = false
    private var target = "English"
    private var onLine: ((String, String) -> Unit)? = null

    fun start(src: AppLang, dst: AppLang, cb: (String, String) -> Unit) {
        stop()
        active = true
        target = englishName(dst)
        onLine = cb
        ears = EarsFactory.create(ctx, core.prefs, src.tag).also { it.onFatal = { active = false } }
        listen()
    }

    fun stop() {
        active = false
        handler.removeCallbacksAndMessages(null)
        ears?.release()
        ears = null
    }

    private fun listen() {
        if (!active) return
        ears?.listen(0) { text ->
            if (text.isNotBlank()) {
                scope.launch {
                    val tr = core.translate(text, target)
                    onLine?.invoke(text, tr)
                }
            }
            handler.postDelayed({ listen() }, 100)
        }
    }

    companion object {
        fun englishName(l: AppLang) = when (l) {
            AppLang.English -> "English"
            AppLang.Hindi -> "Hindi"
            AppLang.Odia -> "Odia"
        }
    }
}
