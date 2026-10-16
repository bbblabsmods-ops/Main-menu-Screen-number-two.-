package com.example.questlauncher

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val Bg = Color(0xFF111720)
private val PillBg = Color(0xFF0A0F15)
private val Dim = Color(0xFF8A94A3)

@Composable
fun LauncherScreen(viewModel: LauncherViewModel) {
    val apps by viewModel.apps.collectAsStateWithLifecycle()

    var tab by remember { mutableStateOf(LauncherTab.GAMES) }
    var sort by remember { mutableStateOf(SortMode.NAME) }
    var searching by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var sortMenu by remember { mutableStateOf(false) }
    var settingsMenu by remember { mutableStateOf(false) }

    val visible = remember(apps, tab, sort, query) {
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

    Box(Modifier.fillMaxSize().background(Bg)) {
        Column(Modifier.fillMaxSize().padding(horizontal = 28.dp, vertical = 20.dp)) {

            // Header: status | tabs | actions
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                    StatusInfo()
                }
                TabPill(selected = tab, onSelect = { tab = it })
                Row(
                    Modifier.weight(1f),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
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
                    Box {
                        IconButton(onClick = { settingsMenu = true }) {
                            Icon(Icons.Default.Settings, "Settings", tint = Color.White)
                        }
                        DropdownMenu(expanded = settingsMenu, onDismissRequest = { settingsMenu = false }) {
                            // Placeholder: this is where "Your games", "Unknown sources", etc. will go.
                            DropdownMenuItem(
                                text = { Text("Launcher settings (coming soon)") },
                                onClick = { settingsMenu = false },
                            )
                        }
                    }
                }
            }

            if (searching) {
                val focus = remember { FocusRequester() }
                LaunchedEffect(Unit) { focus.requestFocus() }
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    singleLine = true,
                    placeholder = { Text("Search ${tab.title.lowercase()}") },
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp).focusRequester(focus),
                )
            }

            Spacer(Modifier.size(16.dp))

            LazyVerticalGrid(
                columns = GridCells.Adaptive(if (tab.wide) 210.dp else 112.dp),
                modifier = Modifier.weight(1f),
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

        // Home handle
        Box(
            Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 10.dp)
                .size(width = 72.dp, height = 6.dp)
                .clip(CircleShape)
                .background(Color(0xFFBFC5CE))
        )
    }
}

@Composable
private fun TabPill(selected: LauncherTab, onSelect: (LauncherTab) -> Unit) {
    Row(
        Modifier.clip(RoundedCornerShape(50)).background(PillBg).padding(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LauncherTab.entries.forEach { t ->
            val isSel = t == selected
            Row(
                Modifier
                    .clip(RoundedCornerShape(50))
                    .background(if (isSel) Color(0xFF1B2330) else Color.Transparent)
                    .combinedClickable(onClick = { onSelect(t) })
                    .padding(horizontal = 22.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (t == LauncherTab.GAMES) {
                    Icon(
                        Icons.Default.Star, null,
                        tint = if (isSel) Color.White else Dim,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(Modifier.width(4.dp))
                }
                Text(t.title, color = if (isSel) Color.White else Dim, fontSize = 16.sp)
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
        Spacer(Modifier.width(2.dp))
        Text("$battery%", color = Dim, fontSize = 13.sp)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AppCell(
    app: AppEntry,
    onLaunch: () -> Unit,
    onInfo: () -> Unit,
    onMove: (LauncherTab) -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    val wide = app.tab.wide

    Box {
        if (wide) {
            // Wide card: banner if the app has one, otherwise a gradient with its icon.
            Box(
                Modifier
                    .fillMaxWidth()
                    .aspectRatio(16f / 9f)
                    .clip(RoundedCornerShape(18.dp))
                    .background(
                        Brush.linearGradient(listOf(Color(0xFF8E7CE0), Color(0xFF5B7FD6)))
                    )
                    .combinedClickable(onClick = onLaunch, onLongClick = { menu = true }),
                contentAlignment = Alignment.Center,
            ) {
                if (app.banner != null) {
                    Image(
                        app.banner, app.label,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop,
                    )
                } else {
                    Image(app.icon, app.label, modifier = Modifier.size(64.dp))
                }
            }
        } else {
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(18.dp))
                    .combinedClickable(onClick = onLaunch, onLongClick = { menu = true })
                    .padding(6.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Image(
                    app.icon, app.label,
                    modifier = Modifier.size(76.dp).clip(RoundedCornerShape(20.dp)),
                )
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
