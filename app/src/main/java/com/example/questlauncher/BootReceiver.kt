package com.example.questlauncher

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Opens the launcher when the headset finishes starting, if "Start on boot" is on. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val prefs = context.getSharedPreferences("assistant", Context.MODE_PRIVATE)
        if (!prefs.getBoolean("autostart", false)) return
        try {
            val open = Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra("from_boot", true)
            context.startActivity(open)
        } catch (e: Exception) {
        }
    }
}
