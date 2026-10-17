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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
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
import androidx.compose.ui.graphics.ImageBitmap
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val Bg = Color(0xFF0E131B)
private val Glass = Color(0x14FFFFFF)
private val GlassBorder = Color(0x22FFFFFF)
private val Accent = Color(0xFF7C5CFF)
private val Dim = Color(0xFF8A94A3)

private enum class Page(val title: String, val icon: ImageVector) {
    GAMES("Games", Icons.Default.SportsEsports),
    APPS("Apps", Icons.Default.Apps),
    TOOLS("Tools", Icons.Default.Build),
}

@Composable
fun LauncherScreen(viewModel: LauncherViewModel) {
    val apps by viewModel.apps.collectAsStateWithLifecycle()
    val hiddenCount by viewModel.hiddenCount.collectAsStateWithLifecycle()
    val refreshResult by viewModel.refreshResult.collectAsStateWithLifecycle()

    var page by remember { mutableStateOf(Page.GAMES) }
    var sort by remember { mutableStateOf(SortMode.NAME) }
    var searching by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var sortMenu by remember { mutableStateOf(false) }

    val tab = if (page == Page.APPS) LauncherTab.APPS else LauncherTab.GAMES

    val visible = remember(apps, page, sort, query) {
        apps.asSequence()
            .filter { it.tab == tab }
            .filter { query.isBlank() || it.label.contains(query, ignoreCase = true) }
            .let {
                when (sort) {
                    SortMode.NAME -> it.sortedBy { a -> a.label.lowercase() }
                    SortMode.RECENT -> it.sortedByDescending { a -> a.installTime }
                }
            }
            .toList()
    }

    Row(Modifier.fillMaxSize().background(Bg)) {
        Rail(page) { page = it }

        Column(Modifier.weight(1f).fillMaxHeight().padding(horizontal = 24.dp, vertical = 18.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(page.title, color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.SemiBold)
                    if (page != Page.TOOLS) {
                        Text("${visible.size} items", color = Dim, fontSize = 13.sp)
                    }
                }
                if (page != Page.TOOLS) {
                    Box {
                        IconButton(onClick = { sortMenu = true }) {
                            Icon(Icons.AutoMirrored.Filled.Sort, "Sort", tint = Color.White)
                        }
                        DropdownMenu(expanded = sortMenu, onDismissRequest = { sortMenu = false }) {
                            SortMode.entries.forEach { mode ->
                                DropdownMenuItem(
                                    text = { Text(mode.label) },
                                    onClick = { sort = mode; sortMenu = false },
                                )
                            }
                        }
                    }
                    IconButton(onClick = {
                        searching = !searching
                        if (!searching) query = ""
                    }) {
                        Icon(Icons.Default.Search, "Search", tint = Color.White)
                    }
                }
                Spacer(Modifier.width(12.dp))
                StatusInfo()
            }

            if (searching && page != Page.TOOLS) {
                val focus = remember { FocusRequester() }
                LaunchedEffect(Unit) { focus.requestFocus() }
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    singleLine = true,
                    placeholder = { Text("Search ${page.title.lowercase()}") },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White,
                        cursorColor = Color.White,
                        focusedBorderColor = Accent,
                        unfocusedBorderColor = GlassBorder,
                    ),
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp).focusRequester(focus),
                )
            }

            Spacer(Modifier.size(14.dp))

            if (page == Page.TOOLS) {
                ToolsPage(viewModel, hiddenCount)
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(if (tab.wide) 190.dp else 120.dp),
                    modifier = Modifier.weight(1f),
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    items(visible, key = { it.packageName }) { app ->
                        AppCell(
                            app = app,
                            viewModel = viewModel,
                        )
                    }
                }
            }
        }
    }

    refreshResult?.let { r ->
        val clipboard = LocalClipboardManager.current
        val command = "adb shell setprop debug.oculus.refreshRate ${r.hz}"
        AlertDialog(
            onDismissRequest = { viewModel.dismissRefreshResult() },
            title = { Text(if (r.applied) "${r.hz} Hz applied" else "Couldn't set ${r.hz} Hz") },
            text = {
                Text(
                    if (r.applied) "Refresh rate was set to ${r.hz} Hz."
                    else if (r.hz !in listOf(60, 72, 90, 120))
                        "Quest only supports 60, 72, 90 and 120 Hz, so ${r.hz} Hz is ignored."
                    else "Connect Wireless debugging in Tools first, or run this from a computer:\n\n$command"
                )
            },
            confirmButton = { TextButton(onClick = { viewModel.dismissRefreshResult() }) { Text("OK") } },
            dismissButton = {
                if (!r.applied) TextButton(onClick = { clipboard.setText(AnnotatedString(command)) }) {
                    Text("Copy command")
                }
            },
        )
    }
}

@Composable
private fun Rail(selected: Page, onSelect: (Page) -> Unit) {
    Column(
        Modifier
            .fillMaxHeight()
            .width(96.dp)
            .background(Glass)
            .padding(vertical = 18.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Page.entries.forEach { p ->
            val sel = p == selected
            Column(
                Modifier
                    .width(76.dp)
                    .clip(RoundedCornerShape(18.dp))
                    .background(if (sel) Accent else Color.Transparent)
                    .clickable { onSelect(p) }
                    .padding(vertical = 12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Icon(p.icon, p.title, tint = if (sel) Color.White else Dim, modifier = Modifier.size(26.dp))
                Spacer(Modifier.size(4.dp))
                Text(p.title, color = if (sel) Color.White else Dim, fontSize = 12.sp)
            }
        }
    }
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
        Text("$battery%", color = Dim, fontSize = 13.sp)
    }
}

@Composable
private fun AppCell(app: AppEntry, viewModel: LauncherViewModel) {
    var menu by remember { mutableStateOf(false) }
    val wide = app.tab.wide
    val pkg = app.packageName

    val banner by produceState<ImageBitmap?>(null, pkg) {
        value = if (wide) withContext(Dispatchers.IO) { viewModel.art.banner(pkg) } else null
    }
    val onlineIcon by produceState<ImageBitmap?>(null, pkg, app.iconBlank) {
        value = if (app.iconBlank) withContext(Dispatchers.IO) { viewModel.art.icon(pkg) } else null
    }
    val icon = onlineIcon ?: app.icon
    val hue = (pkg.hashCode() and 0x7fffffff) % 360

    Box {
        if (wide) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .aspectRatio(16f / 9f)
                    .clip(RoundedCornerShape(18.dp))
                    .background(
                        Brush.linearGradient(
                            listOf(Color.hsv(hue.toFloat(), 0.45f, 0.55f), Color.hsv(((hue + 40) % 360).toFloat(), 0.5f, 0.35f))
                        )
                    )
                    .border(1.dp, GlassBorder, RoundedCornerShape(18.dp))
                    .combinedClickable(onClick = { viewModel.launch(app) }, onLongClick = { menu = true }),
                contentAlignment = Alignment.Center,
            ) {
                val b = banner
                if (b != null) {
                    Image(b, app.label, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                } else {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Image(icon, app.label, modifier = Modifier.size(64.dp).clip(RoundedCornerShape(16.dp)))
                        Spacer(Modifier.size(6.dp))
                        Text(
                            app.label,
                            color = Color.White,
                            fontSize = 13.sp,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(horizontal = 8.dp),
                        )
                    }
                }
            }
        } else {
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(18.dp))
                    .combinedClickable(onClick = { viewModel.launch(app) }, onLongClick = { menu = true })
                    .padding(6.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Image(icon, app.label, modifier = Modifier.size(76.dp).clip(RoundedCornerShape(20.dp)))
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
                onClick = { menu = false; viewModel.moveTo(app, target) },
            )
            DropdownMenuItem(
                text = { Text("Hide") },
                onClick = { menu = false; viewModel.hide(app) },
            )
            DropdownMenuItem(
                text = { Text("App info") },
                onClick = { menu = false; viewModel.openAppInfo(app) },
            )
        }
    }
}

@Composable
private fun ToolsPage(viewModel: LauncherViewModel, hiddenCount: Int) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        SectionTitle("Refresh rate")
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            listOf(60, 72, 90, 120, 200).forEach { hz ->
                ToolChip("$hz Hz") { viewModel.setRefreshRate(hz) }
            }
        }

        SectionTitle("Shortcuts")
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            ToolChip("Android Settings") { viewModel.openAndroidSettings() }
            ToolChip("YouTube VR") { viewModel.openYouTubeVr() }
            ToolChip("Developer options") { viewModel.openDeveloperSettings() }
        }

        if (hiddenCount > 0) {
            SectionTitle("Hidden apps")
            Row { ToolChip("Show $hiddenCount hidden apps") { viewModel.unhideAll() } }
        }

        AdbPanel(viewModel)
        Spacer(Modifier.size(24.dp))
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
}

@Composable
private fun ToolChip(text: String, onClick: () -> Unit) {
    val shape = RoundedCornerShape(16.dp)
    Box(
        Modifier
            .clip(shape)
            .background(Glass)
            .border(1.dp, GlassBorder, shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = Color.White, fontSize = 15.sp)
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
