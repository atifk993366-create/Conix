package com.conix.assistant.dialer

import android.net.Uri
import android.provider.ContactsContract
import android.telecom.Call
import android.telecom.CallScreeningService
import com.conix.assistant.core.Prefs

/**
 * Android's call screening hook: allow, reject or silence a call before it rings.
 * It cannot answer or talk to the caller; that part is handled by the Away and Driving modes.
 */
class KoriCallScreening : CallScreeningService() {
    override fun onScreenCall(callDetails: Call.Details) {
        val prefs = Prefs(this)
        val num = callDetails.handle?.schemeSpecificPart ?: ""
        val key = AwayWatch.key(num)
        val b = CallResponse.Builder()
        val response = when {
            key.isNotEmpty() && key in prefs.allowlist -> b.build()
            key.isNotEmpty() && key in prefs.blocklist ->
                b.setDisallowCall(true).setRejectCall(true).setSkipNotification(true).build()
            key.isEmpty() && prefs.blockPrivate ->
                b.setDisallowCall(true).setRejectCall(true).setSkipNotification(true).build()
            prefs.silenceUnknown && key.isNotEmpty() && !isContact(num) -> b.setSilenceCall(true).build()
            else -> b.build()
        }
        respondToCall(callDetails, response)
    }

    private fun isContact(num: String): Boolean = try {
        val uri = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(num))
        contentResolver.query(uri, arrayOf(ContactsContract.PhoneLookup._ID), null, null, null)?.use { it.count > 0 } ?: true
    } catch (e: Exception) { true }   // without contacts access do not silence anybody
}
