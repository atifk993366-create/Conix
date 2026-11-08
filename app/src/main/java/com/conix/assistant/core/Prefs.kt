package com.conix.assistant.core

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.conix.assistant.brain.Providers
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class Prefs(ctx: Context) {
    private val p = ctx.getSharedPreferences("kori_prefs", Context.MODE_PRIVATE)

    /** Primary AI provider; the others with keys are used automatically as fallbacks. */
    var provider: String
        get() = p.getString("provider", "gemini") ?: "gemini"
        set(v) { p.edit().putString("provider", v).apply() }

    fun modelOf(id: String): String =
        p.getString("model_$id", null)?.takeIf { it.isNotBlank() } ?: Providers.byId(id).textModel
    fun setModelOf(id: String, v: String) { p.edit().putString("model_$id", v.trim()).apply() }
    fun visionOf(id: String): String =
        p.getString("vmodel_$id", null)?.takeIf { it.isNotBlank() } ?: Providers.byId(id).visionModel
    fun setVisionOf(id: String, v: String) { p.edit().putString("vmodel_$id", v.trim()).apply() }

    var model: String
        get() = modelOf(provider)
        set(v) { setModelOf(provider, v) }

    var wakeMinutes: Int
        get() = p.getInt("wake_minutes", 30)
        set(v) { p.edit().putInt("wake_minutes", v).apply() }

    var langTag: String
        get() = p.getString("lang_tag", "en-IN") ?: "en-IN"
        set(v) { p.edit().putString("lang_tag", v).apply() }

    /** "kori" = offline Vosk engine on Kori's own microphone, "google" = Android/Google recognizer. */
    var engine: String
        get() = p.getString("engine", "kori") ?: "kori"
        set(v) { p.edit().putString("engine", v).apply() }

    /** Save commands and answers as training examples for Kori's own model. */
    var trainingLog: Boolean
        get() = p.getBoolean("training_log", true)
        set(v) { p.edit().putBoolean("training_log", v).apply() }

    /** "smooth" = minimal prompts, "strict" = confirm + phone unlock for sensitive actions. */
    var safety: String
        get() = p.getString("safety", "smooth") ?: "smooth"
        set(v) { p.edit().putString("safety", v).apply() }

    var announceWa: Boolean
        get() = p.getBoolean("announce_wa", false)
        set(v) { p.edit().putBoolean("announce_wa", v).apply() }

    var callAssist: Boolean
        get() = p.getBoolean("call_assist", true)
        set(v) { p.edit().putBoolean("call_assist", v).apply() }

    var autoSendWa: Boolean
        get() = p.getBoolean("auto_send_wa", true)
        set(v) { p.edit().putBoolean("auto_send_wa", v).apply() }

    var ownerName: String
        get() = p.getString("owner_name", "") ?: ""
        set(v) { p.edit().putString("owner_name", v).apply() }

    /** Away mode end time in millis; Long.MAX_VALUE means "until I stop it". */
    var awayUntil: Long
        get() = p.getLong("away_until", 0L)
        set(v) { p.edit().putLong("away_until", v).apply() }

    /** When true, calls and questions work by voice even while the phone is locked. */
    var lockScreenMode: Boolean
        get() = p.getBoolean("lock_screen_mode", false)
        set(v) { p.edit().putBoolean("lock_screen_mode", v).apply() }

    /** Text callers whose message Kori could not hear. */
    var awaySms: Boolean
        get() = p.getBoolean("away_sms", true)
        set(v) { p.edit().putBoolean("away_sms", v).apply() }

    /** Package names Kori must never open or read notifications from. */
    var blockedApps: Set<String>
        get() = p.getStringSet("blocked_apps", null)?.toSet() ?: setOf("com.whatsapp.w4b")
        set(v) { p.edit().putStringSet("blocked_apps", HashSet(v)).apply() }
}

/** Stores secrets (API keys, memory) encrypted with an Android Keystore AES-GCM key. */
class SecretStore(ctx: Context) {
    private val dir = File(ctx.filesDir, "secrets").apply { mkdirs() }

    fun put(name: String, value: String) {
        File(dir, name).writeBytes(encrypt(value.toByteArray(Charsets.UTF_8)))
    }

    fun get(name: String): String? {
        val f = File(dir, name)
        if (!f.exists()) return null
        return try { String(decrypt(f.readBytes()), Charsets.UTF_8) } catch (e: Exception) { null }
    }

    fun remove(name: String) { File(dir, name).delete() }

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

    companion object { private const val ALIAS = "kori_secret_key" }
}
