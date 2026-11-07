package com.conix.assistant.core

import android.content.Context
import com.conix.assistant.CommandRouter
import com.conix.assistant.brain.Brain

/** One pipeline used by the chat screen and the wake-word service. Call from the main thread. */
class KoriCore(ctx: Context) {
    private val app = ctx.applicationContext
    val prefs = Prefs(app)
    val secrets = SecretStore(app)
    val brain = Brain(prefs, secrets)
    private val exec = ActionExecutor(app, brain, prefs)
    private val router = CommandRouter(app)

    fun callControl(answer: Boolean) = exec.callControl(answer)

    /** [typed] = true when the user typed it (or tapped), false when it came from speech recognition. */
    suspend fun handle(text: String, research: Boolean = false, typed: Boolean = true): String {
        if (!research) {
            val fp = FastPath.parse(text)
            if (fp != null) {
                val fb = exec.execute(fp.first, typed)
                return listOf(fp.second, fb).filter { it.isNotBlank() }.joinToString("\n").ifBlank { "Done." }
            }
        }
        if (!brain.hasKey()) {
            val note = if (research) "\n\n(Web research needs an AI key - add one in Settings.)" else ""
            return router.handle(text) + note
        }
        if (research && prefs.provider != "gemini") {
            return "Web research uses Google Search and needs the Gemini provider. Switch provider in Settings."
        }
        return try {
            val r = brain.ask(text, research)
            val fb = exec.execute(r.action, typed)
            buildString {
                append(r.reply)
                if (fb.isNotBlank()) append("\n").append(fb)
                if (r.sources.isNotEmpty()) {
                    append("\n\nSources:")
                    r.sources.take(5).forEach { append("\n- ${it.first}: ${it.second}") }
                }
            }.trim()
        } catch (e: Exception) {
            router.handle(text) + "\n\n(AI brain unavailable: ${e.message?.take(160)}. Used offline commands.)"
        }
    }
}
