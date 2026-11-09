package com.conix.assistant

import android.media.AudioAttributes
import android.media.AudioManager
import android.os.Bundle
import android.speech.tts.TextToSpeech
import androidx.activity.compose.setContent
import androidx.fragment.app.FragmentActivity
import com.conix.assistant.core.KoriCore
import java.util.Locale

enum class AppLang(val label: String, val tag: String, val locale: Locale) {
    English("English", "en-IN", Locale("en", "IN")),
    Hindi("हिन्दी", "hi-IN", Locale("hi", "IN")),
    Odia("ଓଡ଼ିଆ", "or-IN", Locale("or", "IN"))
}

class MainActivity : FragmentActivity(), TextToSpeech.OnInitListener {
    private var tts: TextToSpeech? = null
    private var ttsReady = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        tts = TextToSpeech(this, this)
        val core = KoriCore(applicationContext)
        setContent {
            ConixApp(
                core = core,
                speak = { text, lang -> speak(text, lang, false) },
                speakCall = { text, lang -> speak(text, lang, true) }
            )
        }
    }

    override fun onStart() { super.onStart(); KoriBus.appInForeground = true }
    override fun onResume() { super.onResume(); KoriBus.resumeTick++ }
    override fun onStop() {
        KoriBus.appInForeground = false
        com.conix.assistant.core.BackupManager.autoBackup(this)
        super.onStop()
    }

    override fun onInit(status: Int) { ttsReady = status == TextToSpeech.SUCCESS }

    /** Returns false if voice output for this language is unavailable. [call] = play into an ongoing call (speakerphone). */
    private fun speak(text: String, lang: AppLang, call: Boolean): Boolean {
        val engine = tts ?: return false
        if (!ttsReady) return false
        val r = engine.setLanguage(lang.locale)
        if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) return false
        engine.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(if (call) AudioAttributes.USAGE_VOICE_COMMUNICATION else AudioAttributes.USAGE_ASSISTANT)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
        )
        if (call) {
            try { getSystemService(AudioManager::class.java).isSpeakerphoneOn = true } catch (_: Exception) { }
        }
        engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, "kori_reply")
        return true
    }

    override fun onDestroy() {
        tts?.stop(); tts?.shutdown()
        super.onDestroy()
    }
}
