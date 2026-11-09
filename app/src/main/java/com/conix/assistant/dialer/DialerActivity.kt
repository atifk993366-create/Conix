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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.delay

/** Kori's own phone app: dialer, recent calls and the in-call screen. */
class DialerActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        val start = intent?.data?.schemeSpecificPart ?: ""
        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    primary = Color(0xFF4FC3F7), background = Color(0xFF0A1220), surface = Color(0xFF121C33),
                    onBackground = Color.White, onSurface = Color.White, onPrimary = Color(0xFF00212E)
                )
            ) {
                Box(Modifier.fillMaxSize().background(Color(0xFF0A1220)).systemBarsPadding()) {
                    if (CallRegistry.call != null) InCallScreen() else KeypadScreen(start)
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
private fun InCallScreen() {
    val st = CallRegistry.state
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(st) { while (true) { delay(1000); now = System.currentTimeMillis() } }
    val label = when (st) {
        Call.STATE_RINGING -> "Incoming call"
        Call.STATE_DIALING, Call.STATE_CONNECTING -> "Calling..."
        Call.STATE_HOLDING -> "On hold"
        Call.STATE_DISCONNECTING -> "Ending..."
        Call.STATE_ACTIVE -> {
            val t = if (CallRegistry.connectedAt > 0) ((now - CallRegistry.connectedAt) / 1000).coerceAtLeast(0) else 0
            "%02d:%02d".format(t / 60, t % 60)
        }
        else -> ""
    }
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(CallRegistry.name.ifBlank { CallRegistry.number.ifBlank { "Unknown" } }, fontSize = 30.sp, textAlign = TextAlign.Center)
        if (CallRegistry.name.isNotBlank()) Text(CallRegistry.number, color = Color(0xFF9AA7BD))
        Spacer(Modifier.height(8.dp))
        Text(label, color = Color(0xFF4ADE80), fontSize = 18.sp)
        Spacer(Modifier.height(48.dp))
        if (st == Call.STATE_RINGING) {
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Button(onClick = { CallRegistry.answer() }) { Text("Answer") }
                OutlinedButton(onClick = { CallRegistry.reject() }) { Text("Decline") }
            }
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = { CallRegistry.setMuted(!CallRegistry.muted) }) { Text(if (CallRegistry.muted) "Unmute" else "Mute") }
                OutlinedButton(onClick = { CallRegistry.setSpeaker(!CallRegistry.speaker) }) { Text(if (CallRegistry.speaker) "Speaker off" else "Speaker") }
            }
            Spacer(Modifier.height(24.dp))
            Button(onClick = { CallRegistry.hangup() }) { Text("End call") }
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
private fun KeypadScreen(start: String) {
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
            ctx.getSystemService(TelecomManager::class.java).placeCall(Uri.fromParts("tel", number, null), Bundle())
        } catch (e: Exception) {
            info = "Could not place the call: ${e.message}"
        }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(if (number.isEmpty()) "Enter a number" else number, fontSize = 30.sp, color = if (number.isEmpty()) Color(0xFF9AA7BD) else Color.White)
        Spacer(Modifier.height(12.dp))
        rows.forEach { r ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                r.forEach { k ->
                    OutlinedButton(onClick = { number += k }, modifier = Modifier.weight(1f).height(56.dp)) { Text(k, fontSize = 22.sp) }
                }
            }
            Spacer(Modifier.height(8.dp))
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { place() }, modifier = Modifier.weight(2f).height(56.dp)) { Text("Call") }
            OutlinedButton(onClick = { if (number.isNotEmpty()) number = number.dropLast(1) }, modifier = Modifier.weight(1f).height(56.dp)) { Text("Del") }
        }
        if (info.isNotEmpty()) Text(info, color = Color(0xFFFFB74D), modifier = Modifier.padding(top = 8.dp))
        Spacer(Modifier.height(16.dp))
        if (recent.isNotEmpty()) {
            Text("Recent", color = Color(0xFF9AA7BD), modifier = Modifier.align(Alignment.Start))
            recent.forEach { (nm, num) ->
                Text(
                    if (nm.isBlank()) num else "$nm  ($num)",
                    modifier = Modifier.fillMaxWidth().clickable { number = num }.padding(vertical = 10.dp)
                )
            }
        }
    }
}
