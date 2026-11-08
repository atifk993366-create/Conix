package com.conix.assistant.speech

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import com.conix.assistant.core.Prefs
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream
import kotlin.math.sqrt

/** Something that listens once and returns the recognized text ("" if nothing was heard). */
interface Ears {
    var onFatal: ((String) -> Unit)?
    fun listen(timeoutMs: Long, cb: (String) -> Unit)
    fun cancel()
    fun release()
}

object EarsFactory {
    /** Kori's own offline engine when its model is downloaded; otherwise the Google recognizer. */
    fun create(ctx: Context, prefs: Prefs, tag: String): Ears {
        val app = ctx.applicationContext
        val dir = ModelManager.dir(app, tag)
        return if (prefs.engine == "kori" && dir != null && ModelManager.isReady(app, tag)) KoriEars(dir.absolutePath)
        else GoogleEars(app, tag)
    }
}

/** Offline voice models (Vosk) downloaded once into the app's private storage. */
object ModelManager {
    private val models = HashMap<String, Model>()

    private fun name(tag: String): String? = when (tag) {
        "en-IN" -> "vosk-model-small-en-in-0.4"
        "hi-IN" -> "vosk-model-small-hi-0.22"
        else -> null
    }

    fun supported(tag: String) = name(tag) != null

    fun dir(ctx: Context, tag: String): File? = name(tag)?.let { File(File(ctx.filesDir, "vosk"), it) }

    fun isReady(ctx: Context, tag: String): Boolean = dir(ctx, tag)?.let { File(it, "am").isDirectory } ?: false

    @Synchronized
    fun model(path: String): Model = models.getOrPut(path) { Model(path) }

    /** Blocking. Returns "" on success or an error message. */
    fun download(ctx: Context, tag: String, onProgress: (Int) -> Unit): String {
        val n = name(tag) ?: return "No offline voice model for this language."
        val root = File(ctx.filesDir, "vosk").apply { mkdirs() }
        val zip = File(root, "$n.zip")
        return try {
            val c = URL("https://alphacephei.com/vosk/models/$n.zip").openConnection() as HttpURLConnection
            c.connectTimeout = 15000
            c.readTimeout = 30000
            val total = c.contentLengthLong
            c.inputStream.use { ins ->
                FileOutputStream(zip).use { out ->
                    val buf = ByteArray(65536)
                    var done = 0L
                    while (true) {
                        val r = ins.read(buf)
                        if (r < 0) break
                        out.write(buf, 0, r)
                        done += r
                        if (total > 0) onProgress((done * 90 / total).toInt())
                    }
                }
            }
            ZipInputStream(FileInputStream(zip)).use { zin ->
                var e = zin.nextEntry
                while (e != null) {
                    val f = File(root, e.name)
                    if (!f.canonicalPath.startsWith(root.canonicalPath)) throw SecurityException("bad zip entry")
                    if (e.isDirectory) f.mkdirs() else {
                        f.parentFile?.mkdirs()
                        FileOutputStream(f).use { zin.copyTo(it) }
                    }
                    e = zin.nextEntry
                }
            }
            zip.delete()
            onProgress(100)
            ""
        } catch (e: Exception) {
            zip.delete()
            "Download failed: ${e.message}"
        }
    }
}

/** Android / Google speech recognizer (works for Odia and as a fallback). */
class GoogleEars(private val ctx: Context, private val tag: String) : Ears {
    override var onFatal: ((String) -> Unit)? = null
    private val handler = Handler(Looper.getMainLooper())
    private var sr: SpeechRecognizer? = null
    private var onDevice = true

    override fun listen(timeoutMs: Long, cb: (String) -> Unit) {
        cancel()
        if (!SpeechRecognizer.isRecognitionAvailable(ctx)) {
            onFatal?.invoke("Speech recognition is not available on this phone.")
            return
        }
        val r = if (Build.VERSION.SDK_INT >= 33 && onDevice && SpeechRecognizer.isOnDeviceRecognitionAvailable(ctx))
            SpeechRecognizer.createOnDeviceSpeechRecognizer(ctx) else SpeechRecognizer.createSpeechRecognizer(ctx)
        var done = false
        r.setRecognitionListener(object : RecognitionListener {
            override fun onResults(results: Bundle?) {
                if (done) return
                done = true
                cb(results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty().trim())
            }
            override fun onError(error: Int) {
                if (done) return
                done = true
                if (error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) onFatal?.invoke("Microphone permission is missing.")
                if (error == 12 || error == 13) onDevice = false
                handler.postDelayed({ cb("") }, if (error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY) 1200L else 0L)
            }
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        sr = r
        r.startListening(
            Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE, tag)
                .putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
                .putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, ctx.packageName)
        )
    }

    override fun cancel() { sr?.destroy(); sr = null }
    override fun release() = cancel()
}

/**
 * Kori's own ears: records from the microphone itself (AudioRecord) and recognizes offline with
 * Vosk. Only audio that contains speech is decoded; silence is dropped.
 */
class KoriEars(private val modelPath: String) : Ears {
    override var onFatal: ((String) -> Unit)? = null
    private val main = Handler(Looper.getMainLooper())

    private class Request(val timeoutMs: Long, val cb: (String) -> Unit, val startedAt: Long)

    @Volatile private var req: Request? = null
    @Volatile private var running = false
    private var thread: Thread? = null

    override fun listen(timeoutMs: Long, cb: (String) -> Unit) {
        req = Request(timeoutMs, cb, SystemClock.elapsedRealtime())
        if (!running) {
            running = true
            thread = Thread { loop() }.also { it.name = "kori-ears"; it.start() }
        }
    }

    override fun cancel() { req = null }

    override fun release() {
        running = false
        req = null
    }

    private fun deliver(r: Request, text: String) { main.post { r.cb(text) } }

    private fun fatal(msg: String) { main.post { onFatal?.invoke(msg) } }

    private fun rms(a: ShortArray, n: Int): Double {
        var s = 0.0
        for (i in 0 until n) { val v = a[i].toDouble(); s += v * v }
        return sqrt(s / n)
    }

    @SuppressLint("MissingPermission")
    private fun loop() {
        var rec: AudioRecord? = null
        var recognizer: Recognizer? = null
        try {
            val model = ModelManager.model(modelPath)
            val minBuf = AudioRecord.getMinBufferSize(SR, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            val ar = AudioRecord(
                MediaRecorder.AudioSource.MIC, SR,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuf, SR)
            )
            rec = ar
            if (ar.state != AudioRecord.STATE_INITIALIZED) { fatal("Microphone is not available."); return }
            ar.startRecording()
            val reco = Recognizer(model, SR.toFloat())
            recognizer = reco
            val frame = ShortArray(1600)               // 100 ms
            val preRoll = ArrayDeque<ShortArray>()
            var inSpeech = false
            var lastVoice = 0L
            var speechStart = 0L
            var floor = 200.0
            var current: Request? = null
            while (running) {
                val n = ar.read(frame, 0, frame.size)
                if (n <= 0) { Thread.sleep(20); continue }
                val r = req
                if (r == null) {
                    if (inSpeech) { reco.reset(); inSpeech = false }
                    preRoll.clear()
                    current = null
                    continue
                }
                if (r !== current) { current = r; reco.reset(); inSpeech = false; preRoll.clear() }
                val level = rms(frame, n)
                val voice = level > maxOf(450.0, floor * 3.0)
                if (!voice) floor = floor * 0.97 + level * 0.03
                val now = SystemClock.elapsedRealtime()
                if (!inSpeech) {
                    preRoll.addLast(frame.copyOf(n))
                    while (preRoll.size > 4) preRoll.removeFirst()
                    if (voice) {
                        inSpeech = true
                        lastVoice = now
                        speechStart = now
                        for (f in preRoll) reco.acceptWaveForm(f, f.size)
                        preRoll.clear()
                    } else if (r.timeoutMs > 0 && now - r.startedAt > r.timeoutMs) {
                        if (req === r) { req = null; deliver(r, "") }
                    }
                } else {
                    reco.acceptWaveForm(frame, n)
                    if (voice) lastVoice = now
                    if (now - lastVoice > 800 || now - speechStart > 12000) {
                        val text = try { JSONObject(reco.finalResult).optString("text", "") } catch (_: Exception) { "" }
                        inSpeech = false
                        reco.reset()
                        if (req === r) { req = null; deliver(r, text.trim()) }
                    }
                }
            }
        } catch (e: Throwable) {
            fatal(e.message ?: e.javaClass.simpleName)
        } finally {
            try { rec?.stop() } catch (_: Exception) { }
            rec?.release()
            try { recognizer?.close() } catch (_: Exception) { }
            running = false
        }
    }

    companion object { private const val SR = 16000 }
}
