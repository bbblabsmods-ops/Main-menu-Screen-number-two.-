import org.gradle.api.tasks.util.PatternFilterable

// ASSISTANT SWITCH
//   false = the assistant files stay in the project, but the build skips them (they "are nothing")
//   true  = the assistant is built into the app again
val assistantOn = false
extra["assistantOn"] = assistantOn

if (!assistantOn) {
    val skipped = listOf(
        "MainActivity.kt",
        "Assistant.kt",
        "AssistantHub.kt",
        "AssistantPage.kt",
        "Brain.kt",
        "GirlVoice.kt",
        "Popup.kt",
        "VoiceService.kt",
        "Voice.kt",
        "WakeListener.kt",
        "BootReceiver.kt",
    )
    tasks.matching { it.name.startsWith("compile") && it.name.endsWith("Kotlin") && it is PatternFilterable }
        .configureEach { (this as PatternFilterable).exclude(skipped.map { "**/$it" }) }
}
