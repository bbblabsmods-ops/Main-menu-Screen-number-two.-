package com.example.questlauncher

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val ChatDim = Color(0xFF9AA4B5)

@Composable
fun AssistantPage(viewModel: LauncherViewModel) {
    val context = LocalContext.current
    val assistant = remember { Assistant(context, viewModel) }

    val messages by AssistantHub.messages.collectAsState()
    val status by AssistantHub.status.collectAsState()

    var input by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    var claudeKey by remember { mutableStateOf(assistant.apiKey()) }
    var geminiKey by remember { mutableStateOf(assistant.geminiKey()) }
    var geminiModel by remember { mutableStateOf(assistant.geminiModel()) }
    var wake by remember { mutableStateOf(assistant.wakeWord()) }
    var voiceOn by remember { mutableStateOf(assistant.voiceOn()) }
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()

    fun hasMic(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    var micOk by remember { mutableStateOf(hasMic()) }
    var popupOk by remember { mutableStateOf(Settings.canDrawOverlays(context)) }

    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.size - 1)
    }

    fun startVoice() {
        try {
            ContextCompat.startForegroundService(context, Intent(context, VoiceService::class.java))
        } catch (e: Exception) {
            AssistantHub.setStatus("Couldn't start listening: " + (e.message ?: e.javaClass.simpleName))
        }
    }

    fun stopVoice() {
        context.stopService(Intent(context, VoiceService::class.java))
    }

    fun send() {
        val text = input.trim()
        if (text.isEmpty() || busy) return
        input = ""
        AssistantHub.add(ChatMessage(true, text))
        busy = true
        scope.launch {
            val answer = try {
                assistant.reply(text, AssistantHub.messages.value)
            } catch (e: Exception) {
                "Something went wrong: ${e.message}"
            }
            AssistantHub.add(ChatMessage(false, answer))
            busy = false
        }
    }

    val micLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        micOk = granted
        if (granted) {
            voiceOn = true
            assistant.setVoiceOn(true)
            startVoice()
        } else {
            AssistantHub.setStatus(
                "Microphone permission was blocked. Open AI settings and tap \"Grant mic (Wireless debugging)\"."
            )
            showSettings = true
        }
    }

    fun toggleMic() {
        if (voiceOn) {
            voiceOn = false
            assistant.setVoiceOn(false)
            stopVoice()
            AssistantHub.setStatus("Mic is off.")
        } else if (hasMic()) {
            micOk = true
            voiceOn = true
            assistant.setVoiceOn(true)
            startVoice()
        } else {
            micLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    fun grantMicWithAdb() {
        scope.launch {
            AssistantHub.setStatus("Asking the headset for microphone permission…")
            val connected = withContext(Dispatchers.IO) {
                val adb = AdbController(context)
                if (adb.isConnected()) {
                    adb.shell("pm grant ${context.packageName} android.permission.RECORD_AUDIO")
                    true
                } else {
                    false
                }
            }
            if (!connected) {
                AssistantHub.setStatus("Connect Wireless debugging in Tools first, then try again.")
            } else {
                micOk = hasMic()
                if (micOk) {
                    voiceOn = true
                    assistant.setVoiceOn(true)
                    startVoice()
                    AssistantHub.setStatus("Microphone allowed.")
                } else {
                    AssistantHub.setStatus("The headset still says no. Try again, or allow the microphone in Settings > Apps.")
                }
            }
        }
    }

    fun allowPopup() {
        scope.launch {
            AssistantHub.setStatus("Allowing popups…")
            val connected = withContext(Dispatchers.IO) {
                val adb = AdbController(context)
                if (adb.isConnected()) {
                    adb.shell("appops set ${context.packageName} SYSTEM_ALERT_WINDOW allow")
                    true
                } else {
                    false
                }
            }
            popupOk = Settings.canDrawOverlays(context)
            if (popupOk) {
                AssistantHub.setStatus("Popups allowed. They also let BBB open apps while you're in another app.")
            } else if (connected) {
                AssistantHub.setStatus("The headset still says no. Try again in a moment.")
            } else {
                try {
                    val intent = Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:${context.packageName}"),
                    )
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(intent)
                } catch (e: Exception) {
                }
                AssistantHub.setStatus(
                    "If Settings didn't open, connect Wireless debugging in Tools, then tap this again."
                )
            }
        }
    }

    val fieldColors = OutlinedTextFieldDefaults.colors(
        focusedTextColor = Color.White,
        unfocusedTextColor = Color.White,
        cursorColor = Color.White,
        focusedBorderColor = Color(0xFF2DD4F0),
        unfocusedBorderColor = Color(0x33FFFFFF),
        focusedLabelColor = Color.White,
        unfocusedLabelColor = ChatDim,
    )

    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                if (status.isNotEmpty()) status
                else if (wake) "Say \"Hey BBB\" first. Turn on Mic to talk."
                else "Wake word is off.",
                color = ChatDim, fontSize = 13.sp, modifier = Modifier.weight(1f),
            )
            ChipButton(if (voiceOn) "Mic: On" else "Mic: Off", voiceOn) { toggleMic() }
            ChipButton("AI settings", false) {
                showSettings = !showSettings
                popupOk = Settings.canDrawOverlays(context)
                micOk = hasMic()
            }
        }

        if (showSettings) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = 380.dp)
                    .glass(22.dp)
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    "Free AI: BBB already tries a free AI that needs no key and no payment. If it is busy " +
                        "or slow, add a free Google Gemini key from aistudio.google.com/apikey. " +
                        "Google lists its Flash models as free of charge on the free tier. " +
                        "Free-tier requests may be used by Google to improve its products.",
                    color = ChatDim, fontSize = 13.sp,
                )
                OutlinedTextField(
                    value = geminiKey,
                    onValueChange = { geminiKey = it },
                    label = { Text("Gemini key (free)") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    colors = fieldColors,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = geminiModel,
                    onValueChange = { geminiModel = it },
                    label = { Text("Gemini model name") },
                    singleLine = true,
                    colors = fieldColors,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = claudeKey,
                    onValueChange = { claudeKey = it },
                    label = { Text("Claude key (paid, optional)") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    colors = fieldColors,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    ChipButton("Save", true) {
                        assistant.saveGeminiKey(geminiKey)
                        assistant.saveGeminiModel(geminiModel)
                        assistant.saveApiKey(claudeKey)
                        showSettings = false
                    }
                    ChipButton("Remove keys", false) {
                        geminiKey = ""
                        claudeKey = ""
                        assistant.saveGeminiKey("")
                        assistant.saveApiKey("")
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    ChipButton(if (wake) "Wake word: On" else "Wake word: Off", wake) {
                        wake = !wake
                        assistant.setWakeWord(wake)
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    ChipButton(
                        if (popupOk) "Popup over other apps: Allowed" else "Allow popup over other apps",
                        popupOk,
                    ) { allowPopup() }
                }
                if (!micOk) {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        ChipButton("Grant mic (Wireless debugging)", false) { grantMicWithAdb() }
                    }
                }
            }
        }

        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            items(messages.size) { i -> Bubble(messages[i]) }
            if (busy) {
                item { Text("Thinking…", color = ChatDim, fontSize = 14.sp) }
            }
        }

        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                placeholder = { Text("Say \"Hey BBB, …\"") },
                singleLine = true,
                shape = RoundedCornerShape(22.dp),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { send() }),
                colors = fieldColors,
                modifier = Modifier.weight(1f),
            )
            ChipButton("Send", true) { send() }
        }
    }
}

@Composable
private fun Bubble(m: ChatMessage) {
    val shape = RoundedCornerShape(22.dp)
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = if (m.fromUser) Arrangement.End else Arrangement.Start,
    ) {
        Box(
            Modifier
                .widthIn(max = 560.dp)
                .clip(shape)
                .then(if (m.fromUser) Modifier.background(AccentGradient) else Modifier.background(GlassFill))
                .border(1.dp, GlassEdge, shape)
                .padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            Text(m.text, color = Color.White, fontSize = 15.sp)
        }
    }
}

@Composable
private fun ChipButton(text: String, primary: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(20.dp)
    Box(
        Modifier
            .hoverLift(1.07f)
            .clip(shape)
            .then(if (primary) Modifier.background(AccentGradient) else Modifier.background(GlassFill))
            .border(1.dp, GlassEdge, shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
    }
}
