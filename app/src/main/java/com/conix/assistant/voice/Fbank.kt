package com.conix.assistant.voice

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sin

/** Kaldi-style 80-bin log-mel filterbank features (16 kHz, 25 ms window, 10 ms shift). */
object Fbank {
    private const val SR = 16000
    private const val FRAME_LEN = 400
    private const val SHIFT = 160
    private const val NFFT = 512
    const val NMEL = 80
    private const val PREEMPH = 0.97
    private const val EPS = 1.1920929e-7

    private val window = DoubleArray(FRAME_LEN) { (0.5 - 0.5 * cos(2.0 * PI * it / (FRAME_LEN - 1))).pow(0.85) }

    private fun mel(f: Double) = 1127.0 * ln(1.0 + f / 700.0)

    private val banks: Array<DoubleArray> by lazy {
        val low = 20.0
        val high = SR / 2.0
        val melLow = mel(low)
        val melHigh = mel(high)
        val delta = (melHigh - melLow) / (NMEL + 1)
        Array(NMEL) { b ->
            val left = melLow + b * delta
            val center = left + delta
            val right = center + delta
            DoubleArray(NFFT / 2 + 1) { i ->
                val m = mel(i * SR.toDouble() / NFFT)
                when {
                    m > left && m <= center -> (m - left) / (center - left)
                    m > center && m < right -> (right - m) / (right - center)
                    else -> 0.0
                }
            }
        }
    }

    /** Returns [frames][80] features with per-dimension mean removed. */
    fun compute(pcm: ShortArray): Array<FloatArray> {
        if (pcm.size < FRAME_LEN) return emptyArray()
        val nFrames = 1 + (pcm.size - FRAME_LEN) / SHIFT
        val feats = Array(nFrames) { FloatArray(NMEL) }
        val re = DoubleArray(NFFT)
        val im = DoubleArray(NFFT)
        val x = DoubleArray(FRAME_LEN)
        for (f in 0 until nFrames) {
            val off = f * SHIFT
            var mean = 0.0
            for (i in 0 until FRAME_LEN) { x[i] = pcm[off + i].toDouble(); mean += x[i] }
            mean /= FRAME_LEN
            for (i in 0 until FRAME_LEN) x[i] -= mean
            for (i in FRAME_LEN - 1 downTo 1) x[i] -= PREEMPH * x[i - 1]
            x[0] -= PREEMPH * x[0]
            for (i in 0 until NFFT) {
                re[i] = if (i < FRAME_LEN) x[i] * window[i] else 0.0
                im[i] = 0.0
            }
            fft(re, im)
            for (b in 0 until NMEL) {
                var e = 0.0
                val w = banks[b]
                for (i in 0..NFFT / 2) {
                    if (w[i] != 0.0) e += w[i] * (re[i] * re[i] + im[i] * im[i])
                }
                feats[f][b] = ln(max(e, EPS)).toFloat()
            }
        }
        // mean normalization over time
        for (b in 0 until NMEL) {
            var m = 0.0
            for (f in 0 until nFrames) m += feats[f][b]
            m /= nFrames
            for (f in 0 until nFrames) feats[f][b] = (feats[f][b] - m).toFloat()
        }
        return feats
    }

    private fun fft(re: DoubleArray, im: DoubleArray) {
        val n = re.size
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while ((j and bit) != 0) { j = j xor bit; bit = bit shr 1 }
            j = j xor bit
            if (i < j) {
                val tr = re[i]; re[i] = re[j]; re[j] = tr
                val ti = im[i]; im[i] = im[j]; im[j] = ti
            }
        }
        var len = 2
        while (len <= n) {
            val ang = -2.0 * PI / len
            val wr = cos(ang)
            val wi = sin(ang)
            var i = 0
            while (i < n) {
                var cr = 1.0
                var ci = 0.0
                val half = len / 2
                for (k in 0 until half) {
                    val ur = re[i + k]
                    val ui = im[i + k]
                    val vr = re[i + k + half] * cr - im[i + k + half] * ci
                    val vi = re[i + k + half] * ci + im[i + k + half] * cr
                    re[i + k] = ur + vr
                    im[i + k] = ui + vi
                    re[i + k + half] = ur - vr
                    im[i + k + half] = ui - vi
                    val ncr = cr * wr - ci * wi
                    ci = cr * wi + ci * wr
                    cr = ncr
                }
                i += len
            }
            len = len shl 1
        }
    }
}
