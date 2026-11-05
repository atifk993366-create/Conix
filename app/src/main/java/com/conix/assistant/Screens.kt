package com.conix.assistant

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.speech.RecognizerIntent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

@Composable
fun StatusRow(title: String, status: String, ok: Boolean) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(10.dp).background(if (ok) Cx.Good else Cx.Warn, CircleShape))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(status, color = Cx.Muted, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
fun LangChips(lang: AppLang, onLang: (AppLang) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        AppLang.values().forEach { l ->
            FilterChip(selected = lang == l, onClick = { onLang(l) }, label = { Text(l.label) })
        }
    }
}

@Composable
fun DashboardScreen(
    micOk: Boolean, notifOk: Boolean, lang: AppLang,
    onGrant: () -> Unit, onQuick: (String) -> Unit
) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Image(painterResource(R.drawable.conix_logo), "CONIX logo", Modifier.size(64.dp))
            Spacer(Modifier.width(12.dp))
            Column {
                Text("CONIX AI", style = MaterialTheme.typography.headlineSmall)
                Text("Personal Autonomous AI Assistant", color = Cx.Muted, style = MaterialTheme.typography.bodySmall)
            }
        }
        CxCard {
            Text("System status", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(6.dp))
            StatusRow("Microphone permission", if (micOk) "Granted" else "Not granted", micOk)
            StatusRow("Notifications permission", if (notifOk) "Granted" else "Not granted", notifOk)
            StatusRow("Voice security (your voice only)", "Not enabled yet - Phase 2", false)
            StatusRow("Wake word \"Hey Conix\"", "Not enabled yet - Phase 3", false)
            StatusRow("Active language", lang.label, true)
            if (!micOk || !notifOk) {
                Spacer(Modifier.height(8.dp))
                Button(onClick = onGrant) { Text("Grant permissions") }
            }
        }
        CxCard {
            Text("Quick commands", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AssistChip(onClick = { onQuick("open youtube") }, label = { Text("YouTube") })
                AssistChip(onClick = { onQuick("open google") }, label = { Text("Google") })
                AssistChip(onClick = { onQuick("open settings") }, label = { Text("Settings") })
            }
            Spacer(Modifier.height(4.dp))
            Text(
                "Private actions stay locked until voice verification is added.",
                color = Cx.Muted, style = MaterialTheme.typography.bodySmall
            )
        }
    }
}

@Composable
fun VoiceScreen(
    lang: AppLang, micOk: Boolean,
    onLang: (AppLang) -> Unit, onRequestPerms: () -> Unit, onHeard: (String) -> Unit
) {
    var heard by remember { mutableStateOf("") }
    var error by remember { mutableStateOf("") }
    val recog = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        val t = r.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
        if (r.resultCode == Activity.RESULT_OK && t != null) { heard = t; onHeard(t) }
    }
    fun listen() {
        if (!micOk) { onRequestPerms(); return }
        error = ""
        try {
            recog.launch(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, lang.tag)
                putExtra(RecognizerIntent.EXTRA_PROMPT, "Speak to CONIX")
            })
        } catch (e: Exception) {
            error = "Speech recognition is not available on this phone."
        }
    }
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        LangChips(lang, onLang)
        Spacer(Modifier.height(40.dp))
        Box(
            Modifier.size(150.dp).background(Cx.Accent, CircleShape).clickable { listen() },
            contentAlignment = Alignment.Center
        ) { Icon(Icons.Filled.Mic, "Speak", Modifier.size(64.dp), tint = Color(0xFF00212E)) }
        Spacer(Modifier.height(20.dp))
        Text("Tap to speak", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        if (heard.isNotEmpty()) Text("Heard: $heard", color = Cx.Muted, textAlign = TextAlign.Center)
        if (error.isNotEmpty()) Text(error, color = Cx.Warn, textAlign = TextAlign.Center)
        Spacer(Modifier.height(24.dp))
        Text(
            "Hands-free \"Hey Conix\" wake word comes in Phase 3. Language support depends on the speech packs installed on your phone.",
            color = Cx.Muted, style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center
        )
    }
}

@Composable
fun ChatScreen(messages: List<ChatLine>, onSend: (String) -> Unit) {
    var input by remember { mutableStateOf("") }
    val listState = rememberLazyListState()
    LaunchedEffect(messages.size) { if (messages.isNotEmpty()) listState.animateScrollToItem(messages.size - 1) }
    Column(Modifier.fillMaxSize()) {
        LazyColumn(
            Modifier.weight(1f).fillMaxWidth().padding(horizontal = 12.dp),
            state = listState, verticalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(vertical = 12.dp)
        ) {
            items(messages) { m ->
                val mine = m.from == "you"
                Row(Modifier.fillMaxWidth(), horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start) {
                    Box(
                        Modifier.widthIn(max = 290.dp)
                            .background(if (mine) Cx.Accent else Cx.Card, RoundedCornerShape(16.dp))
                            .padding(12.dp)
                    ) { Text(m.text, color = if (mine) Color(0xFF00212E) else Color.White) }
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = input, onValueChange = { input = it },
                modifier = Modifier.weight(1f), placeholder = { Text("Message CONIX") },
                singleLine = true, shape = RoundedCornerShape(24.dp)
            )
            IconButton(onClick = { onSend(input); input = "" }) {
                Icon(Icons.AutoMirrored.Filled.Send, "Send", tint = Cx.Accent)
            }
        }
    }
}

@Composable
fun SettingsScreen(
    lang: AppLang, micOk: Boolean, notifOk: Boolean,
    onLang: (AppLang) -> Unit, onGrant: () -> Unit, onTestVoice: () -> Unit
) {
    val ctx = LocalContext.current
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text("Settings", style = MaterialTheme.typography.headlineSmall)
        CxCard {
            Text("Language", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            LangChips(lang, onLang)
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = onTestVoice) { Text("Test voice output") }
        }
        CxCard {
            Text("Permissions", style = MaterialTheme.typography.titleMedium)
            StatusRow("Microphone", if (micOk) "Granted" else "Not granted", micOk)
            StatusRow("Notifications", if (notifOk) "Granted" else "Not granted", notifOk)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onGrant) { Text("Request") }
                OutlinedButton(onClick = {
                    ctx.startActivity(
                        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + ctx.packageName))
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                }) { Text("App settings") }
            }
        }
        CxCard {
            Text("Wake phrase", style = MaterialTheme.typography.titleMedium)
            Text("Hey Conix", color = Cx.Accent)
            Text("Detection is not active yet (Phase 3).", color = Cx.Muted, style = MaterialTheme.typography.bodySmall)
        }
        CxCard {
            Text("About", style = MaterialTheme.typography.titleMedium)
            Text("CONIX AI v0.2.0 - Phase 1 build", color = Cx.Muted)
            Text("No voice-identity check yet; do not use for private actions.", color = Cx.Muted, style = MaterialTheme.typography.bodySmall)
        }
    }
}
