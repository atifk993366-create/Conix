package com.conix.assistant.core

import android.content.Context
import android.os.Build
import android.os.Environment
import android.util.Base64
import com.conix.assistant.voice.VoiceVault
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Keeps Kori's data in the shared folder /Kori on the phone's storage (like Minecraft does), so a
 * reinstall or update can pick it up again. Keys and the voice profile are protected with your passphrase.
 */
object BackupManager {
    private val MAGIC = "KORI1".toByteArray()

    fun dir(): File = File(Environment.getExternalStorageDirectory(), "Kori")

    fun hasAccess(): Boolean = Build.VERSION.SDK_INT >= 30 && Environment.isExternalStorageManager()

    fun hasBackup(): Boolean = hasAccess() && File(dir(), "kori_backup.bin").exists()

    private fun derive(pass: CharArray, salt: ByteArray): SecretKeySpec {
        val spec = PBEKeySpec(pass, salt, 120_000, 256)
        val raw = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        return SecretKeySpec(raw, "AES")
    }

    private fun encrypt(pass: CharArray, data: ByteArray): ByteArray {
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, derive(pass, salt))
        return MAGIC + salt + c.iv + c.doFinal(data)
    }

    private fun decrypt(pass: CharArray, blob: ByteArray): ByteArray {
        require(blob.size > 40 && blob.copyOfRange(0, 5).contentEquals(MAGIC)) { "Not a Kori backup" }
        val salt = blob.copyOfRange(5, 21)
        val iv = blob.copyOfRange(21, 33)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, derive(pass, salt), GCMParameterSpec(128, iv))
        return c.doFinal(blob, 33, blob.size - 33)
    }

    private fun dumpPrefs(ctx: Context, name: String): JSONObject {
        val sp = ctx.getSharedPreferences(name, Context.MODE_PRIVATE)
        val o = JSONObject()
        for ((k, v) in sp.all) {
            val e = JSONObject()
            when (v) {
                is Boolean -> e.put("t", "b").put("v", v)
                is Int -> e.put("t", "i").put("v", v)
                is Long -> e.put("t", "l").put("v", v)
                is Float -> e.put("t", "f").put("v", v.toDouble())
                is String -> e.put("t", "s").put("v", v)
                is Set<*> -> e.put("t", "S").put("v", JSONArray(v.map { it.toString() }))
                else -> continue
            }
            o.put(k, e)
        }
        return o
    }

    private fun loadPrefs(ctx: Context, name: String, o: JSONObject) {
        val ed = ctx.getSharedPreferences(name, Context.MODE_PRIVATE).edit()
        for (k in o.keys()) {
            val e = o.getJSONObject(k)
            when (e.getString("t")) {
                "b" -> ed.putBoolean(k, e.getBoolean("v"))
                "i" -> ed.putInt(k, e.getInt("v"))
                "l" -> ed.putLong(k, e.getLong("v"))
                "f" -> ed.putFloat(k, e.getDouble("v").toFloat())
                "s" -> ed.putString(k, e.getString("v"))
                "S" -> {
                    val a = e.getJSONArray("v")
                    ed.putStringSet(k, HashSet(List(a.length()) { a.getString(it) }))
                }
            }
        }
        ed.apply()
    }

    fun backup(ctx: Context, pass: String): String {
        if (!hasAccess()) return "Allow all-files access first (Settings > Backup)."
        if (pass.isBlank()) return "Enter a passphrase first."
        return try {
            val secrets = SecretStore(ctx)
            val root = JSONObject().put("v", 1)
            val sec = JSONObject()
            for (n in secrets.names()) secrets.get(n)?.let { sec.put(n, it) }
            root.put("secrets", sec)
            val voice = JSONObject()
            for ((n, b) in VoiceVault(ctx).exportRaw()) voice.put(n, Base64.encodeToString(b, Base64.NO_WRAP))
            root.put("voice", voice)
            root.put("prefs", dumpPrefs(ctx, "kori_prefs"))
            root.put("voicePrefs", dumpPrefs(ctx, "conix_voice"))
            val log = File(ctx.filesDir, "training/log.jsonl")
            if (log.exists()) root.put("train", Base64.encodeToString(log.readBytes(), Base64.NO_WRAP))
            val blob = encrypt(pass.toCharArray(), root.toString().toByteArray(Charsets.UTF_8))
            dir().mkdirs()
            val tmp = File(dir(), "kori_backup.tmp")
            tmp.writeBytes(blob)
            val dst = File(dir(), "kori_backup.bin")
            if (dst.exists()) dst.delete()
            tmp.renameTo(dst)
            File(dir(), "README.txt").writeText("Kori AI backup. Keep this folder; Kori restores from it after a reinstall. You need your passphrase.\n")
            "Backed up (${blob.size / 1024} KB) to ${dst.absolutePath}"
        } catch (e: Exception) {
            "Backup failed: ${e.message}"
        }
    }

    fun restore(ctx: Context, pass: String): String {
        if (!hasAccess()) return "Allow all-files access first (Settings > Backup)."
        val f = File(dir(), "kori_backup.bin")
        if (!f.exists()) return "No backup found in ${dir().absolutePath}"
        return try {
            val root = JSONObject(String(decrypt(pass.toCharArray(), f.readBytes()), Charsets.UTF_8))
            val secrets = SecretStore(ctx)
            val sec = root.optJSONObject("secrets") ?: JSONObject()
            var keys = 0
            for (n in sec.keys()) { secrets.put(n, sec.getString(n)); keys++ }
            val vm = HashMap<String, ByteArray>()
            val voice = root.optJSONObject("voice") ?: JSONObject()
            for (n in voice.keys()) vm[n] = Base64.decode(voice.getString(n), Base64.DEFAULT)
            VoiceVault(ctx).importRaw(vm)
            root.optJSONObject("prefs")?.let { loadPrefs(ctx, "kori_prefs", it) }
            root.optJSONObject("voicePrefs")?.let { loadPrefs(ctx, "conix_voice", it) }
            val train = root.optString("train", "")
            if (train.isNotEmpty()) {
                val lf = File(ctx.filesDir, "training/log.jsonl")
                lf.parentFile?.mkdirs()
                lf.writeBytes(Base64.decode(train, Base64.DEFAULT))
            }
            "Restored $keys saved items, ${vm.size} voice files and your settings. Restart Kori once."
        } catch (e: javax.crypto.AEADBadTagException) {
            "Wrong passphrase."
        } catch (e: Exception) {
            "Restore failed: ${e.message}"
        }
    }

    /** Called when you leave the app. Runs on a background thread. */
    fun autoBackup(ctx: Context) {
        val app = ctx.applicationContext
        val prefs = Prefs(app)
        if (!prefs.backupAuto || !hasAccess()) return
        val pass = SecretStore(app).get("backup_pass") ?: return
        Thread { backup(app, pass) }.start()
    }
}
