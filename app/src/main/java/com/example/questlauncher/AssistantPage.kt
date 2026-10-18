package com.example.questlauncher

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
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
import kotlinx.coroutines.launch

private val ChatDim = Color(0xFF9AA4B5)

@Composable
fun AssistantPage(viewModel: LauncherViewModel) {
    val context = LocalContext.current
    val assistant = remember { Assistant(context, viewModel) }
    val messages = remember {
        mutableStateListOf(
            ChatMessage(
                false,
                "Hi! I'm the BBB assistant. Try: \"open Beat Saber\", \"what time is it\", " +
                    "\"set 90 Hz\" or \"what is a black hole\"."
            )
        )
    }
    var input by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    var keyText by remember { mutableStateOf(assistant.apiKey()) }
    var hasKey by remember { mutableStateOf(assistant.apiKey().isNotEmpty()) }
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()

    fun send() {
        val text = input.trim()
        if (text.isEmpty() || busy) return
        input = ""
        messages.add(ChatMessage(true, text))
        busy = true
        scope.launch {
            val answer = try {
                assistant.reply(text, messages.toList())
            } catch (e: Exception) {
                "Something went wrong: ${e.message}"
            }
            messages.add(ChatMessage(false, answer))
            busy = false
            listState.animateScrollToItem(messages.size - 1)
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
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (hasKey) "AI: Claude is on" else "AI: basic mode (add a key in AI settings)",
                color = ChatDim, fontSize = 13.sp, modifier = Modifier.weight(1f),
            )
            Box(
                Modifier
                    .hoverLift(1.06f)
                    .glass(18.dp)
                    .clickable { showSettings = !showSettings }
                    .padding(horizontal = 16.dp, vertical = 10.dp),
            ) {
                Text("AI settings", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            }
        }

        if (showSettings) {
            Column(
                Modifier.fillMaxWidth().glass(22.dp).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    "For full AI answers, get an API key at console.anthropic.com and paste it here. " +
                        "It is saved only on this headset. Each question costs a small amount.",
                    color = ChatDim, fontSize = 13.sp,
                )
                OutlinedTextField(
                    value = keyText,
                    onValueChange = { keyText = it },
                    label = { Text("Claude API key") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    colors = fieldColors,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    ChatButton("Save key", true) {
                        assistant.saveApiKey(keyText)
                        hasKey = keyText.trim().isNotEmpty()
                        showSettings = false
                    }
                    ChatButton("Remove key", false) {
                        keyText = ""
                        assistant.saveApiKey("")
                        hasKey = false
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
                placeholder = { Text("Ask me or tell me to do something…") },
                singleLine = true,
                shape = RoundedCornerShape(22.dp),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { send() }),
                colors = fieldColors,
                modifier = Modifier.weight(1f),
            )
            ChatButton("Send", true) { send() }
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
private fun ChatButton(text: String, primary: Boolean, onClick: () -> Unit) {
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
