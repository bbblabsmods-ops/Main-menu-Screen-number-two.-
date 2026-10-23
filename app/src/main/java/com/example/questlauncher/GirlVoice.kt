package com.example.questlauncher

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.MediaPlayer
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Speaks answers out loud in a girl voice.
 * 1. Gemini text-to-speech (free with your Gemini key)
 * 2. Google Translate's voice (no key)
 * 3. Whatever [fallback] does (the eSpeak app, if you have it)
 */
class GirlVoice(
    private val context: Context,
    private val fallback: (String) -> Unit,
) {

    private val prefs = context.getSharedPreferences("assistant", Context.MODE_PRIVATE)

    @Volatile private var track: AudioTrack? = null
    @Volatile private var player: MediaPlayer? = null
    @Volatile private var generation = 0

    fun enabled(): Boolean = prefs.getBoolean("speak", true)

    fun voiceName(): String = prefs.getString("voice_name", "Kore") ?: "Kore"

    /** Stops talking right away. */
    fun stop() {
        generation++
        try {
            track?.stop()
        } catch (e: Exception) {
        }
        try {
            player?.stop()
        } catch (e: Exception) {
        }
    }

    fun shutdown() {
        stop()
    }

    fun speak(raw: String) {
        if (!enabled()) return
        val text = clean(raw)
        if (text.isEmpty()) return
        stop()
        val mine = generation
        Thread {
            var done = false
            val key = prefs.getString("gemini_key", "") ?: ""
            if (key.isNotEmpty()) {
                for (model in listOf("gemini-3.8-flash-tts", "gemini-3.8-flash-lite-tts")) {
                    if (mine != generation) return@Thread
                    val wav = fetchGemini(model, key, text)
                    if (wav != null) {
                        done = playWav(wav, mine)
                        if (done) break
                    }
                }
            }
            if (!done && mine == generation) {
                done = playTranslate(text, mine)
            }
            if (!done && mine == generation) {
                fallback(text)
            }
        }.start()
    }

    private fun clean(raw: String): String {
        var t = raw.replace("*", "").replace("#", "").replace("`", "").replace("_", " ")
        t = t.replace(Regex("\\s+"), " ").trim()
        if (t.length > 300) {
            val cut = t.substring(0, 300)
            val end = cut.lastIndexOf(". ")
            t = if (end > 100) cut.substring(0, end + 1) else cut
        }
        return t
    }

    // ---- Gemini ----

    private fun fetchGemini(model: String, key: String, text: String): ByteArray? {
        return try {
            val body = JSONObject()
                .put("model", model)
                .put(
                    "input",
                    JSONArray().put(
                        JSONObject()
                            .put("type", "user_input")
                            .put("content", JSONArray().put(JSONObject().put("type", "text").put("text", text)))
                    )
                )
                .put("response_format", JSONObject().put("type", "audio"))
                .put(
                    "generation_config",
                    JSONObject().put("speech_config", JSONArray().put(JSONObject().put("voice", voiceName())))
                )
            val c = URL("https://generativelanguage.googleapis.com/v1beta/interactions")
                .openConnection() as HttpURLConnection
            c.requestMethod = "POST"
            c.connectTimeout = 8000
            c.readTimeout = 20000
            c.setRequestProperty("x-goog-api-key", key)
            c.setRequestProperty("Content-Type", "application/json")
            c.doOutput = true
            c.outputStream.use { it.write(body.toString().toByteArray()) }
            val code = c.responseCode
            val stream = if (code in 200..299) c.inputStream else c.errorStream
            val reply = stream?.bufferedReader()?.use { it.readText() } ?: ""
            c.disconnect()
            if (code !in 200..299) return null
            val data = findData(JSONObject(reply)) ?: return null
            Base64.decode(data, Base64.DEFAULT)
        } catch (e: Exception) {
            null
        }
    }

    /** Finds the long base64 "data" value wherever the reply puts it. */
    private fun findData(o: Any?): String? {
        if (o is JSONObject) {
            val d = o.optString("data", "")
            if (d.length > 200) return d
            val keys = o.keys()
            while (keys.hasNext()) {
                val found = findData(o.opt(keys.next()))
                if (found != null) return found
            }
        } else if (o is JSONArray) {
            for (i in 0 until o.length()) {
                val found = findData(o.opt(i))
                if (found != null) return found
            }
        }
        return null
    }

    private fun playWav(bytes: ByteArray, mine: Int): Boolean {
        var rate = 24000
        var start = 0
        var end = bytes.size
        if (bytes.size > 44 && bytes[0] == 'R'.code.toByte() && bytes[1] == 'I'.code.toByte()) {
            rate = le32(bytes, 24).let { if (it in 8000..48000) it else 24000 }
            var p = 12
            var found = false
            while (p + 8 <= bytes.size) {
                val id = String(bytes, p, 4, Charsets.US_ASCII)
                val size = le32(bytes, p + 4)
                if (id == "data") {
                    start = p + 8
                    end = if (size > 0 && start + size <= bytes.size) start + size else bytes.size
                    found = true
                    break
                }
                p += 8 + size
            }
            if (!found) start = 44
        }
        val len = (end - start) and 1.inv()
        if (len <= 0) return false
        if (mine != generation) return true

        var t: AudioTrack? = null
        return try {
            t = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANT)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(rate)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setBufferSizeInBytes(len)
                .setTransferMode(AudioTrack.MODE_STATIC)
                .build()
            t.write(bytes, start, len)
            track = t
            if (mine != generation) return true
            t.play()
            val frames = len / 2
            val limit = System.currentTimeMillis() + frames * 1000L / rate + 2000L
            while (mine == generation && t.playbackHeadPosition < frames && System.currentTimeMillis() < limit) {
                Thread.sleep(40)
            }
            true
        } catch (e: Exception) {
            false
        } finally {
            try {
                t?.stop()
            } catch (e: Exception) {
            }
            try {
                t?.release()
            } catch (e: Exception) {
            }
            if (track === t) track = null
        }
    }

    private fun le32(b: ByteArray, o: Int): Int =
        (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8) or
            ((b[o + 2].toInt() and 0xFF) shl 16) or ((b[o + 3].toInt() and 0xFF) shl 24)

    // ---- Google Translate voice (no key) ----

    private fun playTranslate(text: String, mine: Int): Boolean {
        var mp: MediaPlayer? = null
        var file: File? = null
        return try {
            var part = text
            if (part.length > 190) {
                val cut = part.substring(0, 190)
                val end = cut.lastIndexOf(". ")
                part = if (end > 60) cut.substring(0, end + 1) else cut.substring(0, cut.lastIndexOf(' ').let { if (it > 40) it else 190 })
            }
            val url = "https://translate.google.com/translate_tts?ie=UTF-8&client=tw-ob&tl=en&q=" +
                URLEncoder.encode(part, "UTF-8")
            val c = URL(url).openConnection() as HttpURLConnection
            c.connectTimeout = 8000
            c.readTimeout = 15000
            c.setRequestProperty("User-Agent", "Mozilla/5.0")
            if (c.responseCode != 200) return false
            file = File(context.cacheDir, "bbb_voice.mp3")
            c.inputStream.use { input -> file.outputStream().use { input.copyTo(it) } }
            c.disconnect()
            if (file.length() < 500 || mine != generation) return file.length() >= 500

            val latch = CountDownLatch(1)
            mp = MediaPlayer()
            player = mp
            mp.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANT)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            mp.setDataSource(file.absolutePath)
            mp.setOnCompletionListener { latch.countDown() }
            mp.setOnErrorListener { _, _, _ ->
                latch.countDown()
                true
            }
            mp.prepare()
            if (mine != generation) return true
            mp.start()
            latch.await(20, TimeUnit.SECONDS)
            true
        } catch (e: Exception) {
            false
        } finally {
            try {
                mp?.release()
            } catch (e: Exception) {
            }
            if (player === mp) player = null
        }
    }
}
