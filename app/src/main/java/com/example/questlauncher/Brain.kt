package com.example.questlauncher

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Handles the commands that control the headset (close games, volume, screenshot, list games),
 * web and Llama questions, then hands everything else (open an app, time, math, stories) to the Assistant.
 */
class Brain(
    private val context: Context,
    private val viewModel: LauncherViewModel,
    private val assistant: Assistant,
) {

    private val prefs = context.getSharedPreferences("assistant", Context.MODE_PRIVATE)

    fun tavilyKey(): String = prefs.getString("tavily_key", "") ?: ""
    fun saveTavilyKey(key: String) {
        prefs.edit().putString("tavily_key", key.trim()).apply()
    }

    fun groqKey(): String = prefs.getString("groq_key", "") ?: ""
    fun saveGroqKey(key: String) {
        prefs.edit().putString("groq_key", key.trim()).apply()
    }

    private val wakeRegex = Regex(
        "^\\s*(?:hey|hi|ok|okay|hello|yo)[,.\\s]+(?:b\\.?\\s?b\\.?\\s?b\\.?|bee\\s?bee\\s?bee|triple\\s?b)" +
            "(?:\\s+assistant)?[,.:!?\\s]*",
        RegexOption.IGNORE_CASE,
    )

    private val closeAllRe = Regex(
        "^(?:please\\s+)?(?:close|quit|exit|kill|shut down|stop|clear|end)\\s+(?:all|every|everything)\\b(.*)$"
    )
    private val clearTabsRe = Regex(
        "^(?:please\\s+)?(?:clear|close|quit|exit|kill|end)\\s+" +
            "(?:my\\s+|the\\s+|all\\s+(?:of\\s+)?(?:my\\s+)?)?(?:open\\s+)?(?:tabs|windows|recents?|programs)\\b.*$"
    )
    private val closeThisRe = Regex(
        "^(?:please\\s+)?(?:close|quit|exit|kill|shut down)(?:\\s+(?:this|that|it|the game|the app|this game|" +
            "this app|the current game|the current app|current game|current app|the window|this window|the program))?$"
    )
    private val closeOneRe = Regex(
        "^(?:please\\s+)?(?:close|quit|exit|kill|shut down|force close|force stop)\\s+(?:the\\s+)?(.+)$"
    )
    private val openAllRe = Regex("^(?:please\\s+)?(?:open|launch|start|play|run)\\s+(?:all|every|everything)\\b.*$")
    private val screenshotRe = Regex("^(?:please\\s+)?(?:take\\s+(?:a\\s+)?)?(?:screenshot|screen shot|screen capture|capture (?:the )?screen)\\b.*$")
    private val homeRe = Regex("^(?:please\\s+)?(?:go\\s+(?:to\\s+)?(?:the\\s+)?home(?:\\s+screen)?|take me home|home screen|home)$")

    private val questionRe = Regex(
        "^(?:who|what|whats|what's|when|where|why|how|which|is|are|was|were|does|do|did|can|could|will|" +
            "tell me|search|google|look up|find out|find|define|explain|give me)\\b"
    )
    private val localRe = Regex(
        "what time is it|what's the time|what is the time|the time now|current time|tell me the time|time is it|" +
            "what(?:'s| is) (?:the )?date|what day|today's date|date today|what is today|what's today|" +
            "battery|refresh rate|hertz|\\d+\\s*hz|\\b(?:story|tale)\\b|" +
            "\\b(?:plus|minus|times|divided|multiplied|squared|cubed|percent|square root|to the power)\\b|" +
            "\\d\\s*[-+*/x×÷^]\\s*\\d|\\d+\\s+over\\s+\\d+|what can you do|^(?:hi|hello|hey|help)$"
    )

    suspend fun reply(input: String, history: List<ChatMessage>): String {
        val m = wakeRegex.find(input)
        if (m == null && assistant.wakeWord()) return assistant.reply(input, history)
        val rest = if (m != null) input.substring(m.range.last + 1) else input
        val q = rest.trim().trimEnd('?', '!', '.', ' ', ',').lowercase()
        if (q.isEmpty()) return assistant.reply(input, history)

        val device = deviceCommand(q)
        if (device != null) return device

        if (questionRe.containsMatchIn(q) && !localRe.containsMatchIn(q)) {
            val web = webAnswer(q)
            if (web != null) return web
            val llama = askLlama(q)
            if (llama != null) return llama
        }
        return assistant.reply(input, history)
    }

    // ---- controlling the headset ----

    private suspend fun deviceCommand(q: String): String? {
        val all = closeAllRe.find(q)
        if (all != null || clearTabsRe.containsMatchIn(q)) {
            val tail = if (all != null) all.groupValues[1] else q
            val scope = when {
                tail.contains("game") -> LauncherTab.GAMES
                tail.contains("app") -> LauncherTab.APPS
                else -> null
            }
            return closeGroup(scope)
        }
        if (closeThisRe.matches(q)) return closeForeground()

        val one = closeOneRe.find(q)
        if (one != null) return closeOne(one.groupValues[1].trim())

        if (openAllRe.containsMatchIn(q)) {
            return "Quest can only run one game at a time, so I can't open them all together. " +
                "Say \"open\" and a game's name, or say \"list my games\"."
        }
        if (isListQuestion(q)) return listApps(q)

        val volume = volumeDirection(q)
        if (volume != 0) return changeVolume(volume > 0)
        if (screenshotRe.matches(q)) return screenshot()
        if (homeRe.matches(q)) return goHome()
        return null
    }

    private fun volumeDirection(q: String): Int {
        if (questionRe.containsMatchIn(q)) return 0
        val about = q.contains("volume") || q == "louder" || q == "quieter" || q == "softer" ||
            q == "turn it up" || q == "turn it down" || q == "turn up" || q == "turn down"
        if (!about) return 0
        val up = q.contains("up") || q.contains("louder") || q.contains("raise") || q.contains("increase")
        val down = q.contains("down") || q.contains("quieter") || q.contains("softer") ||
            q.contains("lower") || q.contains("decrease")
        return if (up == down) 0 else if (up) 1 else -1
    }

    private suspend fun changeVolume(up: Boolean): String = withContext(Dispatchers.IO) {
        val adb = adb() ?: return@withContext NO_ADB
        val key = if (up) 24 else 25
        adb.shell("input keyevent $key; input keyevent $key; input keyevent $key")
        if (up) "Volume up." else "Volume down."
    }

    private suspend fun screenshot(): String = withContext(Dispatchers.IO) {
        val adb = adb() ?: return@withContext NO_ADB
        val name = "bbb_" + System.currentTimeMillis() + ".png"
        val path = "/sdcard/Oculus/Screenshots/$name"
        val out = adb.shell("mkdir -p /sdcard/Oculus/Screenshots; screencap -p $path; ls -l $path").getOrNull() ?: ""
        if (out.contains(name) && !out.contains("No such file")) {
            "Screenshot saved to your Screenshots folder."
        } else {
            "I couldn't take a screenshot. Quest may block it from here."
        }
    }

    private suspend fun goHome(): String = withContext(Dispatchers.IO) {
        val adb = adb() ?: return@withContext NO_ADB
        adb.shell("input keyevent 3")
        "Going home."
    }

    private fun isListQuestion(q: String): Boolean =
        Regex("\\b(?:list|what|which|show|tell me|name|read)\\b.*\\b(?:games|apps)\\b").containsMatchIn(q) &&
            Regex("\\b(?:my|i have|installed|do i|have i|i've)\\b").containsMatchIn(q)

    private fun listApps(q: String): String {
        val wantGames = q.contains("game")
        val wantApps = q.contains("app")
        val scope = if (wantGames && !wantApps) LauncherTab.GAMES else if (wantApps && !wantGames) LauncherTab.APPS else null
        val noun = when (scope) {
            LauncherTab.GAMES -> "games"
            LauncherTab.APPS -> "apps"
            else -> "apps and games"
        }
        val list = viewModel.apps.value
            .filter { scope == null || it.tab == scope }
            .map { it.label }
            .sortedBy { it.lowercase() }
        if (list.isEmpty()) return "I don't see any $noun yet. Try again in a moment."
        val shown = list.take(15).joinToString(", ")
        val more = if (list.size > 15) " and ${list.size - 15} more" else ""
        return "You have ${list.size} $noun: $shown$more."
    }

    private fun adb(): AdbController? {
        val a = AdbController(context.applicationContext)
        return if (a.isConnected() || a.connect("127.0.0.1", null).isSuccess) a else null
    }

    private fun isProtected(pkg: String): Boolean {
        if (pkg == context.packageName || pkg == "android") return true
        for (p in PROTECTED) {
            if (pkg.startsWith(p)) return true
        }
        return false
    }

    private fun labelFor(pkg: String): String =
        viewModel.apps.value.firstOrNull { it.packageName == pkg }?.label ?: pkg

    private fun runningProcesses(adb: AdbController): Set<String> {
        val out = adb.shell("ps -A -o NAME").getOrNull() ?: return emptySet()
        return out.lines().map { it.trim() }.filter { it.isNotEmpty() && it != "NAME" }.toSet()
    }

    private suspend fun closeGroup(scope: LauncherTab?): String = withContext(Dispatchers.IO) {
        val adb = adb() ?: return@withContext NO_ADB
        val noun = when (scope) {
            LauncherTab.GAMES -> "games"
            LauncherTab.APPS -> "apps"
            else -> "apps and games"
        }
        val candidates = viewModel.apps.value.filter {
            (scope == null || it.tab == scope) && !isProtected(it.packageName)
        }
        if (candidates.isEmpty()) return@withContext "I don't see any $noun to close yet. Try again in a moment."

        val running = runningProcesses(adb)
        val targets = if (running.isEmpty()) {
            candidates
        } else {
            candidates.filter { a -> running.any { it == a.packageName || it.startsWith(a.packageName + ":") } }
        }
        if (targets.isEmpty()) return@withContext "Nothing was running."

        for (chunk in targets.chunked(20)) {
            adb.shell(chunk.joinToString("; ") { "am force-stop ${it.packageName}" })
        }
        val names = targets.take(6).joinToString(", ") { it.label }
        val more = if (targets.size > 6) " and ${targets.size - 6} more" else ""
        "Closed ${targets.size} $noun: $names$more."
    }

    private suspend fun closeForeground(): String = withContext(Dispatchers.IO) {
        val adb = adb() ?: return@withContext NO_ADB
        val out = adb.shell("dumpsys activity activities | grep -E 'topResumedActivity|mResumedActivity'")
            .getOrNull() ?: ""
        val pkg = Regex("u\\d+ ([A-Za-z0-9_.]+)/").find(out)?.groupValues?.get(1)
            ?: return@withContext "I couldn't tell which app is open."
        if (isProtected(pkg)) return@withContext "That's part of Quest itself, so I left it alone."
        adb.shell("am force-stop $pkg")
        "Closed ${labelFor(pkg)}."
    }

    private suspend fun closeOne(name: String): String = withContext(Dispatchers.IO) {
        val app = findApp(name) ?: return@withContext "I couldn't find an app called \"$name\"."
        if (isProtected(app.packageName)) return@withContext "I left ${app.label} alone."
        val adb = adb() ?: return@withContext NO_ADB
        adb.shell("am force-stop ${app.packageName}")
        "Closed ${app.label}."
    }

    // ---- finding the app you meant, even if it was misheard ----

    private fun findApp(name: String): AppEntry? {
        val n = name.lowercase()
        val apps = viewModel.apps.value
        return apps.firstOrNull { it.label.lowercase() == n }
            ?: apps.firstOrNull { it.label.lowercase().contains(n) }
            ?: apps.firstOrNull { it.label.length >= 3 && n.contains(it.label.lowercase()) }
            ?: closest(n, apps)
    }

    private fun squash(s: String): String = s.lowercase().filter { it.isLetterOrDigit() }

    private fun sound(s: String): String {
        val t = squash(s)
            .replace("ph", "f").replace("ck", "k").replace("c", "k").replace("q", "k")
            .replace("z", "s").replace("x", "ks").replace("w", "v")
        return Regex("(.)\\1+").replace(t, "\$1")
    }

    private fun editDistance(a: String, b: String): Int {
        var prev = IntArray(b.length + 1) { it }
        var cur = IntArray(b.length + 1)
        for (i in 1..a.length) {
            cur[0] = i
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + cost)
            }
            val swap = prev
            prev = cur
            cur = swap
        }
        return prev[b.length]
    }

    private fun similarity(a: String, b: String): Double {
        if (a.isEmpty() || b.isEmpty()) return 0.0
        return 1.0 - editDistance(a, b).toDouble() / maxOf(a.length, b.length)
    }

    private fun matchScore(query: String, label: String): Double {
        val whole = maxOf(
            similarity(squash(query), squash(label)),
            similarity(sound(query), sound(label)),
        )
        var word = 0.0
        val qWords = query.lowercase().split(Regex("[^a-z0-9]+")).filter { it.length >= 4 }
        val lWords = label.lowercase().split(Regex("[^a-z0-9]+")).filter { it.length >= 4 }
        for (q in qWords) {
            for (l in lWords) {
                word = maxOf(word, similarity(sound(q), sound(l)))
            }
        }
        return maxOf(whole, word * 0.85)
    }

    private fun closest(query: String, apps: List<AppEntry>): AppEntry? {
        var best: AppEntry? = null
        var bestScore = 0.0
        for (a in apps) {
            val s = matchScore(query, a.label)
            if (s > bestScore) {
                bestScore = s
                best = a
            }
        }
        return if (bestScore >= 0.5) best else null
    }

    // ---- answering questions ----

    /** Live web search through Tavily (free key). */
    private suspend fun webAnswer(question: String): String? = withContext(Dispatchers.IO) {
        val key = tavilyKey()
        if (key.isEmpty()) return@withContext null
        try {
            val body = JSONObject()
                .put("query", question)
                .put("search_depth", "basic")
                .put("max_results", 3)
                .put("include_answer", true)
            val c = URL("https://api.tavily.com/search").openConnection() as HttpURLConnection
            c.requestMethod = "POST"
            c.connectTimeout = 8000
            c.readTimeout = 20000
            c.setRequestProperty("Authorization", "Bearer $key")
            c.setRequestProperty("Content-Type", "application/json")
            c.doOutput = true
            c.outputStream.use { it.write(body.toString().toByteArray()) }
            val code = c.responseCode
            val stream = if (code in 200..299) c.inputStream else c.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() } ?: ""
            c.disconnect()
            if (code !in 200..299) return@withContext null

            val o = JSONObject(text)
            val answer = o.optString("answer").trim()
            if (answer.isNotEmpty() && answer != "null") return@withContext shorten(answer)
            val results = o.optJSONArray("results")
            if (results != null && results.length() > 0) {
                val snippet = results.getJSONObject(0).optString("content").trim()
                if (snippet.isNotEmpty()) return@withContext shorten(snippet)
            }
            null
        } catch (e: Exception) {
            null
        }
    }

    /** Meta's open Llama models, run for free on Groq (free key). */
    private suspend fun askLlama(question: String): String? = withContext(Dispatchers.IO) {
        val key = groqKey()
        if (key.isEmpty()) return@withContext null
        for (model in listOf("llama-3.3-70b-versatile", "llama-3.1-8b-instant")) {
            try {
                val messages = JSONArray()
                    .put(
                        JSONObject().put("role", "system").put(
                            "content",
                            "You are BBB, a voice assistant on a Meta Quest headset. Answer in one to three short " +
                                "sentences of plain text with no markdown. If you aren't sure, or it needs live " +
                                "information you don't have, say so briefly."
                        )
                    )
                    .put(JSONObject().put("role", "user").put("content", question))
                val body = JSONObject()
                    .put("model", model)
                    .put("messages", messages)
                    .put("max_tokens", 220)
                    .put("temperature", 0.4)
                val c = URL("https://api.groq.com/openai/v1/chat/completions").openConnection() as HttpURLConnection
                c.requestMethod = "POST"
                c.connectTimeout = 8000
                c.readTimeout = 20000
                c.setRequestProperty("Authorization", "Bearer $key")
                c.setRequestProperty("Content-Type", "application/json")
                c.doOutput = true
                c.outputStream.use { it.write(body.toString().toByteArray()) }
                val code = c.responseCode
                val stream = if (code in 200..299) c.inputStream else c.errorStream
                val text = stream?.bufferedReader()?.use { it.readText() } ?: ""
                c.disconnect()
                if (code in 200..299) {
                    val answer = JSONObject(text).getJSONArray("choices").getJSONObject(0)
                        .getJSONObject("message").optString("content").trim()
                    if (answer.isNotEmpty()) return@withContext shorten(answer)
                }
            } catch (e: Exception) {
            }
        }
        null
    }

    private fun shorten(text: String): String {
        if (text.length <= 420) return text
        val cut = text.substring(0, 420)
        val end = cut.lastIndexOf(". ")
        return if (end > 150) cut.substring(0, end + 1) else cut + "…"
    }

    companion object {
        private const val NO_ADB =
            "Wireless debugging isn't connected. Open Tools in the launcher and connect it, then try again."

        private val PROTECTED = listOf(
            "com.oculus.vrshell", "com.oculus.systemux", "com.oculus.os.", "com.oculus.shellenv",
            "com.oculus.ossplash", "com.oculus.voidactivity", "com.oculus.horizon",
            "com.android.", "com.google.android.gms",
        )

        private val FAIL_STARTS = listOf(
            "I couldn't", "Something went wrong", "The free AI didn't", "That took too long",
            "Wireless debugging", "Quest can only", "I left",
        )

        /** True when the answer is really an apology, so the app plays the "couldn't do it" sound. */
        fun failed(answer: String): Boolean = FAIL_STARTS.any { answer.startsWith(it) }
    }
}
