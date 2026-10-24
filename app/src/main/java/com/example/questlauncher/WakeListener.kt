package com.example.questlauncher

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import org.json.JSONArray
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream

/**
 * Listens on the headset, but in two stages so it only understands you after "Hey BBB":
 *  1. A strict listener that can only recognise "hey b b b". It writes everything else as
 *     "unknown", so it cannot turn your other speech into words.
 *  2. After the wake phrase, a normal listener turns your command into text for a few seconds.
 * About 1.5 seconds of sound is kept in memory (never saved) so the start of your sentence
 * isn't lost when you say "Hey BBB open Gorilla Tag" in one breath.
 * If the microphone drops for any reason, listening restarts by itself.
 */
class WakeListener(
    private val context: Context,
    private val onStatus: (String) -> Unit,
    private val onWake: () -> Unit,
    private val onCommand: (String) -> Unit,
    private val onGiveUp: () -> Unit,
) {

    private val modelName = "vosk-model-small-en-us-0.15"
    private val modelUrl = "https://alphacephei.com/vosk/models/$modelName.zip"
    private val modelDir: File get() = File(context.filesDir, modelName)
    private val main = Handler(Looper.getMainLooper())

    @Volatile private var model: Model? = null
    @Volatile private var wanted = false
    @Volatile private var worker: Thread? = null

    // Only used by the listening thread.
    private var window = false
    private var windowEnd = 0L
    private var lastPartial = ""
    private var lastChange = 0L
    private val ring = ShortArray(RATE * 3 / 2)
    private var ringPos = 0
    private var ringFilled = 0

    private fun say(text: String) {
        main.post { onStatus(text) }
    }

    fun start() {
        wanted = true
        val w = worker
        if (w != null && w.isAlive) return
        val t = Thread {
            var wait = 2000L
            while (wanted) {
                val began = SystemClock.elapsedRealtime()
                listen()
                if (!wanted) break
                // It ended because of a problem. Try again, waiting a little longer each time.
                if (SystemClock.elapsedRealtime() - began > 30000L) wait = 2000L
                try {
                    Thread.sleep(wait)
                } catch (e: InterruptedException) {
                }
                wait = minOf(wait * 2, 15000L)
            }
        }
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

    /** Everything after "hey bbb", however the speech engine wrote it. */
    private fun afterWake(text: String): String {
        val t = text.trim().lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }
        for (i in t.indices) {
            if (t[i] !in HEY_STRICT) continue
            var j = i + 1
            var b = 0
            while (j < t.size && b < 5 && t[j] in BS) {
                j++
                b++
            }
            if (b >= 2 || (b == 1 && t[i + 1] in BIG_B)) return t.drop(j).joinToString(" ")
        }
        // The words look different, but the strict listener did hear the wake phrase.
        var i = 0
        if (i < t.size && t[i] in HEY_LOOSE) i++
        var b = 0
        while (i < t.size && b < 5 && t[i] in BS) {
            i++
            b++
        }
        return t.drop(i).joinToString(" ")
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

    private fun ringWrite(buf: ShortArray, n: Int) {
        for (i in 0 until n) {
            ring[ringPos] = buf[i]
            ringPos = (ringPos + 1) % ring.size
        }
        ringFilled = minOf(ring.size, ringFilled + n)
    }

    private fun ringSnapshot(): ShortArray {
        val out = ShortArray(ringFilled)
        var p = (ringPos - ringFilled + ring.size) % ring.size
        for (i in 0 until ringFilled) {
            out[i] = ring[p]
            p = (p + 1) % ring.size
        }
        return out
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
            val s = Recognizer(m, RATE.toFloat(), wakeGrammar())
            spotter = s

            val r = openMic()
            record = r
            r.startRecording()
            say("Waiting for \"Hey BBB\"…")

            window = false
            lastPartial = ""
            ringPos = 0
            ringFilled = 0

            val buf = ShortArray(800)
            var wakeFired = false
            var emptyReads = 0

            while (wanted) {
                val n = r.read(buf, 0, buf.size)
                if (n < 0) throw IllegalStateException("The microphone stopped (error $n).")
                if (n == 0) {
                    // Some headsets return nothing while another app holds the mic. Don't spin forever.
                    emptyReads++
                    if (emptyReads > 400) throw IllegalStateException("The microphone gave no sound.")
                    Thread.sleep(10)
                    continue
                }
                emptyReads = 0
                val now = SystemClock.elapsedRealtime()
                ringWrite(buf, n)

                // Stage 1: only "hey b b b". Always on.
                val spotDone = s.acceptWaveForm(buf, n)
                val spotText = if (spotDone) textOf(s.getResult()) else partialOf(s.getPartialResult())
                var justOpened = false
                if (!wakeFired && isWake(spotText)) {
                    wakeFired = true
                    if (!window) {
                        main.post { onWake() }
                        openWindow(f, now)
                        justOpened = true
                    }
                }
                if (spotDone) wakeFired = false

                // Stage 2: your command. Only runs after the wake phrase.
                if (window && !justOpened) feedFull(f, buf, n, now)

                if (window && now > windowEnd) {
                    window = false
                    f.reset()
                    lastPartial = ""
                    main.post { onGiveUp() }
                    say("Waiting for \"Hey BBB\"…")
                }
            }
        } catch (e: Exception) {
            say("Voice problem: " + (e.message ?: e.javaClass.simpleName) + ". Trying again…")
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

    /** Starts the command listener and gives it the last moment of sound so nothing is missed. */
    private fun openWindow(f: Recognizer, now: Long) {
        f.reset()
        lastPartial = ""
        lastChange = now
        windowEnd = now + 12000L
        window = true
        val snap = ringSnapshot()
        val chunk = ShortArray(800)
        var i = 0
        while (i < snap.size) {
            val len = minOf(800, snap.size - i)
            System.arraycopy(snap, i, chunk, 0, len)
            feedFull(f, chunk, len, now)
            i += len
            if (!window) break
        }
    }

    private fun feedFull(f: Recognizer, buf: ShortArray, n: Int, now: Long) {
        var text: String? = null
        if (f.acceptWaveForm(buf, n)) {
            lastPartial = ""
            text = textOf(f.getResult())
        } else {
            val p = partialOf(f.getPartialResult()) ?: ""
            if (p != lastPartial) {
                lastPartial = p
                lastChange = now
            } else if (p.isNotEmpty() && now - lastChange >= STABLE_MS) {
                // The words stopped changing, so you're done. Don't wait for the slower timer.
                text = textOf(f.getFinalResult())
                f.reset()
                lastPartial = ""
            }
        }
        if (text != null) handleText(f, text, now)
    }

    private fun handleText(f: Recognizer, text: String, now: Long) {
        val cmd = afterWake(text)
        if (cmd.isNotBlank()) {
            window = false
            f.reset()
            lastPartial = ""
            main.post { onCommand(cmd) }
        } else {
            // Only the wake phrase so far. Keep waiting for the command.
            windowEnd = now + 8000L
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

        private val HEY_STRICT = setOf("hey", "hay", "okay", "ok", "hi", "hello", "they", "ay")
        private val HEY_LOOSE = setOf("hey", "hay", "hi", "he", "a", "okay", "ok", "hello", "they", "ay")
        private val BIG_B = setOf("bbb", "bbc", "bb")
        private val BS = setOf(
            "b", "be", "bee", "bi", "bb", "bbb", "bbc", "bc", "d", "dee", "p", "pea",
            "v", "vee", "by", "bye", "buy", "bay", "been", "bean",
        )
    }
}
