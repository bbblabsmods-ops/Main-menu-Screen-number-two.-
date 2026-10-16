package com.example.questlauncher

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels

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
    }
}
