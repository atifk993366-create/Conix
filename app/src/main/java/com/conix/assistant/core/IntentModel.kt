package com.conix.assistant.core

import android.content.Context
import android.content.Intent
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/**
 * Tiny on-device intent classifier: character n-gram TF-IDF + multinomial logistic regression,
 * exported by training/02_train_intent_classifier.py. The arithmetic here mirrors scikit-learn exactly.
 */
class IntentModel(json: String) {
    val labels: List<String>
    val version: String
    val dataset: String
    val threshold: Float
    private val vocab = HashMap<String, Int>()
    private val idf: FloatArray
    private val coef: Array<FloatArray>
    private val intercept: FloatArray
    private val lo: Int
    private val hi: Int

    init {
        val o = JSONObject(json)
        version = o.optString("version")
        dataset = o.optString("dataset")
        threshold = o.optDouble("threshold", 0.9).toFloat()
        lo = o.optInt("ngram_min", 2)
        hi = o.optInt("ngram_max", 4)
        val la = o.getJSONArray("labels")
        labels = List(la.length()) { la.getString(it) }
        val v = o.getJSONObject("vocab")
        val keys = v.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            vocab[k] = v.getInt(k)
        }
        val ia = o.getJSONArray("idf")
        idf = FloatArray(ia.length()) { ia.getDouble(it).toFloat() }
        val ca = o.getJSONArray("coef")
        coef = Array(ca.length()) { r ->
            val row = ca.getJSONArray(r)
            FloatArray(row.length()) { c -> row.getDouble(c).toFloat() }
        }
        val ba = o.getJSONArray("intercept")
        intercept = FloatArray(ba.length()) { ba.getDouble(it).toFloat() }
    }

    /** Must stay identical to norm() in the training script. */
    private fun normalize(t: String): String {
        val sb = StringBuilder(t.length)
        for (ch in t.lowercase()) sb.append(if (Character.isDigit(ch)) '0' else ch)
        return sb.toString()
    }

    private fun grams(text: String): List<String> {
        val out = ArrayList<String>()
        for (word in text.split(Regex("\\s+"))) {
            if (word.isEmpty()) continue
            val w = " $word "
            for (n in lo..hi) {
                var off = 0
                out.add(w.substring(off, minOf(off + n, w.length)))
                while (off + n < w.length) {
                    off++
                    out.add(w.substring(off, off + n))
                }
                if (off == 0) break
            }
        }
        return out
    }

    /** Returns the best label and its probability. */
    fun predict(text: String): Pair<String, Float> {
        val counts = HashMap<Int, Int>()
        for (g in grams(normalize(text))) {
            val i = vocab[g] ?: continue
            counts[i] = (counts[i] ?: 0) + 1
        }
        if (counts.isEmpty()) return Pair("none", 0f)
        val x = HashMap<Int, Float>()
        var sq = 0.0
        for ((i, c) in counts) {
            val v = c * idf[i]
            x[i] = v
            sq += (v * v).toDouble()
        }
        val norm = Math.sqrt(sq).toFloat()
        if (norm > 0f) for (k in x.keys.toList()) x[k] = (x[k] ?: 0f) / norm
        val scores = FloatArray(labels.size)
        for (k in labels.indices) {
            var s = intercept[k]
            val row = coef[k]
            for ((i, v) in x) s += v * row[i]
            scores[k] = s
        }
        val mx = scores.maxOrNull() ?: 0f
        val ex = DoubleArray(scores.size) { Math.exp((scores[it] - mx).toDouble()) }
        val total = ex.sum()
        var best = 0
        for (k in ex.indices) if (ex[k] > ex[best]) best = k
        return Pair(labels[best], (ex[best] / total).toFloat())
    }
}

/** Loads the intent model and turns confident predictions into actions (slots are filled by simple rules). */
class LocalIntents(private val ctx: Context) {
    var model: IntentModel? = null
        private set
    var source: String = "not loaded"
        private set
    var sha: String = ""
        private set

    private fun sha256(b: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }.take(16)

    private fun modelDirs(): List<File> = listOf(File(BackupManager.dir(), "models"), File(ctx.filesDir, "models"))

    @Synchronized
    fun ensureLoaded(): IntentModel? {
        if (model != null) return model
        for (d in modelDirs()) {
            val f = File(d, "kori_intent_model.json")
            try {
                if (f.exists()) {
                    val b = f.readBytes()
                    model = IntentModel(String(b, Charsets.UTF_8))
                    source = f.absolutePath
                    sha = sha256(b)
                    return model
                }
            } catch (_: Exception) { }
        }
        try {
            val b = ctx.assets.open("kori_intent_model.json").use { it.readBytes() }
            model = IntentModel(String(b, Charsets.UTF_8))
            source = "built-in"
            sha = sha256(b)
        } catch (e: Exception) {
            source = "failed to load: ${e.message}"
        }
        return model
    }

    @Synchronized
    fun reload() { model = null; ensureLoaded() }

    /** Saves a model or test file picked by the user (kori_intent_model.json / kori_intent_tests.json). */
    fun importFile(name: String, bytes: ByteArray): String {
        if (name != "kori_intent_model.json" && name != "kori_intent_tests.json") return "Pick kori_intent_model.json or kori_intent_tests.json."
        return try {
            if (name == "kori_intent_model.json") IntentModel(String(bytes, Charsets.UTF_8))   // validates the file
            val dir = File(ctx.filesDir, "models").apply { mkdirs() }
            File(dir, name).writeBytes(bytes)
            if (BackupManager.hasAccess()) {
                val ext = File(BackupManager.dir(), "models").apply { mkdirs() }
                File(ext, name).writeBytes(bytes)
            }
            if (name == "kori_intent_model.json") reload()
            "Imported $name."
        } catch (e: Exception) {
            "That file is not a valid Kori model: ${e.message}"
        }
    }

    /** Re-runs the fixed test sentences exported by the training script, like the PDF's Colab-vs-phone check. */
    fun selfTest(): String {
        val m = ensureLoaded() ?: return "No intent model loaded."
        val custom = modelDirs().map { File(it, "kori_intent_tests.json") }.firstOrNull { it.exists() }
        val text = try {
            custom?.readText() ?: if (source == "built-in") ctx.assets.open("kori_intent_tests.json").bufferedReader().use { it.readText() } else null
        } catch (_: Exception) { null } ?: return "No test file for this model. Import kori_intent_tests.json from the same training run."
        val arr = JSONArray(text)
        var ok = 0
        val t0 = System.nanoTime()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            if (m.predict(o.getString("text")).first == o.getString("expected")) ok++
        }
        val ms = (System.nanoTime() - t0) / 1e6 / maxOf(1, arr.length())
        return "Self-test: $ok/${arr.length()} sentences give the same answer as in training. %.2f ms per command.".format(ms)
    }

    fun route(raw: String): Pair<JSONObject, String>? {
        val m = ensureLoaded() ?: return null
        val t = raw.trim()
        if (t.isEmpty() || t.length > 80) return null
        val (label, p) = m.predict(t)
        if (p < m.threshold) return null
        val a = build(label, t) ?: return null
        return Pair(a, "")
    }

    // ---------- slot filling (only for intents that are safe to run without the cloud brain) ----------
    private fun ascii(s: String): String {
        val sb = StringBuilder()
        for (ch in s) sb.append(if (Character.isDigit(ch)) ('0' + Character.digit(ch, 10)) else ch)
        return sb.toString()
    }

    private fun has(t: String, vararg w: String) = w.any { t.contains(it) }

    private fun obj(type: String, vararg kv: Pair<String, Any>): JSONObject {
        val o = JSONObject().put("type", type)
        for ((k, v) in kv) o.put(k, v)
        return o
    }

    private val aliases = mapOf(
        "यूट्यूब" to "youtube", "ୟୁଟ୍ୟୁବ୍" to "youtube", "व्हाट्सएप" to "whatsapp", "वॉट्सऐप" to "whatsapp", "ହ୍ୱାଟସଆପ୍" to "whatsapp",
        "इंस्टाग्राम" to "instagram", "ଇନଷ୍ଟାଗ୍ରାମ୍" to "instagram", "क्रोम" to "chrome", "କ୍ରୋମ୍" to "chrome",
        "स्पॉटिफाई" to "spotify", "कैमरा" to "camera", "କ୍ୟାମେରା" to "camera", "गैलरी" to "gallery", "कैलकुलेटर" to "calculator",
        "मैप्स" to "maps", "सेटिंग" to "settings", "जीमेल" to "gmail", "फेसबुक" to "facebook", "टेलीग्राम" to "telegram"
    )

    private fun appName(text: String): String? {
        for ((k, v) in aliases) if (text.contains(k)) return v
        val pm = ctx.packageManager
        val labels = pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
            .map { it.loadLabel(pm).toString() }
        return labels.filter { it.length >= 3 && text.contains(it.lowercase()) }.maxByOrNull { it.length }
    }

    private fun build(label: String, raw: String): JSONObject? {
        val t = ascii(raw.lowercase())
        val n = Regex("\\d+").find(t)?.value?.toIntOrNull()
        return when (label) {
            "timer" -> {
                val v = n ?: return null
                val secs = when {
                    has(t, "hour", "ghant", "घंट", "ଘଣ୍ଟ") -> v * 3600
                    has(t, "second", "sec", "सेकंड", "ସେକେଣ୍ଡ") -> v
                    else -> v * 60
                }
                obj("timer", "seconds" to secs, "label" to "Kori")
            }
            "alarm" -> {
                val hm = Regex("(\\d{1,2})[:.](\\d{2})").find(t)
                var h = (hm?.groupValues?.get(1)?.toIntOrNull()) ?: n ?: return null
                val mi = hm?.groupValues?.get(2)?.toIntOrNull() ?: 0
                val pm = Regex("\\bpm\\b|p\\.m").containsMatchIn(t) || has(t, "shaam", "sham", "evening", "raat", "रात", "शाम", "ସନ୍ଧ୍ୟା", "ରାତି")
                val am = Regex("\\bam\\b|a\\.m").containsMatchIn(t) || has(t, "morning", "subah", "सुबह", "ସକାଳ")
                if (pm && h < 12) h += 12
                if (am && h == 12) h = 0
                if (h !in 0..23 || mi !in 0..59) return null
                obj("alarm", "hour" to h, "minute" to mi, "label" to "Kori")
            }
            "flashlight" -> obj("flashlight", "on" to !has(t, "off", "band", "bund", "बंद", "ବନ୍ଦ", "bujha", "बुझा", "ନିଭା"))
            "volume" -> {
                val dir = when {
                    has(t, "mute", "chup", "म्यूट", "ମ୍ୟୁଟ୍") -> "mute"
                    has(t, "down", "decrease", "lower", "kam", "कम", "କମ", "dheere") -> "down"
                    has(t, "up", "increase", "louder", "badha", "बढ", "ବଢ", "tez", "zyada") -> "up"
                    else -> return null
                }
                obj("volume", "dir" to dir)
            }
            "music" -> {
                val cmd = when {
                    has(t, "previous", "back", "pichla", "पिछला", "ପୂର୍ବ") -> "previous"
                    has(t, "next", "skip", "agla", "अगला", "ପରବର୍ତ୍ତୀ", "ଆଗକୁ") -> "next"
                    has(t, "pause", "stop", "roko", "ruk", "band", "रोक", "बंद", "ବନ୍ଦ") -> "pause"
                    else -> "play"
                }
                obj("music", "cmd" to cmd)
            }
            "device_info" -> obj("device_info", "what" to if (has(t, "wifi", "internet", "network", "इंटरनेट", "ଇଣ୍ଟରନେଟ୍")) "network" else "battery")
            "open_app" -> obj("open_app", "name" to (appName(raw.lowercase()) ?: return null))
            "camera_identify" -> obj("camera_identify", "question" to "What is this thing in front of me?")
            "driving_on" -> obj("driving_on")
            "driving_off" -> obj("driving_off")
            "away_on" -> obj("away_on", "minutes" to (n ?: 10))
            "away_off" -> obj("away_off")
            "game_break" -> obj("game_break", "seconds" to 120)
            "stop_assistant" -> obj("stop_assistant")
            else -> null   // questions, chat, messages, calls ... need the cloud brain for details
        }
    }
}
