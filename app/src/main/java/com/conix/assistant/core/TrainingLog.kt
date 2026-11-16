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

    /** Exports "text,intent" for training/01_prepare_dataset.py. Phone numbers and e-mail addresses are masked. */
    @Synchronized
    fun exportIntents(): String {
        if (!file.exists() || file.length() == 0L) return "Nothing to export yet."
        if (Build.VERSION.SDK_INT < 29) return "Export needs Android 10 or newer."
        val sb = StringBuilder("text,intent\n")
        var n = 0
        file.forEachLine { line ->
            try {
                val msgs = JSONObject(line).getJSONArray("messages")
                val user = msgs.getJSONObject(1).getString("content")
                val out = JSONObject(msgs.getJSONObject(2).getString("content"))
                val act = out.opt("action")
                val intent = when (act) {
                    is JSONObject -> act.optString("type").ifBlank { "chat" }
                    is JSONArray -> act.optJSONObject(0)?.optString("type")?.ifBlank { "chat" } ?: "chat"
                    else -> "chat"
                }
                val clean = user.replace(Regex("[\\w.+-]+@[\\w-]+\\.[\\w.]+"), "<EMAIL>").replace(Regex("\\+?\\d[\\d\\s-]{6,}\\d"), "<NUM>")
                sb.append('"').append(clean.replace("\"", "\"\"")).append("\",").append(intent).append('\n')
                n++
            } catch (_: Exception) { }
        }
        val name = "kori_intents_" + SimpleDateFormat("yyyyMMdd_HHmm", Locale.ENGLISH).format(Date()) + ".csv"
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "text/csv")
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
        }
        val uri = app.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return "Could not create the file in Downloads."
        app.contentResolver.openOutputStream(uri)?.use { it.write(sb.toString().toByteArray(Charsets.UTF_8)) }
        return "Saved $n labelled commands to Downloads/$name (check the labels before training)."
    }

    private fun msg(role: String, content: String) = JSONObject().put("role", role).put("content", content)

    companion object {
        const val SYSTEM = "You are Kori, an assistant on the user's Android phone. Reply with one JSON object: {\"reply\": string, \"action\": object or null}."
    }
}
