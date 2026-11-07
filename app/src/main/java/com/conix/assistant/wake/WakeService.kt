package com.conix.assistant.wake

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import com.conix.assistant.AppLang
import com.conix.assistant.ChatLine
import com.conix.assistant.KoriBus
import com.conix.assistant.MainActivity
import com.conix.assistant.core.KoriCore
import com.conix.assistant.core.Notifs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Beta "Hey Kori" listener. Uses Android speech recognition (on-device when available) and
 * reacts only to phrases that start with the wake word. Runs as a visible foreground service.
 */
class WakeService : Service(), RecognitionListener {
    private val handler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private lateinit var core: KoriCore
    private var sr: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var stopAt = 0L
    private var awaiting = false
    private var busy = false
    private var onDevice = true

    private val wakeRe = Regex(
        "^\\s*(?:(?:hey|hi|hello|ok|okay)\\s+)?(?:kori|corey|cory|kauri|khori)\\b[\\s,.!]*",
        RegexOption.IGNORE_CASE
    )

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        core = KoriCore(this)
        tts = TextToSpeech(this) { ttsReady = it == TextToSpeech.SUCCESS }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) { stopSelf(); return START_NOT_STICKY }
        Notifs.ensure(this)
        val n = buildNotification()
        try {
            if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
            else startForeground(NOTIF_ID, n)
        } catch (e: Exception) {
            KoriBus.messages.add(ChatLine("conix", "Could not start listening: ${e.message}"))
            stopSelf()
            return START_NOT_STICKY
        }
        stopAt = System.currentTimeMillis() + core.prefs.wakeMinutes * 60_000L
        KoriBus.wakeActive = true
        busy = false
        startListening()
        return START_NOT_STICKY
    }

    private fun buildNotification(): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(
            this, 1, Intent(this, WakeService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(this, Notifs.CH_LISTEN)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("Kori is listening for \"Hey Kori\"")
            .setContentText("Microphone is on. Tap Stop to turn it off.")
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(Icon.createWithResource(this, android.R.drawable.ic_menu_close_clear_cancel), "Stop", stop).build())
            .build()
    }

    private fun currentLang(): AppLang = AppLang.values().firstOrNull { it.tag == core.prefs.langTag } ?: AppLang.English

    private fun startListening() {
        if (busy) return
        if (System.currentTimeMillis() > stopAt) { stopSelf(); return }
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            KoriBus.messages.add(ChatLine("conix", "Speech recognition is not available on this phone."))
            stopSelf(); return
        }
        sr?.destroy()
        val r = if (Build.VERSION.SDK_INT >= 33 && onDevice && SpeechRecognizer.isOnDeviceRecognitionAvailable(this))
            SpeechRecognizer.createOnDeviceSpeechRecognizer(this) else SpeechRecognizer.createSpeechRecognizer(this)
        r.setRecognitionListener(this)
        sr = r
        r.startListening(
            Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE, currentLang().tag)
                .putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
                .putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, packageName)
        )
    }

    private fun stopListening() {
        sr?.destroy()
        sr = null
    }

    private fun say(text: String, then: () -> Unit) {
        val t = tts
        if (t == null || !ttsReady) { then(); return }
        t.setLanguage(currentLang().locale)
        t.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) { handler.post { then() } }
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) { handler.post { then() } }
        })
        t.speak(text, TextToSpeech.QUEUE_FLUSH, null, "kori_wake")
    }

    private fun resume() { busy = false; startListening() }

    override fun onResults(results: Bundle?) {
        val heard = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty().trim()
        if (heard.isEmpty()) { handler.postDelayed({ startListening() }, 300); return }
        val stripped = heard.replace(wakeRe, "").trim()
        val addressed = stripped.length != heard.length
        when {
            addressed && stripped.isEmpty() -> {
                awaiting = true; busy = true; stopListening(); say("Yes?") { resume() }
            }
            addressed || awaiting -> {
                awaiting = false
                runCommand(if (addressed) stripped else heard)
            }
            else -> handler.postDelayed({ startListening() }, 200)
        }
    }

    private fun runCommand(cmd: String) {
        busy = true
        stopListening()
        val c = cmd.lowercase()
        if (c.startsWith("stop") || c.contains("band karo") || c.contains("stop listening")) {
            say("Okay, I'll stop listening.") { stopSelf() }
            return
        }
        KoriBus.messages.add(ChatLine("you", cmd))
        scope.launch {
            val reply = try { core.handle(cmd) } catch (e: Exception) { "Error: ${e.message}" }
            KoriBus.messages.add(ChatLine("conix", reply))
            var spoken = reply.substringBefore("\n\nSources:").take(300)
            if (KoriBus.pending != null) {
                Notifs.needsConfirmation(this@WakeService)
                spoken += " Please confirm on your phone."
            }
            say(spoken) { resume() }
        }
    }

    override fun onError(error: Int) {
        if (error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) { stopSelf(); return }
        if (error == 12 || error == 13) onDevice = false   // language not supported on-device
        if (busy) return
        val delay = if (error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY) 1200L else 400L
        handler.postDelayed({ startListening() }, delay)
    }

    override fun onReadyForSpeech(params: Bundle?) {}
    override fun onBeginningOfSpeech() {}
    override fun onRmsChanged(rmsdB: Float) {}
    override fun onBufferReceived(buffer: ByteArray?) {}
    override fun onEndOfSpeech() {}
    override fun onPartialResults(partialResults: Bundle?) {}
    override fun onEvent(eventType: Int, params: Bundle?) {}

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        sr?.destroy()
        tts?.stop(); tts?.shutdown()
        scope.cancel()
        KoriBus.wakeActive = false
        super.onDestroy()
    }

    companion object {
        const val ACTION_STOP = "com.conix.assistant.STOP_WAKE"
        private const val NOTIF_ID = 77
    }
}
