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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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

        // If Mic was on, start the background listener again (for example after a restart).
        val prefs = getSharedPreferences("assistant", MODE_PRIVATE)
        val micOk = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        if (prefs.getBoolean("voice_on", false) && micOk) {
            try {
                ContextCompat.startForegroundService(this, Intent(this, VoiceService::class.java))
            } catch (e: Exception) {
                AssistantHub.setStatus("Couldn't start listening: " + (e.message ?: e.javaClass.simpleName))
            }
        }
    }
}
