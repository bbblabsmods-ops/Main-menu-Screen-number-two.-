@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.example.questlauncher

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.widget.Toast
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val BgTop = Color(0xFF06080F)
private val BgBottom = Color(0xFF151033)
private val Accent = Color(0xFF7C5CFF)
private val Dim = Color(0xFF9AA4B5)

internal val GlassFill = Color(0x1AFFFFFF)
internal val GlassEdge = Brush.linearGradient(listOf(Color(0x66FFFFFF), Color(0x10FFFFFF)))
internal val AccentGradient = Brush.linearGradient(listOf(Color(0xFF7C5CFF), Color(0xFF2DD4F0)))

/** Frosted-glass look: soft fill with a bright edge. */
internal fun Modifier.glass(radius: Dp = 20.dp): Modifier =
    this
        .clip(RoundedCornerShape(radius))
        .background(GlassFill)
        .border(1.dp, GlassEdge, RoundedCornerShape(radius))

/** Grows a little when the controller points at it. */
@Composable
internal fun Modifier.hoverLift(amount: Float = 1.05f): Modifier {
    val source = remember { MutableInteractionSource() }
    val hovered by source.collectIsHoveredAsState()
    val s by animateFloatAsState(if (hovered) amount else 1f, label = "hover")
    return this
        .hoverable(source)
        .graphicsLayer {
            scaleX = s
            scaleY = s
        }
}

private val Swatches = listOf(
    Color(0xFFE5534B), Color(0xFFF2994A), Color(0xFFF2C94C), Color(0xFF3DDC84),
    Color(0xFF27AE9A), Color(0xFF2D9CDB), Color(0xFF2F5BEA), Color(0xFF7C5CFF),
    Color(0xFFBB6BD9), Color(0xFFEB5FA0), Color(0xFF8B5E3C), Color(0xFF6B7280),
    Color(0xFF111827), Color(0xFFFFFFFF),
)

private enum class Page(val title: String, val icon: ImageVector) {
    GAMES("Games", Icons.Default.SportsEsports),
    APPS("Apps", Icons.Default.Apps),
    TOOLS("Tools", Icons.Default.Build),
    COLORS("Colors", Icons.Default.Palette),
    ASSISTANT("Assistant", Icons.Default.AutoAwesome),
}

@Composable
fun LauncherScreen(viewModel: LauncherViewModel) {
    val context = LocalContext.current
    val tileColors = remember { TileColors(context) }
    val settingsRepo = remember { AppRepository(context) }

    val apps by viewModel.apps.collectAsStateWithLifecycle()
    val hiddenCount by viewModel.hiddenCount.collectAsStateWithLifecycle()
    val refreshResult by viewModel.refreshResult.collectAsStateWithLifecycle()

    var page by remember { mutableStateOf(Page.GAMES) }
    var sort by remember { mutableStateOf(SortMode.NAME) }
    var searching by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var sortMenu by remember { mutableStateOf(false) }

    val isGrid = page == Page.GAMES || page == Page.APPS
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

    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(BgTop, BgBottom)))) {
        // soft glowing light behind everything
        Box(
            Modifier
                .size(460.dp)
                .align(Alignment.TopEnd)
                .background(Brush.radialGradient(listOf(Color(0x447C5CFF), Color.Transparent)), CircleShape)
        )
        Box(
            Modifier
                .size(420.dp)
                .align(Alignment.BottomStart)
                .background(Brush.radialGradient(listOf(Color(0x332DD4F0), Color.Transparent)), CircleShape)
        )

        Row(Modifier.fillMaxSize()) {
            Rail(page) { page = it }

            Column(Modifier.weight(1f).fillMaxHeight().padding(end = 24.dp, top = 18.dp, bottom = 18.dp, start = 6.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(page.title, color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.Bold)
                            Spacer(Modifier.width(12.dp))
                            Box(
                                Modifier
                                    .clip(RoundedCornerShape(50))
                                    .background(AccentGradient)
                                    .padding(horizontal = 14.dp, vertical = 5.dp),
                            ) {
                                Text("BBB", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.ExtraBold)
                            }
                        }
                        if (isGrid) {
                            Text("${visible.size} items", color = Dim, fontSize = 13.sp)
                        }
                    }
                    if (isGrid) {
                        Box {
                            GlassIconButton(Icons.AutoMirrored.Filled.Sort, "Sort") { sortMenu = true }
                            DropdownMenu(expanded = sortMenu, onDismissRequest = { sortMenu = false }) {
                                SortMode.entries.forEach { mode ->
                                    DropdownMenuItem(
                                        text = { Text(mode.label) },
                                        onClick = { sort = mode; sortMenu = false },
                                    )
                                }
                            }
                        }
                        Spacer(Modifier.width(10.dp))
                        GlassIconButton(Icons.Default.Search, "Search") {
                            searching = !searching
                            if (!searching) query = ""
                        }
                        Spacer(Modifier.width(10.dp))
                    }
                    StatusInfo()
                }

                if (searching && isGrid) {
                    val focus = remember { FocusRequester() }
                    LaunchedEffect(Unit) { focus.requestFocus() }
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        singleLine = true,
                        placeholder = { Text("Search ${page.title.lowercase()}") },
                        shape = RoundedCornerShape(20.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White,
                            cursorColor = Color.White,
                            focusedBorderColor = Color(0xFF2DD4F0),
                            unfocusedBorderColor = Color(0x33FFFFFF),
                        ),
                        modifier = Modifier.fillMaxWidth().padding(top = 10.dp).focusRequester(focus),
                    )
                }

                Spacer(Modifier.size(16.dp))

                when (page) {
                    Page.TOOLS -> ToolsPage(viewModel, hiddenCount)
                    Page.COLORS -> ColorsPage(apps, tileColors)
                    Page.ASSISTANT -> AssistantPage(viewModel)
                    else -> LazyVerticalGrid(
                        columns = GridCells.Adaptive(if (tab.wide) 190.dp else 120.dp),
                        modifier = Modifier.weight(1f),
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        if (page == Page.APPS && (query.isBlank() || "settings".contains(query.trim(), ignoreCase = true))) {
                            item(key = "bbb_settings_tile") { SettingsCell(settingsRepo) }
                        }
                        items(visible, key = { it.packageName }) { app ->
                            AppCell(app = app, viewModel = viewModel, tileColors = tileColors)
                        }
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
private fun GlassIconButton(icon: ImageVector, description: String, onClick: () -> Unit) {
    Box(
        Modifier
            .hoverLift(1.1f)
            .size(46.dp)
            .glass(23.dp)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, description, tint = Color.White, modifier = Modifier.size(22.dp))
    }
}

@Composable
private fun Rail(selected: Page, onSelect: (Page) -> Unit) {
    Column(
        Modifier
            .padding(14.dp)
            .fillMaxHeight()
            .width(84.dp)
            .glass(32.dp)
            .verticalScroll(rememberScrollState())
            .padding(vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier.size(48.dp).clip(CircleShape).background(AccentGradient),
            contentAlignment = Alignment.Center,
        ) {
            Text("B", color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.ExtraBold)
        }
        Spacer(Modifier.size(8.dp))
        Page.entries.forEach { p ->
            val sel = p == selected
            Column(
                Modifier
                    .hoverLift(1.08f)
                    .width(64.dp)
                    .clip(RoundedCornerShape(22.dp))
                    .then(if (sel) Modifier.background(AccentGradient) else Modifier)
                    .clickable { onSelect(p) }
                    .padding(vertical = 10.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Icon(p.icon, p.title, tint = if (sel) Color.White else Dim, modifier = Modifier.size(26.dp))
                Spacer(Modifier.size(3.dp))
                Text(
                    p.title,
                    color = if (sel) Color.White else Dim,
                    fontSize = 11.sp,
                    fontWeight = if (sel) FontWeight.Bold else FontWeight.Normal,
                )
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

    Row(
        Modifier.glass(23.dp).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(time, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.width(10.dp))
        Icon(
            if (wifi) Icons.Default.Wifi else Icons.Default.WifiOff, null,
            tint = Color.White, modifier = Modifier.size(17.dp),
        )
        Spacer(Modifier.width(8.dp))
        Icon(Icons.Default.BatteryChargingFull, null, tint = Color(0xFF3DDC84), modifier = Modifier.size(17.dp))
        Text("$battery%", color = Dim, fontSize = 12.sp)
    }
}

/**
 * The Settings tile in the Apps tab.
 * Tap: Android's App info page for Settings. Long-press: more Android screens.
 */
@Composable
private fun SettingsCell(repo: AppRepository) {
    val context = LocalContext.current
    var menu by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(22.dp)

    fun report(ok: Boolean) {
        if (!ok) Toast.makeText(context, "Couldn't open that screen", Toast.LENGTH_SHORT).show()
    }

    Box {
        Column(
            Modifier
                .hoverLift(1.08f)
                .fillMaxWidth()
                .clip(shape)
                .background(Color(0x12FFFFFF))
                .border(1.dp, GlassEdge, shape)
                .combinedClickable(
                    onClick = { report(repo.openSettingsAppInfo()) },
                    onLongClick = { menu = true },
                )
                .padding(8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(
                Modifier.size(76.dp).clip(RoundedCornerShape(19.dp)).background(AccentGradient),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Default.Settings, "Settings", tint = Color.White, modifier = Modifier.size(44.dp))
            }
            Spacer(Modifier.size(6.dp))
            Text(
                "Settings",
                color = Color.White,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
            )
        }

        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(
                text = { Text("App info (Settings)") },
                onClick = { menu = false; report(repo.openSettingsAppInfo()) },
            )
            DropdownMenuItem(
                text = { Text("Android Settings") },
                onClick = { menu = false; report(repo.openAndroidSettings()) },
            )
            DropdownMenuItem(
                text = { Text("All apps list") },
                onClick = { menu = false; report(repo.openAllAppsList()) },
            )
            DropdownMenuItem(
                text = { Text("Developer options") },
                onClick = { menu = false; report(repo.openDeveloperSettings()) },
            )
        }
    }
}

@Composable
private fun AppCell(app: AppEntry, viewModel: LauncherViewModel, tileColors: TileColors) {
    var menu by remember { mutableStateOf(false) }
    val wide = app.tab.wide
    val pkg = app.packageName

    val banner by produceState<ImageBitmap?>(null, pkg) {
        if (wide) {
            var tries = 0
            while (value == null && tries < 4) {
                value = withContext(Dispatchers.IO) { viewModel.art.banner(pkg) }
                if (value == null) {
                    tries++
                    delay(5000L * tries)
                }
            }
        }
    }
    val onlineIcon by produceState<ImageBitmap?>(null, pkg, app.iconBlank) {
        if (app.iconBlank) {
            var tries = 0
            while (value == null && tries < 4) {
                value = withContext(Dispatchers.IO) { viewModel.art.icon(pkg) }
                if (value == null) {
                    tries++
                    delay(5000L * tries)
                }
            }
        }
    }
    val icon = onlineIcon ?: app.icon
    val hue = (pkg.hashCode() and 0x7fffffff) % 360
    val custom = tileColors.forApp(pkg)

    Box {
        if (wide) {
            val shape = RoundedCornerShape(24.dp)
            val brush = if (custom != null) {
                Brush.linearGradient(listOf(custom, lerp(custom, Color.Black, 0.35f)))
            } else {
                Brush.linearGradient(
                    listOf(Color.hsv(hue.toFloat(), 0.5f, 0.6f), Color.hsv(((hue + 40) % 360).toFloat(), 0.55f, 0.32f))
                )
            }
            Box(
                Modifier
                    .hoverLift(1.06f)
                    .fillMaxWidth()
                    .aspectRatio(16f / 9f)
                    .clip(shape)
                    .background(brush)
                    .border(1.dp, GlassEdge, shape)
                    .combinedClickable(onClick = { viewModel.launch(app) }, onLongClick = { menu = true }),
                contentAlignment = Alignment.Center,
            ) {
                val b = banner
                if (b != null) {
                    Image(
                        b, app.label,
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(if (custom != null) 8.dp else 0.dp)
                            .clip(RoundedCornerShape(if (custom != null) 16.dp else 0.dp)),
                        contentScale = ContentScale.Crop,
                    )
                } else {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        IconOrLetter(icon, app, onlineIcon != null, 64)
                        Spacer(Modifier.size(6.dp))
                        Text(
                            app.label,
                            color = Color.White,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(horizontal = 8.dp),
                        )
                    }
                }
            }
        } else {
            val shape = RoundedCornerShape(22.dp)
            Column(
                Modifier
                    .hoverLift(1.08f)
                    .fillMaxWidth()
                    .clip(shape)
                    .background(custom ?: Color(0x12FFFFFF))
                    .border(1.dp, GlassEdge, shape)
                    .combinedClickable(onClick = { viewModel.launch(app) }, onLongClick = { menu = true })
                    .padding(8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                IconOrLetter(icon, app, onlineIcon != null, 76)
                Spacer(Modifier.size(6.dp))
                Text(
                    app.label,
                    color = Color.White,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
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
private fun IconOrLetter(icon: ImageBitmap, app: AppEntry, haveArt: Boolean, size: Int) {
    val shape = RoundedCornerShape((size / 4).dp)
    if (app.iconBlank && !haveArt) {
        val hue = ((app.packageName.hashCode() and 0x7fffffff) % 360).toFloat()
        Box(
            Modifier.size(size.dp).clip(shape).background(Color.hsv(hue, 0.5f, 0.75f)),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                app.label.firstOrNull()?.uppercase() ?: "?",
                color = Color.White,
                fontSize = (size / 2).sp,
                fontWeight = FontWeight.Bold,
            )
        }
    } else {
        Image(icon, app.label, modifier = Modifier.size(size.dp).clip(shape))
    }
}

@Composable
private fun ColorsPage(apps: List<AppEntry>, tileColors: TileColors) {
    val sorted = remember(apps) { apps.sortedBy { it.label.lowercase() } }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        SectionTitle("Every game and app")
        Text("Pick one color for all of them. Tap the X to go back to the default look.", color = Dim, fontSize = 13.sp)
        SwatchRow(selected = tileColors.colors["*"]?.let { Color(it) }) { tileColors.setAll(it) }

        SectionTitle("Each one")
        Text("Pick a color for just one. It wins over the color above.", color = Dim, fontSize = 13.sp)

        sorted.forEach { app ->
            Column(
                Modifier
                    .fillMaxWidth()
                    .glass(22.dp)
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    "${app.label}  ·  ${app.tab.title}",
                    color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                )
                SwatchRow(
                    selected = if (tileColors.isCustom(app.packageName))
                        tileColors.colors[app.packageName]?.let { Color(it) } else null,
                ) { tileColors.set(app.packageName, it) }
            }
        }
        Spacer(Modifier.size(24.dp))
    }
}

@Composable
private fun SwatchRow(selected: Color?, onPick: (Color?) -> Unit) {
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(
            Modifier
                .hoverLift(1.15f)
                .size(40.dp)
                .clip(CircleShape)
                .background(GlassFill)
                .border(if (selected == null) 3.dp else 1.dp, if (selected == null) Color.White else Color(0x33FFFFFF), CircleShape)
                .clickable { onPick(null) },
            contentAlignment = Alignment.Center,
        ) {
            Text("✕", color = Color.White, fontSize = 16.sp)
        }
        Swatches.forEach { c ->
            val isSel = selected == c
            Box(
                Modifier
                    .hoverLift(1.15f)
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(c)
                    .border(if (isSel) 3.dp else 1.dp, if (isSel) Color.White else Color(0x33FFFFFF), CircleShape)
                    .clickable { onPick(c) },
            )
        }
    }
}

@Composable
private fun ToolsPage(viewModel: LauncherViewModel, hiddenCount: Int) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        SectionTitle("Refresh rate")
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            listOf(60, 72, 90, 120, 200).forEach { hz ->
                ToolChip("$hz Hz", primary = true) { viewModel.setRefreshRate(hz) }
            }
        }

        SectionTitle("Shortcuts")
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            ToolChip("Android Settings") { viewModel.openAndroidSettings() }
            ToolChip("YouTube VR") { viewModel.openYouTubeVr() }
            ToolChip("Developer options") { viewModel.openDeveloperSettings() }
        }

        if (hiddenCount > 0) {
            SectionTitle("Hidden apps")
            Row { ToolChip("Show $hiddenCount hidden apps") { viewModel.unhideAll() } }
        }

        AdbPanel(viewModel)
        ImageCheck(viewModel)
        Spacer(Modifier.size(24.dp))
    }
}

@Composable
private fun ImageCheck(viewModel: LauncherViewModel) {
    var lines by remember { mutableStateOf(listOf<String>()) }
    Column(
        Modifier.fillMaxWidth().glass(26.dp).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        SectionTitle("Image check")
        Text(
            "If some games have no picture, tap Show log and send a screenshot of it.",
            color = Dim, fontSize = 13.sp,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            ToolChip("Show log") { lines = viewModel.art.log().takeLast(40).reversed() }
            ToolChip("Clear image cache") {
                viewModel.art.clearCache()
                lines = viewModel.art.log().reversed()
            }
        }
        lines.forEach {
            Text(it, color = Dim, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.width(5.dp).height(20.dp).clip(RoundedCornerShape(3.dp)).background(AccentGradient))
        Spacer(Modifier.width(10.dp))
        Text(text, color = Color.White, fontSize = 19.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun ToolChip(text: String, primary: Boolean = false, onClick: () -> Unit) {
    val shape = RoundedCornerShape(20.dp)
    Box(
        Modifier
            .hoverLift(1.07f)
            .clip(shape)
            .then(if (primary) Modifier.background(AccentGradient) else Modifier.background(GlassFill))
            .border(1.dp, GlassEdge, shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 22.dp, vertical = 15.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold)
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
