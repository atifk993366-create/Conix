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

class Provider(val id: String, val label: String, val base: String, val textModel: String, val visionModel: String)

object Providers {
    val all = listOf(
        Provider("gemini", "Gemini (free tier)", "", "gemini-3.8-flash", "gemini-3.8-flash"),
        Provider("groq", "Groq (free)", "https://api.groq.com/openai/v1", "llama-3.3-70b-versatile", "meta-llama/llama-4-scout-17b-16e-instruct"),
        Provider("cerebras", "Cerebras (free)", "https://api.cerebras.ai/v1", "llama-3.3-70b", ""),
        Provider("openrouter", "OpenRouter (free models)", "https://openrouter.ai/api/v1", "meta-llama/llama-3.3-70b-instruct:free", "google/gemma-3-27b-it:free"),
        Provider("mistral", "Mistral (free)", "https://api.mistral.ai/v1", "mistral-small-latest", "mistral-small-latest"),
        Provider("github", "GitHub Models (free)", "https://models.github.ai/inference", "openai/gpt-4.1-mini", "openai/gpt-4.1-mini"),
        Provider("claude", "Claude (paid)", "", "claude-haiku-5-5", "claude-haiku-5-5")
    )

    fun byId(id: String): Provider = all.firstOrNull { it.id == id } ?: all[0]

    fun signup(id: String): String = when (id) {
        "gemini" -> "aistudio.google.com"
        "groq" -> "console.groq.com"
        "cerebras" -> "cloud.cerebras.ai"
        "openrouter" -> "openrouter.ai/keys"
        "mistral" -> "console.mistral.ai"
        "github" -> "github.com/settings/tokens (Models access)"
        else -> "console.anthropic.com"
    }
}

data class BrainResult(
    val reply: String,
    val action: JSONObject?,
    val sources: List<Pair<String, String>> = emptyList(),
    val provider: String = ""
)

class Brain(private val prefs: Prefs, private val secrets: SecretStore) {
    private val history = ArrayList<Pair<String, String>>()

    private fun keyOf(id: String): String? = secrets.get("key_$id")?.takeIf { it.isNotBlank() }

    fun hasKey(): Boolean = Providers.all.any { keyOf(it.id) != null }

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

    private fun langName() = when (prefs.langTag) { "hi-IN" -> "Hindi"; "or-IN" -> "Odia"; else -> "English" }

    /** Primary provider first, then every other provider that has a key. */
    private fun chain(): List<Provider> {
        val primary = Providers.byId(prefs.provider)
        return (listOf(primary) + Providers.all.filter { it.id != primary.id }).filter { keyOf(it.id) != null }
    }

    private fun <T> over(op: (Provider, String) -> T): T {
        val errs = ArrayList<String>()
        for (p in chain()) {
            val k = keyOf(p.id) ?: continue
            try { return op(p, k) } catch (e: Exception) { errs.add("${p.id}: ${e.message?.take(110)}") }
        }
        if (errs.isEmpty()) throw IllegalStateException("No API key saved")
        throw IllegalStateException(errs.joinToString(" | "))
    }

    // ---------------------------------------------------------------- public API
    suspend fun ask(user: String, research: Boolean): BrainResult = withContext(Dispatchers.IO) {
        val r = if (research) researchAnswer(user) else over { p, k -> askJson(p, k, user) }
        history.add("user" to user)
        history.add("assistant" to r.reply)
        while (history.size > 16) history.removeAt(0)
        r
    }

    suspend fun complete(system: String, user: String): String = withContext(Dispatchers.IO) {
        over { p, k -> completeOn(p, k, system, user) }
    }

    suspend fun describeImage(jpeg: ByteArray, question: String): BrainResult = withContext(Dispatchers.IO) {
        val b64 = Base64.encodeToString(jpeg, Base64.NO_WRAP)
        val prompt = "You are Kori. The user points their phone camera at something and asks: \"$question\". " +
            "1) Identify the object, plant, animal, food or device as specifically as you can (use any visible text, brand or model). " +
            "2) Add the most useful facts: what it is, what it is used for, key details (verify with web search when available). " +
            "3) End with a line starting \"Safety:\" giving practical precautions (hazards, never eat unknown plants, mushrooms or pills, electrical or chemical risks, keep away from children and pets). If it looks risky or you are unsure, say to ask an expert. " +
            "Never identify real people from their faces; describe them generally. If the photo is unclear, say what would help. " +
            "Reply in ${langName()}, plain text, at most 120 words."
        val errs = ArrayList<String>()
        for (p in chain()) {
            val k = keyOf(p.id) ?: continue
            val vm = prefs.visionOf(p.id)
            if (vm.isBlank()) continue
            try {
                return@withContext when (p.id) {
                    "gemini" -> visionGemini(k, vm, b64, question, prompt)
                    "claude" -> BrainResult(visionClaude(k, vm, b64, question, prompt), null, emptyList(), "claude")
                    else -> BrainResult(visionOpenAi(p, k, vm, b64, question, prompt), null, emptyList(), p.id)
                }
            } catch (e: Exception) {
                errs.add("${p.id}: ${e.message?.take(100)}")
            }
        }
        throw IllegalStateException(
            if (errs.isEmpty()) "No provider with image support has a key. Add Gemini, Groq, OpenRouter or Mistral." else errs.joinToString(" | ")
        )
    }

    // ---------------------------------------------------------------- prompts
    private fun system(research: Boolean): String {
        val now = SimpleDateFormat("EEEE, d MMMM yyyy HH:mm", Locale.ENGLISH).format(Date())
        val facts = facts()
        val factText = if (facts.isEmpty()) "none" else facts.joinToString("; ")
        if (research) {
            return "You are Kori, a research assistant on the user's phone. Current date/time: $now. " +
                "Answer concisely in the user's language (English, Hindi or Odia) and mention which sources support key facts. Plain text only."
        }
        return """
You are Kori, a personal AI assistant running on the user's Android phone. Current date/time: $now.
Reply in the same language and script the user writes in (English, Hindi or Odia). Keep replies short and natural because they are spoken aloud.
Known facts about the user: $factText

Decide yourself whether the message is a command (do something on the phone), a question, or chat. Answer simple general-knowledge questions and chat directly with "action": null.
Respond ONLY with one JSON object: {"reply": "<what to say>", "action": <one action object or null>}
Actions:
{"type":"research","query":"..."}   (use for questions that need current or factual web information: news, prices, people, events, specs, how-to)
{"type":"camera_identify","question":"what the user wants to know about what is in front of the camera"}
{"type":"away_on","minutes":10}
{"type":"away_off"}
{"type":"game_break","seconds":120}
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

    // ---------------------------------------------------------------- per-provider calls
    private fun askJson(p: Provider, key: String, user: String): BrainResult {
        val sys = system(false)
        val text = when (p.id) {
            "gemini" -> {
                val contents = JSONArray()
                for ((role, t) in history) contents.put(turn(if (role == "user") "user" else "model", t))
                contents.put(turn("user", user))
                val body = JSONObject().put("systemInstruction", sysObj(sys)).put("contents", contents)
                    .put("generationConfig", JSONObject().put("responseMimeType", "application/json"))
                geminiCall(key, body, 20000, prefs.modelOf("gemini"), true).first
            }
            "claude" -> {
                val msgs = JSONArray()
                for ((role, t) in history) msgs.put(JSONObject().put("role", role).put("content", t))
                msgs.put(JSONObject().put("role", "user").put("content", user))
                claudeCall(key, prefs.modelOf("claude"), sys, msgs)
            }
            else -> {
                val msgs = JSONArray().put(JSONObject().put("role", "system").put("content", sys))
                for ((role, t) in history) msgs.put(JSONObject().put("role", role).put("content", t))
                msgs.put(JSONObject().put("role", "user").put("content", user))
                openAiCall(p, key, prefs.modelOf(p.id), msgs)
            }
        }
        val r = parse(text)
        return BrainResult(r.reply, r.action, emptyList(), p.id)
    }

    private fun completeOn(p: Provider, key: String, system: String, user: String): String = when (p.id) {
        "gemini" -> {
            val body = JSONObject().put("systemInstruction", sysObj(system)).put("contents", JSONArray().put(turn("user", user)))
            geminiCall(key, body, 25000, prefs.modelOf("gemini"), true).first
        }
        "claude" -> claudeCall(key, prefs.modelOf("claude"), system, JSONArray().put(JSONObject().put("role", "user").put("content", user)))
        else -> {
            val msgs = JSONArray().put(JSONObject().put("role", "system").put("content", system))
                .put(JSONObject().put("role", "user").put("content", user))
            openAiCall(p, key, prefs.modelOf(p.id), msgs)
        }
    }

    private fun researchAnswer(q: String): BrainResult {
        val gk = keyOf("gemini")
        if (gk != null) {
            try {
                val contents = JSONArray()
                for ((role, t) in history) contents.put(turn(if (role == "user") "user" else "model", t))
                contents.put(turn("user", q))
                val body = JSONObject().put("systemInstruction", sysObj(system(true))).put("contents", contents)
                    .put("tools", JSONArray().put(JSONObject().put("google_search", JSONObject())))
                val (text, sources) = geminiCall(gk, body, 40000, prefs.modelOf("gemini"), true)
                return BrainResult(text, null, sources, "gemini")
            } catch (_: Exception) { /* quota or outage: use free retrieval below */ }
        }
        val (ctxText, sources) = WebSearch.wikipedia(q, prefs.langTag)
        val prompt = if (ctxText.isBlank()) q else
            "Question: $q\n\nSources:\n$ctxText\nAnswer concisely using the sources. If they do not contain the answer, say so and answer from your own knowledge."
        val sys = system(true)
        return over { p, k -> BrainResult(completeOn(p, k, sys, prompt), null, sources, p.id) }
    }

    // ---------------------------------------------------------------- vision
    private fun visionGemini(key: String, model: String, b64: String, question: String, prompt: String): BrainResult {
        fun body(withSearch: Boolean): JSONObject {
            val parts = JSONArray()
                .put(JSONObject().put("inline_data", JSONObject().put("mime_type", "image/jpeg").put("data", b64)))
                .put(JSONObject().put("text", question))
            val b = JSONObject().put("systemInstruction", sysObj(prompt))
                .put("contents", JSONArray().put(JSONObject().put("role", "user").put("parts", parts)))
            if (withSearch) b.put("tools", JSONArray().put(JSONObject().put("google_search", JSONObject())))
            return b
        }
        return try {
            val (t, s) = geminiCall(key, body(true), 40000, model, false)
            BrainResult(t, null, s, "gemini")
        } catch (e: Exception) {
            // search grounding has its own (smaller) quota: retry as a plain image question
            val (t, s) = geminiCall(key, body(false), 40000, model, false)
            BrainResult(t, null, s, "gemini")
        }
    }

    private fun visionClaude(key: String, model: String, b64: String, question: String, prompt: String): String {
        val content = JSONArray()
            .put(JSONObject().put("type", "image").put("source", JSONObject().put("type", "base64").put("media_type", "image/jpeg").put("data", b64)))
            .put(JSONObject().put("type", "text").put("text", question))
        return claudeCall(key, model, prompt, JSONArray().put(JSONObject().put("role", "user").put("content", content)))
    }

    private fun visionOpenAi(p: Provider, key: String, model: String, b64: String, question: String, prompt: String): String {
        val content = JSONArray()
            .put(JSONObject().put("type", "text").put("text", question))
            .put(JSONObject().put("type", "image_url").put("image_url", JSONObject().put("url", "data:image/jpeg;base64,$b64")))
        val msgs = JSONArray()
            .put(JSONObject().put("role", "system").put("content", prompt))
            .put(JSONObject().put("role", "user").put("content", content))
        return openAiCall(p, key, model, msgs, 40000)
    }

    // ---------------------------------------------------------------- HTTP clients
    private fun turn(role: String, text: String) =
        JSONObject().put("role", role).put("parts", JSONArray().put(JSONObject().put("text", text)))

    private fun sysObj(text: String) = JSONObject().put("parts", JSONArray().put(JSONObject().put("text", text)))

    private fun geminiCall(
        key: String, body: JSONObject, readMs: Int, first: String, updatePref: Boolean
    ): Pair<String, List<Pair<String, String>>> {
        val gc = body.optJSONObject("generationConfig") ?: JSONObject().also { body.put("generationConfig", it) }
        gc.put("thinkingConfig", JSONObject().put("thinkingLevel", "low"))   // faster answers
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
                        if (updatePref && m != first && firstMissing) prefs.setModelOf("gemini", m)
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

    private fun claudeCall(key: String, model: String, system: String, messages: JSONArray): String {
        val body = JSONObject().put("model", model).put("max_tokens", 900)
            .put("system", system).put("messages", messages)
        val (code, resp) = post(
            "https://api.anthropic.com/v1/messages",
            mapOf("x-api-key" to key, "anthropic-version" to "2023-06-01"), body.toString(), 30000
        )
        if (code !in 200..299) throw IllegalStateException("HTTP $code ${errMsg(resp)}")
        val arr = JSONObject(resp).optJSONArray("content") ?: throw IllegalStateException("Empty response from model")
        val sb = StringBuilder()
        for (i in 0 until arr.length()) sb.append(arr.optJSONObject(i)?.optString("text", "") ?: "")
        return sb.toString().trim()
    }

    /** Works for Groq, Cerebras, OpenRouter, Mistral, GitHub Models (OpenAI-compatible APIs). */
    private fun openAiCall(p: Provider, key: String, model: String, messages: JSONArray, readMs: Int = 25000): String {
        val body = JSONObject().put("model", model).put("messages", messages)
            .put("temperature", 0.3).put("max_tokens", 900)
        val (code, resp) = post(p.base + "/chat/completions", mapOf("Authorization" to "Bearer $key"), body.toString(), readMs)
        if (code !in 200..299) throw IllegalStateException("HTTP $code ${errMsg(resp)}")
        val content = JSONObject(resp).optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")?.opt("content")
        return if (content is String) content.trim() else ""
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
        val err = JSONObject(resp).opt("error")
        when (err) {
            is JSONObject -> err.optString("message", resp.take(200))
            is String -> err
            else -> resp.take(200)
        }
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
