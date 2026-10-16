@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
package com.example.questlauncher

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val BgTop = Color(0xFF151A2E)
private val BgBottom = Color(0xFF07090F)
private val Accent = Color(0xFF7C5CFF)
private val Glass = Color(0x14FFFFFF)
private val GlassBorder = Color(0x22FFFFFF)
private val Dim = Color(0xFF8A94A3)

private enum class Page(val title: String) {
    GAMES("Games"),
    APPS("Apps"),
    TOOLS("Tools"),
}

private fun iconFor(page: Page): ImageVector = when (page) {
    Page.GAMES -> Icons.Default.Star
    Page.APPS -> Icons.Default.Apps
    Page.TOOLS -> Icons.Default.Build
}

@Composable
fun LauncherScreen(viewModel: LauncherViewModel) {
    val apps by viewModel.apps.collectAsStateWithLifecycle()
    val refreshResult by viewModel.refreshResult.collectAsStateWithLifecycle()

    var page by remember { mutableStateOf(Page.GAMES) }
    var sort by remember { mutableStateOf(SortMode.NAME) }
    var searching by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var selectedHz by remember { mutableStateOf<Int?>(null) }

    val listTab: LauncherTab? = when (page) {
        Page.GAMES -> LauncherTab.GAMES
        Page.APPS -> LauncherTab.APPS
        Page.TOOLS -> null
    }

    val visible = remember(apps, page, sort, query) {
        val t = listTab
        if (t == null) {
            emptyList<AppEntry>()
        } else {
            apps.asSequence()
                .filter { it.tab == t }
                .filter { query.isBlank() || it.label.contains(query, ignoreCase = true) }
                .let {
                    when (sort) {
                        SortMode.NAME -> it.sortedBy { a -> a.label.lowercase() }
                        SortMode.RECENT -> it.sortedByDescending { a -> a.installTime }
                    }
                }
                .toList()
        }
    }

    Row(
        Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(BgTop, BgBottom)))
            .padding(20.dp)
    ) {
        Rail(
            page = page,
            onSelect = {
                page = it
                query = ""
                searching = false
            },
        )
        Spacer(Modifier.width(22.dp))

        Column(Modifier.weight(1f).fillMaxHeight()) {

            // Header
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(page.title, color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.Bold)
                if (listTab != null) {
                    Spacer(Modifier.width(12.dp))
                    Text("${visible.size}", color = Dim, fontSize = 16.sp)
                }
                Spacer(Modifier.weight(1f))
                if (listTab != null) {
                    Pill(sort.label) {
                        sort = if (sort == SortMode.NAME) SortMode.RECENT else SortMode.NAME
                    }
                    Spacer(Modifier.width(4.dp))
                    IconButton(onClick = {
                        searching = !searching
                        if (!searching) query = ""
                    }) {
                        Icon(Icons.Default.Search, "Search", tint = Color.White)
                    }
                    Spacer(Modifier.width(8.dp))
                }
                StatusInfo()
            }

            if (searching && listTab != null) {
                val focus = remember { FocusRequester() }
                LaunchedEffect(Unit) { focus.requestFocus() }
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    singleLine = true,
                    placeholder = { Text("Search ${page.title.lowercase()}") },
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp).focusRequester(focus),
                )
            }

            Spacer(Modifier.size(16.dp))

            if (page == Page.TOOLS) {
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    ToolsPage(
                        viewModel = viewModel,
                        selectedHz = selectedHz,
                        onPickHz = { hz ->
                            selectedHz = hz
                            viewModel.setRefreshRate(hz)
                        },
                    )
                }
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(if (page == Page.GAMES) 190.dp else 120.dp),
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    items(visible, key = { it.packageName }) { app ->
                        AppCell(
                            app = app,
                            onLaunch = { viewModel.launch(app) },
                            onInfo = { viewModel.openAppInfo(app) },
                            onMove = { viewModel.moveTo(app, it) },
                        )
                    }
                }
            }
        }
    }

    // Result of a refresh-rate change
    refreshResult?.let { r ->
        val clipboard = LocalClipboardManager.current
        val cmd = "setprop debug.oculus.refreshRate ${r.hz}"
        val supported = r.hz in listOf(60, 72, 90, 120)
        AlertDialog(
            onDismissRequest = { viewModel.dismissRefreshResult() },
            title = {
                Text(if (r.applied) "Refresh rate set to ${r.hz} Hz" else "Couldn't set ${r.hz} Hz directly")
            },
            text = {
                Text(
                    when {
                        r.applied -> "The new rate should apply now."
                        !supported -> "${r.hz} Hz isn't a Quest refresh rate, so Quest will most likely ignore it. " +
                            "Quest supports 60, 72, 90 and 120 Hz."
                        else -> "Quest only lets apps change this through ADB. Run this command in an ADB shell " +
                            "(for example in StripesADB):\n\n$cmd"
                    }
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    if (!r.applied) clipboard.setText(AnnotatedString(cmd))
                    viewModel.dismissRefreshResult()
                }) {
                    Text(if (r.applied) "OK" else "Copy command")
                }
            },
            dismissButton = {
                if (!r.applied) {
                    TextButton(onClick = { viewModel.dismissRefreshResult() }) { Text("Close") }
                }
            },
        )
    }
}

@Composable
private fun Rail(page: Page, onSelect: (Page) -> Unit) {
    val shape = RoundedCornerShape(32.dp)
    Column(
        Modifier
            .fillMaxHeight()
            .width(104.dp)
            .clip(shape)
            .background(Glass)
            .border(1.dp, GlassBorder, shape)
            .padding(vertical = 18.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Page.entries.forEach { p ->
            val selected = p == page
            Column(
                Modifier
                    .width(84.dp)
                    .clip(RoundedCornerShape(22.dp))
                    .background(if (selected) Accent else Color.Transparent)
                    .clickable { onSelect(p) }
                    .padding(vertical = 12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Icon(
                    iconFor(p), p.title,
                    tint = if (selected) Color.White else Dim,
                    modifier = Modifier.size(26.dp),
                )
                Spacer(Modifier.size(4.dp))
                Text(p.title, color = if (selected) Color.White else Dim, fontSize = 12.sp)
            }
        }
    }
}

@Composable
private fun Pill(text: String, onClick: () -> Unit) {
    val shape = RoundedCornerShape(50)
    Text(
        text,
        color = Color.White,
        fontSize = 14.sp,
        modifier = Modifier
            .clip(shape)
            .background(Glass)
            .border(1.dp, GlassBorder, shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 9.dp),
    )
}

@Composable
private fun StatusInfo() {
    val context = LocalContext.current
    val timeFmt = remember { SimpleDateFormat("h:mm a", Locale.getDefault()) }

    val time by produceState(timeFmt.format(Date())) {
        while (true) {
            value = timeFmt.format(Date())
            delay(10_000)
        }
    }
    val wifi by produceState(true) {
        while (true) {
            value = isWifiConnected(context)
            delay(15_000)
        }
    }
    val battery by produceState(batteryLevel(context)) {
        while (true) {
            value = batteryLevel(context)
            delay(30_000)
        }
    }

    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(time, color = Color.White, fontSize = 15.sp)
        Spacer(Modifier.width(8.dp))
        Icon(
            if (wifi) Icons.Default.Wifi else Icons.Default.WifiOff, null,
            tint = Color.White, modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(6.dp))
        Icon(Icons.Default.BatteryChargingFull, null, tint = Color.White, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(2.dp))
        Text("$battery%", color = Dim, fontSize = 13.sp)
    }
}

@Composable
private fun ToolsPage(
    viewModel: LauncherViewModel,
    selectedHz: Int?,
    onPickHz: (Int) -> Unit,
) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        ToolCard(
            "Refresh rate",
            "Quest modes are 60, 72, 90 and 120 Hz. 200 Hz isn't a Quest mode, so it will probably be ignored.",
        ) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                listOf(60, 72, 90, 120, 200).forEach { hz ->
                    HzChip(Modifier.weight(1f), hz, hz == selectedHz) { onPickHz(hz) }
                }
            }
        }

        ToolCard("Shortcuts", null) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                ActionButton(Modifier.weight(1f), "Android Settings") { viewModel.openAndroidSettings() }
                ActionButton(Modifier.weight(1f), "YouTube VR") { viewModel.openYouTubeVr() }
                ActionButton(Modifier.weight(1f), "Developer options") { viewModel.openDeveloperSettings() }
            }
        }

        ToolCard(
            "Wireless debugging",
            "Status: not connected. The in-app connection comes in the next update. " +
                "For now: turn on Wireless debugging in Developer options, pair it in StripesADB, " +
                "then tap a refresh rate above and use the copied command.",
        ) {}
    }
}

@Composable
private fun ToolCard(title: String, note: String?, content: @Composable () -> Unit) {
    val shape = RoundedCornerShape(24.dp)
    Column(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(Glass)
            .border(1.dp, GlassBorder, shape)
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(title, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
        if (note != null) {
            Text(note, color = Dim, fontSize = 13.sp)
        }
        content()
    }
}

@Composable
private fun HzChip(modifier: Modifier, hz: Int, selected: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(16.dp)
    Box(
        modifier
            .clip(shape)
            .background(if (selected) Accent else Color(0x1AFFFFFF))
            .border(1.dp, if (selected) Accent else GlassBorder, shape)
            .clickable(onClick = onClick)
            .padding(vertical = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text("$hz Hz", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun ActionButton(modifier: Modifier, text: String, onClick: () -> Unit) {
    val shape = RoundedCornerShape(16.dp)
    Box(
        modifier
            .clip(shape)
            .background(Color(0x1AFFFFFF))
            .border(1.dp, GlassBorder, shape)
            .clickable(onClick = onClick)
            .padding(vertical = 14.dp, horizontal = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = Color.White, fontSize = 14.sp, textAlign = TextAlign.Center)
    }
}

@Composable
private fun AppCell(
    app: AppEntry,
    onLaunch: () -> Unit,
    onInfo: () -> Unit,
    onMove: (LauncherTab) -> Unit,
) {
    var menu by remember { mutableStateOf(false) }

    Box {
        if (app.tab.wide) {
            val shape = RoundedCornerShape(24.dp)
            Box(
                Modifier
                    .fillMaxWidth()
                    .aspectRatio(1.3f)
                    .clip(shape)
                    .background(
                        Brush.verticalGradient(
                            listOf(Color(app.accent).copy(alpha = 0.65f), Color(0xFF0E1220))
                        )
                    )
                    .border(1.dp, GlassBorder, shape)
                    .combinedClickable(onClick = onLaunch, onLongClick = { menu = true }),
            ) {
                if (app.banner != null) {
                    Image(
                        app.banner, app.label,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop,
                    )
                } else {
                    Image(
                        app.icon, app.label,
                        modifier = Modifier
                            .align(Alignment.Center)
                            .padding(bottom = 22.dp)
                            .size(78.dp)
                            .clip(RoundedCornerShape(20.dp)),
                    )
                }
                Box(
                    Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .background(
                            Brush.verticalGradient(listOf(Color.Transparent, Color(0xDD000000)))
                        )
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                ) {
                    Text(
                        app.label,
                        color = Color.White,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        } else {
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(22.dp))
                    .combinedClickable(onClick = onLaunch, onLongClick = { menu = true })
                    .padding(8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                val tile = RoundedCornerShape(24.dp)
                Box(
                    Modifier
                        .size(80.dp)
                        .clip(tile)
                        .background(Glass)
                        .border(1.dp, GlassBorder, tile),
                    contentAlignment = Alignment.Center,
                ) {
                    Image(
                        app.icon, app.label,
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(6.dp)
                            .clip(RoundedCornerShape(18.dp)),
                    )
                }
                Spacer(Modifier.size(6.dp))
                Text(
                    app.label,
                    color = Color.White,
                    fontSize = 13.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                )
            }
        }

        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            val target = if (app.tab == LauncherTab.GAMES) LauncherTab.APPS else LauncherTab.GAMES
            DropdownMenuItem(
                text = { Text("Move to ${target.title}") },
                onClick = { menu = false; onMove(target) },
            )
            DropdownMenuItem(
                text = { Text("App info") },
                onClick = { menu = false; onInfo() },
            )
        }
    }
}

private fun isWifiConnected(context: Context): Boolean {
    val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
    return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
}

private fun batteryLevel(context: Context): Int {
    val intent = context.registerReceiver(null, android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED))
    val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
    val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
    return if (level < 0) 0 else level * 100 / scale
}
