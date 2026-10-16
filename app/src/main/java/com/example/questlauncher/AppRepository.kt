package com.example.questlauncher

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.graphics.Bitmap
import android.graphics.Color
import android.provider.Settings
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.drawable.toBitmap

/**
 * Which tab an app belongs to. Add new entries here (e.g. YOUR_GAMES, UNKNOWN_SOURCES).
 */
enum class LauncherTab(val title: String, val wide: Boolean) {
    GAMES("Games", wide = true),
    APPS("Apps", wide = false),
}

data class AppEntry(
    val packageName: String,
    val activityName: String,
    val label: String,
    val icon: ImageBitmap,
    val banner: ImageBitmap?,
    val accent: Int,
    val tab: LauncherTab,
    val installTime: Long,
)

class AppRepository(private val context: Context) {

    private val prefs = context.getSharedPreferences("launcher", Context.MODE_PRIVATE)

    private companion object {
        const val VR_CATEGORY = "com.oculus.intent.category.VR"
        const val KEY_FORCE_GAMES = "force_games"
        const val KEY_FORCE_APPS = "force_apps"
        const val REFRESH_PROP = "debug.oculus.refreshRate"
        const val DEFAULT_ACCENT = 0xFF5B4FE0
    }

    fun load(): List<AppEntry> {
        val pm = context.packageManager
        val forceGames = prefs.getStringSet(KEY_FORCE_GAMES, emptySet()) ?: emptySet()
        val forceApps = prefs.getStringSet(KEY_FORCE_APPS, emptySet()) ?: emptySet()

        val vrPackages = pm.queryIntentActivities(
            Intent(Intent.ACTION_MAIN).addCategory(VR_CATEGORY), 0
        ).map { it.activityInfo.packageName }.toSet()

        val launchables = pm.queryIntentActivities(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0
        ) + pm.queryIntentActivities(
            Intent(Intent.ACTION_MAIN).addCategory(VR_CATEGORY), 0
        )

        val seen = HashSet<String>()
        val result = ArrayList<AppEntry>()

        for (ri in launchables) {
            val info = ri.activityInfo
            val pkg = info.packageName
            if (pkg == context.packageName || !seen.add(pkg)) continue

            val appInfo = info.applicationInfo
            val autoGame = pkg in vrPackages ||
                (appInfo.flags and ApplicationInfo.FLAG_IS_GAME) != 0 ||
                appInfo.category == ApplicationInfo.CATEGORY_GAME

            val isGame = when {
                pkg in forceGames -> true
                pkg in forceApps -> false
                else -> autoGame
            }

            // Never skip an app just because its icon failed to load.
            val iconBitmap: Bitmap = runCatching {
                ri.loadIcon(pm).toBitmap(256, 256)
            }.getOrElse {
                pm.defaultActivityIcon.toBitmap(256, 256)
            }

            val bannerBitmap = runCatching {
                appInfo.loadBanner(pm)?.let { it.toBitmap(480, 270).asImageBitmap() }
            }.getOrNull()

            val installTime = runCatching {
                pm.getPackageInfo(pkg, 0).lastUpdateTime
            }.getOrDefault(0L)

            val label = runCatching { ri.loadLabel(pm).toString() }.getOrDefault(pkg)

            result += AppEntry(
                packageName = pkg,
                activityName = info.name,
                label = label,
                icon = iconBitmap.asImageBitmap(),
                banner = bannerBitmap,
                accent = accentOf(iconBitmap),
                tab = if (isGame) LauncherTab.GAMES else LauncherTab.APPS,
                installTime = installTime,
            )
        }
        return result
    }

    /** Average colour of the icon, used to tint the card. Falls back for black/white icons. */
    private fun accentOf(bmp: Bitmap): Int {
        val fallback = DEFAULT_ACCENT.toInt()
        return runCatching {
            val px = Bitmap.createScaledBitmap(bmp, 1, 1, true).getPixel(0, 0)
            val r = Color.red(px)
            val g = Color.green(px)
            val b = Color.blue(px)
            if (maxOf(r, g, b) < 50 || minOf(r, g, b) > 215) fallback
            else Color.rgb(r, g, b)
        }.getOrDefault(fallback)
    }

    fun launch(app: AppEntry) {
        val intent = Intent(Intent.ACTION_MAIN).apply {
            component = ComponentName(app.packageName, app.activityName)
        }
        startSafely(intent)
    }

    fun openAppInfo(app: AppEntry) {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = android.net.Uri.parse("package:${app.packageName}")
        }
        startSafely(intent)
    }

    fun openAndroidSettings(): Boolean = startSafely(Intent(Settings.ACTION_SETTINGS))

    fun openDeveloperSettings(): Boolean =
        startSafely(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)) || openAndroidSettings()

    /** Opens an installed app by package name. Returns false if it isn't installed. */
    fun openPackage(pkg: String): Boolean {
        val pm = context.packageManager
        val direct = pm.getLaunchIntentForPackage(pkg)
        if (direct != null) return startSafely(direct)

        val vr = pm.queryIntentActivities(
            Intent(Intent.ACTION_MAIN).addCategory(VR_CATEGORY).setPackage(pkg), 0
        ).firstOrNull() ?: return false
        val intent = Intent(Intent.ACTION_MAIN).apply {
            component = ComponentName(vr.activityInfo.packageName, vr.activityInfo.name)
        }
        return startSafely(intent)
    }

    private fun startSafely(intent: Intent): Boolean = runCatching {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        context.startActivity(intent)
        true
    }.getOrDefault(false)

    /** Manually move an app to a different tab. Stored locally. */
    fun moveTo(app: AppEntry, tab: LauncherTab) {
        val games = (prefs.getStringSet(KEY_FORCE_GAMES, emptySet()) ?: emptySet()).toMutableSet()
        val apps = (prefs.getStringSet(KEY_FORCE_APPS, emptySet()) ?: emptySet()).toMutableSet()
        games.remove(app.packageName)
        apps.remove(app.packageName)
        if (tab == LauncherTab.GAMES) games.add(app.packageName) else apps.add(app.packageName)
        prefs.edit()
            .putStringSet(KEY_FORCE_GAMES, games)
            .putStringSet(KEY_FORCE_APPS, apps)
            .apply()
    }

    /**
     * Tries to set the Quest refresh rate. Normal apps are usually not allowed to,
     * so we check the property afterwards and report whether it really changed.
     */
    fun setRefreshRate(hz: Int): Boolean = runCatching {
        val set = Runtime.getRuntime().exec(arrayOf("sh", "-c", "setprop $REFRESH_PROP $hz"))
        set.waitFor()
        val get = Runtime.getRuntime().exec(arrayOf("getprop", REFRESH_PROP))
        val value = get.inputStream.bufferedReader().readText().trim()
        get.waitFor()
        value == hz.toString()
    }.getOrDefault(false)
}
