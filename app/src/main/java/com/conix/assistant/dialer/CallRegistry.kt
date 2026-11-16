package com.conix.assistant.dialer

import android.os.Handler
import android.os.Looper
import android.telecom.Call
import android.telecom.CallAudioState
import android.telecom.VideoProfile
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** The current phone call(s), shared between the dialer screens and Kori's services. */
object CallRegistry {
    @Volatile var call: Call? = null
    val calls = ArrayList<Call>()
    var callCount by mutableIntStateOf(0)
    var state by mutableIntStateOf(Call.STATE_DISCONNECTED)
    var number by mutableStateOf("")
    var name by mutableStateOf("")
    var muted by mutableStateOf(false)
    var speaker by mutableStateOf(false)
    var connectedAt by mutableLongStateOf(0L)

    @Volatile var onRinging: ((String?) -> Unit)? = null
    @Volatile var onActive: (() -> Unit)? = null
    @Volatile var onIdle: (() -> Unit)? = null

    private val main = Handler(Looper.getMainLooper())

    fun answer() { call?.answer(VideoProfile.STATE_AUDIO_ONLY) }

    fun reject() {
        val c = call ?: return
        if (c.state == Call.STATE_RINGING) c.reject(false, null) else c.disconnect()
    }

    /** Declines the ringing call and lets the phone send [text] to the caller as an SMS. */
    fun rejectWithMessage(text: String) {
        val c = call ?: return
        if (c.state == Call.STATE_RINGING) c.reject(true, text) else c.disconnect()
    }

    fun hangup() { call?.disconnect() }
    fun hold() { call?.hold() }
    fun unhold() { call?.unhold() }

    fun swap() {
        val cur = call ?: return
        val other = calls.firstOrNull { it !== cur } ?: return
        cur.hold()
        other.unhold()
        call = other
        KoriInCallService.instance?.refresh(other)
    }

    fun playDtmf(c: Char) {
        val cur = call ?: return
        cur.playDtmfTone(c)
        main.postDelayed({ cur.stopDtmfTone() }, 160)
    }

    /** Only the phone app (InCallService) is allowed to change the call's audio route. */
    fun routeSpeaker(on: Boolean) {
        KoriInCallService.instance?.setAudioRoute(if (on) CallAudioState.ROUTE_SPEAKER else CallAudioState.ROUTE_EARPIECE)
    }

    fun muteCall(m: Boolean) { KoriInCallService.instance?.setMuted(m) }
}
