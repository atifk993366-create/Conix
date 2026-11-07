package com.conix.assistant.core

import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Notes left by callers while Away mode was on (stored encrypted). */
object CallNotes {
    private const val KEY = "call_notes"

    fun add(store: SecretStore, who: String, summary: String, transcript: String) {
        val arr = load(store)
        arr.put(JSONObject().put("t", System.currentTimeMillis()).put("who", who).put("summary", summary).put("transcript", transcript))
        while (arr.length() > 30) arr.remove(0)
        store.put(KEY, arr.toString())
    }

    fun all(store: SecretStore): List<String> {
        val arr = load(store)
        val fmt = SimpleDateFormat("d MMM HH:mm", Locale.ENGLISH)
        return List(arr.length()) { i ->
            val o = arr.getJSONObject(arr.length() - 1 - i)
            val said = o.optString("transcript").take(200)
            "${fmt.format(Date(o.optLong("t")))} - ${o.optString("who")}: ${o.optString("summary")}" +
                if (said.isNotBlank()) "\n   Said: \"$said\"" else ""
        }
    }

    fun summary(store: SecretStore): String {
        val l = all(store)
        return if (l.isEmpty()) "No call notes." else "Call notes:\n" + l.take(5).joinToString("\n")
    }

    fun clear(store: SecretStore) = store.remove(KEY)

    private fun load(store: SecretStore): JSONArray =
        try { JSONArray(store.get(KEY) ?: "[]") } catch (e: Exception) { JSONArray() }
}
