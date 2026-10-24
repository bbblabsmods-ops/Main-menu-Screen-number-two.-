package com.example.questlauncher

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.graphics.Bitmap
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.drawable.toBitmap

enum class LauncherTab(val title: String, val wide: Boolean) {
    GAMES("Games", wide = true),
    APPS("Apps", wide = false),
}

data class AppEntry(
    val packageName: String,
    val activityName: String,
    val label: String,
    val labelJunk: Boolean,
    val icon: ImageBitmap,
    val iconBlank: Boolean,
    val tab: LauncherTab,
    val installTime: Long,
)

class AppRepository(private val context: Context) {

    private val prefs = context.getSharedPreferences("launcher", Context.MODE_PRIVATE)

    private companion object {
        const val VR_CATEGORY = "com.oculus.intent.category.VR"
        const val KEY_FORCE_GAMES = "force_games"
        const val KEY_FORCE_APPS = "force_apps"
        const val KEY_HIDDEN = "hidden"

        val SYSTEM_PREFIXES = listOf(
            "com.oculus.os.",
            "com.oculus.ossplash",
            "com.oculus.shellenv",
            "com.oculus.systemux",
            "com.oculus.voidactivity",
            "com.oculus.vrshell",
        )
    }

    fun hiddenCount(): Int = hidden().size

    private fun hidden(): Set<String> = prefs.getStringSet(KEY_HIDDEN, emptySet()) ?: emptySet()

    fun hide(app: AppEntry) {
        prefs.edit().putStringSet(KEY_HIDDEN, hidden() + app.packageName).apply()
    }

    fun unhideAll() {
        prefs.edit().putStringSet(KEY_HIDDEN, emptySet()).apply()
    }

    fun load(): List<AppEntry> {
        val pm = context.packageManager
        val forceGames = prefs.getStringSet(KEY_FORCE_GAMES, emptySet()) ?: emptySet()
        val forceApps = prefs.getStringSet(KEY_FORCE_APPS, emptySet()) ?: emptySet()
        val hidden = hidden()

        val vrIntent = Intent(Intent.ACTION_MAIN).addCategory(VR_CATEGORY)
        val vrPackages = pm.queryIntentActivities(vrIntent, 0).map { it.activityInfo.packageName }.toSet()

        val launchables = pm.queryIntentActivities(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0
        ) + pm.queryIntentActivities(vrIntent, 0)

        val seen = HashSet<String>()
        val result = ArrayList<AppEntry>()

        for (ri in launchables) {
            val info = ri.activityInfo
            val pkg = info.packageName
            if (pkg == context.packageName) continue
            if (SYSTEM_PREFIXES.any { pkg.startsWith(it) }) continue
            if (pkg in hidden) continue
            if (!seen.add(pkg)) continue

            val appInfo = info.applicationInfo
            val autoGame = pkg in vrPackages ||
                (appInfo.flags and ApplicationInfo.FLAG_IS_GAME) != 0 ||
                appInfo.category == ApplicationInfo.CATEGORY_GAME
            val isGame = when {
                pkg in forceGames -> true
                pkg in forceApps -> false
                else -> autoGame
            }

            val bitmap = runCatching { ri.loadIcon(pm).toBitmap(256, 256) }.getOrNull()
            val blank = bitmap == null || isBlank(bitmap)
            val iconBitmap = (bitmap ?: Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)).asImageBitmap()

            val rawLabel = runCatching { ri.loadLabel(pm).toString() }.getOrDefault("")
            val junk = rawLabel.isBlank() || rawLabel == pkg || (rawLabel.contains('.') && !rawLabel.contains(' '))

            val installTime = runCatching { pm.getPackageInfo(pkg, 0).lastUpdateTime }.getOrDefault(0L)

            result += AppEntry(
                packageName = pkg,
                activityName = info.name,
                label = if (junk) fallbackName(pkg) else rawLabel,
                labelJunk = junk,
                icon = iconBitmap,
                iconBlank = blank,
                tab = if (isGame) LauncherTab.GAMES else LauncherTab.APPS,
                installTime = installTime,
            )
        }
        return result
    }

    private fun fallbackName(pkg: String): String =
        pkg.substringAfterLast('.').replaceFirstChar { it.uppercase() }

    /**
     * True when the icon is empty, a single flat colour (what Quest gives us for most games),
     * or almost completely see-through, so a coloured letter tile is shown instead of nothing.
     */
    private fun isBlank(b: Bitmap): Boolean {
        val w = b.width
        val h = b.height
        var first: Int? = null
        var visible = 0
        var total = 0
        var differs = false
        for (y in 0 until h step 16) {
            for (x in 0 until w step 16) {
                total++
                val p = b.getPixel(x, y)
                if ((p ushr 24) > 20) visible++
                if (first == null) {
                    first = p
                } else if (!differs && p != first) {
                    val dr = Math.abs(((p shr 16) and 255) - ((first shr 16) and 255))
                    val dg = Math.abs(((p shr 8) and 255) - ((first shr 8) and 255))
                    val db = Math.abs((p and 255) - (first and 255))
                    if (dr + dg + db > 24) differs = true
                }
            }
        }
        if (total == 0) return true
        // Fewer than 15% of the sampled points have any colour: the icon is basically invisible.
        if (visible * 100 < total * 15) return true
        return !differs
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

    /** Tries each screen in order and stops at the first one that opens. */
    private fun startFirst(vararg intents: Intent): Boolean {
        for (i in intents) {
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (runCatching { context.startActivity(i) }.isSuccess) return true
        }
        return false
    }

    /** The Android "App info" page for the Settings app (Open, Disable, Force stop, Permissions…). */
    fun openSettingsAppInfo(): Boolean = startFirst(
        Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
            .setData(android.net.Uri.parse("package:com.android.settings")),
        Intent(android.provider.Settings.ACTION_MANAGE_ALL_APPLICATIONS_SETTINGS),
        Intent(android.provider.Settings.ACTION_SETTINGS),
    )

    /** Android's full list of apps. Tap any app there to get its App info page. */
    fun openAllAppsList(): Boolean = startFirst(
        Intent(android.provider.Settings.ACTION_MANAGE_ALL_APPLICATIONS_SETTINGS),
        Intent(android.provider.Settings.ACTION_APPLICATION_SETTINGS),
        Intent(android.provider.Settings.ACTION_SETTINGS),
    )

    fun openAndroidSettings(): Boolean = runCatching {
        context.startActivity(
            Intent(android.provider.Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }.isSuccess

    fun openDeveloperSettings(): Boolean = runCatching {
        context.startActivity(
            Intent(android.provider.Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }.isSuccess

    fun openPackage(pkg: String): Boolean {
        val pm = context.packageManager
        val intent = pm.getLaunchIntentForPackage(pkg)
            ?: pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(VR_CATEGORY), 0)
                .firstOrNull { it.activityInfo.packageName == pkg }
                ?.let {
                    Intent(Intent.ACTION_MAIN).setComponent(
                        ComponentName(it.activityInfo.packageName, it.activityInfo.name)
                    )
                }
            ?: return false
        return runCatching { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess
    }

    fun moveTo(app: AppEntry, tab: LauncherTab) {
        val games = (prefs.getStringSet(KEY_FORCE_GAMES, emptySet()) ?: emptySet()).toMutableSet()
        val apps = (prefs.getStringSet(KEY_FORCE_APPS, emptySet()) ?: emptySet()).toMutableSet()
        games.remove(app.packageName)
        apps.remove(app.packageName)
        if (tab == LauncherTab.GAMES) games.add(app.packageName) else apps.add(app.packageName)
        prefs.edit().putStringSet(KEY_FORCE_GAMES, games).putStringSet(KEY_FORCE_APPS, apps).apply()
    }

    /** Quest normally refuses this without ADB; the screen then shows the command to copy. */
    fun setRefreshRate(hz: Int): Boolean = runCatching {
        Runtime.getRuntime().exec(arrayOf("setprop", "debug.oculus.refreshRate", hz.toString())).waitFor() == 0
    }.getOrDefault(false)
}
