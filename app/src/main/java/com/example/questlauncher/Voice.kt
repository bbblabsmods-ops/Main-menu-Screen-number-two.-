package com.example.questlauncher

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.RecognitionListener
import org.vosk.android.SpeechService
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.zip.ZipInputStream

/** Listens to the headset microphone with the free offline Vosk model. */
class VoiceListener(
    private val context: Context,
    private val onText: (String) -> Unit,
    private val onStatus: (String) -> Unit,
    private val onPartial: (String) -> Unit = {},
) : RecognitionListener {

    private val modelName = "vosk-model-small-en-us-0.15"
    private val modelUrl = "https://alphacephei.com/vosk/models/$modelName.zip"
    private val modelDir: File get() = File(context.filesDir, modelName)
    private val main = Handler(Looper.getMainLooper())

    @Volatile private var model: Model? = null
    @Volatile private var recognizer: Recognizer? = null
    @Volatile private var service: SpeechService? = null
    @Volatile private var starting = false
    @Volatile private var wanted = false

    private fun say(text: String) {
        main.post { onStatus(text) }
    }

    fun start() {
        wanted = true
        if (service != null || starting) return
        starting = true
        Thread {
            try {
                ensureModel()
                val m = model ?: Model(modelDir.absolutePath).also { model = it }
                val rec = Recognizer(m, 16000.0f)
                recognizer = rec
                val svc = SpeechService(rec, 16000.0f)
                svc.startListening(this@VoiceListener)
                service = svc
                if (!wanted) {
                    stop()
                } else {
                    say("Listening. Say \"Hey BBB\".")
                }
            } catch (e: Exception) {
                say("Voice problem: " + (e.message ?: e.javaClass.simpleName))
            } finally {
                starting = false
            }
        }.start()
    }

    fun stop() {
        wanted = false
        val s = service
        service = null
        if (s != null) {
            try {
                s.stop()
                s.shutdown()
            } catch (e: Exception) {
            }
        }
        try {
            recognizer?.close()
        } catch (e: Exception) {
        }
        recognizer = null
    }

    private fun ensureModel() {
        val dir = modelDir
        if (File(dir, "am").exists()) return

        say("Downloading voice model (40 MB, one time)…")
        val zip = File(context.cacheDir, "$modelName.zip")
        val c = URL(modelUrl).openConnection() as HttpURLConnection
        c.connectTimeout = 15000
        c.readTimeout = 30000
        if (c.responseCode != 200) throw IllegalStateException("Download failed (${c.responseCode})")
        val total = c.contentLength.toLong()
        c.inputStream.use { input ->
            zip.outputStream().use { out ->
                val buf = ByteArray(65536)
                var done = 0L
                var last = 0
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    done += n
                    if (total > 0) {
                        val p = (done * 100 / total).toInt()
                        if (p >= last + 10) {
                            last = p
                            say("Downloading voice model… $p%")
                        }
                    }
                }
            }
        }
        c.disconnect()

        say("Unpacking voice model…")
        val root = context.filesDir.canonicalFile
        ZipInputStream(zip.inputStream().buffered()).use { zin ->
            var entry = zin.nextEntry
            while (entry != null) {
                val target = File(root, entry.name).canonicalFile
                if (!target.path.startsWith(root.path + File.separator)) throw SecurityException("bad zip")
                if (entry.isDirectory) {
                    target.mkdirs()
                } else {
                    target.parentFile?.mkdirs()
                    target.outputStream().use { zin.copyTo(it) }
                }
                entry = zin.nextEntry
            }
        }
        zip.delete()
        if (!File(dir, "am").exists()) throw IllegalStateException("Voice model looks incomplete")
    }

    private fun textOf(hypothesis: String?): String? {
        if (hypothesis == null) return null
        return try {
            JSONObject(hypothesis).optString("text").trim().ifEmpty { null }
        } catch (e: Exception) {
            null
        }
    }

    /** Words heard so far, while you are still talking. Used to react to "Hey BBB" instantly. */
    override fun onPartialResult(hypothesis: String?) {
        if (hypothesis == null) return
        val p = try {
            JSONObject(hypothesis).optString("partial").trim()
        } catch (e: Exception) {
            ""
        }
        if (p.isNotEmpty()) main.post { onPartial(p) }
    }

    override fun onResult(hypothesis: String?) {
        val t = textOf(hypothesis) ?: return
        main.post { onText(t) }
    }

    override fun onFinalResult(hypothesis: String?) {}

    override fun onError(exception: Exception?) {
        say("Voice problem: " + (exception?.message ?: "unknown"))
    }

    override fun onTimeout() {}
}

/** Reads answers out loud when the headset has a speech voice installed. */
class Speaker(context: Context) {
    private var tts: TextToSpeech? = null
    private var ready = false

    init {
        tts = TextToSpeech(context.applicationContext) { status ->
            if (status == TextToSpeech.SUCCESS) {
                try {
                    tts?.language = Locale.US
                    ready = true
                } catch (e: Exception) {
                }
            }
        }
    }

    fun speak(text: String) {
        if (!ready) return
        try {
            tts?.speak(text.take(400), TextToSpeech.QUEUE_FLUSH, null, "bbb")
        } catch (e: Exception) {
        }
    }

    fun shutdown() {
        try {
            tts?.stop()
            tts?.shutdown()
        } catch (e: Exception) {
        }
        tts = null
        ready = false
    }
}

/** A short two-tone beep, made by the app itself so it doesn't depend on any system sound. */
class Beeper {
    private val rate = 22050
    private val pcm: ShortArray = makeTone()

    private fun makeTone(): ShortArray {
        val n = (rate * 0.22).toInt()
        val out = ShortArray(n)
        for (i in 0 until n) {
            val t = i.toDouble() / rate
            val f = if (i < n / 2) 880.0 else 1320.0
            val edge = minOf(i, n - i).toDouble() / (rate * 0.02)
            val env = if (edge > 1.0) 1.0 else edge
            out[i] = (Math.sin(2.0 * Math.PI * f * t) * env * 0.6 * 32767.0).toInt().toShort()
        }
        return out
    }

    fun beep() {
        Thread {
            try {
                val attrs = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
                val format = AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(rate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
                val track = AudioTrack.Builder()
                    .setAudioAttributes(attrs)
                    .setAudioFormat(format)
                    .setBufferSizeInBytes(pcm.size * 2)
                    .setTransferMode(AudioTrack.MODE_STATIC)
                    .build()
                track.write(pcm, 0, pcm.size)
                track.play()
                Thread.sleep(450)
                track.release()
            } catch (e: Exception) {
            }
        }.start()
    }
}
