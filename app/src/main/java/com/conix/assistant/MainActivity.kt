package com.conix.assistant

import android.os.Bundle
import android.speech.tts.TextToSpeech
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import java.util.Locale

enum class AppLang(val label: String, val tag: String, val locale: Locale) {
    English("English", "en-IN", Locale("en", "IN")),
    Hindi("हिन्दी", "hi-IN", Locale("hi", "IN")),
    Odia("ଓଡ଼ିଆ", "or-IN", Locale("or", "IN"))
}

class MainActivity : ComponentActivity(), TextToSpeech.OnInitListener {
    private var tts: TextToSpeech? = null
    private var ttsReady = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        tts = TextToSpeech(this, this)
        val router = CommandRouter(applicationContext)
        setContent { ConixApp(router = router, speak = { text, lang -> speak(text, lang) }) }
    }

    override fun onInit(status: Int) { ttsReady = status == TextToSpeech.SUCCESS }

    /** Returns false if voice output for this language is unavailable. */
    private fun speak(text: String, lang: AppLang): Boolean {
        val engine = tts ?: return false
        if (!ttsReady) return false
        val r = engine.setLanguage(lang.locale)
        if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) return false
        engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, "conix_reply")
        return true
    }

    override fun onDestroy() {
        tts?.stop(); tts?.shutdown()
        super.onDestroy()
    }
}
