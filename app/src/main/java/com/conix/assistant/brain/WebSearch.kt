package com.conix.assistant.brain

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** Free, key-less retrieval used when Google Search grounding is not available. */
object WebSearch {
    fun wikipedia(q: String, langTag: String): Pair<String, List<Pair<String, String>>> {
        val lang = when (langTag) { "hi-IN" -> "hi"; "or-IN" -> "or"; else -> "en" }
        return try {
            val url = "https://$lang.wikipedia.org/w/api.php?action=query&format=json&generator=search&gsrlimit=3" +
                "&gsrsearch=" + URLEncoder.encode(q, "UTF-8") +
                "&prop=extracts&exintro=1&explaintext=1&exlimit=3&exchars=700"
            val c = URL(url).openConnection() as HttpURLConnection
            c.connectTimeout = 8000
            c.readTimeout = 8000
            c.setRequestProperty("User-Agent", "KoriAI/0.7 (personal Android assistant)")
            val text = c.inputStream.bufferedReader().use { it.readText() }
            c.disconnect()
            val pages = JSONObject(text).optJSONObject("query")?.optJSONObject("pages")
                ?: return Pair("", emptyList())
            val sb = StringBuilder()
            val src = ArrayList<Pair<String, String>>()
            val keys = pages.keys()
            while (keys.hasNext()) {
                val pg = pages.getJSONObject(keys.next())
                val ex = pg.optString("extract")
                if (ex.isNotBlank()) {
                    val title = pg.optString("title")
                    sb.append("- ").append(title).append(": ").append(ex).append("\n")
                    src.add(title to "https://$lang.wikipedia.org/?curid=${pg.optInt("pageid")}")
                }
            }
            Pair(sb.toString(), src)
        } catch (e: Exception) {
            Pair("", emptyList())
        }
    }
}
