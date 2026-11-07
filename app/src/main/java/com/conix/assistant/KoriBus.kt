package com.conix.assistant

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** An action that needs the user's confirmation and phone unlock before it runs. */
class PendingAction(val title: String, val detail: String, val run: () -> String)

object KoriBus {
    val messages = mutableStateListOf(
        ChatLine("conix", "Hello! I'm Kori. Type or speak a command. Add an AI key in Settings to unlock conversations, research and smart actions.")
    )
    var pending by mutableStateOf<PendingAction?>(null)
    var wakeActive by mutableStateOf(false)
    @Volatile var appInForeground = false
}
