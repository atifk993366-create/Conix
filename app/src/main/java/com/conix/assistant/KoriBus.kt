package com.conix.assistant

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** An action waiting for confirmation (and phone unlock when [needsUnlock] is true). */
class PendingAction(val title: String, val detail: String, val needsUnlock: Boolean = true, val run: () -> String)

object KoriBus {
    val messages = mutableStateListOf(
        ChatLine("conix", "Hello! I'm Kori. Type or speak - I work out by myself whether it's a command or a question. Add an AI key in Settings to unlock everything.")
    )
    var pending by mutableStateOf<PendingAction?>(null)
    var wakeActive by mutableStateOf(false)
    var resumeTick by mutableIntStateOf(0)
    var notesTick by mutableIntStateOf(0)
    var awayUntil by mutableLongStateOf(0L)
    /** When non-null the camera screen opens with this question. */
    var cameraRequest by mutableStateOf<String?>(null)
    @Volatile var appInForeground = false
}
