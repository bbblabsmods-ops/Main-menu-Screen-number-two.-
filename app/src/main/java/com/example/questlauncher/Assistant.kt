package com.example.questlauncher

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class ChatMessage(val fromUser: Boolean, val text: String)

class Assistant(private val context: Context, private val viewModel: LauncherViewModel) {

    private val prefs = context.getSharedPreferences("assistant", Context.MODE_PRIVATE)

    fun apiKey(): String = prefs.getString("claude_key", "") ?: ""

    fun saveApiKey(key: String) {
        prefs.edit().putString("claude_key", key.trim()).apply()
    }

    private val helpText =
        "I can open apps (\"open Beat Saber\"), tell you the time, date or battery, " +
            "set the refresh rate (\"set 90 Hz\"), open Settings, and look things up " +
            "(\"what is a black hole\"). Add a Claude key in AI settings to answer anything."

    suspend fun reply(input: String, history: List<ChatMessage>): String {
        val q = input.trim().lowercase().trimEnd('?', '!', '.', ' ')

        val local = withContext(Dispatchers.Main) { tryCommand(q) }
        if (local != null) return local

        return withContext(Dispatchers.IO) {
            val key = apiKey()
            if (key.isNotEmpty()) {
                askClaude(key, history)
            } else {
                val term = searchTerm(q)
                val answer = if (term.length > 2) webAnswer(term) else null
                answer ?: ("I couldn't find an answer for that. " + helpText)
            }
        }
    }

    // ---- things the launcher can do itself ----

    private fun tryCommand(q: String): String? {
        if (q in listOf("hi", "hello", "hey", "help", "what can you do", "what can you do for me")) {
            return helpText
        }

        if (q == "time" || Regex("\\b(what time|the time|time is it|current time)\\b").containsMatchIn(q)) {
            return "It's " + SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date()) + "."
        }
        if (Regex("\\b(date|what day|day is it|today)\\b").containsMatchIn(q) && !q.startsWith("open")) {
            return "Today is " + SimpleDateFormat("EEEE, MMMM d, yyyy", Locale.getDefault()).format(Date()) + "."
        }
        if (q.contains("battery")) {
            val i = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            val level = i?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
            val scale = i?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
            return if (level < 0) "I can't read the battery right now." else "The battery is at ${level * 100 / scale}%."
        }

        if (q.contains("refresh") || q.contains("hz") || q.contains("hertz")) {
            val m = Regex("(\\d{2,3})").find(q)
            val hz = m?.groupValues?.get(1)?.toIntOrNull()
            if (hz != null) {
                viewModel.setRefreshRate(hz)
                return "Setting the refresh rate to $hz Hz. A message will tell you if it worked."
            }
        }

        val open = Regex("^(?:please\\s+)?(?:open|launch|start|play|run)\\s+(?:the\\s+)?(.+)$").find(q)
        if (open != null) return openByName(open.groupValues[1].trim())

        return null
    }

    private fun openByName(name: String): String {
        val n = name.lowercase()
        if (n.contains("developer")) {
            viewModel.openDeveloperSettings()
            return "Opening developer options."
        }
        if (n == "settings" || n == "android settings") {
            viewModel.openAndroidSettings()
            return "Opening Android Settings."
        }

        val apps = viewModel.apps.value
        val hit = apps.firstOrNull { it.label.lowercase() == n }
            ?: apps.firstOrNull { it.label.lowercase().contains(n) }
            ?: apps.firstOrNull { n.contains(it.label.lowercase()) }
            ?: apps.firstOrNull { a ->
                n.split(" ").any { w -> w.length > 3 && a.label.lowercase().contains(w) }
            }

        if (hit != null) {
            viewModel.launch(hit)
            return "Opening ${hit.label}."
        }
        if (n.contains("youtube")) {
            viewModel.openYouTubeVr()
            return "Opening YouTube VR."
        }
        return "I couldn't find an app called \"$name\"."
    }

    // ---- looking things up ----

    private fun searchTerm(q: String): String {
        val prefix = Regex(
            "^(what is|what's|what are|who is|who's|who was|tell me something about|tell me about|" +
                "search for|search|google|look up|define|explain|why is|how does)\\s+(?:a |an |the )?"
        )
        return q.replace(prefix, "").trim()
    }

    private fun webAnswer(term: String): String? {
        val enc = URLEncoder.encode(term, "UTF-8")

        val ddg = httpGet("https://api.duckduckgo.com/?q=$enc&format=json&no_html=1&skip_disambig=1")
        if (ddg != null) {
            try {
                val o = JSONObject(ddg)
                val abs = o.optString("AbstractText")
                if (abs.isNotBlank()) return shorten(abs)
                val ans = o.optString("Answer")
                if (ans.isNotBlank()) return ans
            } catch (e: Exception) {
            }
        }

        val search = httpGet("https://en.wikipedia.org/w/api.php?action=query&list=search&srsearch=$enc&utf8=1&format=json&srlimit=1")
        if (search != null) {
            try {
                val hits = JSONObject(search).getJSONObject("query").getJSONArray("search")
                if (hits.length() > 0) {
                    val title = hits.getJSONObject(0).getString("title")
                    val t = URLEncoder.encode(title, "UTF-8").replace("+", "%20")
                    val summary = httpGet("https://en.wikipedia.org/api/rest_v1/page/summary/$t")
                    if (summary != null) {
                        val extract = JSONObject(summary).optString("extract")
                        if (extract.isNotBlank()) return shorten(extract)
                    }
                }
            } catch (e: Exception) {
            }
        }
        return null
    }

    private fun shorten(text: String): String {
        if (text.length <= 480) return text
        val cut = text.substring(0, 480)
        val end = cut.lastIndexOf(". ")
        return if (end > 200) cut.substring(0, end + 1) else cut + "…"
    }

    private fun httpGet(url: String): String? =
        try {
            val c = URL(url).openConnection() as HttpURLConnection
            c.connectTimeout = 8000
            c.readTimeout = 10000
            c.setRequestProperty("User-Agent", "BBBLauncher/1.0 (Quest launcher app)")
            try {
                if (c.responseCode == 200) c.inputStream.bufferedReader().use { it.readText() } else null
            } finally {
                c.disconnect()
            }
        } catch (e: Exception) {
            null
        }

    // ---- real AI (needs a Claude API key) ----

    private fun askClaude(key: String, history: List<ChatMessage>): String {
        return try {
            val now = SimpleDateFormat("EEEE, MMMM d, yyyy h:mm a", Locale.getDefault()).format(Date())
            val system = "You are the assistant inside BBB Launcher, a launcher app on a Meta Quest headset. " +
                "Answer briefly, in under 100 words, in plain text. The current date and time is $now. " +
                "Opening apps, the time, battery and refresh rate are handled by the launcher itself " +
                "when the user types for example \"open Beat Saber\"."

            val msgs = JSONArray()
            history.takeLast(8).dropWhile { !it.fromUser }.forEach { m ->
                msgs.put(
                    JSONObject()
                        .put("role", if (m.fromUser) "user" else "assistant")
                        .put("content", m.text)
                )
            }
            val body = JSONObject()
                .put("model", "claude-haiku-5-5")
                .put("max_tokens", 400)
                .put("system", system)
                .put("messages", msgs)

            val c = URL("https://api.anthropic.com/v1/messages").openConnection() as HttpURLConnection
            c.requestMethod = "POST"
            c.connectTimeout = 10000
            c.readTimeout = 30000
            c.setRequestProperty("x-api-key", key)
            c.setRequestProperty("anthropic-version", "2023-06-01")
            c.setRequestProperty("content-type", "application/json")
            c.doOutput = true
            try {
                c.outputStream.use { it.write(body.toString().toByteArray()) }
                val code = c.responseCode
                val stream = if (code in 200..299) c.inputStream else c.errorStream
                val text = stream?.bufferedReader()?.use { it.readText() } ?: ""
                if (code in 200..299) {
                    JSONObject(text).getJSONArray("content").getJSONObject(0).getString("text")
                } else {
                    val msg = try {
                        JSONObject(text).getJSONObject("error").getString("message")
                    } catch (e: Exception) {
                        text.take(120)
                    }
                    "Claude error $code: $msg"
                }
            } finally {
                c.disconnect()
            }
        } catch (e: Exception) {
            "I couldn't reach Claude. Check the Wi-Fi. (${e.javaClass.simpleName})"
        }
    }
}
