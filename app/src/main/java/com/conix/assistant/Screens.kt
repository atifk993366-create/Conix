package com.conix.assistant

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.conix.assistant.access.KoriAccessibility
import com.conix.assistant.core.KoriCore
import com.conix.assistant.notif.KoriNotificationListener
import com.conix.assistant.wake.WakeService

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
    onGrant: () -> Unit, onQuick: (String) -> Unit,
    enrolled: Boolean = false, brainReady: Boolean = false, wakeOn: Boolean = false
) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Image(painterResource(R.drawable.conix_logo), "Kori logo", Modifier.size(64.dp))
            Spacer(Modifier.width(12.dp))
            Column {
                Text("Kori AI", style = MaterialTheme.typography.headlineSmall)
                Text("Personal Autonomous AI Assistant", color = Cx.Muted, style = MaterialTheme.typography.bodySmall)
            }
        }
        CxCard {
            Text("System status", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(6.dp))
            StatusRow("AI brain", if (brainReady) "Connected (key saved)" else "Offline commands only - add a key in Settings", brainReady)
            StatusRow("Wake word \"Hey Kori\" (beta)", if (wakeOn) "Listening now" else "Off - start it in Settings", wakeOn)
            StatusRow("Microphone permission", if (micOk) "Granted" else "Not granted", micOk)
            StatusRow("Notifications permission", if (notifOk) "Granted" else "Not granted", notifOk)
            StatusRow("Voice ID", if (enrolled) "Profile saved - testing only, never authorizes actions" else "Not registered", enrolled)
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
                AssistChip(onClick = { onQuick("hello") }, label = { Text("Hello") })
            }
            Spacer(Modifier.height(4.dp))
            Text(
                "Calls, SMS, WhatsApp and message reading always need your confirmation and phone unlock.",
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
                putExtra(RecognizerIntent.EXTRA_PROMPT, "Speak to Kori")
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
            "For hands-free \"Hey Kori\", start the wake-word listener in Settings. Language support depends on the speech packs installed on your phone.",
            color = Cx.Muted, style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center
        )
    }
}

@Composable
fun ChatScreen(
    messages: List<ChatLine>, thinking: Boolean, research: Boolean,
    onResearch: (Boolean) -> Unit, onSend: (String) -> Unit
) {
    var input by remember { mutableStateOf("") }
    val listState = rememberLazyListState()
    LaunchedEffect(messages.size) { if (messages.isNotEmpty()) listState.animateScrollToItem(messages.size - 1) }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            FilterChip(selected = research, onClick = { onResearch(!research) }, label = { Text("Web research") })
            if (thinking) {
                Spacer(Modifier.width(12.dp))
                Text("Kori is thinking...", color = Cx.Muted, style = MaterialTheme.typography.bodySmall)
            }
        }
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
                modifier = Modifier.weight(1f), placeholder = { Text("Message Kori") },
                singleLine = true, shape = RoundedCornerShape(24.dp)
            )
            IconButton(onClick = { onSend(input); input = "" }) {
                Icon(Icons.AutoMirrored.Filled.Send, "Send", tint = Cx.Accent)
            }
        }
    }
}

@Composable
private fun PermRow(label: String, perm: String, tick: Int) {
    val ctx = LocalContext.current
    val ok = tick >= 0 && ContextCompat.checkSelfPermission(ctx, perm) == PackageManager.PERMISSION_GRANTED
    StatusRow(label, if (ok) "Granted" else "Not granted", ok)
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun BlockedAppsDialog(initial: Set<String>, onDone: (Set<String>) -> Unit, onCancel: () -> Unit) {
    val ctx = LocalContext.current
    val apps = remember {
        val pm = ctx.packageManager
        pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
            .map { it.loadLabel(pm).toString() to it.activityInfo.packageName }
            .distinctBy { it.second }
            .sortedBy { it.first.lowercase() }
    }
    val sel = remember { mutableStateListOf<String>().apply { addAll(initial) } }
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("Apps Kori must ignore") },
        text = {
            LazyColumn(Modifier.heightIn(max = 420.dp)) {
                items(apps) { app ->
                    val pkg = app.second
                    Row(
                        Modifier.fillMaxWidth().clickable { if (pkg in sel) sel.remove(pkg) else sel.add(pkg) },
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(checked = pkg in sel, onCheckedChange = { if (it) { if (pkg !in sel) sel.add(pkg) } else sel.remove(pkg) })
                        Column {
                            Text(app.first)
                            Text(pkg, color = Cx.Muted, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onDone(sel.toSet()) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onCancel) { Text("Cancel") } }
    )
}

@Composable
fun SettingsScreen(
    core: KoriCore, lang: AppLang, micOk: Boolean, notifOk: Boolean,
    onLang: (AppLang) -> Unit, onGrant: () -> Unit, onGrantComm: () -> Unit, onTestVoice: () -> Unit
) {
    val ctx = LocalContext.current
    val tick = KoriBus.resumeTick   // forces a refresh when you return from system settings
    var provider by remember { mutableStateOf(core.prefs.provider) }
    var model by remember { mutableStateOf(core.prefs.model) }
    var keyInput by remember { mutableStateOf("") }
    var keySaved by remember { mutableStateOf(core.brain.hasKey()) }
    var factCount by remember { mutableIntStateOf(core.brain.facts().size) }
    var minutes by remember { mutableIntStateOf(core.prefs.wakeMinutes) }
    var safety by remember { mutableStateOf(core.prefs.safety) }
    var callAssist by remember { mutableStateOf(core.prefs.callAssist) }
    var announceWa by remember { mutableStateOf(core.prefs.announceWa) }
    var autoSend by remember { mutableStateOf(core.prefs.autoSendWa) }
    var blocked by remember { mutableStateOf(core.prefs.blockedApps) }
    var showPicker by remember { mutableStateOf(false) }

    fun open(i: Intent) { try { ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: Exception) { } }

    if (showPicker) {
        BlockedAppsDialog(
            initial = blocked,
            onDone = { blocked = it; core.prefs.blockedApps = it; showPicker = false },
            onCancel = { showPicker = false }
        )
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text("Settings", style = MaterialTheme.typography.headlineSmall)

        CxCard {
            Text("AI brain", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = provider == "gemini", onClick = {
                    provider = "gemini"; core.prefs.provider = "gemini"; model = core.prefs.model; keySaved = core.brain.hasKey()
                }, label = { Text("Gemini (free tier)") })
                FilterChip(selected = provider == "claude", onClick = {
                    provider = "claude"; core.prefs.provider = "claude"; model = core.prefs.model; keySaved = core.brain.hasKey()
                }, label = { Text("Claude (paid)") })
            }
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = model, onValueChange = { model = it; core.prefs.model = it },
                label = { Text("Model name") }, singleLine = true, modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = keyInput, onValueChange = { keyInput = it },
                label = { Text(if (keySaved) "API key saved - paste to replace" else "API key") },
                singleLine = true, visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = {
                    if (keyInput.isNotBlank()) {
                        core.secrets.put("key_$provider", keyInput.trim()); keyInput = ""; keySaved = true
                    }
                }) { Text("Save key") }
                OutlinedButton(onClick = { core.secrets.remove("key_$provider"); keySaved = false }) { Text("Remove key") }
            }
            Spacer(Modifier.height(6.dp))
            Text(
                "Free Gemini key: aistudio.google.com. If you see a model error, change the model name. The key is stored encrypted on this phone.",
                color = Cx.Muted, style = MaterialTheme.typography.bodySmall
            )
            Spacer(Modifier.height(8.dp))
            Text("Memory: $factCount saved facts", color = Cx.Muted)
            OutlinedButton(onClick = { core.brain.clearMemory(); factCount = 0 }) { Text("Clear memory") }
        }

        CxCard {
            Text("Speed and safety", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = safety == "smooth", onClick = { safety = "smooth"; core.prefs.safety = "smooth" }, label = { Text("Smooth") })
                FilterChip(selected = safety == "strict", onClick = { safety = "strict"; core.prefs.safety = "strict" }, label = { Text("Strict") })
            }
            Spacer(Modifier.height(6.dp))
            Text(
                "Smooth: calls and WhatsApp drafts run instantly. Messages you type go straight out; messages from your voice need a spoken \"yes\". Private reads need an unlocked phone. Strict: always confirm and unlock.",
                color = Cx.Muted, style = MaterialTheme.typography.bodySmall
            )
        }

        CxCard {
            Text("Calls and WhatsApp", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            SwitchRow("Announce incoming calls (say answer / reject)", callAssist) { callAssist = it; core.prefs.callAssist = it }
            SwitchRow("Read new WhatsApp messages aloud", announceWa) { announceWa = it; core.prefs.announceWa = it }
            SwitchRow("Auto-tap Send in WhatsApp", autoSend) { autoSend = it; core.prefs.autoSendWa = it }
            Text(
                "Call and message announcements work while the Hey Kori listener is on. After a message is read, say \"reply\" and your answer. Only the standard WhatsApp (com.whatsapp) is used.",
                color = Cx.Muted, style = MaterialTheme.typography.bodySmall
            )
        }

        CxCard {
            Text("Wake word \"Hey Kori\" (beta)", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(6.dp))
            Text("Auto-stop after:", color = Cx.Muted)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(10, 30, 60).forEach { m ->
                    FilterChip(selected = minutes == m, onClick = { minutes = m; core.prefs.wakeMinutes = m }, label = { Text("$m min") })
                }
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = {
                    if (!micOk) onGrant() else ContextCompat.startForegroundService(ctx, Intent(ctx, WakeService::class.java))
                }) { Text("Start listening") }
                OutlinedButton(onClick = { ctx.stopService(Intent(ctx, WakeService::class.java)) }) { Text("Stop") }
            }
            Spacer(Modifier.height(6.dp))
            Text(
                if (KoriBus.wakeActive) "Listening now. A notification shows while the microphone is on." else "Off.",
                color = Cx.Muted, style = MaterialTheme.typography.bodySmall
            )
            Text(
                "Beta: uses Android speech recognition, so it uses more battery and may mishear. Android may block opening apps from the background; Kori then shows a tap-to-open notification.",
                color = Cx.Muted, style = MaterialTheme.typography.bodySmall
            )
            Spacer(Modifier.height(6.dp))
            OutlinedButton(onClick = {
                open(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + ctx.packageName)))
            }) { Text("Allow display over other apps") }
        }

        CxCard {
            Text("Excluded apps", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            Text(
                "Kori will never open these apps or read their notifications. WhatsApp Business is excluded by default.",
                color = Cx.Muted, style = MaterialTheme.typography.bodySmall
            )
            Spacer(Modifier.height(6.dp))
            Text(if (blocked.isEmpty()) "None" else blocked.joinToString("\n"))
            Spacer(Modifier.height(6.dp))
            Button(onClick = { showPicker = true }) { Text("Choose apps to exclude") }
            Spacer(Modifier.height(4.dp))
            Text(
                "Cloned apps (dual apps) live in a separate profile that Kori cannot reach, so they are never touched.",
                color = Cx.Muted, style = MaterialTheme.typography.bodySmall
            )
        }

        CxCard {
            Text("Language", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            LangChips(lang, onLang)
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = onTestVoice) { Text("Test voice output") }
        }

        CxCard {
            Text("Permissions and access", style = MaterialTheme.typography.titleMedium)
            StatusRow("Microphone", if (micOk) "Granted" else "Not granted", micOk)
            StatusRow("Notifications", if (notifOk) "Granted" else "Not granted", notifOk)
            PermRow("Contacts", Manifest.permission.READ_CONTACTS, tick)
            PermRow("Make calls", Manifest.permission.CALL_PHONE, tick)
            PermRow("Answer / reject calls", Manifest.permission.ANSWER_PHONE_CALLS, tick)
            PermRow("Phone state (incoming call alerts)", Manifest.permission.READ_PHONE_STATE, tick)
            PermRow("Caller number", Manifest.permission.READ_CALL_LOG, tick)
            PermRow("Send SMS", Manifest.permission.SEND_SMS, tick)
            PermRow("Read SMS", Manifest.permission.READ_SMS, tick)
            val acc = KoriAccessibility.instance != null
            val nl = KoriNotificationListener.connected
            val overlay = Settings.canDrawOverlays(ctx)
            StatusRow("Accessibility (YouTube / WhatsApp tapping)", if (acc) "On" else "Off", acc)
            StatusRow("Notification access (WhatsApp, reading)", if (nl) "On" else "Off", nl)
            StatusRow("Display over other apps", if (overlay) "Allowed" else "Not allowed (optional)", overlay)
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onGrant) { Text("Mic + notifications") }
                OutlinedButton(onClick = onGrantComm) { Text("Contacts, calls, SMS") }
            }
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { open(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }) { Text("Accessibility") }
                OutlinedButton(onClick = { open(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }) { Text("Notification access") }
            }
            Spacer(Modifier.height(6.dp))
            OutlinedButton(onClick = {
                open(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + ctx.packageName)))
            }) { Text("App settings (allow restricted settings)") }
            Spacer(Modifier.height(4.dp))
            Text(
                "Statuses refresh when you come back from system settings. If a permission stays off, open App settings, tap the three dots and choose Allow restricted settings.",
                color = Cx.Muted, style = MaterialTheme.typography.bodySmall
            )
        }

        CxCard {
            Text("About", style = MaterialTheme.typography.titleMedium)
            Text("Kori AI v0.5.0", color = Cx.Muted)
            Text(
                "Voice ID is experimental and never authorizes actions.",
                color = Cx.Muted, style = MaterialTheme.typography.bodySmall
            )
        }
    }
}
