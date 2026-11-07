package com.conix.assistant.core

import org.json.JSONObject

/** Instant local parsing of common commands, so they run without waiting for the AI. */
object FastPath {
    private val I = RegexOption.IGNORE_CASE
    private val wake = Regex("^\\s*(?:(?:hey|hi|hello|ok|okay)\\s+)?(?:kori|corey|cory|kauri|khori)\\b[\\s,.!]*", I)
    private val verbRe = Regex("(?:search(?:\\s+for)?|find|look\\s+for|play)\\s+(?:a\\s+|an\\s+|the\\s+|some\\s+)?(.+)", I)

    private fun act(type: String, vararg kv: Pair<String, Any>): JSONObject {
        val o = JSONObject().put("type", type)
        for ((k, v) in kv) o.put(k, v)
        return o
    }

    fun parse(input: String): Pair<JSONObject, String>? {
        val t = input.trim().replace(wake, "").trim().trimEnd('.', '!', '?', ' ')
        if (t.isEmpty()) return null
        val q = t.lowercase()

        // camera: "what is this thing in front of me"
        if (Regex("^(?:what(?:'s| is| are)?\\s+(?:this|that|these)(?:\\s+(?:thing|object|item))?(?:\\s+in front of me)?|what am i looking at|identify\\s+(?:this|that)(?:\\s+(?:thing|object))?|scan this|(?:ye|yeh)\\s+kya\\s+hai)$", I).containsMatchIn(t))
            return act("camera_identify", "question" to "What is this thing in front of me?") to ""
        Regex("^what(?:'s| is)?\\s+(?:this|that)\\s+(.+?)\\s+in front of me$", I).find(t)?.let {
            return act("camera_identify", "question" to "What is this ${it.groupValues[1]} in front of me?") to ""
        }

        // away mode
        Regex("^(?:i(?:'m| am)|main)\\s+(?:going to be\\s+)?(?:away|unavailable|busy)(?:\\s+for\\s+(\\d+)\\s*min\\w*)?$|^away mode(?:\\s+on)?(?:\\s+for\\s+(\\d+)\\s*min\\w*)?$", I).find(t)?.let { r ->
            val m = (r.groupValues[1].ifEmpty { r.groupValues[2] }).ifEmpty { "10" }.toInt()
            return act("away_on", "minutes" to m) to ""
        }
        if (Regex("^(?:i(?:'m| am)\\s+back|main\\s+(?:wapas\\s+)?aa\\s+gaya|away mode\\s+off)$", I).containsMatchIn(t))
            return act("away_off") to ""

        // call control
        if (Regex("^(?:answer|pick up|receive|accept)(?:\\s+(?:the|this))?\\s+call$|^call\\s+(?:utha|uthao|utha lo)$", I).containsMatchIn(t))
            return act("answer_call") to ""
        if (Regex("^(?:reject|decline|cut|end|hang up|disconnect)(?:\\s+(?:the|this))?\\s+call$|^call\\s+(?:kaat|kaat do|cut karo|cut kar do)$", I).containsMatchIn(t))
            return act("reject_call") to ""

        // music
        Regex("^(pause|resume|play|next|skip|previous|stop)(?:\\s+the)?\\s+(?:music|song|track)$", I).find(t)?.let {
            return act("music", "cmd" to musicCmd(it.groupValues[1])) to ""
        }
        Regex("^(pause|next|previous|skip)$", I).find(t)?.let {
            return act("music", "cmd" to musicCmd(it.groupValues[1])) to ""
        }

        yt(t, q)?.let { return it }

        // call
        Regex("^(?:call|dial|phone)\\s+(.+)$", I).find(t)?.let { return act("call", "to" to it.groupValues[1].trim()) to "" }
        Regex("^(.+?)\\s+ko\\s+(?:call|phone|fone)(?:\\s+(?:karo|kar do|lagao|laga do))?$", I).find(t)?.let {
            return act("call", "to" to it.groupValues[1].trim()) to ""
        }

        // messages
        Regex("^(?:send\\s+)?(?:a\\s+)?(whatsapp|sms|text)?\\s*(?:message|msg)\\s+to\\s+(.+?)\\s+(?:saying|that says|that|:)\\s+(.+)$", I).find(t)?.let {
            return msg(it.groupValues[1], it.groupValues[2], it.groupValues[3]) to ""
        }
        Regex("^(whatsapp|sms|text)\\s+(.+?)\\s+(?:saying|that|:)\\s+(.+)$", I).find(t)?.let {
            return msg(it.groupValues[1], it.groupValues[2], it.groupValues[3]) to ""
        }
        Regex("^(.+?)\\s+ko\\s+(?:(whatsapp|sms|text)\\s+)?(?:message\\s+|msg\\s+)?(?:bhejo|bhej do|karo|kar do)\\s+(?:ki\\s+)?(.+)$", I).find(t)?.let {
            return msg(it.groupValues[2], it.groupValues[1], it.groupValues[3]) to ""
        }
        Regex("^reply\\s+(?:to\\s+)?(.+?)\\s+(?:saying|with|:)\\s+(.+)$", I).find(t)?.let {
            return act("wa_reply", "to" to it.groupValues[1].trim(), "text" to it.groupValues[2].trim()) to ""
        }
        Regex("^reply\\s+(.+)$", I).find(t)?.let {
            return act("wa_reply", "to" to "", "text" to it.groupValues[1].trim()) to ""
        }

        // reading
        if (Regex("\\b(read|check|padho|padh)\\b", I).containsMatchIn(t)) {
            if (q.contains("notification")) return act("read_notifications") to ""
            if (q.contains("sms")) return act("read_sms") to ""
            if (q.contains("whatsapp") || q.contains("message")) return act("read_whatsapp") to ""
        }

        // alarm / timer
        Regex("(?:alarm\\s+(?:for|at)|wake me(?:\\s+up)?\\s+at)\\s+(\\d{1,2})(?::(\\d{2}))?\\s*(a\\.?m\\.?|p\\.?m\\.?)?", I).find(t)?.let { r ->
            var h = r.groupValues[1].toInt()
            val mi = r.groupValues[2].ifEmpty { "0" }.toInt()
            val ap = r.groupValues[3].lowercase()
            if (ap.startsWith("p") && h < 12) h += 12
            if (ap.startsWith("a") && h == 12) h = 0
            return act("alarm", "hour" to h, "minute" to mi, "label" to "Kori") to ""
        }
        Regex("timer\\s+(?:for\\s+)?(\\d+)\\s*(sec\\w*|min\\w*|hour\\w*|hr\\w*)", I).find(t)?.let { r ->
            val n = r.groupValues[1].toInt()
            val u = r.groupValues[2].lowercase()
            val secs = if (u.startsWith("sec")) n else if (u.startsWith("min")) n * 60 else n * 3600
            return act("timer", "seconds" to secs, "label" to "Kori") to ""
        }

        // device
        if (q.contains("flashlight") || q.contains("torch")) {
            val off = q.contains(" off") || q.contains("band") || q.contains("bund")
            return act("flashlight", "on" to !off) to ""
        }
        if (q.contains("volume") || q == "mute") {
            val dir = when {
                q.contains("mute") -> "mute"
                q.contains("up") || q.contains("increase") || q.contains("badhao") -> "up"
                q.contains("down") || q.contains("decrease") || q.contains("kam") -> "down"
                else -> ""
            }
            if (dir.isNotEmpty()) return act("volume", "dir" to dir) to ""
        }
        if (q.contains("battery") && !q.contains("saver")) return act("device_info", "what" to "battery") to ""

        // shopping
        Regex("^(?:search|find|buy|look for)\\s+(.+?)\\s+on\\s+(amazon|flipkart|meesho)$", I).find(t)?.let {
            return act("shop", "query" to it.groupValues[1].trim(), "site" to it.groupValues[2].lowercase()) to ""
        }

        // open app
        Regex("^(?:open|launch|start)\\s+(?:the\\s+)?(.+?)(?:\\s+app)?$", I).find(t)?.let { r ->
            val name = r.groupValues[1].trim()
            if (!Regex("\\b(and|then)\\b|,", I).containsMatchIn(name)) return act("open_app", "name" to name) to ""
        }
        Regex("^(.+?)\\s+(?:kholo|khol do|khol)$", I).find(t)?.let {
            return act("open_app", "name" to it.groupValues[1].trim()) to ""
        }
        return null
    }

    private fun musicCmd(w: String) = when (w.lowercase()) {
        "pause", "stop" -> "pause"
        "next", "skip" -> "next"
        "previous" -> "previous"
        else -> "play"
    }

    private fun msg(channel: String, to: String, text: String): JSONObject {
        val sms = channel.lowercase() == "sms" || channel.lowercase() == "text"
        return act(if (sms) "sms" else "whatsapp", "to" to to.trim(), "text" to text.trim())
    }

    private fun yt(t: String, q: String): Pair<JSONObject, String>? {
        if (!(q.contains("youtube") || q.startsWith("play "))) return null
        val m = verbRe.find(t) ?: return null
        var term = m.groupValues[1]
        term = term.replace(Regex("\\s*(?:,|\\band\\b|\\bthen\\b)\\s*(?:subscribe|like|open|play)\\b.*$", I), "")
        term = term.replace(Regex("\\s+on\\s+youtube$", I), "").replace(Regex("\\s+channel$", I), "").trim(' ', ',', '.')
        if (term.isEmpty()) return null
        val subscribe = q.contains("subscribe")
        val channel = q.contains("channel") || subscribe
        val like = Regex("\\b(like it|like the video|like this|hit like)\\b").containsMatchIn(q)
        val play = Regex("\\bplay\\b").containsMatchIn(q) && !channel
        return act(
            "youtube", "query" to term, "channel" to channel,
            "subscribe" to subscribe, "like" to like, "play_first" to play
        ) to "Searching YouTube for \"$term\"."
    }
}
