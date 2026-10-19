package com.example.questlauncher

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.core.content.ContextCompat

class MainActivity : ComponentActivity() {

    private val viewModel: LauncherViewModel by viewModels()

    @Volatile
    private var granting = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        seedGeminiKey()
        setContent {
            LauncherScreen(viewModel)
        }
    }

    override fun onResume() {
        super.onResume()
        // Picks up installs, uninstalls and updates made while we were away.
        viewModel.refresh()
        // Reconnects to Wireless debugging by itself if you've paired before.
        viewModel.autoConnectQuietly()
        startVoiceIfWanted()
        grantMicQuietly()
    }

    private fun hasMic(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    /** If Mic was on, start the background listener again (for example after a restart). */
    private fun startVoiceIfWanted() {
        val prefs = getSharedPreferences("assistant", MODE_PRIVATE)
        if (prefs.getBoolean("voice_on", false) && hasMic()) {
            try {
                ContextCompat.startForegroundService(this, Intent(this, VoiceService::class.java))
            } catch (e: Exception) {
                AssistantHub.setStatus("Couldn't start listening: " + (e.message ?: e.javaClass.simpleName))
            }
        }
    }

    /** After a fresh install, allows the microphone through Wireless debugging so no menu is needed. */
    private fun grantMicQuietly() {
        if (hasMic() || granting) return
        granting = true
        Thread {
            try {
                val adb = AdbController(applicationContext)
                var tries = 0
                while (tries < 15 && !adb.isConnected()) {
                    Thread.sleep(1000)
                    tries++
                }
                if (adb.isConnected()) {
                    adb.shell("pm grant $packageName android.permission.RECORD_AUDIO")
                    runOnUiThread { startVoiceIfWanted() }
                }
            } catch (e: Exception) {
            } finally {
                granting = false
            }
        }.start()
    }

    /** Copies the Gemini key built into this app (if any) so the AI works without typing it. */
    private fun seedGeminiKey() {
        try {
            val prefs = getSharedPreferences("assistant", MODE_PRIVATE)
            if (prefs.getString("gemini_key", "").isNullOrEmpty()) {
                val key = assets.open("gemini.txt").bufferedReader().use { it.readText() }.trim()
                if (key.isNotEmpty()) prefs.edit().putString("gemini_key", key).apply()
            }
        } catch (e: Exception) {
        }
    }
}
