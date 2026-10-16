package com.example.questlauncher

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

private val PanelAccent = Color(0xFF7C5CFF)
private val PanelGlass = Color(0x14FFFFFF)
private val PanelBorder = Color(0x22FFFFFF)
private val PanelDim = Color(0xFF8A94A3)

@Composable
fun AdbPanel(viewModel: LauncherViewModel) {
    val state by viewModel.adb.collectAsStateWithLifecycle()

    var host by remember { mutableStateOf("127.0.0.1") }
    var pairPort by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    var connectPort by remember { mutableStateOf("") }

    LaunchedEffect(Unit) { viewModel.refreshAdbState() }

    val shape = RoundedCornerShape(24.dp)
    Column(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(PanelGlass)
            .border(1.dp, PanelBorder, shape)
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(10.dp)
                    .clip(CircleShape)
                    .background(if (state.connected) Color(0xFF3DDC84) else Color(0xFFE5534B))
            )
            Spacer(Modifier.width(10.dp))
            Text("Wireless debugging", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
        }

        Text(state.message, color = Color.White, fontSize = 14.sp)

        Text(
            "1. Open Developer options (button above) and turn on Wireless debugging.\n" +
                "2. Tap \"Pair device with pairing code\" and keep that window open.\n" +
                "3. Type the pairing port and the 6-digit code below, then tap Pair.\n" +
                "4. Tap Connect. Leave the connect port empty to find it automatically, " +
                "or type the port shown on the main Wireless debugging screen.",
            color = PanelDim,
            fontSize = 13.sp,
        )

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Field(host, "IP address", false, Modifier.weight(1.3f)) { host = it }
            Field(pairPort, "Pairing port", true, Modifier.weight(1f)) { pairPort = it }
            Field(code, "Pairing code", true, Modifier.weight(1f)) { code = it }
        }

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            PanelButton("Pair", !state.busy, Modifier.weight(1f), primary = true) {
                viewModel.adbPair(host.trim(), pairPort.trim().toIntOrNull(), code.trim())
            }
        }

        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Field(connectPort, "Connect port (optional)", true, Modifier.weight(1.3f)) { connectPort = it }
            PanelButton("Connect", !state.busy, Modifier.weight(1f), primary = true) {
                val text = connectPort.trim()
                if (text.isEmpty()) {
                    viewModel.adbConnect(host.trim(), null)
                } else {
                    val port = text.toIntOrNull()
                    if (port == null) viewModel.adbNote("The connect port must be a number.")
                    else viewModel.adbConnect(host.trim(), port)
                }
            }
            if (state.connected) {
                PanelButton("Disconnect", !state.busy, Modifier.weight(1f), primary = false) {
                    viewModel.adbDisconnect()
                }
            }
        }
    }
}

@Composable
private fun Field(
    value: String,
    label: String,
    number: Boolean,
    modifier: Modifier,
    onChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(
            keyboardType = if (number) KeyboardType.Number else KeyboardType.Text
        ),
        colors = OutlinedTextFieldDefaults.colors(
            focusedTextColor = Color.White,
            unfocusedTextColor = Color.White,
            cursorColor = Color.White,
            focusedBorderColor = PanelAccent,
            unfocusedBorderColor = PanelBorder,
            focusedLabelColor = Color.White,
            unfocusedLabelColor = PanelDim,
        ),
        modifier = modifier,
    )
}

@Composable
private fun PanelButton(
    text: String,
    enabled: Boolean,
    modifier: Modifier,
    primary: Boolean,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(16.dp)
    Box(
        modifier
            .alpha(if (enabled) 1f else 0.5f)
            .clip(shape)
            .background(if (primary) PanelAccent else Color(0x1AFFFFFF))
            .border(1.dp, if (primary) PanelAccent else PanelBorder, shape)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = 16.dp, horizontal = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
    }
}
