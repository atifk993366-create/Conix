package com.conix.assistant.core

import android.Manifest
import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.provider.AlarmClock
import android.provider.CalendarContract
import android.provider.ContactsContract
import android.provider.Settings
import android.telecom.TelecomManager
import android.telephony.SmsManager
import android.view.KeyEvent
import androidx.core.content.ContextCompat
import com.conix.assistant.KoriBus
import com.conix.assistant.PendingAction
import com.conix.assistant.access.KoriAccessibility
import com.conix.assistant.brain.Brain
import com.conix.assistant.notif.KoriNotificationListener
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.net.URLEncoder
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * Executes actions. Safety levels: 0 = low (call, WhatsApp draft), 1 = sends a message,
 * 2 = reads private data. "smooth" mode asks the least; "strict" always confirms + unlocks.
 */
class ActionExecutor(private val ctx: Context, private val brain: Brain, private val prefs: Prefs) {
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var interactive = true

    fun execute(a: JSONObject?, interactive: Boolean = true): String {
        if (a == null) return ""
        this.interactive = interactive
        return try {
            when (a.optString("type")) {
                "open_app" -> openApp(a.optString("name"))
                "open_url" -> {
                    val u = a.optString("url")
                    if (u.startsWith("https://") || u.startsWith("http://")) { view(u); "" } else "I can only open web links."
                }
                "web_search" -> { view("https://www.google.com/search?q=" + enc(a.optString("query"))); "" }
                "youtube" -> youtube(a)
                "alarm" -> alarm(a)
                "timer" -> timer(a)
                "calendar" -> calendar(a)
                "music" -> music(a.optString("cmd"))
                "volume" -> volume(a.optString("dir"))
                "flashlight" -> flashlight(a.optBoolean("on", true))
                "settings" -> settingsPanel(a.optString("panel"))
                "device_info" -> deviceInfo(a.optString("what"))
                "call" -> call(a.optString("to"))
                "sms" -> sms(a.optString("to"), a.optString("text"))
                "whatsapp" -> whatsapp(a.optString("to"), a.optString("text"))
                "wa_reply" -> waReply(a.optString("to"), a.optString("text"))
                "email" -> email(a)
                "read_notifications" -> gate("Read notifications", "Kori will read your recent notifications.", 2) { KoriNotificationListener.summary() }
                "read_whatsapp" -> gate("Read WhatsApp messages", "Kori will read your new WhatsApp messages.", 2) { KoriNotificationListener.waSummary() }
                "read_sms" -> gate("Read messages", "Kori will read your latest SMS messages.", 2) { readSms() }
                "answer_call" -> callControl(true)
                "reject_call" -> callControl(false)
                "shop" -> shop(a.optString("query"), a.optString("site"))
                "remember" -> { brain.addFact(a.optString("text")); "Saved to memory." }
                else -> ""
            }
        } catch (e: Exception) {
            "Action failed: ${e.message}"
        }
    }

    // ---------- helpers ----------
    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
    private fun view(url: String) = Notifs.launch(ctx, Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    private fun granted(p: String) = ContextCompat.checkSelfPermission(ctx, p) == PackageManager.PERMISSION_GRANTED

    private fun gate(title: String, detail: String, level: Int, run: () -> String): String {
        val strict = prefs.safety == "strict"
        if (!strict) {
            if (level == 0) return run()
            if (level == 1 && interactive) return run()
            if (level == 2) {
                val km = ctx.getSystemService(KeyguardManager::class.java)
                return if (km.isKeyguardLocked) "Please unlock your phone first." else run()
            }
        }
        KoriBus.pending = PendingAction(title, detail, strict, run)
        return "Waiting for your confirmation."
    }

    // ---------- apps / web ----------
    private fun openApp(name: String): String {
        val pm = ctx.packageManager
        val blocked = prefs.blockedApps
        val list = pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
            .filter { it.activityInfo.packageName !in blocked }
        val n = name.trim().lowercase()
        val hit = list.firstOrNull { it.loadLabel(pm).toString().lowercase() == n }
            ?: list.firstOrNull { it.loadLabel(pm).toString().lowercase().contains(n) }
            ?: return "I couldn't find an app called $name."
        val i = pm.getLaunchIntentForPackage(hit.activityInfo.packageName) ?: return "I couldn't open $name."
        Notifs.launch(ctx, i)
        return "Opening ${hit.loadLabel(pm)}."
    }

    private fun youtube(a: JSONObject): String {
        val term = a.optString("query")
        val subscribe = a.optBoolean("subscribe", false)
        val like = a.optBoolean("like", false)
        val play = a.optBoolean("play_first", false)
        val channel = a.optBoolean("channel", false) || subscribe
        view("https://www.youtube.com/results?search_query=" + enc(term) + if (channel) "&sp=EgIQAg%253D%253D" else "")
        if (!(subscribe || like || play)) return ""
        if (KoriAccessibility.instance == null) {
            return "To tap Subscribe / Like / Play for you, enable Kori in Accessibility settings. Until then, tap it yourself."
        }
        scope.launch {
            delay(4500)
            if (subscribe) tryClick { it.startsWith("subscribe") && !it.startsWith("subscribed") }
            if (play) tryClick { it.contains("play video") }
            if (like) {
                delay(if (play) 7000 else 1000)
                tryClick { it.contains("like this video") }
            }
        }
        return "Working on it in YouTube..."
    }

    private suspend fun tryClick(match: (String) -> Boolean) {
        repeat(8) {
            val ok = KoriAccessibility.instance?.clickMatching(match) ?: false
            if (ok) return
            delay(1500)
        }
    }

    private fun shop(query: String, site: String): String {
        val q = enc(query)
        val amazon = "https://www.amazon.in/s?k=$q"
        val flipkart = "https://www.flipkart.com/search?q=$q"
        val meesho = "https://www.meesho.com/search?q=$q"
        return when (site.lowercase()) {
            "flipkart" -> { view(flipkart); "" }
            "meesho" -> { view(meesho); "" }
            "all" -> { view(amazon); "Opened Amazon. Compare on Flipkart: $flipkart\nMeesho: $meesho" }
            else -> { view(amazon); "" }
        }
    }

    // ---------- device ----------
    private fun alarm(a: JSONObject): String {
        val h = a.optInt("hour", -1)
        val m = a.optInt("minute", 0)
        if (h !in 0..23 || m !in 0..59) return "I need a valid time for the alarm."
        val i = Intent(AlarmClock.ACTION_SET_ALARM)
            .putExtra(AlarmClock.EXTRA_HOUR, h).putExtra(AlarmClock.EXTRA_MINUTES, m)
            .putExtra(AlarmClock.EXTRA_MESSAGE, a.optString("label", "Kori"))
            .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
        Notifs.launch(ctx, i)
        return "Alarm set for %02d:%02d.".format(h, m)
    }

    private fun timer(a: JSONObject): String {
        val s = a.optInt("seconds", 0)
        if (s <= 0) return "I need a duration for the timer."
        val i = Intent(AlarmClock.ACTION_SET_TIMER)
            .putExtra(AlarmClock.EXTRA_LENGTH, s)
            .putExtra(AlarmClock.EXTRA_MESSAGE, a.optString("label", "Kori"))
            .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
        Notifs.launch(ctx, i)
        return "Timer started for ${s / 60} min ${s % 60} s."
    }

    private fun calendar(a: JSONObject): String {
        val start = LocalDateTime.parse(a.optString("start")).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val i = Intent(Intent.ACTION_INSERT).setData(CalendarContract.Events.CONTENT_URI)
            .putExtra(CalendarContract.Events.TITLE, a.optString("title"))
            .putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, start)
            .putExtra(CalendarContract.EXTRA_EVENT_END_TIME, start + 3600_000L)
        Notifs.launch(ctx, i)
        return "I opened the calendar entry - tap Save."
    }

    private fun music(cmd: String): String {
        val code = when (cmd.lowercase()) {
            "play" -> KeyEvent.KEYCODE_MEDIA_PLAY
            "pause" -> KeyEvent.KEYCODE_MEDIA_PAUSE
            "next" -> KeyEvent.KEYCODE_MEDIA_NEXT
            "previous" -> KeyEvent.KEYCODE_MEDIA_PREVIOUS
            else -> return "I didn't understand that music command."
        }
        val am = ctx.getSystemService(AudioManager::class.java)
        am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
        am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
        return "Done."
    }

    private fun volume(dir: String): String {
        val am = ctx.getSystemService(AudioManager::class.java)
        val d = when (dir.lowercase()) {
            "up" -> AudioManager.ADJUST_RAISE
            "down" -> AudioManager.ADJUST_LOWER
            "mute" -> AudioManager.ADJUST_MUTE
            else -> return "Say volume up, down or mute."
        }
        am.adjustStreamVolume(AudioManager.STREAM_MUSIC, d, AudioManager.FLAG_SHOW_UI)
        return "Done."
    }

    private fun flashlight(on: Boolean): String {
        val cm = ctx.getSystemService(CameraManager::class.java)
        val id = cm.cameraIdList.firstOrNull { cm.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true }
            ?: return "This phone has no flashlight."
        cm.setTorchMode(id, on)
        return if (on) "Flashlight on." else "Flashlight off."
    }

    private fun settingsPanel(panel: String): String {
        val action = when (panel.lowercase()) {
            "wifi" -> Settings.ACTION_WIFI_SETTINGS
            "bluetooth" -> Settings.ACTION_BLUETOOTH_SETTINGS
            "display" -> Settings.ACTION_DISPLAY_SETTINGS
            "sound" -> Settings.ACTION_SOUND_SETTINGS
            "battery" -> Settings.ACTION_BATTERY_SAVER_SETTINGS
            "internet" -> Settings.Panel.ACTION_INTERNET_CONNECTIVITY
            else -> Settings.ACTION_SETTINGS
        }
        Notifs.launch(ctx, Intent(action))
        return "Android doesn't let apps flip these switches directly, so I opened the panel."
    }

    private fun deviceInfo(what: String): String {
        val sb = StringBuilder()
        if (what.lowercase() != "network") {
            val i = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            val level = i?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
            val scale = i?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
            val st = i?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
            val charging = st == BatteryManager.BATTERY_STATUS_CHARGING || st == BatteryManager.BATTERY_STATUS_FULL
            if (level >= 0) sb.append("Battery ${level * 100 / scale}%").append(if (charging) ", charging. " else ". ")
        }
        if (what.lowercase() != "battery") {
            val cm = ctx.getSystemService(ConnectivityManager::class.java)
            val caps = cm.getNetworkCapabilities(cm.activeNetwork)
            sb.append(
                when {
                    caps == null -> "No internet connection."
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Connected via Wi-Fi."
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "Connected via mobile data."
                    else -> "Connected to the internet."
                }
            )
        }
        return sb.toString().trim()
    }

    // ---------- communication ----------
    private fun lookup(to: String): Pair<String, String>? {
        val t = to.trim()
        if (t.isNotEmpty() && t.none { it.isLetter() } && t.count { it.isDigit() } >= 7) return t to t.filter { it.isDigit() || it == '+' }
        if (!granted(Manifest.permission.READ_CONTACTS)) {
            throw IllegalStateException("Contacts permission is not granted. Settings > Contacts, calls, SMS.")
        }
        val c = ctx.contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME, ContactsContract.CommonDataKinds.Phone.NUMBER),
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " LIKE ?", arrayOf("%$t%"), null
        )
        c?.use { if (it.moveToFirst()) return it.getString(0) to it.getString(1) }
        return null
    }

    private fun call(to: String): String {
        val (name, number) = lookup(to) ?: return "I couldn't find \"$to\" in your contacts."
        return gate("Call $name?", "Number: $number", 0) {
            if (granted(Manifest.permission.CALL_PHONE)) {
                try {
                    ctx.getSystemService(TelecomManager::class.java).placeCall(Uri.parse("tel:$number"), Bundle())
                } catch (e: Exception) {
                    Notifs.launch(ctx, Intent(Intent.ACTION_CALL, Uri.parse("tel:$number")))
                }
                "Calling $name."
            } else {
                Notifs.launch(ctx, Intent(Intent.ACTION_DIAL, Uri.parse("tel:$number")))
                "Opened the dialer for $name (Call permission not granted)."
            }
        }
    }

    private fun sms(to: String, text: String): String {
        if (text.isBlank()) return "What should the message say?"
        val (name, number) = lookup(to) ?: return "I couldn't find \"$to\" in your contacts."
        return gate("Send SMS to $name?", "To: $number\n\"$text\"", 1) {
            if (granted(Manifest.permission.SEND_SMS)) {
                val sm = if (Build.VERSION.SDK_INT >= 31) ctx.getSystemService(SmsManager::class.java)
                else @Suppress("DEPRECATION") SmsManager.getDefault()
                sm.sendMultipartTextMessage(number, null, sm.divideMessage(text), null, null)
                "SMS sent to $name."
            } else {
                Notifs.launch(ctx, Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:$number")).putExtra("sms_body", text))
                "Opened the SMS app with your message (SMS permission not granted) - tap send."
            }
        }
    }

    private fun whatsapp(to: String, text: String): String {
        if ("com.whatsapp" in prefs.blockedApps) return "WhatsApp is on your excluded apps list."
        val (name, number) = lookup(to) ?: return "I couldn't find \"$to\" in your contacts."
        var digits = number.filter { it.isDigit() }
        if (digits.length == 10) digits = "91$digits"   // assumes Indian numbers saved without country code
        return gate("WhatsApp to $name?", "To: +$digits\n\"$text\"", 1) {
            // Only the standard WhatsApp (never Business or clones)
            val i = Intent(Intent.ACTION_VIEW, Uri.parse("https://wa.me/$digits?text=" + enc(text))).setPackage("com.whatsapp")
            Notifs.launch(ctx, i)
            if (prefs.autoSendWa && KoriAccessibility.instance != null) {
                scope.launch { delay(2800); tryClick { it == "send" || it.startsWith("send ") } }
                "Sending to $name on WhatsApp..."
            } else {
                "WhatsApp opened for $name - tap send."
            }
        }
    }

    private fun waReply(to: String, text: String): String {
        if (text.isBlank()) return "What should the reply say?"
        val sender = to.ifBlank { KoriNotificationListener.lastWaSender() ?: "" }
        if (sender.isBlank()) return "I don't have a recent WhatsApp message to reply to."
        return gate("Reply to $sender on WhatsApp?", "\"$text\"", 1) {
            if (KoriNotificationListener.reply(ctx, sender, text)) "Reply sent to $sender."
            else "I couldn't reply directly (the notification is gone). Open the chat and send it."
        }
    }

    private fun email(a: JSONObject): String {
        val i = Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:" + a.optString("to")))
            .putExtra(Intent.EXTRA_SUBJECT, a.optString("subject"))
            .putExtra(Intent.EXTRA_TEXT, a.optString("body"))
        Notifs.launch(ctx, i)
        return "I opened an email draft - review it and tap send."
    }

    private fun readSms(): String {
        if (!granted(Manifest.permission.READ_SMS)) return "SMS permission is not granted."
        val c = ctx.contentResolver.query(Uri.parse("content://sms/inbox"), arrayOf("address", "body"), null, null, "date DESC")
            ?: return "Could not read messages."
        val out = ArrayList<String>()
        c.use { while (it.moveToNext() && out.size < 5) out.add(it.getString(0) + ": " + it.getString(1)) }
        return if (out.isEmpty()) "No messages found." else out.joinToString("\n")
    }

    /** Answer or reject the ringing call (needs the "Answer phone calls" permission). */
    fun callControl(answer: Boolean): String {
        if (!granted(Manifest.permission.ANSWER_PHONE_CALLS)) {
            return "Answer-calls permission is not granted (Settings > Contacts, calls, SMS)."
        }
        val tm = ctx.getSystemService(TelecomManager::class.java)
        return try {
            if (answer) {
                tm.acceptRingingCall(); "Answering."
            } else if (Build.VERSION.SDK_INT >= 28) {
                tm.endCall(); "Call rejected."
            } else {
                "Rejecting calls needs Android 9 or newer."
            }
        } catch (e: SecurityException) {
            "Android blocked call control: ${e.message}"
        }
    }
}
