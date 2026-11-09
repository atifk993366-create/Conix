package com.conix.assistant.dialer

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import com.conix.assistant.ChatLine
import com.conix.assistant.KoriBus
import com.conix.assistant.core.CallNotes
import com.conix.assistant.core.SecretStore
import org.json.JSONObject

/** Numbers Kori texted in Away mode; their replies become call notes. */
object AwayWatch {
    private const val PREF = "away_watch"
    private fun key(n: String) = n.filter { it.isDigit() }.takeLast(10)

    fun add(ctx: Context, number: String, who: String) {
        val sp = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        sp.edit().putString(key(number), JSONObject().put("who", who).put("t", System.currentTimeMillis()).toString()).apply()
    }

    fun take(ctx: Context, number: String): String? {
        val sp = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        val raw = sp.getString(key(number), null) ?: return null
        val o = try { JSONObject(raw) } catch (e: Exception) { return null }
        if (System.currentTimeMillis() - o.optLong("t") > 6 * 3600_000L) { sp.edit().remove(key(number)).apply(); return null }
        sp.edit().remove(key(number)).apply()
        return o.optString("who")
    }
}

class SmsReplyReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return
        val msgs = Telephony.Sms.Intents.getMessagesFromIntent(intent) ?: return
        for (m in msgs) {
            val from = m.originatingAddress ?: continue
            val who = AwayWatch.take(context, from) ?: continue
            val body = m.messageBody ?: ""
            CallNotes.add(SecretStore(context), who.ifBlank { from }, "Replied by SMS: $body", body)
            KoriBus.notesTick++
            KoriBus.messages.add(ChatLine("conix", "SMS reply from ${who.ifBlank { from }}: $body"))
        }
    }
}
