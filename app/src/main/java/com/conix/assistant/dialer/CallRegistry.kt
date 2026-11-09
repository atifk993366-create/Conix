package com.conix.assistant.dialer

import android.telecom.Call
import android.telecom.CallAudioState
import android.telecom.VideoProfile
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** The current phone call, shared between the dialer screens and Kori's voice service. */
object CallRegistry {
    @Volatile var call: Call? = null
    var state by mutableIntStateOf(Call.STATE_DISCONNECTED)
    var number by mutableStateOf("")
    var name by mutableStateOf("")
    var muted by mutableStateOf(false)
    var speaker by mutableStateOf(false)
    var connectedAt by mutableLongStateOf(0L)

    @Volatile var onRinging: ((String?) -> Unit)? = null
    @Volatile var onActive: (() -> Unit)? = null
    @Volatile var onIdle: (() -> Unit)? = null

    fun answer() { call?.answer(VideoProfile.STATE_AUDIO_ONLY) }

    fun reject() {
        val c = call ?: return
        if (c.state == Call.STATE_RINGING) c.reject(false, null) else c.disconnect()
    }

    fun hangup() { call?.disconnect() }

    /** Only the phone app (InCallService) is allowed to change the call's audio route. */
    fun routeSpeaker(on: Boolean) {
        KoriInCallService.instance?.setAudioRoute(if (on) CallAudioState.ROUTE_SPEAKER else CallAudioState.ROUTE_EARPIECE)
    }

    fun muteCall(m: Boolean) { KoriInCallService.instance?.setMuted(m) }
}
