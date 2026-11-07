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

    /** Cuts leading/trailing silence so the embedding is based on speech only. */
    fun trimSilence(pcm: ShortArray): ShortArray {
        val frame = SR / 50
        val n = pcm.size / frame
        if (n < 5) return pcm
        val rms = DoubleArray(n) { f ->
            var s = 0.0
            for (i in 0 until frame) { val v = pcm[f * frame + i].toDouble(); s += v * v }
            sqrt(s / frame)
        }
        val peak = rms.maxOrNull() ?: 0.0
        val thr = maxOf(150.0, peak * 0.08)
        var first = -1
        var last = -1
        for (f in 0 until n) if (rms[f] >= thr) { if (first < 0) first = f; last = f }
        if (first < 0) return ShortArray(0)
        val a = maxOf(0, first - 5) * frame
        val b = minOf(n, last + 6) * frame
        return pcm.copyOfRange(a, b)
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
