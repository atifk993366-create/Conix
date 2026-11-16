package com.conix.assistant.core

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** What Kori is allowed to do, and a check of every action before it runs. */
object Policy {
    val categories = linkedMapOf(
        "calls" to "Calls (make, answer, reject)",
        "messages" to "Messages (SMS, WhatsApp, email)",
        "private" to "Reading private data (notifications, SMS, WhatsApp)",
        "device" to "Device control (apps, alarms, settings, music, flashlight)",
        "web" to "Web (search, YouTube, shopping, camera lookup)",
        "modes" to "Away, Driving and Game break modes"
    )

    fun categoryOf(type: String): String = when (type) {
        "call", "answer_call", "reject_call" -> "calls"
        "sms", "whatsapp", "wa_reply", "email" -> "messages"
        "read_notifications", "read_whatsapp", "read_sms" -> "private"
        "away_on", "away_off", "driving_on", "driving_off", "game_break" -> "modes"
        "youtube", "web_search", "open_url", "shop", "camera_identify", "research" -> "web"
        else -> "device"
    }

    fun alwaysAllowed(type: String) = type in setOf("", "remember", "stop_assistant", "research")

    /** Returns a message when the action is incomplete, otherwise null. */
    fun validate(type: String, a: JSONObject): String? = when (type) {
        "call" -> if (a.optString("to").isBlank()) "Who should I call?" else null
        "sms", "whatsapp" -> if (a.optString("to").isBlank() || a.optString("text").isBlank()) "I need both a contact and the message." else null
        "wa_reply" -> if (a.optString("text").isBlank()) "What should the reply say?" else null
        "open_app" -> if (a.optString("name").isBlank()) "Which app?" else null
        "web_search", "shop", "youtube" -> if (a.optString("query").isBlank()) "What should I search for?" else null
        "open_url" -> if (a.optString("url").isBlank()) "Which link?" else null
        else -> null
    }

    fun describe(a: JSONObject): String = when (a.optString("type")) {
        "sms", "whatsapp", "wa_reply", "email" -> "to=${a.optString("to")} (${a.optString("text").length} characters)"
        else -> a.toString().take(120)
    }
}

/** A local record of what Kori did, so you can review it. Stored on the phone only. */
object AuditLog {
    private fun file(ctx: Context) = File(ctx.filesDir, "audit.log")

    @Synchronized
    fun add(ctx: Context, type: String, detail: String, result: String) {
        try {
            val f = file(ctx)
            val ts = SimpleDateFormat("dd MMM HH:mm:ss", Locale.ENGLISH).format(Date())
            f.appendText("$ts | $type | ${detail.replace('\n', ' ')} | ${result.replace('\n', ' ').take(100)}\n")
            if (f.length() > 200_000) f.writeText(f.readLines().takeLast(400).joinToString("\n") + "\n")
        } catch (_: Exception) { }
    }

    fun recent(ctx: Context, n: Int = 40): List<String> =
        try { file(ctx).readLines().takeLast(n).reversed() } catch (_: Exception) { emptyList() }

    fun clear(ctx: Context) { file(ctx).delete() }
}
