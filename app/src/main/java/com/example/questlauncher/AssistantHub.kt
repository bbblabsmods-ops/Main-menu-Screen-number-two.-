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
                "Hi! Turn on Mic, then say \"Hey BBB\" from anywhere. BBB only listens for that phrase. " +
                    "Try: \"Hey BBB, open Beat Saber\", \"close this game\", \"close all my games\", " +
                    "\"clear all my tabs\", \"list my games\", \"what is six plus six\", or a question like " +
                    "\"what is the capital of France\". You can also type the same thing."
            )
        )
    )
    val messages: StateFlow<List<ChatMessage>> = _messages

    private val _status = MutableStateFlow("")
    val status: StateFlow<String> = _status

    private val _listening = MutableStateFlow(false)
    val listening: StateFlow<Boolean> = _listening

    fun add(message: ChatMessage) {
        _messages.update { (it + message).takeLast(80) }
    }

    fun setStatus(text: String) {
        _status.value = text
    }

    fun setListening(on: Boolean) {
        _listening.value = on
    }
}
