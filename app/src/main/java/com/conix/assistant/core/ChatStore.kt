package com.conix.assistant.core

import android.content.Context
import com.conix.assistant.ChatLine
import org.json.JSONObject
import java.io.File

/** Keeps the conversation on the phone between launches. */
object ChatStore {
    private var file: File? = null

    fun init(ctx: Context): List<ChatLine> {
        val f = File(ctx.filesDir, "chat/history.jsonl")
        f.parentFile?.mkdirs()
        file = f
        if (!f.exists()) return emptyList()
        return try {
            f.readLines().takeLast(300).mapNotNull {
                try { val o = JSONObject(it); ChatLine(o.getString("f"), o.getString("t")) } catch (_: Exception) { null }
            }
        } catch (_: Exception) { emptyList() }
    }

    @Synchronized
    fun append(l: ChatLine) {
        val f = file ?: return
        try {
            f.appendText(JSONObject().put("f", l.from).put("t", l.text).toString() + "\n")
            if (f.length() > 2_000_000) f.writeText(f.readLines().takeLast(500).joinToString("\n") + "\n")
        } catch (_: Exception) { }
    }

    @Synchronized
    fun clear() { file?.delete() }
}
