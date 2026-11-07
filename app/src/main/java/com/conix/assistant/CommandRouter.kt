package com.conix.assistant

import android.content.Context
import android.content.Intent
import android.net.Uri
import java.net.URLEncoder
import java.util.Locale

/** Simple local command router. Real AI brain comes in Phase 5. */
class CommandRouter(private val ctx: Context) {

    private data class YtReq(val term: String, val channel: Boolean, val subscribe: Boolean, val like: Boolean)

    // Speech recognizers often mishear the name, so accept close variants.
    private val wakeRe = Regex(
        "^\\s*(?:(?:hey|hi|hello|ok|okay)\\s+)?(?:kori|corey|cory|kauri|khori)\\b[\\s,.!]*",
        RegexOption.IGNORE_CASE
    )
    private val verbRe = Regex(
        "(?:search(?:\\s+for)?|find|look\\s+for|play)\\s+(?:a\\s+|an\\s+|the\\s+|some\\s+)?(.+)",
        RegexOption.IGNORE_CASE
    )

    fun handle(input: String): String {
        val raw = input.trim().replace(wakeRe, "").trim()
        if (raw.isEmpty()) return "Yes? I'm Kori. What can I do for you?"
        val q = raw.lowercase(Locale.ROOT)

        parseYoutube(raw, q)?.let { return runYoutube(it) }

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
                val term = raw.substringAfter(' ').trim()
                openUrl("https://www.google.com/search?q=" + URLEncoder.encode(term, "UTF-8"))
                "Searching the web for $term."
            }
            q.contains("hello") || q.startsWith("hi") || q.contains("नमस्ते") || q.contains("ନମସ୍କାର") ->
                "Hello! I'm Kori. Voice identity is still in testing mode, so I won't do private or sensitive actions yet."
            else ->
                "I heard: \"$raw\". I can only do basic commands right now (open apps, call, play, search). Add an AI key in Settings, or check your internet, for full answers."
        }
    }

    private fun parseYoutube(raw: String, q: String): YtReq? {
        if (!(q.contains("youtube") || q.startsWith("play "))) return null
        val m = verbRe.find(raw) ?: return null
        var term = m.groupValues[1]
        term = term.replace(Regex("\\s*(?:,|\\band\\b|\\bthen\\b)\\s*(?:subscribe|like|open|play)\\b.*$", RegexOption.IGNORE_CASE), "")
        term = term.replace(Regex("\\s+on\\s+youtube$", RegexOption.IGNORE_CASE), "")
        term = term.replace(Regex("\\s+channel$", RegexOption.IGNORE_CASE), "")
        term = term.trim(' ', ',', '.')
        if (term.isEmpty()) return null
        return YtReq(
            term = term,
            channel = q.contains("channel"),
            subscribe = q.contains("subscribe"),
            like = Regex("\\b(like it|like the video|like this|hit like)\\b").containsMatchIn(q)
        )
    }

    private fun runYoutube(r: YtReq): String {
        val enc = URLEncoder.encode(r.term, "UTF-8")
        val asChannel = r.channel || r.subscribe
        val url = "https://www.youtube.com/results?search_query=$enc" + if (asChannel) "&sp=EgIQAg%253D%253D" else ""
        openUrl(url)
        val sb = StringBuilder("Searching YouTube for \"${r.term}\"")
        sb.append(if (asChannel) " (channels)." else ".")
        when {
            r.subscribe -> sb.append(" I can't tap Subscribe yet - that needs Accessibility control (Phase 6). Please tap it yourself.")
            r.like -> sb.append(" I can't tap Like yet - that needs Accessibility control (Phase 6).")
            else -> sb.append(" Tap a result to play it.")
        }
        return sb.toString()
    }

    private fun openWhatsApp(): String {
        val i = ctx.packageManager.getLaunchIntentForPackage("com.whatsapp")
        return if (i != null) { launch(i); "Opening WhatsApp." } else "WhatsApp is not installed on this phone."
    }

    private fun openUrl(url: String) = launch(Intent(Intent.ACTION_VIEW, Uri.parse(url)))

    private fun launch(i: Intent) = com.conix.assistant.core.Notifs.launch(ctx, i)
}
