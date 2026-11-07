package com.conix.assistant.voice

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import java.nio.FloatBuffer
import kotlin.math.sqrt

/** Runs an ONNX speaker-embedding model (downloaded into assets/speaker_model.onnx by the CI build). */
class SpeakerEmbedder(private val ctx: Context) {
    var error: String? = null
        private set
    val modelName = "CAM++ (3D-Speaker)"

    private var env: OrtEnvironment? = null
    private var session: OrtSession? = null

    @Synchronized
    fun ensureLoaded(): Boolean {
        if (session != null) return true
        return try {
            val bytes = ctx.assets.open("speaker_model.onnx").use { it.readBytes() }
            val e = OrtEnvironment.getEnvironment()
            env = e
            session = e.createSession(bytes, OrtSession.SessionOptions())
            error = null
            true
        } catch (t: Throwable) {
            error = t.message ?: t.javaClass.simpleName
            false
        }
    }

    /** Returns an L2-normalised embedding, or null on failure (see [error]). */
    @Synchronized
    fun embed(pcm: ShortArray): FloatArray? {
        if (!ensureLoaded()) return null
        val s = session ?: return null
        val e = env ?: return null
        val speech = Recorder.trimSilence(pcm)
        if (speech.size < Recorder.SR * 3 / 2) { error = "Not enough speech detected"; return null }
        val feats = Fbank.compute(speech)
        if (feats.size < 50) { error = "Audio too short"; return null }
        return try {
            if (s.inputNames.size != 1) { error = "Unexpected model inputs: ${s.inputNames}"; return null }
            val t = feats.size
            val buf = FloatBuffer.allocate(t * Fbank.NMEL)
            for (f in feats) buf.put(f)
            buf.rewind()
            val tensor = OnnxTensor.createTensor(e, buf, longArrayOf(1, t.toLong(), Fbank.NMEL.toLong()))
            var out: FloatArray? = null
            try {
                s.run(mapOf(s.inputNames.first() to tensor)).use { res ->
                    val v = res.get(0).value
                    out = when (v) {
                        is Array<*> -> v[0] as? FloatArray
                        is FloatArray -> v
                        else -> null
                    }
                }
            } finally {
                tensor.close()
            }
            val o = out
            if (o == null) { error = "Model returned no embedding"; null } else { error = null; l2(o) }
        } catch (t: Throwable) {
            error = t.message ?: t.javaClass.simpleName
            null
        }
    }

    private fun l2(v: FloatArray): FloatArray {
        var n = 0.0
        for (x in v) n += x * x
        val d = sqrt(n).toFloat().coerceAtLeast(1e-9f)
        return FloatArray(v.size) { v[it] / d }
    }
}
