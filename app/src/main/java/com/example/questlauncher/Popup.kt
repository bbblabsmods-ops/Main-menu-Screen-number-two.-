package com.example.questlauncher

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.WindowManager
import android.widget.TextView
import android.widget.Toast

/**
 * Shows short text without taking over your game.
 * Mode 1 (default): a notification plus a toast. Neither takes control away from the game.
 * Mode 2: a banner window on top. On Quest a window like this can take game controls for a moment.
 * Mode 0: nothing, sounds only.
 */
class Popup(private val context: Context) {

    private val prefs = context.getSharedPreferences("assistant", Context.MODE_PRIVATE)
    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val main = Handler(Looper.getMainLooper())
    private var view: TextView? = null
    private val hideRunnable = Runnable { remove() }
    private var channelMade = false

    fun mode(): Int = prefs.getInt("popup_mode", 1)

    fun allowed(): Boolean = Settings.canDrawOverlays(context)

    fun show(text: String, millis: Long = 2500L) {
        when (mode()) {
            0 -> { }
            2 -> banner(text, millis)
            else -> light(text)
        }
    }

    private fun light(text: String) {
        main.post {
            try {
                Toast.makeText(context, text, Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
            }
            try {
                val nm = context.getSystemService(NotificationManager::class.java)
                if (!channelMade) {
                    nm.createNotificationChannel(
                        NotificationChannel(CHANNEL, "BBB replies", NotificationManager.IMPORTANCE_HIGH)
                    )
                    channelMade = true
                }
                val n = Notification.Builder(context, CHANNEL)
                    .setContentTitle("BBB")
                    .setContentText(text)
                    .setStyle(Notification.BigTextStyle().bigText(text))
                    .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                    .setTimeoutAfter(5000L)
                    .setAutoCancel(true)
                    .build()
                nm.notify(NOTE_ID, n)
            } catch (e: Exception) {
            }
        }
    }

    private fun banner(text: String, millis: Long) {
        main.post {
            main.removeCallbacks(hideRunnable)
            if (!allowed()) {
                light(text)
                return@post
            }
            try {
                val v = view ?: makeView().also {
                    windowManager.addView(it, params())
                    view = it
                }
                v.text = text
                main.postDelayed(hideRunnable, minOf(millis, 3500L))
            } catch (e: Exception) {
                view = null
                light(text)
            }
        }
    }

    fun remove() {
        main.post {
            main.removeCallbacks(hideRunnable)
            val v = view
            view = null
            if (v != null) {
                try {
                    windowManager.removeView(v)
                } catch (e: Exception) {
                }
            }
        }
    }

    private fun makeView(): TextView {
        val d = context.resources.displayMetrics.density
        val bg = GradientDrawable()
        bg.setColor(Color.parseColor("#EE101722"))
        bg.cornerRadius = 40f * d
        bg.setStroke((1.5f * d).toInt().coerceAtLeast(1), Color.parseColor("#6636D6F0"))

        val tv = TextView(context)
        tv.setTextColor(Color.WHITE)
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
        tv.typeface = Typeface.DEFAULT_BOLD
        tv.gravity = Gravity.CENTER
        tv.maxWidth = (600 * d).toInt()
        val h = (26 * d).toInt()
        val v = (14 * d).toInt()
        tv.setPadding(h, v, h, v)
        tv.background = bg
        return tv
    }

    private fun params(): WindowManager.LayoutParams {
        val p = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT,
        )
        p.gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
        p.y = (36 * context.resources.displayMetrics.density).toInt()
        return p
    }

    companion object {
        private const val CHANNEL = "bbb_replies"
        private const val NOTE_ID = 8
    }
}
