package com.conix.assistant.core

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Saves (what you said -> what Kori answered/did) as chat-format training examples.
 * The file stays inside the app's private storage until you export it.
 */
class TrainingLog(ctx: Context, private val prefs: Prefs) {
    private val app = ctx.applicationContext
    private val file = File(app.filesDir, "training/log.jsonl").also { it.parentFile?.mkdirs() }

    @Synchronized
    fun add(user: String, reply: String, action: Any?, source: String) {
        if (!prefs.trainingLog || user.isBlank()) return
        if (file.exists() && file.length() > 20_000_000L) return
        val out = JSONObject().put("reply", reply).put("action", action ?: JSONObject.NULL)
        val rec = JSONObject()
            .put(
                "messages", JSONArray()
                    .put(msg("system", SYSTEM))
                    .put(msg("user", user.trim()))
                    .put(msg("assistant", out.toString()))
            )
            .put("source", source)
            .put("ts", System.currentTimeMillis())
        file.appendText(rec.toString() + "\n")
    }

    @Synchronized
    fun count(): Int = if (!file.exists()) 0 else file.useLines { it.count { l -> l.isNotBlank() } }

    @Synchronized
    fun clear() { file.delete() }

    /** Copies the dataset to the Downloads folder so you can upload it to Colab / Drive. */
    @Synchronized
    fun export(): String {
        if (!file.exists() || file.length() == 0L) return "Nothing to export yet."
        if (Build.VERSION.SDK_INT < 29) return "Export needs Android 10 or newer."
        val name = "kori_training_" + SimpleDateFormat("yyyyMMdd_HHmm", Locale.ENGLISH).format(Date()) + ".jsonl"
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "application/json")
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
        }
        val uri = app.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: return "Could not create the file in Downloads."
        app.contentResolver.openOutputStream(uri)?.use { out -> file.inputStream().use { it.copyTo(out) } }
        return "Saved ${count()} examples to Downloads/$name"
    }

    private fun msg(role: String, content: String) = JSONObject().put("role", role).put("content", content)

    companion object {
        const val SYSTEM = "You are Kori, an assistant on the user's Android phone. Reply with one JSON object: {\"reply\": string, \"action\": object or null}."
    }
}
