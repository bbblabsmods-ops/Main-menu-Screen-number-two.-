package com.example.questlauncher

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

/** A small pill at the top of the screen. Falls back to a toast if popups aren't allowed. */
class Popup(private val context: Context) {

    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val main = Handler(Looper.getMainLooper())
    private var view: TextView? = null
    private val hideRunnable = Runnable { remove() }

    fun allowed(): Boolean = Settings.canDrawOverlays(context)

    fun show(text: String, millis: Long = 4000L) {
        main.post {
            main.removeCallbacks(hideRunnable)
            if (!allowed()) {
                Toast.makeText(context, text, Toast.LENGTH_SHORT).show()
                return@post
            }
            try {
                val v = view ?: makeView().also {
                    windowManager.addView(it, params())
                    view = it
                }
                v.text = text
                main.postDelayed(hideRunnable, millis)
            } catch (e: Exception) {
                view = null
                Toast.makeText(context, text, Toast.LENGTH_SHORT).show()
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
}
