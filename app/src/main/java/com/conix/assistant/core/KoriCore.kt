package com.conix.assistant.core

import android.app.KeyguardManager
import android.content.Context
import com.conix.assistant.ChatLine
import com.conix.assistant.CommandRouter
import com.conix.assistant.KoriBus
import com.conix.assistant.brain.Brain

/** One pipeline used by the chat screen and the wake-word service. Call from the main thread. */
class KoriCore(ctx: Context) {
    private val app = ctx.applicationContext
    val prefs = Prefs(app)
    val secrets = SecretStore(app)
    val brain = Brain(prefs, secrets)
    val training = TrainingLog(app, prefs)
    private val exec = ActionExecutor(app, brain, prefs, secrets)
    private val router = CommandRouter(app)

    private val lockAllowed = setOf("", "call", "answer_call", "reject_call", "away_on", "away_off")

    fun callControl(answer: Boolean) = exec.callControl(answer)

    private fun isLocked() = app.getSystemService(KeyguardManager::class.java).isKeyguardLocked

    private fun lockBlock(type: String): String? {
        if (!isLocked()) return null
        if (!prefs.lockScreenMode) return "Unlock your phone first, or turn on lock-screen use in Settings."
        return if (type in lockAllowed) null else "That needs your phone unlocked. On the lock screen I can only make calls and answer questions."
    }

    private fun format(reply: String, fb: String, sources: List<Pair<String, String>>): String = buildString {
        append(reply)
        if (fb.isNotBlank()) append("\n").append(fb)
        if (sources.isNotEmpty()) {
            append("\n\nSources:")
            sources.take(5).forEach { append("\n- ${it.first}: ${it.second}") }
        }
    }.trim()

    /** [typed] = true when the user typed it (or tapped), false when it came from speech recognition. */
    suspend fun handle(text: String, typed: Boolean = true): String {
        val clean = FastPath.clean(text)
        val fp = FastPath.parse(text)
        if (fp != null) {
            lockBlock(fp.first.optString("type"))?.let { return it }
            training.add(clean, fp.second, fp.first, "fastpath")
            val fb = exec.execute(fp.first, typed)
            return listOf(fp.second, fb).filter { it.isNotBlank() }.joinToString("\n").ifBlank { "Done." }
        }
        if (!brain.hasKey()) {
            lockBlock("unknown")?.let { return it }
            return router.handle(text)
        }
        lockBlock("")?.let { return it }
        try {
            val r = brain.ask(text, false)
            val type = r.action?.optString("type") ?: ""
            if (type == "research") {
                KoriBus.messages.add(ChatLine("conix", "Checking the web..."))
                val q = r.action?.optString("query").orEmpty().ifBlank { text }
                val rr = brain.ask(q, true)
                training.add(clean, rr.reply, null, rr.provider.ifBlank { r.provider })
                return format(rr.reply, "", rr.sources)
            }
            if (r.action != null) lockBlock(type)?.let { return it }
            training.add(clean, r.reply, r.action, r.provider)
            val fb = exec.execute(r.action, typed)
            return format(r.reply, fb, r.sources)
        } catch (e: Exception) {
            return router.handle(text) + "\n\n(AI brain unavailable: ${e.message?.take(200)}. Used offline commands.)"
        }
    }

    suspend fun describeImage(jpeg: ByteArray, question: String): String {
        if (!brain.hasKey()) return "Add an AI key in Settings so I can identify things."
        return try {
            val r = brain.describeImage(jpeg, question)
            format(r.reply, "", r.sources)
        } catch (e: Exception) {
            "Couldn't analyze the photo: ${e.message?.take(240)}\n\nTip: add a second free provider (Groq or OpenRouter) in Settings; Kori switches automatically when one is out of quota."
        }
    }

    suspend fun translate(text: String, targetLanguage: String): String {
        if (!brain.hasKey()) return "(An AI key is needed for translation)"
        return try {
            brain.complete("Translate the user's text into $targetLanguage. Output only the translation, nothing else.", text)
        } catch (e: Exception) { "(Translation failed: ${e.message?.take(80)})" }
    }

    suspend fun summarizeCall(who: String, transcript: String): String {
        if (!brain.hasKey()) return transcript.take(200)
        return try {
            brain.complete(
                "Summarize this message that a caller left for the phone's owner in one or two short sentences. Mention the caller's name if given, the reason, and any urgency.",
                "Caller: $who\nTranscript: $transcript"
            )
        } catch (e: Exception) { transcript.take(200) }
    }
}
