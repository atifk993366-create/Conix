package com.conix.assistant.brain

import com.conix.assistant.core.Prefs
import com.conix.assistant.core.SecretStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class BrainResult(val reply: String, val action: JSONObject?, val sources: List<Pair<String, String>> = emptyList())

class Brain(private val prefs: Prefs, private val secrets: SecretStore) {
    private val history = ArrayList<Pair<String, String>>()

    fun hasKey(): Boolean = !secrets.get("key_" + prefs.provider).isNullOrBlank()

    fun facts(): List<String> {
        val raw = secrets.get("memory") ?: return emptyList()
        return try {
            val a = JSONArray(raw)
            List(a.length()) { a.getString(it) }
        } catch (e: Exception) { emptyList() }
    }

    fun addFact(f: String) {
        val l = facts().toMutableList()
        l.add(f.take(200))
        secrets.put("memory", JSONArray(l.takeLast(50)).toString())
    }

    fun clearMemory() { secrets.remove("memory"); history.clear() }

    suspend fun ask(user: String, research: Boolean): BrainResult = withContext(Dispatchers.IO) {
        val key = secrets.get("key_" + prefs.provider) ?: throw IllegalStateException("No API key saved")
        val result = if (prefs.provider == "claude") askClaude(key, user) else askGemini(key, user, research)
        history.add("user" to user)
        history.add("assistant" to result.reply)
        while (history.size > 16) history.removeAt(0)
        result
    }

    private fun system(research: Boolean): String {
        val now = SimpleDateFormat("EEEE, d MMMM yyyy HH:mm", Locale.ENGLISH).format(Date())
        val facts = facts()
        val factText = if (facts.isEmpty()) "none" else facts.joinToString("; ")
        if (research) {
            return "You are Kori, a research assistant on the user's phone. Current date/time: $now. " +
                "Search the web, compare several sources, and answer concisely in the user's language (English, Hindi or Odia). " +
                "Mention which sources support key facts. Plain text only."
        }
        return """
You are Kori, a personal AI assistant running on the user's Android phone. Current date/time: $now.
Reply in the same language and script the user writes in (English, Hindi or Odia). Keep replies short and natural because they are spoken aloud.
Known facts about the user: $factText

Respond ONLY with one JSON object: {"reply": "<what to say>", "action": <one action object or null>}
Actions:
{"type":"open_app","name":"WhatsApp"}
{"type":"open_url","url":"https://..."}
{"type":"web_search","query":"..."}
{"type":"youtube","query":"...","channel":false,"subscribe":false,"like":false,"play_first":false}
{"type":"alarm","hour":7,"minute":30,"label":"..."}
{"type":"timer","seconds":300,"label":"..."}
{"type":"calendar","title":"...","start":"2026-11-07T18:00"}
{"type":"music","cmd":"play|pause|next|previous"}
{"type":"volume","dir":"up|down|mute"}
{"type":"flashlight","on":true}
{"type":"settings","panel":"wifi|bluetooth|display|sound|battery|internet|main"}
{"type":"device_info","what":"battery|network"}
{"type":"call","to":"contact name or number"}
{"type":"sms","to":"contact name or number","text":"..."}
{"type":"whatsapp","to":"contact name or number","text":"..."}
{"type":"email","to":"address","subject":"...","body":"..."}
{"type":"read_notifications"}
{"type":"read_sms"}
{"type":"shop","query":"...","site":"amazon|flipkart|meesho|all"}
{"type":"wa_reply","to":"sender name or empty for the latest WhatsApp sender","text":"..."}
{"type":"read_whatsapp"}
{"type":"answer_call"}
{"type":"reject_call"}
{"type":"remember","text":"a fact the user asked you to remember"}
Rules: never invent phone numbers or addresses. The app handles confirmation and safety itself for call, sms, whatsapp, wa_reply and the read_* actions, so just emit the action. Do not claim something is done; say you are doing it. Use "action": null for normal conversation.
""".trimIndent()
    }

    private fun turn(role: String, text: String) =
        JSONObject().put("role", role).put("parts", JSONArray().put(JSONObject().put("text", text)))

    private fun askGemini(key: String, user: String, research: Boolean): BrainResult {
        val contents = JSONArray()
        for ((role, t) in history) contents.put(turn(if (role == "user") "user" else "model", t))
        contents.put(turn("user", user))
        val body = JSONObject()
            .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", system(research)))))
            .put("contents", contents)
        if (research) body.put("tools", JSONArray().put(JSONObject().put("google_search", JSONObject())))
        else body.put("generationConfig", JSONObject().put("responseMimeType", "application/json"))

        val (code, resp) = post(
            "https://generativelanguage.googleapis.com/v1beta/models/${prefs.model}:generateContent",
            mapOf("x-goog-api-key" to key), body.toString()
        )
        if (code !in 200..299) throw IllegalStateException("HTTP $code ${errMsg(resp)}")
        val cand = JSONObject(resp).optJSONArray("candidates")?.optJSONObject(0)
            ?: throw IllegalStateException("Empty response from model")
        val parts = cand.optJSONObject("content")?.optJSONArray("parts")
        val sb = StringBuilder()
        if (parts != null) for (i in 0 until parts.length()) sb.append(parts.optJSONObject(i)?.optString("text", "") ?: "")
        val sources = ArrayList<Pair<String, String>>()
        val chunks = cand.optJSONObject("groundingMetadata")?.optJSONArray("groundingChunks")
        if (chunks != null) for (i in 0 until chunks.length()) {
            val w = chunks.optJSONObject(i)?.optJSONObject("web")
            if (w != null) sources.add(w.optString("title") to w.optString("uri"))
        }
        return if (research) BrainResult(sb.toString().trim(), null, sources) else parse(sb.toString())
    }

    private fun askClaude(key: String, user: String): BrainResult {
        val msgs = JSONArray()
        for ((role, t) in history) msgs.put(JSONObject().put("role", role).put("content", t))
        msgs.put(JSONObject().put("role", "user").put("content", user))
        val body = JSONObject().put("model", prefs.model).put("max_tokens", 800)
            .put("system", system(false)).put("messages", msgs)
        val (code, resp) = post(
            "https://api.anthropic.com/v1/messages",
            mapOf("x-api-key" to key, "anthropic-version" to "2023-06-01"), body.toString()
        )
        if (code !in 200..299) throw IllegalStateException("HTTP $code ${errMsg(resp)}")
        val arr = JSONObject(resp).optJSONArray("content") ?: throw IllegalStateException("Empty response from model")
        val sb = StringBuilder()
        for (i in 0 until arr.length()) sb.append(arr.optJSONObject(i)?.optString("text", "") ?: "")
        return parse(sb.toString())
    }

    private fun parse(text: String): BrainResult {
        val t = text.trim()
        val s = t.indexOf('{')
        val e = t.lastIndexOf('}')
        if (s >= 0 && e > s) {
            try {
                val o = JSONObject(t.substring(s, e + 1))
                val act = o.optJSONObject("action")
                return BrainResult(o.optString("reply", ""), act)
            } catch (_: Exception) { }
        }
        return BrainResult(t, null)
    }

    private fun errMsg(resp: String): String = try {
        JSONObject(resp).optJSONObject("error")?.optString("message") ?: resp.take(200)
    } catch (e: Exception) { resp.take(200) }

    private fun post(url: String, headers: Map<String, String>, body: String): Pair<Int, String> {
        val c = URL(url).openConnection() as HttpURLConnection
        c.requestMethod = "POST"
        c.connectTimeout = 15000
        c.readTimeout = 60000
        c.doOutput = true
        c.setRequestProperty("Content-Type", "application/json")
        for ((k, v) in headers) c.setRequestProperty(k, v)
        c.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
        val code = c.responseCode
        val stream = if (code in 200..299) c.inputStream else c.errorStream
        val text = stream?.bufferedReader()?.use { it.readText() } ?: ""
        c.disconnect()
        return Pair(code, text)
    }
}
