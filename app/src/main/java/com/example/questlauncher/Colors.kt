package com.example.questlauncher

import android.content.Context
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb

/** Remembers the background color chosen for each game/app. The key "*" means "every tile". */
class TileColors(context: Context) {

    private val prefs = context.getSharedPreferences("tile_colors", Context.MODE_PRIVATE)
    val colors = mutableStateMapOf<String, Int>()

    init {
        prefs.all.forEach { (key, value) -> if (value is Int) colors[key] = value }
    }

    fun forApp(pkg: String): Color? = (colors[pkg] ?: colors["*"])?.let { Color(it) }

    fun isCustom(pkg: String): Boolean = colors.containsKey(pkg)

    /** Color for one app. null puts it back to following the "all" color (or the default). */
    fun set(pkg: String, color: Color?) {
        if (color == null) {
            colors.remove(pkg)
            prefs.edit().remove(pkg).apply()
        } else {
            colors[pkg] = color.toArgb()
            prefs.edit().putInt(pkg, color.toArgb()).apply()
        }
    }

    /** One color for every game and app. Clears each tile's own color. */
    fun setAll(color: Color?) {
        colors.clear()
        val edit = prefs.edit().clear()
        if (color != null) {
            colors["*"] = color.toArgb()
            edit.putInt("*", color.toArgb())
        }
        edit.apply()
    }
}
