package com.example.questlauncher

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.IBinder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** Keeps listening for "Hey BBB" even when the launcher isn't on screen. */
class VoiceService : Service() {

    private lateinit var vm: LauncherViewModel
    private lateinit var assistant: Assistant
    private lateinit var brain: Brain
    private lateinit var speaker: Speaker
    private lateinit var girl: GirlVoice
    private lateinit var beeper: Beeper
    private lateinit var popup: Popup
    private lateinit var listener: WakeListener

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var job: Job? = null
    private var generation = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        vm = LauncherViewModel(application)
        vm.refresh()
        assistant = Assistant(this, vm)
        assistant.launcher = { app -> launchApp(app) }
        brain = Brain(this, vm, assistant)
        speaker = Speaker(this)
        girl = GirlVoice(this) { text -> speaker.speak(text) }
        beeper = Beeper()
        popup = Popup(this)
        listener = WakeListener(
            context = this,
            onStatus = { s -> AssistantHub.setStatus(s) },
            onWake = { onWake() },
            onCommand = { c -> onCommand(c) },
            onGiveUp = { onGiveUp() },
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            getSharedPreferences("assistant", MODE_PRIVATE).edit().putBoolean("voice_on", false).apply()
            stopSelf()
            return START_NOT_STICKY
        }
        try {
            startForeground(NOTE_ID, buildNotification())
        } catch (e: Exception) {
            AssistantHub.setStatus("Couldn't start listening: " + (e.message ?: e.javaClass.simpleName))
            stopSelf()
            return START_NOT_STICKY
        }
        AssistantHub.setListening(true)
        vm.refresh()
        listener.start()
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        listener.stop()
        girl.shutdown()
        speaker.shutdown()
        popup.remove()
        scope.cancel()
        AssistantHub.setListening(false)
        AssistantHub.setStatus("Mic is off.")
        super.onDestroy()
    }

    private fun buildNotification(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "BBB voice", NotificationManager.IMPORTANCE_LOW)
        )
        val stop = PendingIntent.getService(
            this,
            1,
            Intent(this, VoiceService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val action = Notification.Action.Builder(
            Icon.createWithResource(this, android.R.drawable.ic_menu_close_clear_cancel),
            "Turn off",
            stop,
        ).build()
        return Notification.Builder(this, CHANNEL)
            .setContentTitle("BBB is waiting for \"Hey BBB\"")
            .setContentText("It only understands you after that phrase. Tap Turn off to stop.")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .addAction(action)
            .build()
    }

    /** The strict listener heard "hey b b b". */
    private fun onWake() {
        girl.stop()
        beeper.wake()
        popup.show("Sup", 1500L)
        AssistantHub.setStatus("Listening…")
    }

    /** Your command, with the wake phrase already removed. */
    private fun onCommand(command: String) {
        AssistantHub.setStatus("Heard: $command")
        handle("hey bbb $command")
    }

    /** You said "Hey BBB" and then nothing. */
    private fun onGiveUp() {
        beeper.fail()
        popup.show("I didn't catch that", 2500L)
        girl.speak("I didn't catch that")
    }

    /**
     * A new command always replaces the one before it, so a slow or stuck answer can never block
     * you. An answer for a command you've already replaced is thrown away.
     */
    private fun handle(text: String) {
        val mine = ++generation
        job?.cancel()
        girl.stop()
        AssistantHub.add(ChatMessage(true, text))
        popup.show("On it…", 1500L, true)

        job = scope.launch {
            val work = async {
                try {
                    brain.reply(text, AssistantHub.messages.value)
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    "Something went wrong: ${e.message}"
                }
            }
            val answer = try {
                // await() can be abandoned on time-out, even if the request itself is still stuck.
                withTimeoutOrNull(20000L) { work.await() } ?: "That took too long. Try again."
            } catch (e: CancellationException) {
                work.cancel()
                throw e
            } catch (e: Exception) {
                "Something went wrong: ${e.message}"
            }
            work.cancel()
            if (mine != generation) return@launch

            AssistantHub.add(ChatMessage(false, answer))
            if (Brain.failed(answer)) beeper.fail() else beeper.ok()
            popup.show(answer, 3500L)
            girl.speak(answer)
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
        const val ACTION_STOP = "com.example.questlauncher.STOP_VOICE"
    }
}
