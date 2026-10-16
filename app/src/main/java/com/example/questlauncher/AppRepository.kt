package com.example.questlauncher

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.drawable.toBitmap

/**
 * Which tab an app belongs to. Add new entries here (e.g. YOUR_GAMES, UNKNOWN_SOURCES)
 * and the tab bar picks them up from [LauncherTab.entries].
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
    val tab: LauncherTab,
    val installTime: Long,
)

class AppRepository(private val context: Context) {

    private val prefs = context.getSharedPreferences("launcher", Context.MODE_PRIVATE)

    private companion object {
        const val VR_CATEGORY = "com.oculus.intent.category.VR"
        const val KEY_FORCE_GAMES = "force_games"
        const val KEY_FORCE_APPS = "force_apps"
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

            val iconBitmap = runCatching {
                ri.loadIcon(pm).toBitmap(256, 256).asImageBitmap()
            }.getOrNull() ?: continue

            val bannerBitmap = runCatching {
                appInfo.loadBanner(pm)?.let { it.toBitmap(480, 270).asImageBitmap() }
            }.getOrNull()

            val installTime = runCatching {
                pm.getPackageInfo(pkg, 0).lastUpdateTime
            }.getOrDefault(0L)

            result += AppEntry(
                packageName = pkg,
                activityName = info.name,
                label = ri.loadLabel(pm).toString(),
                icon = iconBitmap,
                banner = bannerBitmap,
                tab = if (isGame) LauncherTab.GAMES else LauncherTab.APPS,
                installTime = installTime,
            )
        }
        return result
    }

    fun launch(app: AppEntry) {
        val intent = Intent(Intent.ACTION_MAIN).apply {
            component = ComponentName(app.packageName, app.activityName)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        }
        runCatching { context.startActivity(intent) }
    }

    fun openAppInfo(app: AppEntry) {
        val intent = Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = android.net.Uri.parse("package:${app.packageName}")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching { context.startActivity(intent) }
    }

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
}
