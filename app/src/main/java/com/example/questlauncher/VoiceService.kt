package com.example.questlauncher

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.provider.Settings
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
    private var awaitingUntil = 0L
    private var heardWake = false
    private var busy = false

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
            onPartial = { p -> onPartial(p) },
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

    /** Runs while you are still talking. Beeps the moment it hears "Hey BBB". */
    private fun onPartial(partial: String) {
        if (heardWake) return
        if (assistant.normalizeSpoken(partial) != null) {
            heardWake = true
            beeper.beep()
            popup.show("Sup", 9000L)
            AssistantHub.setStatus("Listening…")
        }
    }

    /** Runs when you finish a phrase. */
    private fun onHeard(heard: String) {
        val now = System.currentTimeMillis()
        val alreadyBeeped = heardWake
        heardWake = false
        AssistantHub.setStatus("Heard: $heard")

        val wake = assistant.normalizeSpoken(heard)
        if (wake == "hey bbb") {
            // Just the wake phrase. The command comes next, so wait for it.
            awaitingUntil = now + 9000L
            if (!alreadyBeeped) beeper.beep()
            popup.show("Sup", 9000L)
        } else if (wake != null) {
            awaitingUntil = 0L
            if (!alreadyBeeped) beeper.beep()
            popup.show("Sup", 2500L)
            handle(wake)
        } else if (now < awaitingUntil && heard.isNotBlank()) {
            awaitingUntil = 0L
            handle("hey bbb $heard")
        } else if (alreadyBeeped) {
            // False alarm: it thought it heard the wake phrase but it wasn't.
            popup.remove()
        }
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
            popup.show(answer, 4000L + answer.take(300).length * 40L)
            speaker.speak(answer)
            busy = false
        }
    }

    /**
     * Android blocks apps from opening other apps from the background. Two ways around it:
     * the "popup over other apps" permission, or the Wireless debugging connection.
     */
    private fun launchApp(app: AppEntry) {
        if (Settings.canDrawOverlays(this)) {
            vm.launch(app)
            return
        }
        val pkg = app.packageName
        val act = app.activityName.toString()
        Thread {
            val adb = AdbController(applicationContext)
            if (adb.isConnected()) {
                val cmd = if (act.isNotBlank() && act != "null") "am start -n '$pkg/$act'" else "monkey -p $pkg 1"
                adb.shell(cmd)
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
