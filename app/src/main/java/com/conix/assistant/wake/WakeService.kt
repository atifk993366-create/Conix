package com.conix.assistant.wake

import android.app.KeyguardManager
import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.ContactsContract
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.telephony.TelephonyManager
import com.conix.assistant.AppLang
import com.conix.assistant.ChatLine
import com.conix.assistant.KoriBus
import com.conix.assistant.MainActivity
import com.conix.assistant.core.KoriCore
import com.conix.assistant.core.Notifs
import com.conix.assistant.notif.KoriNotificationListener
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Beta "Hey Kori" listener. Uses Android speech recognition (on-device when available) and
 * reacts only to phrases that start with the wake word. Also announces incoming calls and
 * (optionally) WhatsApp messages, and asks for a spoken "yes" before sending messages.
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

    private var awaitingConfirm = false
    private var confirmUntil = 0L
    private var replyUntil = 0L
    private var callMode = false
    private var ringing = false
    private var ringNumber: String? = null
    private var receiverRegistered = false

    private val I = RegexOption.IGNORE_CASE
    private val wakeRe = Regex(
        "^\\s*(?:(?:hey|hi|hello|ok|okay)\\s+)?(?:kori|corey|cory|kauri|khori)\\b[\\s,.!]*", I
    )
    private val yesRe = Regex("\\b(yes|yeah|yep|yup|confirm|send|haan|han|ha|ok|okay|kar do|karo)\\b|हाँ|हां|ହଁ", I)
    private val answerRe = Regex("\\b(answer|pick|receive|accept|utha|uthao|uthalo|yes|haan)\\b|उठा|रिसीव", I)
    private val rejectRe = Regex("\\b(reject|decline|cut|cancel|disconnect|kaat|kat|no|nahi)\\b|काट", I)
    private val replyRe = Regex("^(reply|jawab|jawaab|bolo)\\b\\s*", I)

    private val phoneReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, i: Intent?) {
            val state = i?.getStringExtra(TelephonyManager.EXTRA_STATE) ?: return
            when (state) {
                TelephonyManager.EXTRA_STATE_RINGING -> {
                    val num = i.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER)
                    onRinging(num)
                }
                TelephonyManager.EXTRA_STATE_OFFHOOK -> {
                    ringing = false; callMode = false
                    busy = true; stopListening()
                }
                TelephonyManager.EXTRA_STATE_IDLE -> {
                    val wasCall = ringing || callMode || busy
                    ringing = false; callMode = false; ringNumber = null
                    if (wasCall && !awaitingConfirm) handler.postDelayed({ resume() }, 1000)
                }
            }
        }
    }

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
        if (core.prefs.announceWa) {
            KoriNotificationListener.onWhatsApp = { s: String, t: String -> onWhatsApp(s, t) }
        } else {
            KoriNotificationListener.onWhatsApp = null
        }
        if (core.prefs.callAssist && !receiverRegistered) {
            try {
                val f = IntentFilter(TelephonyManager.ACTION_PHONE_STATE_CHANGED)
                if (Build.VERSION.SDK_INT >= 33) registerReceiver(phoneReceiver, f, Context.RECEIVER_EXPORTED)
                else registerReceiver(phoneReceiver, f)
                receiverRegistered = true
            } catch (_: Exception) { }
        }
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

    // ---------- incoming calls ----------
    private fun contactName(number: String?): String? {
        if (number.isNullOrBlank()) return null
        return try {
            val uri = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(number))
            contentResolver.query(uri, arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME), null, null, null)?.use {
                if (it.moveToFirst()) it.getString(0) else null
            }
        } catch (e: Exception) { null }
    }

    private fun onRinging(num: String?) {
        if (num != null) ringNumber = num
        if (ringing) return
        ringing = true
        handler.postDelayed({ if (ringing) announceCall() }, 900)
    }

    private fun announceCall() {
        val who = contactName(ringNumber) ?: ringNumber?.let { it.toList().joinToString(" ") } ?: "an unknown number"
        busy = true
        stopListening()
        callMode = true
        KoriBus.messages.add(ChatLine("conix", "Incoming call from ${contactName(ringNumber) ?: ringNumber ?: "unknown"}"))
        say("Incoming call from $who. Say answer or reject.") { busy = false; startListening() }
    }

    private fun handleCall(h: String) {
        when {
            answerRe.containsMatchIn(h) -> {
                callMode = false
                val r = core.callControl(true)
                KoriBus.messages.add(ChatLine("conix", r))
                busy = true; stopListening()
            }
            rejectRe.containsMatchIn(h) -> {
                callMode = false
                val r = core.callControl(false)
                KoriBus.messages.add(ChatLine("conix", r))
                busy = true; stopListening()
                say(r) { resume() }
            }
            else -> handler.postDelayed({ startListening() }, 300)
        }
    }

    // ---------- WhatsApp announcements ----------
    private fun onWhatsApp(sender: String, text: String) {
        if (busy || callMode || awaitingConfirm) return
        val km = getSystemService(KeyguardManager::class.java)
        if (km.isKeyguardLocked) return   // never read private messages aloud on a locked phone
        busy = true
        stopListening()
        KoriBus.messages.add(ChatLine("conix", "WhatsApp - $sender: $text"))
        replyUntil = System.currentTimeMillis() + 20_000
        say("WhatsApp message from $sender. ${text.take(200)}. Say reply, then your message, to answer.") { resume() }
    }

    // ---------- recognition results ----------
    override fun onResults(results: Bundle?) {
        val heard = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty().trim()
        if (heard.isEmpty()) { handler.postDelayed({ startListening() }, 300); return }
        val h = heard.lowercase()

        if (awaitingConfirm && System.currentTimeMillis() > confirmUntil) {
            awaitingConfirm = false; KoriBus.pending = null
        }
        if (awaitingConfirm) { handleConfirm(h); return }
        if (callMode) { handleCall(h); return }
        if (System.currentTimeMillis() < replyUntil && replyRe.containsMatchIn(heard)) {
            replyUntil = 0
            runCommand("reply " + heard.replace(replyRe, ""))
            return
        }

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

    private fun handleConfirm(h: String) {
        awaitingConfirm = false
        val p = KoriBus.pending
        KoriBus.pending = null
        busy = true
        stopListening()
        if (p != null && yesRe.containsMatchIn(h)) {
            val r = try { p.run() } catch (e: Exception) { "Failed: ${e.message}" }
            KoriBus.messages.add(ChatLine("conix", r))
            say(r.take(200)) { resume() }
        } else {
            KoriBus.messages.add(ChatLine("conix", "Cancelled."))
            say("Cancelled.") { resume() }
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
            val reply = try { core.handle(cmd, false, false) } catch (e: Exception) { "Error: ${e.message}" }
            KoriBus.messages.add(ChatLine("conix", reply))
            var spoken = reply.substringBefore("\n\nSources:").take(300)
            val p = KoriBus.pending
            if (p != null) {
                if (p.needsUnlock) {
                    Notifs.needsConfirmation(this@WakeService)
                    spoken = "Please confirm on your phone."
                } else {
                    awaitingConfirm = true
                    confirmUntil = System.currentTimeMillis() + 25_000
                    spoken = (p.title + " " + p.detail.replace("\n", ". ") + " Say yes to confirm.").take(300)
                }
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
        KoriNotificationListener.onWhatsApp = null
        if (receiverRegistered) { try { unregisterReceiver(phoneReceiver) } catch (_: Exception) { } }
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
