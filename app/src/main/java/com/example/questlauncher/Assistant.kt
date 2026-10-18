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

    /** Set by the background service so apps open even when the launcher isn't on screen. */
    var launcher: ((AppEntry) -> Unit)? = null

    fun apiKey(): String = prefs.getString("claude_key", "") ?: ""
    fun saveApiKey(key: String) { prefs.edit().putString("claude_key", key.trim()).apply() }

    fun geminiKey(): String = prefs.getString("gemini_key", "") ?: ""
    fun saveGeminiKey(key: String) { prefs.edit().putString("gemini_key", key.trim()).apply() }

    fun geminiModel(): String = prefs.getString("gemini_model", "gemini-3.8-flash") ?: "gemini-3.8-flash"
    fun saveGeminiModel(name: String) {
        prefs.edit().putString("gemini_model", name.trim().ifEmpty { "gemini-3.8-flash" }).apply()
    }

    fun wakeWord(): Boolean = prefs.getBoolean("wake_word", true)
    fun setWakeWord(on: Boolean) { prefs.edit().putBoolean("wake_word", on).apply() }

    fun voiceOn(): Boolean = prefs.getBoolean("voice_on", false)
    fun setVoiceOn(on: Boolean) { prefs.edit().putBoolean("voice_on", on).apply() }

    private val wakeRegex = Regex(
        "^\\s*(?:hey|hi|ok|okay|hello|yo)[,.\\s]+(?:b\\.?\\s?b\\.?\\s?b\\.?|bee\\s?bee\\s?bee|triple\\s?b)" +
            "(?:\\s+assistant)?[,.:!?\\s]*",
        RegexOption.IGNORE_CASE,
    )

    // How the speech recognizer tends to write "hey BBB": "hey b b b", "hey be be be", "hey bbc"...
    private val spokenWake = Regex(
        "^(?:hey|hay|hi|ok|okay|hello|a)\\s+(?:bbb|bbc|bb|(?:(?:b|be|bee|d|p|v)\\s+){1,2}(?:b|be|bee|d|p|v))\\b"
    )

    /** Returns "hey bbb <rest>" if the spoken text starts with the wake phrase, otherwise null. */
    fun normalizeSpoken(heard: String): String? {
        val t = heard.trim().lowercase()
        val m = spokenWake.find(t) ?: return null
        val rest = t.substring(m.range.last + 1).trim()
        return if (rest.isEmpty()) "hey bbb" else "hey bbb $rest"
    }

    private val helpText =
        "I can open apps (\"open Beat Saber\"), do math (\"what is 12 times 12\"), tell you the time, " +
            "date or battery, set the refresh rate (\"set 90 Hz\"), open Settings, tell stories, " +
            "and answer questions."

    suspend fun reply(input: String, history: List<ChatMessage>): String {
        var rest = input.trim()
        val wake = wakeRegex.find(rest)
        if (wake != null) {
            rest = rest.substring(wake.range.last + 1)
        } else if (wakeWord()) {
            return "Say \"Hey BBB\" first, like: \"Hey BBB, what time is it?\" " +
                "(You can turn this off in AI settings.)"
        }
        rest = rest.trim().trimEnd('?', '!', '.', ' ', ',')
        val q = rest.lowercase()
        if (q.isEmpty()) return "Sup"

        val local = withContext(Dispatchers.Main) { tryCommand(q) }
        if (local != null) return local

        // The question to send to an AI, without the "Hey BBB" part.
        val aiHistory = history.dropLast(1) + ChatMessage(true, rest)

        return withContext(Dispatchers.IO) {
            val claude = apiKey()
            val gemini = geminiKey()
            if (claude.isNotEmpty()) {
                askClaude(claude, aiHistory)
            } else if (gemini.isNotEmpty()) {
                askGemini(gemini, aiHistory)
            } else {
                val free = askFree(aiHistory)
                if (free != null) {
                    free
                } else if (isStory(q)) {
                    stories.random()
                } else {
                    val term = lookupTerm(q)
                    val answer = if (term != null && term.length > 2) webAnswer(term) else null
                    answer ?: ("The free AI didn't answer (it may be busy). Try again in a few seconds. " +
                        "For steadier answers add a free Gemini key in AI settings.")
                }
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
            val hz = Regex("(\\d{2,3})").find(q)?.groupValues?.get(1)?.toIntOrNull()
            if (hz != null) {
                viewModel.setRefreshRate(hz)
                return "Setting the refresh rate to $hz Hz. A message will tell you if it worked."
            }
        }

        val open = Regex("^(?:please\\s+)?(?:open|launch|start|play|run)\\s+(?:the\\s+)?(.+)$").find(q)
        if (open != null) return openByName(open.groupValues[1].trim())

        return tryMath(q)
    }

    private fun isStory(q: String): Boolean =
        Regex("\\b(story|tale)\\b").containsMatchIn(q) &&
            Regex("^(tell|read|give|say|write|i want|i need|can you tell)").containsMatchIn(q)

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
            val custom = launcher
            if (custom != null) custom(hit) else viewModel.launch(hit)
            return "Opening ${hit.label}."
        }
        if (n.contains("youtube")) {
            viewModel.openYouTubeVr()
            return "Opening YouTube VR."
        }
        return "I couldn't find an app called \"$name\"."
    }

    // ---- math ----

    private val smallNumbers = mapOf(
        "zero" to 0, "one" to 1, "two" to 2, "three" to 3, "four" to 4, "five" to 5,
        "six" to 6, "seven" to 7, "eight" to 8, "nine" to 9, "ten" to 10, "eleven" to 11,
        "twelve" to 12, "thirteen" to 13, "fourteen" to 14, "fifteen" to 15, "sixteen" to 16,
        "seventeen" to 17, "eighteen" to 18, "nineteen" to 19,
    )
    private val tensNumbers = mapOf(
        "twenty" to 20, "thirty" to 30, "forty" to 40, "fifty" to 50,
        "sixty" to 60, "seventy" to 70, "eighty" to 80, "ninety" to 90,
    )

    private fun wordsToDigits(s: String): String {
        val out = ArrayList<String>()
        var total = 0L
        var current = 0L
        var inNumber = false

        fun flush() {
            if (inNumber) {
                out.add((total + current).toString())
                total = 0L
                current = 0L
                inNumber = false
            }
        }

        for (token in s.split(Regex("\\s+"))) {
            val w = token.trimEnd(',', '?')
            val small = smallNumbers[w]
            val tens = tensNumbers[w]
            when {
                small != null -> { current += small; inNumber = true }
                tens != null -> { current += tens; inNumber = true }
                w == "hundred" && inNumber -> current = (if (current == 0L) 1L else current) * 100L
                w == "thousand" && inNumber -> { total += (if (current == 0L) 1L else current) * 1000L; current = 0L }
                w == "million" && inNumber -> { total += (if (current == 0L) 1L else current) * 1000000L; current = 0L }
                w == "and" && inNumber -> { }
                else -> { flush(); out.add(token) }
            }
        }
        flush()
        return out.joinToString(" ")
    }

    private fun tryMath(q: String): String? {
        var s = q.replace(Regex("^(what is|what's|whats|calculate|compute|how much is|what does|work out|solve)\\s+"), "")
        s = s.replace(Regex("\\s+equal$"), "")
        s = wordsToDigits(s)
        s = s.replace("multiplied by", "*").replace("divided by", "/").replace("to the power of", "^")
            .replace("squared", "^2").replace("cubed", "^3")
            .replace("plus", "+").replace("minus", "-").replace("times", "*")
            .replace("×", "*").replace("÷", "/")
        s = s.replace(Regex("(?<=\\d)\\s+over\\s+(?=\\d)"), "/")
        s = s.replace(Regex("(?<=\\d)\\s*x\\s*(?=\\d)"), "*")
        s = Regex("(\\d+(?:\\.\\d+)?)\\s*(?:percent|%)\\s*of\\s*(\\d+(?:\\.\\d+)?)").replace(s) {
            "(" + it.groupValues[1] + "/100*" + it.groupValues[2] + ")"
        }
        s = Regex("square root of\\s*(\\d+(?:\\.\\d+)?)").replace(s) { "sqrt(" + it.groupValues[1] + ")" }
        s = s.trim()

        val plain = s.replace("sqrt", "")
        if (!Regex("^[0-9+\\-*/^().\\s]+$").matches(plain)) return null
        if (!plain.any { it.isDigit() }) return null
        if (!(plain.any { it in "+-*/^" } || s.contains("sqrt"))) return null

        return try {
            val value = Calc(s).parse()
            if (value.isNaN() || value.isInfinite()) "You can't divide by zero."
            else "$s = " + formatNumber(value)
        } catch (e: Exception) {
            null
        }
    }

    private fun formatNumber(d: Double): String =
        if (d == Math.floor(d) && Math.abs(d) < 1e15) d.toLong().toString()
        else String.format(Locale.US, "%.6f", d).trimEnd('0').trimEnd('.')

    private class Calc(private val s: String) {
        private var i = 0

        fun parse(): Double {
            val v = expr()
            skip()
            if (i != s.length) throw IllegalArgumentException("extra text")
            return v
        }

        private fun skip() {
            while (i < s.length && s[i] == ' ') i++
        }

        private fun peek(): Char {
            skip()
            return if (i < s.length) s[i] else '\u0000'
        }

        private fun expr(): Double {
            var v = term()
            while (true) {
                val c = peek()
                if (c == '+') { i++; v += term() }
                else if (c == '-') { i++; v -= term() }
                else return v
            }
        }

        private fun term(): Double {
            var v = power()
            while (true) {
                val c = peek()
                if (c == '*') { i++; v *= power() }
                else if (c == '/') { i++; v /= power() }
                else return v
            }
        }

        private fun power(): Double {
            val base = unary()
            if (peek() == '^') {
                i++
                return Math.pow(base, power())
            }
            return base
        }

        private fun unary(): Double {
            val c = peek()
            if (c == '-') { i++; return -unary() }
            if (c == '+') { i++; return unary() }
            return atom()
        }

        private fun atom(): Double {
            val c = peek()
            if (c == '(') {
                i++
                val v = expr()
                if (peek() != ')') throw IllegalArgumentException("missing )")
                i++
                return v
            }
            if (s.startsWith("sqrt", i)) {
                i += 4
                if (peek() != '(') throw IllegalArgumentException("sqrt needs (")
                i++
                val v = expr()
                if (peek() != ')') throw IllegalArgumentException("missing )")
                i++
                return Math.sqrt(v)
            }
            val start = i
            while (i < s.length && (s[i].isDigit() || s[i] == '.')) i++
            if (start == i) throw IllegalArgumentException("number expected")
            return s.substring(start, i).toDouble()
        }
    }

    // ---- stories (last resort when no AI answers) ----

    private val stories = listOf(
        "The Lighthouse Keeper's Log\n\n" +
            "The lamp at Cape Wren had burned for ninety years, and old Ansel had tended it for forty. " +
            "Each night he wrote one line in the log: No ships. One winter evening he wrote instead: " +
            "One ship, small, lost. He climbed the stairs, trimmed the wick, and held the light steady " +
            "until dawn, long after his arms shook. In the morning a fishing boat rocked in the harbour, " +
            "and a girl on its deck waved at the tower as if at an old friend. Ansel never learned her name. " +
            "That night he wrote, No ships, and for the first time the words felt like a promise rather " +
            "than a complaint.",
        "The Clockmaker's Daughter\n\n" +
            "She inherited forty clocks and no sense of time. They all disagreed, each insisting on its own " +
            "hour, and for a year she tried to make them agree. Then one evening, in the quiet between two " +
            "chimes, she understood: her father had never wanted them to agree. Each clock kept a different " +
            "moment: the morning he taught her to read the dial, the night her mother sang, the afternoon " +
            "the rain finally stopped. She stopped winding them to match. She wound them to remember. " +
            "Visitors found the house loud with contradictions and said it felt, strangely, like being alive.",
        "The Scarecrow Who Looked Up\n\n" +
            "On the last night of the harvest, the scarecrow climbed down from his pole to see what the stars " +
            "looked like without a field in the way. He had guarded the crops for so long he had never once " +
            "looked up. He sat on the hill and watched the sky turn slowly, and understood that all those " +
            "years he had been guarding the wheat from crows, when the real danger was the loneliness of " +
            "a thing that watches the ground and never the heavens. At dawn he climbed back to his post, " +
            "but he left his face tilted upward, and the farmers, puzzled, never turned it back.",
    )

    // ---- looking things up ----

    private fun lookupTerm(q: String): String? {
        val prefix = Regex(
            "^(what is|what's|what are|who is|who's|who was|tell me something about|tell me about|" +
                "search for|search|google|look up|define|explain|why is|how does)\\s+(?:a |an |the )?"
        )
        return if (prefix.containsMatchIn(q)) q.replace(prefix, "").trim() else null
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

    // ---- web helpers ----

    private fun httpGet(url: String): String? =
        try {
            val c = URL(url).openConnection() as HttpURLConnection
            c.connectTimeout = 8000
            c.readTimeout = 20000
            c.setRequestProperty("User-Agent", "BBBLauncher/1.0 (Quest launcher app)")
            try {
                if (c.responseCode == 200) c.inputStream.bufferedReader().use { it.readText() } else null
            } finally {
                c.disconnect()
            }
        } catch (e: Exception) {
            null
        }

    private fun postJson(url: String, headers: Map<String, String>, body: String, readTimeout: Int): Pair<Int, String>? =
        try {
            val c = URL(url).openConnection() as HttpURLConnection
            c.requestMethod = "POST"
            c.connectTimeout = 10000
            c.readTimeout = readTimeout
            for ((k, v) in headers) c.setRequestProperty(k, v)
            c.setRequestProperty("content-type", "application/json")
            c.doOutput = true
            try {
                c.outputStream.use { it.write(body.toByteArray()) }
                val code = c.responseCode
                val stream = if (code in 200..299) c.inputStream else c.errorStream
                val text = stream?.bufferedReader()?.use { it.readText() } ?: ""
                Pair(code, text)
            } finally {
                c.disconnect()
            }
        } catch (e: Exception) {
            null
        }

    private fun errorMessage(text: String?): String {
        if (text == null) return "no answer"
        return try {
            JSONObject(text).getJSONObject("error").getString("message")
        } catch (e: Exception) {
            text.take(120)
        }
    }

    private fun systemPrompt(): String {
        val now = SimpleDateFormat("EEEE, MMMM d, yyyy h:mm a", Locale.getDefault()).format(Date())
        return "You are BBB, the voice assistant inside BBB Launcher on a Meta Quest headset. " +
            "The user says \"Hey BBB\" to talk to you. Answer in plain text without markdown or lists. " +
            "Keep answers short (under 80 words) because they may be read aloud, except when asked for " +
            "a story or something long. The current date and time is $now. " +
            "Opening apps, the time, battery, math and refresh rate are handled by the launcher itself."
    }

    private fun transcript(history: List<ChatMessage>): String =
        history.takeLast(8).joinToString("\n") { (if (it.fromUser) "User: " else "Assistant: ") + it.text } +
            "\nAssistant:"

    // ---- free AI with no key ----

    private fun usable(t: String): Boolean {
        val l = t.lowercase()
        return t.isNotBlank() && !(l.contains("deprecat") || l.contains("enter.pollinations") ||
            l.contains("api key") || l.contains("important notice"))
    }

    private fun askFree(history: List<ChatMessage>): String? {
        val msgs = JSONArray().put(JSONObject().put("role", "system").put("content", systemPrompt()))
        history.takeLast(6).dropWhile { !it.fromUser }.forEach { m ->
            msgs.put(JSONObject().put("role", if (m.fromUser) "user" else "assistant").put("content", m.text))
        }
        val body = JSONObject().put("model", "openai").put("messages", msgs).toString()
        val r = postJson("https://text.pollinations.ai/openai", emptyMap(), body, 30000)
        if (r != null && r.first in 200..299) {
            try {
                val t = JSONObject(r.second).getJSONArray("choices").getJSONObject(0)
                    .getJSONObject("message").getString("content")
                if (usable(t)) return t.trim()
            } catch (e: Exception) {
            }
        }

        val last = history.lastOrNull { it.fromUser }?.text ?: return null
        val enc = URLEncoder.encode(last, "UTF-8").replace("+", "%20")
        val sys = URLEncoder.encode(systemPrompt(), "UTF-8").replace("+", "%20")
        val get = httpGet("https://text.pollinations.ai/$enc?system=$sys&model=openai")
        return if (get != null && usable(get)) get.trim() else null
    }

    // ---- Google Gemini (free key from aistudio.google.com/apikey) ----

    private fun extractGemini(text: String): String? {
        return try {
            val o = JSONObject(text)
            val steps = o.optJSONArray("steps")
            if (steps != null) {
                for (i in steps.length() - 1 downTo 0) {
                    val content = steps.getJSONObject(i).optJSONArray("content") ?: continue
                    val sb = StringBuilder()
                    for (j in 0 until content.length()) sb.append(content.getJSONObject(j).optString("text"))
                    if (sb.isNotBlank()) return sb.toString().trim()
                }
            }
            val parts = o.optJSONArray("candidates")?.optJSONObject(0)?.optJSONObject("content")?.optJSONArray("parts")
            if (parts != null) {
                val sb = StringBuilder()
                for (j in 0 until parts.length()) sb.append(parts.getJSONObject(j).optString("text"))
                if (sb.isNotBlank()) return sb.toString().trim()
            }
            null
        } catch (e: Exception) {
            null
        }
    }

    private fun askGemini(key: String, history: List<ChatMessage>): String {
        val model = geminiModel()
        val headers = mapOf("x-goog-api-key" to key)
        val input = transcript(history)

        val body1 = JSONObject()
            .put("model", model)
            .put("system_instruction", systemPrompt())
            .put("input", input)
            .put("store", false)
        val r1 = postJson("https://generativelanguage.googleapis.com/v1beta/interactions", headers, body1.toString(), 40000)
        if (r1 != null && r1.first in 200..299) {
            val t = extractGemini(r1.second)
            if (t != null) return t
        }

        val body2 = JSONObject()
            .put("system_instruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", systemPrompt()))))
            .put(
                "contents",
                JSONArray().put(
                    JSONObject().put("role", "user")
                        .put("parts", JSONArray().put(JSONObject().put("text", input)))
                )
            )
        val r2 = postJson(
            "https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent",
            headers, body2.toString(), 40000,
        )
        if (r2 != null && r2.first in 200..299) {
            val t = extractGemini(r2.second)
            if (t != null) return t
        }

        val err = r2 ?: r1
        return if (err == null) "I couldn't reach Gemini. Check the Wi-Fi."
        else "Gemini error ${err.first}: ${errorMessage(err.second)}"
    }

    // ---- Claude (paid, optional) ----

    private fun askClaude(key: String, history: List<ChatMessage>): String {
        val msgs = JSONArray()
        history.takeLast(8).dropWhile { !it.fromUser }.forEach { m ->
            msgs.put(JSONObject().put("role", if (m.fromUser) "user" else "assistant").put("content", m.text))
        }
        val body = JSONObject()
            .put("model", "claude-haiku-5-5")
            .put("max_tokens", 900)
            .put("system", systemPrompt())
            .put("messages", msgs)
        val r = postJson(
            "https://api.anthropic.com/v1/messages",
            mapOf("x-api-key" to key, "anthropic-version" to "2023-06-01"),
            body.toString(), 40000,
        ) ?: return "I couldn't reach Claude. Check the Wi-Fi."
        if (r.first in 200..299) {
            try {
                return JSONObject(r.second).getJSONArray("content").getJSONObject(0).getString("text")
            } catch (e: Exception) {
            }
        }
        return "Claude error ${r.first}: ${errorMessage(r.second)}"
    }
}
