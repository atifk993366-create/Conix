package com.conix.assistant.core

import android.content.Context
import com.conix.assistant.KoriBus
import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL

/** Optional: pulls summaries of calls answered by your own cloud call agent (see backend/). Call from a background thread. */
object CloudSync {
    fun pull(ctx: Context): String {
        val prefs = Prefs(ctx)
        val secrets = SecretStore(ctx)
        val url = prefs.cloudUrl
        val token = secrets.get("cloud_token") ?: ""
        if (url.isBlank() || token.isBlank()) return "Add the server address and token first."
        return try {
            val c = URL("$url/notes?since=${prefs.cloudLastId}").openConnection() as HttpURLConnection
            c.connectTimeout = 10000
            c.readTimeout = 15000
            c.setRequestProperty("Authorization", "Bearer $token")
            val code = c.responseCode
            if (code != 200) return "Server answered HTTP $code"
            val arr = JSONArray(c.inputStream.bufferedReader().use { it.readText() })
            c.disconnect()
            var last = prefs.cloudLastId
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                CallNotes.add(secrets, o.optString("who", "Unknown") + " (AI call)", o.optString("summary"), o.optString("transcript"))
                last = maxOf(last, o.optLong("id"))
            }
            prefs.cloudLastId = last
            if (arr.length() > 0) KoriBus.notesTick++
            "Synced ${arr.length()} call note(s)."
        } catch (e: Exception) {
            "Sync failed: ${e.message}"
        }
    }
}
