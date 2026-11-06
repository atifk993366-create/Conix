package com.conix.assistant.voice

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.sqrt

object Recorder {
    const val SR = 16000

    /** Records mono 16 kHz PCM16 for [seconds]. Needs RECORD_AUDIO permission. */
    @SuppressLint("MissingPermission")
    suspend fun record(seconds: Int): ShortArray = withContext(Dispatchers.IO) {
        val minBuf = AudioRecord.getMinBufferSize(SR, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val rec = AudioRecord(
            MediaRecorder.AudioSource.MIC, SR,
            AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuf, SR)
        )
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            rec.release()
            throw IllegalStateException("Microphone is not available")
        }
        val out = ShortArray(SR * seconds)
        var n = 0
        try {
            rec.startRecording()
            while (n < out.size) {
                val r = rec.read(out, n, minOf(1600, out.size - n))
                if (r <= 0) break
                n += r
            }
        } finally {
            try { rec.stop() } catch (_: Exception) { }
            rec.release()
        }
        out.copyOf(n)
    }

    /** Returns a problem description, or null if the recording is usable. */
    fun checkQuality(pcm: ShortArray, minSeconds: Int = 4): String? {
        if (pcm.size < SR * minSeconds) return "Recording was too short. Please try again."
        var sum = 0.0
        var clipped = 0
        for (s in pcm) {
            val v = s.toDouble() / 32768.0
            sum += v * v
            if (abs(s.toInt()) >= 32000) clipped++
        }
        val rms = sqrt(sum / pcm.size)
        if (rms < 0.01) return "Too quiet. Speak closer to the microphone and try again."
        if (clipped.toDouble() / pcm.size > 0.02) return "Too loud / distorted. Move the phone slightly away and try again."
        return null
    }
}
