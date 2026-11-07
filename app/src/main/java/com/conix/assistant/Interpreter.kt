package com.conix.assistant.live

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import com.conix.assistant.AppLang
import com.conix.assistant.core.KoriCore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** Listens continuously and translates what it hears (for calls on speaker or face-to-face). */
class Interpreter(private val ctx: Context, private val core: KoriCore) {
    private val handler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var sr: SpeechRecognizer? = null
    private var active = false
    private var srcTag = "en-IN"
    private var target = "English"
    private var onLine: ((String, String) -> Unit)? = null

    fun start(src: AppLang, dst: AppLang, cb: (String, String) -> Unit) {
        stop()
        active = true
        srcTag = src.tag
        target = englishName(dst)
        onLine = cb
        listen()
    }

    fun stop() {
        active = false
        handler.removeCallbacksAndMessages(null)
        sr?.destroy()
        sr = null
    }

    private fun listen() {
        if (!active) return
        if (!SpeechRecognizer.isRecognitionAvailable(ctx)) { active = false; return }
        sr?.destroy()
        val r = SpeechRecognizer.createSpeechRecognizer(ctx)
        r.setRecognitionListener(object : RecognitionListener {
            override fun onResults(results: Bundle?) {
                val t = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty().trim()
                if (t.isNotEmpty()) {
                    scope.launch {
                        val tr = core.translate(t, target)
                        onLine?.invoke(t, tr)
                    }
                }
                handler.postDelayed({ listen() }, 150)
            }
            override fun onError(error: Int) { handler.postDelayed({ listen() }, 400) }
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        sr = r
        r.startListening(
            Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE, srcTag)
                .putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, ctx.packageName)
        )
    }

    companion object {
        fun englishName(l: AppLang) = when (l) {
            AppLang.English -> "English"
            AppLang.Hindi -> "Hindi"
            AppLang.Odia -> "Odia"
        }
    }
}
