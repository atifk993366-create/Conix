package com.conix.assistant.wake

import android.Manifest
import android.app.KeyguardManager
import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.media.AudioAttributes
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.provider.ContactsContract
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.telephony.SmsManager
import android.telephony.TelephonyManager
import androidx.core.content.ContextCompat
import com.conix.assistant.AppLang
import com.conix.assistant.ChatLine
import com.conix.assistant.KoriBus
import com.conix.assistant.MainActivity
import com.conix.assistant.core.CallNotes
import com.conix.assistant.core.KoriCore
import com.conix.assistant.core.Notifs
import com.conix.assistant.notif.KoriNotificationListener
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * "Hey Kori" listener (beta). Reacts only to phrases starting with the wake word. Also announces
 * incoming calls, can answer them in Away mode and take a message, and reads WhatsApp messages.
 */
class WakeService : Service(), RecognitionListener {
    private val handler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private lateinit var core: KoriCore
    private var sr: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var wl: PowerManager.WakeLock? = null
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

    // Away mode call screening
    private var awayCall = false
    private var awayRound = 0
    private var awayWho = ""
    private var awayNumber: String? = null
    private val awayLines = ArrayList<String>()

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
                TelephonyManager.EXTRA_STATE_RINGING -> onRinging(i.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER))
                TelephonyManager.EXTRA_STATE_OFFHOOK -> {
                    ringing = false
                    if (!awayCall) { callMode = false; busy = true; stopListening() }
                }
                TelephonyManager.EXTRA_STATE_IDLE -> {
                    val wasCall = ringing || callMode || awayCall || busy
                    if (awayCall) endAway(false)
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

    private fun awayActive() = System.currentTimeMillis() < core.prefs.awayUntil
    private fun owner() = core.prefs.ownerName.ifBlank { "the owner" }
    private fun granted(p: String) = ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED

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
        val now = System.currentTimeMillis()
        val awayLeft = core.prefs.awayUntil - now
        stopAt = now + maxOf(core.prefs.wakeMinutes * 60_000L, if (awayLeft > 0) awayLeft + 120_000L else 0L)
        try {
            wl?.let { if (it.isHeld) it.release() }
            wl = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "kori:listen")
                .apply { acquire(stopAt - now + 60_000L) }
        } catch (_: Exception) { }
        KoriBus.wakeActive = true
        busy = false
        KoriNotificationListener.onWhatsApp =
            if (core.prefs.announceWa) { s: String, t: String -> onWhatsApp(s, t) } else null
        if (!receiverRegistered) {
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
        beginRecognition()
    }

    private fun beginRecognition() {
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

    /** [call] = true plays the voice through the in-call audio path (heard by the caller when on speaker). */
    private fun say(text: String, call: Boolean = false, then: () -> Unit) {
        val t = tts
        if (t == null || !ttsReady) { then(); return }
        t.setLanguage(currentLang().locale)
        t.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(if (call) AudioAttributes.USAGE_VOICE_COMMUNICATION else AudioAttributes.USAGE_ASSISTANT)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
        )
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
        if (!awayActive() && !core.prefs.callAssist) return
        ringing = true
        handler.postDelayed({ if (ringing) announceCall() }, 900)
    }

    private fun announceCall() {
        if (awayActive()) { startAwayCall(); return }
        val who = contactName(ringNumber) ?: ringNumber?.toList()?.joinToString(" ") ?: "an unknown number"
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
                KoriBus.messages.add(ChatLine("conix", core.callControl(true)))
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

    // ---------- Away mode: answer, ask the reason, leave a note ----------
    private fun startAwayCall() {
        awayCall = true; callMode = false
        awayLines.clear(); awayRound = 0
        awayNumber = ringNumber
        awayWho = contactName(ringNumber) ?: ringNumber ?: "an unknown number"
        busy = true
        stopListening()
        val r = core.callControl(true)
        if (!r.startsWith("Answering")) {
            awayCall = false
            KoriBus.messages.add(ChatLine("conix", "Away mode could not answer the call: $r"))
            resume(); return
        }
        handler.postDelayed({ awayGreet() }, 1500)
    }

    private fun awayGreet() {
        if (!awayCall) return
        try { getSystemService(AudioManager::class.java).isSpeakerphoneOn = true } catch (_: Exception) { }
        say(
            "Hello, this is Kori, an AI assistant for ${owner()}. ${owner()} is unavailable right now. " +
                "This call may be transcribed so I can pass on your message. Please tell me your name and why you are calling.",
            true
        ) { if (awayCall) beginRecognition() }
    }

    private fun handleAwayResult(heard: String) {
        if (!awayCall) return
        if (heard.isNotBlank()) awayLines.add(heard)
        awayRound++
        val words = awayLines.joinToString(" ").trim().split(Regex("\\s+")).count { it.isNotBlank() }
        when {
            awayLines.isEmpty() && awayRound >= 2 ->
                say("I couldn't hear you. I'll tell ${owner()} that you called. Please send a message instead. Goodbye.", true) { endAway(true) }
            awayLines.isEmpty() ->
                say("Sorry, I couldn't hear you. Please tell me your name and why you are calling.", true) { if (awayCall) beginRecognition() }
            words < 5 && awayRound < 3 ->
                say("Thank you. Could you tell me a little more?", true) { if (awayCall) beginRecognition() }
            else ->
                say("Thank you. I've noted that and will pass it to ${owner()}. Goodbye.", true) { endAway(true) }
        }
    }

    private fun endAway(hangUp: Boolean) {
        if (!awayCall) return
        awayCall = false
        stopListening()
        if (hangUp) core.callControl(false)
        try { getSystemService(AudioManager::class.java).isSpeakerphoneOn = false } catch (_: Exception) { }
        val who = awayWho
        val num = awayNumber
        val transcript = awayLines.joinToString(" / ")
        awayLines.clear()
        scope.launch {
            val note = if (transcript.isBlank()) "Called, but I couldn't hear a message." else core.summarizeCall(who, transcript)
            CallNotes.add(core.secrets, who, note, transcript)
            KoriBus.notesTick++
            KoriBus.messages.add(ChatLine("conix", "Call note - $who: $note"))
            if (transcript.isBlank() && num != null && core.prefs.awaySms) sendAwaySms(num)
        }
        busy = false
        handler.postDelayed({ resume() }, 1200)
    }

    private fun sendAwaySms(num: String) {
        if (!granted(Manifest.permission.SEND_SMS)) return
        try {
            val sm = if (Build.VERSION.SDK_INT >= 31) getSystemService(SmsManager::class.java)
            else @Suppress("DEPRECATION") SmsManager.getDefault()
            val text = "Kori (AI assistant of ${owner()}): ${owner()} can't talk right now. Please text your name and reason and they will get back to you."
            sm.sendMultipartTextMessage(num, null, sm.divideMessage(text), null, null)
        } catch (_: Exception) { }
    }

    // ---------- WhatsApp announcements ----------
    private fun onWhatsApp(sender: String, text: String) {
        if (busy || callMode || awayCall || awaitingConfirm) return
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
        if (awayCall) { handleAwayResult(heard); return }
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
            val reply = try { core.handle(cmd, false) } catch (e: Exception) { "Error: ${e.message}" }
            KoriBus.messages.add(ChatLine("conix", reply))
            var spoken = reply.substringBefore("\n\nSources:").take(400)
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
        if (awayCall) { handleAwayResult(""); return }
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
        try { wl?.let { if (it.isHeld) it.release() } } catch (_: Exception) { }
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
