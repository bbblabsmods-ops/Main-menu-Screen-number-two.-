package com.example.questlauncher

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

/** Shared between the background voice service and the Assistant page. */
object AssistantHub {

    private val _messages = MutableStateFlow(
        listOf(
            ChatMessage(
                false,
                "Hi! Turn on Mic, then say \"Hey BBB\" from anywhere. Try: \"Hey BBB, open Beat Saber\", " +
                    "\"Hey BBB, what is six plus six\", or \"Hey BBB, tell me a story\". " +
                    "You can also type the same thing."
            )
        )
    )
    val messages: StateFlow<List<ChatMessage>> = _messages

    private val _status = MutableStateFlow("")
    val status: StateFlow<String> = _status

    fun add(message: ChatMessage) {
        _messages.update { (it + message).takeLast(80) }
    }

    fun setStatus(text: String) {
        _status.value = text
    }
}
