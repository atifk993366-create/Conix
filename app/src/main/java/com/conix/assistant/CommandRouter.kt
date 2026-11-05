package com.conix.assistant

import android.content.Context
import android.content.Intent
import android.net.Uri
import java.util.Locale

/** Simple local command router. Real AI brain comes in Phase 5. */
class CommandRouter(private val ctx: Context) {

    fun handle(raw: String): String {
        val q = raw.trim().lowercase(Locale.ROOT)
        return when {
            q.contains("open youtube") || q.contains("यूट्यूब खोल") || q.contains("ୟୁଟ୍ୟୁବ୍ ଖୋଲ") -> {
                openUrl("https://www.youtube.com"); "Opening YouTube."
            }
            q.contains("open google") || q.contains("गूगल खोल") || q.contains("ଗୁଗୁଲ୍ ଖୋଲ") -> {
                openUrl("https://www.google.com"); "Opening Google."
            }
            q.contains("open whatsapp") || q.contains("व्हाट्सएप खोल") || q.contains("वॉट्सऐप खोल") -> openWhatsApp()
            q.contains("open settings") || q.contains("सेटिंग खोल") || q.contains("ସେଟିଂ") -> {
                launch(Intent(android.provider.Settings.ACTION_SETTINGS)); "Opening Android settings."
            }
            q.startsWith("search ") || q.startsWith("खोजो ") || q.startsWith("ଖୋଜ ") -> {
                val term = raw.trim().substringAfter(' ').trim()
                openUrl("https://www.google.com/search?q=" + java.net.URLEncoder.encode(term, "UTF-8"))
                "Searching the web for $term."
            }
            q.contains("hello") || q.contains("hi conix") || q.contains("नमस्ते") || q.contains("ନମସ୍କାର") ->
                "Hello! CONIX AI is online. Voice identity verification is not enabled yet, so I will not perform private or sensitive actions."
            else ->
                "I heard: \"$raw\". This build supports basic commands only (open youtube / google / whatsapp / settings, search ...). The AI brain arrives in Phase 5."
        }
    }

    private fun openWhatsApp(): String {
        val i = ctx.packageManager.getLaunchIntentForPackage("com.whatsapp")
        return if (i != null) { launch(i); "Opening WhatsApp." } else "WhatsApp is not installed on this phone."
    }

    private fun openUrl(url: String) = launch(Intent(Intent.ACTION_VIEW, Uri.parse(url)))

    private fun launch(i: Intent) {
        try {
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ctx.startActivity(i)
        } catch (_: Exception) { }
    }
}
