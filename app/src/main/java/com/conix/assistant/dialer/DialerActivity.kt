package com.conix.assistant.dialer

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.CallLog
import android.telecom.Call
import android.telecom.TelecomManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.Dialpad
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.SimCard
import androidx.compose.material.icons.filled.SwapCalls
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material.icons.filled.Voicemail
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.delay

/** Kori's own phone app: dialer, recent calls and an in-call screen laid out like the Realme phone app. */
class DialerActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        val start = intent?.data?.schemeSpecificPart ?: ""
        val forCall = CallRegistry.call != null
        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    primary = Color(0xFF4FC3F7), background = Color.Black, surface = Color(0xFF121212),
                    onBackground = Color.White, onSurface = Color.White, onPrimary = Color(0xFF00212E)
                )
            ) {
                Box(Modifier.fillMaxSize().background(Color.Black).systemBarsPadding()) {
                    DialerApp(start, forCall) { finish() }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
    }
}

@Composable
private fun DialerApp(start: String, forCall: Boolean, onClose: () -> Unit) {
    var addCall by remember { mutableStateOf(false) }
    val inCall = CallRegistry.call != null
    var hadCall by remember { mutableStateOf(inCall) }
    LaunchedEffect(inCall) {
        if (inCall) hadCall = true
        else if (hadCall && forCall) { delay(1200); onClose() }
    }
    if (inCall && !addCall) InCallScreen(onAddCall = { addCall = true })
    else KeypadScreen(start, inCall) { addCall = false }
}

private val Dark = Color(0xFF1B1B1B)
private val Light = Color(0xFFD9D9D9)

@Composable
private fun RoundBtn(label: String, icon: ImageVector, active: Boolean = false, enabled: Boolean = true, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(100.dp)) {
        Box(
            Modifier.size(84.dp).clip(CircleShape)
                .background(if (active) Light else Dark)
                .clickable(enabled = enabled) { onClick() },
            contentAlignment = Alignment.Center
        ) {
            Icon(
                icon, contentDescription = label, modifier = Modifier.size(34.dp),
                tint = if (active) Color(0xFF111111) else if (enabled) Color(0xFFBDBDBD) else Color(0xFF555555)
            )
        }
        Spacer(Modifier.height(6.dp))
        Text(label, color = if (enabled) Color(0xFFCCCCCC) else Color(0xFF666666), fontSize = 15.sp, textAlign = TextAlign.Center)
    }
}

@Composable
private fun CircleBtn(icon: ImageVector, bg: Color, size: Int, tint: Color = Color.White, onClick: () -> Unit) {
    Box(
        Modifier.size(size.dp).clip(CircleShape).background(bg).clickable { onClick() },
        contentAlignment = Alignment.Center
    ) { Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size((size / 2.4).dp)) }
}

private fun region(num: String): String =
    if (num.startsWith("+91") || num.filter { it.isDigit() }.length == 10) "India" else ""

private fun simLabel(ctx: Context): String = try {
    val h = CallRegistry.call?.details?.accountHandle
    if (h == null) "" else ctx.getSystemService(TelecomManager::class.java).getPhoneAccount(h)?.label?.toString() ?: ""
} catch (e: Exception) { "" }

@Composable
private fun InCallScreen(onAddCall: () -> Unit) {
    val ctx = LocalContext.current
    val st = CallRegistry.state
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var showKeys by remember { mutableStateOf(false) }
    var dtmf by remember { mutableStateOf("") }
    LaunchedEffect(st) { while (true) { delay(1000); now = System.currentTimeMillis() } }

    val label = when (st) {
        Call.STATE_RINGING -> "Incoming call..."
        Call.STATE_DIALING, Call.STATE_CONNECTING -> "Calling..."
        Call.STATE_HOLDING -> "On hold"
        Call.STATE_DISCONNECTING, Call.STATE_DISCONNECTED -> "Call ended"
        Call.STATE_ACTIVE -> {
            val t = if (CallRegistry.connectedAt > 0) ((now - CallRegistry.connectedAt) / 1000).coerceAtLeast(0) else 0
            "%02d:%02d".format(t / 60, t % 60)
        }
        else -> ""
    }
    val title = CallRegistry.name.ifBlank { CallRegistry.number.ifBlank { "Unknown" } }

    Column(Modifier.fillMaxSize().padding(horizontal = 24.dp)) {
        Spacer(Modifier.height(64.dp))
        Text(title, fontSize = 52.sp, color = Color.White, lineHeight = 56.sp)
        if (CallRegistry.name.isNotBlank()) Text(CallRegistry.number, fontSize = 22.sp, color = Color.White)
        val reg = region(CallRegistry.number)
        if (reg.isNotEmpty()) Text(reg, fontSize = 20.sp, color = Color(0xFF9E9E9E))
        Spacer(Modifier.height(28.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.SimCard, contentDescription = null, tint = Color.White, modifier = Modifier.size(30.dp))
            Spacer(Modifier.width(12.dp))
            Text(label, fontSize = 32.sp, color = Color.White)
        }
        val sim = simLabel(ctx)
        if (sim.isNotEmpty()) Text(sim, color = Color(0xFF9E9E9E), modifier = Modifier.padding(start = 42.dp))
        Spacer(Modifier.weight(1f))

        if (st == Call.STATE_RINGING) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircleBtn(Icons.Filled.CallEnd, Color(0xFFEF4444), 88) { CallRegistry.reject() }
                    Spacer(Modifier.height(6.dp))
                    Text("Decline", color = Color(0xFFCCCCCC))
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircleBtn(Icons.Filled.Call, Color(0xFF22C55E), 88) { CallRegistry.answer() }
                    Spacer(Modifier.height(6.dp))
                    Text("Answer", color = Color(0xFFCCCCCC))
                }
            }
            Spacer(Modifier.height(20.dp))
            Text("Reply with a message", color = Color(0xFF9E9E9E), modifier = Modifier.align(Alignment.CenterHorizontally))
            Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                listOf("Can't talk now", "I'm driving", "Call you later").forEach { t ->
                    OutlinedButton(onClick = { CallRegistry.rejectWithMessage(t) }) { Text(t, fontSize = 12.sp) }
                }
            }
            Spacer(Modifier.height(24.dp))
        } else if (showKeys) {
            Text(dtmf, fontSize = 28.sp, color = Color.White, modifier = Modifier.align(Alignment.CenterHorizontally))
            listOf(listOf('1', '2', '3'), listOf('4', '5', '6'), listOf('7', '8', '9'), listOf('*', '0', '#')).forEach { r ->
                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                    r.forEach { k ->
                        Box(
                            Modifier.size(72.dp).clip(CircleShape).background(Dark).clickable { dtmf += k; CallRegistry.playDtmf(k) },
                            contentAlignment = Alignment.Center
                        ) { Text(k.toString(), fontSize = 26.sp, color = Color.White) }
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(onClick = { showKeys = false }) { Text("Hide") }
                CircleBtn(Icons.Filled.CallEnd, Color(0xFFEF4444), 76) { CallRegistry.hangup() }
            }
        } else {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                RoundBtn("Recording\nunavailable", Icons.Filled.Voicemail, enabled = false) { }
                RoundBtn("Hold", Icons.Filled.Pause, active = st == Call.STATE_HOLDING) {
                    if (st == Call.STATE_HOLDING) CallRegistry.unhold() else CallRegistry.hold()
                }
                RoundBtn("Add call", Icons.Filled.Add) { onAddCall() }
            }
            Spacer(Modifier.height(16.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                RoundBtn("Mute", Icons.Filled.MicOff, active = CallRegistry.muted) { CallRegistry.muteCall(!CallRegistry.muted) }
                RoundBtn("Video call", Icons.Filled.Videocam, enabled = false) { }
                if (CallRegistry.callCount > 1) RoundBtn("Swap", Icons.Filled.SwapCalls) { CallRegistry.swap() }
                else Spacer(Modifier.width(100.dp))
            }
            Spacer(Modifier.height(36.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                CircleBtn(Icons.Filled.VolumeUp, if (CallRegistry.speaker) Light else Color(0xFF2B2B2B), 84,
                    if (CallRegistry.speaker) Color(0xFF111111) else Color.White) { CallRegistry.routeSpeaker(!CallRegistry.speaker) }
                CircleBtn(Icons.Filled.CallEnd, Color(0xFFEF4444), 96) { CallRegistry.hangup() }
                CircleBtn(Icons.Filled.Dialpad, Color(0xFF2B2B2B), 84) { showKeys = true }
            }
            Spacer(Modifier.height(36.dp))
        }
    }
}

private fun recents(ctx: Context): List<Pair<String, String>> {
    if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.READ_CALL_LOG) != PackageManager.PERMISSION_GRANTED) return emptyList()
    val out = ArrayList<Pair<String, String>>()
    try {
        ctx.contentResolver.query(
            CallLog.Calls.CONTENT_URI, arrayOf(CallLog.Calls.NUMBER, CallLog.Calls.CACHED_NAME),
            null, null, CallLog.Calls.DATE + " DESC"
        )?.use { c ->
            while (c.moveToNext() && out.size < 20) {
                val num = c.getString(0) ?: continue
                out.add((c.getString(1) ?: "") to num)
            }
        }
    } catch (_: Exception) { }
    return out
}

@Composable
private fun KeypadScreen(start: String, inCall: Boolean, onBack: () -> Unit) {
    val ctx = LocalContext.current
    var number by remember { mutableStateOf(start) }
    var info by remember { mutableStateOf("") }
    val recent = remember { recents(ctx) }
    val rows = listOf(listOf("1", "2", "3"), listOf("4", "5", "6"), listOf("7", "8", "9"), listOf("*", "0", "#"))

    fun place() {
        if (number.isBlank()) return
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.CALL_PHONE) != PackageManager.PERMISSION_GRANTED) {
            info = "Allow the Call permission in Kori > Settings first."
            return
        }
        try {
            ctx.getSystemService(TelecomManager::class.java).placeCall(Uri.fromParts("tel", number, null), android.os.Bundle())
            if (inCall) onBack()
        } catch (e: Exception) {
            info = "Could not place the call: ${e.message}"
        }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        if (inCall) OutlinedButton(onClick = onBack, modifier = Modifier.align(Alignment.Start)) { Text("Back to call") }
        Spacer(Modifier.height(8.dp))
        Text(if (number.isEmpty()) "Enter a number" else number, fontSize = 32.sp, color = if (number.isEmpty()) Color(0xFF9E9E9E) else Color.White)
        Spacer(Modifier.height(12.dp))
        rows.forEach { r ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                r.forEach { k ->
                    OutlinedButton(onClick = { number += k }, modifier = Modifier.weight(1f).height(58.dp)) { Text(k, fontSize = 22.sp) }
                }
            }
            Spacer(Modifier.height(8.dp))
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { place() }, modifier = Modifier.weight(2f).height(58.dp)) { Text("Call") }
            OutlinedButton(onClick = { if (number.isNotEmpty()) number = number.dropLast(1) }, modifier = Modifier.weight(1f).height(58.dp)) { Text("Del") }
        }
        if (info.isNotEmpty()) Text(info, color = Color(0xFFFFB74D), modifier = Modifier.padding(top = 8.dp))
        Spacer(Modifier.height(16.dp))
        if (recent.isNotEmpty()) {
            Text("Recent", color = Color(0xFF9E9E9E), modifier = Modifier.align(Alignment.Start))
            recent.forEach { (nm, num) ->
                Text(
                    if (nm.isBlank()) num else "$nm  ($num)",
                    modifier = Modifier.fillMaxWidth().clickable { number = num }.padding(vertical = 10.dp)
                )
            }
        }
    }
}
