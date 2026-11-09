package com.conix.assistant.voice

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlin.math.sqrt

const val ENROLL_SAMPLES = 5

/** Stores voice samples and the voice profile encrypted with an Android Keystore AES-GCM key. */
class VoiceVault(ctx: Context) {
    private val dir = File(ctx.filesDir, "voice").apply { mkdirs() }
    private val prefs = ctx.getSharedPreferences("conix_voice", Context.MODE_PRIVATE)

    var threshold: Float
        get() = prefs.getFloat("threshold", 0.5f)
        set(v) { prefs.edit().putFloat("threshold", v).apply() }

    var featureB: Boolean
        get() = prefs.getBoolean("feat_b", false)
        set(v) { prefs.edit().putBoolean("feat_b", v).apply() }

    fun sampleCount(): Int {
        var n = 0
        while (n < ENROLL_SAMPLES && File(dir, "sample_$n.enc").exists()) n++
        return n
    }

    fun isEnrolled() = File(dir, "profile.enc").exists()

    fun saveSample(i: Int, pcm: ShortArray) {
        val bb = ByteBuffer.allocate(pcm.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        for (s in pcm) bb.putShort(s)
        File(dir, "sample_$i.enc").writeBytes(encrypt(bb.array()))
    }

    fun loadSample(i: Int): ShortArray? {
        val f = File(dir, "sample_$i.enc")
        if (!f.exists()) return null
        val bb = ByteBuffer.wrap(decrypt(f.readBytes())).order(ByteOrder.LITTLE_ENDIAN)
        return ShortArray(bb.remaining() / 2) { bb.short }
    }

    fun saveProfile(embs: List<FloatArray>) {
        val dim = embs.first().size
        val bb = ByteBuffer.allocate(8 + embs.size * dim * 4).order(ByteOrder.LITTLE_ENDIAN)
        bb.putInt(embs.size); bb.putInt(dim)
        for (e in embs) for (x in e) bb.putFloat(x)
        File(dir, "profile.enc").writeBytes(encrypt(bb.array()))
    }

    fun loadProfile(): List<FloatArray>? {
        val f = File(dir, "profile.enc")
        if (!f.exists()) return null
        val bb = ByteBuffer.wrap(decrypt(f.readBytes())).order(ByteOrder.LITTLE_ENDIAN)
        val n = bb.int
        val dim = bb.int
        return List(n) { FloatArray(dim) { bb.float } }
    }

    fun clear() {
        dir.listFiles()?.forEach { it.delete() }
    }

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val kg = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        kg.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return kg.generateKey()
    }

    private fun encrypt(data: ByteArray): ByteArray {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, key())
        return c.iv + c.doFinal(data)
    }

    private fun decrypt(b: ByteArray): ByteArray {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, b.copyOfRange(0, 12)))
        return c.doFinal(b, 12, b.size - 12)
    }

    fun exportRaw(): Map<String, ByteArray> {
        val out = HashMap<String, ByteArray>()
        dir.listFiles()?.forEach { f -> try { out[f.name] = decrypt(f.readBytes()) } catch (_: Exception) { } }
        return out
    }

    fun importRaw(m: Map<String, ByteArray>) {
        for ((n, b) in m) File(dir, n).writeBytes(encrypt(b))
    }

    companion object {
        private const val ALIAS = "conix_voice_key"

        fun dot(a: FloatArray, b: FloatArray): Float {
            var s = 0f
            for (i in a.indices) s += a[i] * b[i]
            return s
        }

        fun centroid(embs: List<FloatArray>): FloatArray {
            val c = FloatArray(embs.first().size)
            for (e in embs) for (i in c.indices) c[i] += e[i]
            var n = 0.0
            for (x in c) n += x * x
            val d = sqrt(n).toFloat().coerceAtLeast(1e-9f)
            return FloatArray(c.size) { c[it] / d }
        }
    }
}
