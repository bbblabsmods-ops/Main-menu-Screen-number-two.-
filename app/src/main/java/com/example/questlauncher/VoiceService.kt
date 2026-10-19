package com.example.questlauncher

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.os.SystemClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/** Keeps listening for "Hey BBB" even when the launcher isn't on screen. */
class VoiceService : Service() {

    private lateinit var vm: LauncherViewModel
    private lateinit var assistant: Assistant
    private lateinit var speaker: Speaker
    private lateinit var beeper: Beeper
    private lateinit var popup: Popup
    private lateinit var voice: VoiceListener

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var wakePending = false
    private var wakeAt = 0L
    private var awaitingUntil = 0L
    private var busy = false

    // Ways the speech engine tends to write "hey" and "B" when it hears "hey BBB".
    private val heyWords = setOf("hey", "hay", "hi", "he", "a", "okay", "ok", "hello", "they", "ay")
    private val bWords = setOf(
        "b", "be", "bee", "bi", "bb", "bbb", "bbc", "bc", "d", "dee", "p", "pea",
        "v", "vee", "by", "bye", "buy", "bay", "been", "bean",
    )

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        vm = LauncherViewModel(application)
        vm.refresh()
        assistant = Assistant(this, vm)
        assistant.launcher = { app -> launchApp(app) }
        speaker = Speaker(this)
        beeper = Beeper()
        popup = Popup(this)
        voice = VoiceListener(
            context = this,
            onText = { heard -> onHeard(heard) },
            onStatus = { s -> AssistantHub.setStatus(s) },
            onWake = { onWake() },
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            startForeground(NOTE_ID, buildNotification())
        } catch (e: Exception) {
            AssistantHub.setStatus("Couldn't start listening: " + (e.message ?: e.javaClass.simpleName))
            stopSelf()
            return START_NOT_STICKY
        }
        vm.refresh()
        voice.start()
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        voice.stop()
        speaker.shutdown()
        popup.remove()
        scope.cancel()
        AssistantHub.setStatus("Mic is off.")
        super.onDestroy()
    }

    private fun buildNotification(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "BBB voice", NotificationManager.IMPORTANCE_LOW)
        )
        return Notification.Builder(this, CHANNEL)
            .setContentTitle("BBB is listening")
            .setContentText("Say \"Hey BBB\". Turn it off with Mic in the Assistant page.")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .build()
    }

    /** The strict listener heard "hey b b b". Runs right away, while you're still talking. */
    private fun onWake() {
        val now = SystemClock.elapsedRealtime()
        if (now - wakeAt < 1500L) return
        wakeAt = now
        wakePending = true
        beeper.wake()
        popup.show("Sup", 1500L)
        AssistantHub.setStatus("Listening…")
    }

    /** You finished a phrase. */
    private fun onHeard(heard: String) {
        val now = SystemClock.elapsedRealtime()
        AssistantHub.setStatus("Heard: $heard")

        val pending = wakePending && now - wakeAt < 15000L
        val fuzzyWake = assistant.normalizeSpoken(heard) != null

        if (pending || fuzzyWake) {
            if (!pending) {
                // The strict listener missed it but the words look right. Count it anyway.
                beeper.wake()
                popup.show("Sup", 1500L)
            }
            wakePending = false
            val rest = stripWake(heard)
            if (rest.isBlank()) {
                // Only the wake phrase so far. The command is coming next.
                awaitingUntil = now + 9000L
            } else {
                awaitingUntil = 0L
                handle("hey bbb $rest")
            }
        } else if (now < awaitingUntil && heard.isNotBlank()) {
            awaitingUntil = 0L
            handle("hey bbb $heard")
        }
    }

    /** Removes the "hey bbb" part, however it was written, and keeps the command. */
    private fun stripWake(heard: String): String {
        val tokens = heard.trim().lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }
        var i = 0
        if (i < tokens.size && tokens[i] in heyWords) i++
        var b = 0
        while (i < tokens.size && b < 5 && tokens[i] in bWords) {
            i++
            b++
        }
        return tokens.drop(i).joinToString(" ")
    }

    private fun handle(text: String) {
        if (busy) {
            AssistantHub.setStatus("Still working on the last one…")
            return
        }
        busy = true
        AssistantHub.add(ChatMessage(true, text))
        scope.launch {
            val answer = try {
                assistant.reply(text, AssistantHub.messages.value)
            } catch (e: Exception) {
                "Something went wrong: ${e.message}"
            }
            AssistantHub.add(ChatMessage(false, answer))
            val failed = answer.startsWith("I couldn't") ||
                answer.startsWith("Something went wrong") ||
                answer.startsWith("The free AI didn't")
            if (failed) beeper.fail() else beeper.ok()
            popup.show(answer, 3500L)
            speaker.speak(answer)
            busy = false
        }
    }

    /**
     * Android blocks apps from opening other apps from the background, so this goes through the
     * Wireless debugging connection, reconnecting first if it dropped.
     */
    private fun launchApp(app: AppEntry) {
        val pkg = app.packageName
        val act = app.activityName.toString()
        Thread {
            val adb = AdbController(applicationContext)
            val connected = adb.isConnected() || adb.connect("127.0.0.1", null).isSuccess
            if (connected) {
                var out = ""
                if (act.isNotBlank() && act != "null") {
                    out = adb.shell("am start -n '$pkg/$act'").getOrNull() ?: ""
                }
                val bad = out.contains("Error", true) || out.contains("Exception", true) ||
                    act.isBlank() || act == "null"
                if (bad) {
                    adb.shell("monkey -p $pkg -c android.intent.category.LAUNCHER 1")
                }
            } else {
                scope.launch { vm.launch(app) }
            }
        }.start()
    }

    companion object {
        private const val CHANNEL = "bbb_voice"
        private const val NOTE_ID = 7
    }
}
