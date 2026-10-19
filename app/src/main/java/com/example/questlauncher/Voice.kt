package com.example.questlauncher

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import org.json.JSONArray
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.zip.ZipInputStream

/**
 * Listens to the headset microphone with the free offline Vosk model.
 * Two listeners share the same audio: a strict one that only recognises "hey b b b" (fast and
 * accurate), and a normal one that writes down everything you say.
 */
class VoiceListener(
    private val context: Context,
    private val onText: (String) -> Unit,
    private val onStatus: (String) -> Unit,
    private val onWake: () -> Unit,
) {

    private val modelName = "vosk-model-small-en-us-0.15"
    private val modelUrl = "https://alphacephei.com/vosk/models/$modelName.zip"
    private val modelDir: File get() = File(context.filesDir, modelName)
    private val main = Handler(Looper.getMainLooper())

    @Volatile private var model: Model? = null
    @Volatile private var wanted = false
    @Volatile private var worker: Thread? = null

    private fun say(text: String) {
        main.post { onStatus(text) }
    }

    fun start() {
        wanted = true
        val w = worker
        if (w != null && w.isAlive) return
        val t = Thread { listen() }
        worker = t
        t.start()
    }

    fun stop() {
        wanted = false
    }

    private fun wakeGrammar(): String {
        val sounds = listOf("b", "be", "bee")
        val list = JSONArray()
        for (hey in listOf("hey", "okay")) {
            for (x in sounds) {
                for (y in sounds) {
                    for (z in sounds) {
                        list.put("$hey $x $y $z")
                    }
                }
            }
        }
        list.put("[unk]")
        return list.toString()
    }

    private fun isWake(text: String?): Boolean {
        if (text == null) return false
        val t = text.split(" ").filter { it.isNotEmpty() }
        if (t.size < 3) return false
        if (t[0] != "hey" && t[0] != "okay") return false
        var b = 0
        for (i in 1 until t.size) {
            if (t[i] == "b" || t[i] == "be" || t[i] == "bee") b++ else break
        }
        return b >= 2
    }

    private fun openMic(): AudioRecord {
        val minBuf = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val size = maxOf(minBuf, RATE * 2)
        for (source in intArrayOf(MediaRecorder.AudioSource.VOICE_RECOGNITION, MediaRecorder.AudioSource.MIC)) {
            try {
                val r = AudioRecord(source, RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, size)
                if (r.state == AudioRecord.STATE_INITIALIZED) return r
                r.release()
            } catch (e: Exception) {
            }
        }
        throw IllegalStateException("The microphone couldn't start. Another app may be using it, or the permission is missing.")
    }

    private fun listen() {
        var record: AudioRecord? = null
        var full: Recognizer? = null
        var spotter: Recognizer? = null
        try {
            ensureModel()
            val m = model ?: Model(modelDir.absolutePath).also { model = it }
            val f = Recognizer(m, RATE.toFloat())
            full = f
            val s: Recognizer? = try {
                Recognizer(m, RATE.toFloat(), wakeGrammar())
            } catch (e: Exception) {
                null
            }
            spotter = s

            val r = openMic()
            record = r
            r.startRecording()
            say("Listening. Say \"Hey BBB\".")

            val buf = ShortArray(800)
            var lastPartial = ""
            var lastChange = 0L
            var wakeFired = false

            while (wanted) {
                val n = r.read(buf, 0, buf.size)
                if (n < 0) throw IllegalStateException("The microphone stopped (error $n).")
                if (n == 0) continue

                // 1) Strict listener: only "hey b b b". Beeps the moment it hears it.
                if (s != null) {
                    val spotDone = s.acceptWaveForm(buf, n)
                    val spotText = if (spotDone) textOf(s.getResult()) else partialOf(s.getPartialResult())
                    if (!wakeFired && isWake(spotText)) {
                        wakeFired = true
                        main.post { onWake() }
                    }
                    if (spotDone) wakeFired = false
                }

                // 2) Normal listener: the words of your sentence.
                val now = SystemClock.elapsedRealtime()
                if (f.acceptWaveForm(buf, n)) {
                    lastPartial = ""
                    val t = textOf(f.getResult())
                    if (t != null) main.post { onText(t) }
                } else {
                    val p = partialOf(f.getPartialResult()) ?: ""
                    if (p != lastPartial) {
                        lastPartial = p
                        lastChange = now
                    } else if (p.isNotEmpty() && now - lastChange >= STABLE_MS) {
                        // The words stopped changing, so you're done. Don't wait for the slower timer.
                        val t = textOf(f.getFinalResult())
                        f.reset()
                        lastPartial = ""
                        if (t != null) main.post { onText(t) }
                    }
                }
            }
        } catch (e: Exception) {
            say("Voice problem: " + (e.message ?: e.javaClass.simpleName))
        } finally {
            try {
                record?.stop()
            } catch (e: Exception) {
            }
            try {
                record?.release()
            } catch (e: Exception) {
            }
            try {
                full?.close()
            } catch (e: Exception) {
            }
            try {
                spotter?.close()
            } catch (e: Exception) {
            }
        }
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

    private fun textOf(json: String?): String? {
        if (json == null) return null
        return try {
            JSONObject(json).optString("text").trim().ifEmpty { null }
        } catch (e: Exception) {
            null
        }
    }

    private fun partialOf(json: String?): String? {
        if (json == null) return null
        return try {
            JSONObject(json).optString("partial").trim().ifEmpty { null }
        } catch (e: Exception) {
            null
        }
    }

    companion object {
        private const val RATE = 16000
        private const val STABLE_MS = 450L
    }
}

/**
 * Reads answers out loud. Quest has no speech engine of its own, so this only works if you
 * have installed one, such as the free eSpeak app. Otherwise it stays silent.
 */
class Speaker(context: Context) {
    private val app = context.applicationContext
    private var tts: TextToSpeech? = null

    @Volatile
    var ready = false
        private set

    init {
        open(ESPEAK)
    }

    private fun open(engine: String?) {
        var created: TextToSpeech? = null
        val listener = TextToSpeech.OnInitListener { status ->
            val t = created
            if (status == TextToSpeech.SUCCESS && t != null) {
                try {
                    t.language = Locale.US
                } catch (e: Exception) {
                }
                tts = t
                ready = true
            } else {
                try {
                    t?.shutdown()
                } catch (e: Exception) {
                }
                if (engine != null) open(null)
            }
        }
        created = try {
            TextToSpeech(app, listener, engine)
        } catch (e: Exception) {
            null
        }
        if (created == null && engine != null) open(null)
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

    companion object {
        private const val ESPEAK = "com.reecedunn.espeak"
    }
}

/** Short sounds made by the app itself, so they don't depend on any system sound. */
class Beeper {
    private val rate = 22050
    private val wakePcm = makeTone(doubleArrayOf(880.0, 1320.0), 110)
    private val okPcm = makeTone(doubleArrayOf(1100.0, 1650.0, 2200.0), 70)
    private val failPcm = makeTone(doubleArrayOf(330.0, 220.0), 130)

    private fun makeTone(freqs: DoubleArray, segMs: Int): ShortArray {
        val seg = rate * segMs / 1000
        val out = ShortArray(seg * freqs.size)
        for (k in freqs.indices) {
            for (i in 0 until seg) {
                val t = i.toDouble() / rate
                val edge = minOf(i, seg - i).toDouble() / (rate * 0.008)
                val env = if (edge > 1.0) 1.0 else edge
                out[k * seg + i] = (Math.sin(2.0 * Math.PI * freqs[k] * t) * env * 0.5 * 32767.0).toInt().toShort()
            }
        }
        return out
    }

    /** Heard "Hey BBB". */
    fun wake() = play(wakePcm)

    /** Did what you asked. */
    fun ok() = play(okPcm)

    /** Couldn't do it. */
    fun fail() = play(failPcm)

    private fun play(pcm: ShortArray) {
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
                Thread.sleep(pcm.size * 1000L / rate + 200L)
                track.release()
            } catch (e: Exception) {
            }
        }.start()
    }
}
