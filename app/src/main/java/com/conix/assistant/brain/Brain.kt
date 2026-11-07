package com.conix.assistant.brain

import android.util.Base64
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

    private fun key(): String = secrets.get("key_" + prefs.provider) ?: throw IllegalStateException("No API key saved")

    private fun langName() = when (prefs.langTag) { "hi-IN" -> "Hindi"; "or-IN" -> "Odia"; else -> "English" }

    /** Normal turn (JSON reply + action) or, with [research], a web-grounded answer. */
    suspend fun ask(user: String, research: Boolean): BrainResult = withContext(Dispatchers.IO) {
        val k = key()
        val result = if (prefs.provider == "claude") askClaude(k, user) else askGemini(k, user, research)
        history.add("user" to user)
        history.add("assistant" to result.reply)
        while (history.size > 16) history.removeAt(0)
        result
    }

    /** One-off plain-text completion (translation, summaries). */
    suspend fun complete(system: String, user: String): String = withContext(Dispatchers.IO) {
        val k = key()
        if (prefs.provider == "claude") {
            claudeCall(k, system, JSONArray().put(JSONObject().put("role", "user").put("content", user)))
        } else {
            val body = JSONObject().put("systemInstruction", sysObj(system)).put("contents", JSONArray().put(turn("user", user)))
            geminiCall(k, body).first
        }
    }

    /** Identify what is in a photo, verify with web search (Gemini) and add safety notes. */
    suspend fun describeImage(jpeg: ByteArray, question: String): BrainResult = withContext(Dispatchers.IO) {
        val k = key()
        val b64 = Base64.encodeToString(jpeg, Base64.NO_WRAP)
        val prompt = "You are Kori. The user points their phone camera at something and asks: \"$question\". " +
            "1) Identify the object, plant, animal, food or device as specifically as you can (use any visible text, brand or model). " +
            "2) Verify with web search and add the most useful facts: what it is, what it is used for, key details. " +
            "3) End with a line starting \"Safety:\" giving practical precautions (hazards, never eat unknown plants, mushrooms or pills, electrical or chemical risks, keep away from children and pets). If it looks risky or you are unsure, say to ask an expert. " +
            "Never identify real people from their faces; describe them generally. If the photo is unclear, say what would help. " +
            "Reply in ${langName()}, plain text, at most 120 words."
        if (prefs.provider == "claude") {
            val content = JSONArray()
                .put(JSONObject().put("type", "image").put("source", JSONObject().put("type", "base64").put("media_type", "image/jpeg").put("data", b64)))
                .put(JSONObject().put("type", "text").put("text", question))
            val text = claudeCall(k, prompt, JSONArray().put(JSONObject().put("role", "user").put("content", content)))
            BrainResult(text, null)
        } else {
            val parts = JSONArray()
                .put(JSONObject().put("inline_data", JSONObject().put("mime_type", "image/jpeg").put("data", b64)))
                .put(JSONObject().put("text", question))
            val body = JSONObject()
                .put("systemInstruction", sysObj(prompt))
                .put("contents", JSONArray().put(JSONObject().put("role", "user").put("parts", parts)))
                .put("tools", JSONArray().put(JSONObject().put("google_search", JSONObject())))
            val (text, sources) = geminiCall(k, body, 40000)
            BrainResult(text, null, sources)
        }
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
        val researchLine = if (prefs.provider == "gemini")
            "{\"type\":\"research\",\"query\":\"...\"}   (use for questions that need current or factual web information: news, prices, people, events, specs, how-to)\n" else ""
        return """
You are Kori, a personal AI assistant running on the user's Android phone. Current date/time: $now.
Reply in the same language and script the user writes in (English, Hindi or Odia). Keep replies short and natural because they are spoken aloud.
Known facts about the user: $factText

Decide yourself whether the message is a command (do something on the phone), a question, or chat. Answer simple general-knowledge questions and chat directly with "action": null.
Respond ONLY with one JSON object: {"reply": "<what to say>", "action": <one action object or null>}
Actions:
$researchLine{"type":"camera_identify","question":"what the user wants to know about what is in front of the camera"}
{"type":"away_on","minutes":10}
{"type":"away_off"}
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
{"type":"wa_reply","to":"sender name or empty for the latest WhatsApp sender","text":"..."}
{"type":"read_whatsapp"}
{"type":"answer_call"}
{"type":"reject_call"}
{"type":"email","to":"address","subject":"...","body":"..."}
{"type":"read_notifications"}
{"type":"read_sms"}
{"type":"shop","query":"...","site":"amazon|flipkart|meesho|all"}
{"type":"remember","text":"a fact the user asked you to remember"}
Rules: never invent phone numbers or addresses. The app handles confirmation and safety itself, so just emit the action. Do not claim something is done; say you are doing it. Use camera_identify when the user asks what something in front of them is.
""".trimIndent()
    }

    private fun turn(role: String, text: String) =
        JSONObject().put("role", role).put("parts", JSONArray().put(JSONObject().put("text", text)))

    private fun sysObj(text: String) = JSONObject().put("parts", JSONArray().put(JSONObject().put("text", text)))

    private fun askGemini(key: String, user: String, research: Boolean): BrainResult {
        val contents = JSONArray()
        for ((role, t) in history) contents.put(turn(if (role == "user") "user" else "model", t))
        contents.put(turn("user", user))
        val body = JSONObject().put("systemInstruction", sysObj(system(research))).put("contents", contents)
        if (research) body.put("tools", JSONArray().put(JSONObject().put("google_search", JSONObject())))
        else body.put("generationConfig", JSONObject().put("responseMimeType", "application/json"))
        val (text, sources) = geminiCall(key, body, if (research) 40000 else 20000)
        return if (research) BrainResult(text.trim(), null, sources) else parse(text)
    }

    private fun geminiCall(key: String, body: JSONObject, readMs: Int = 20000): Pair<String, List<Pair<String, String>>> {
        val gc = body.optJSONObject("generationConfig") ?: JSONObject().also { body.put("generationConfig", it) }
        gc.put("thinkingConfig", JSONObject().put("thinkingLevel", "low"))   // faster answers
        val first = prefs.model
        val models = listOf(first, "gemini-3.8-flash", "gemini-3.6-flash").distinct()
        var lastErr = "unknown error"
        var timeouts = 0
        var firstMissing = false
        for (m in models) {
            var withThinking = true
            for (attempt in 0..1) {
                try {
                    if (!withThinking) gc.remove("thinkingConfig")
                    val (code, resp) = post(
                        "https://generativelanguage.googleapis.com/v1beta/models/$m:generateContent",
                        mapOf("x-goog-api-key" to key), body.toString(), readMs
                    )
                    if (code in 200..299) {
                        if (m != first && firstMissing) prefs.model = m
                        return parseGemini(resp)
                    }
                    val msg = errMsg(resp)
                    lastErr = "HTTP $code $msg (model $m)"
                    if (code == 400 && withThinking && msg.contains("thinking", ignoreCase = true)) { withThinking = false; continue }
                    if (code == 401 || code == 403) throw IllegalStateException(lastErr)
                    if (code == 404 && m == first) firstMissing = true
                    break
                } catch (e: java.net.SocketTimeoutException) {
                    lastErr = "timeout on $m"; timeouts++; break
                } catch (e: java.io.IOException) {
                    lastErr = "network error: ${e.message}"; break
                }
            }
            if (timeouts >= 2) break
        }
        throw IllegalStateException(lastErr)
    }

    private fun parseGemini(resp: String): Pair<String, List<Pair<String, String>>> {
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
        return Pair(sb.toString().trim(), sources)
    }

    private fun askClaude(key: String, user: String): BrainResult {
        val msgs = JSONArray()
        for ((role, t) in history) msgs.put(JSONObject().put("role", role).put("content", t))
        msgs.put(JSONObject().put("role", "user").put("content", user))
        return parse(claudeCall(key, system(false), msgs))
    }

    private fun claudeCall(key: String, system: String, messages: JSONArray): String {
        val body = JSONObject().put("model", prefs.model).put("max_tokens", 900)
            .put("system", system).put("messages", messages)
        val (code, resp) = post(
            "https://api.anthropic.com/v1/messages",
            mapOf("x-api-key" to key, "anthropic-version" to "2023-06-01"), body.toString()
        )
        if (code !in 200..299) throw IllegalStateException("HTTP $code ${errMsg(resp)}")
        val arr = JSONObject(resp).optJSONArray("content") ?: throw IllegalStateException("Empty response from model")
        val sb = StringBuilder()
        for (i in 0 until arr.length()) sb.append(arr.optJSONObject(i)?.optString("text", "") ?: "")
        return sb.toString().trim()
    }

    private fun parse(text: String): BrainResult {
        val t = text.trim()
        val s = t.indexOf('{')
        val e = t.lastIndexOf('}')
        if (s >= 0 && e > s) {
            try {
                val o = JSONObject(t.substring(s, e + 1))
                return BrainResult(o.optString("reply", ""), o.optJSONObject("action"))
            } catch (_: Exception) { }
        }
        return BrainResult(t, null)
    }

    private fun errMsg(resp: String): String = try {
        JSONObject(resp).optJSONObject("error")?.optString("message") ?: resp.take(200)
    } catch (e: Exception) { resp.take(200) }

    private fun post(url: String, headers: Map<String, String>, body: String, readMs: Int = 30000): Pair<Int, String> {
        val c = URL(url).openConnection() as HttpURLConnection
        c.requestMethod = "POST"
        c.connectTimeout = 10000
        c.readTimeout = readMs
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
