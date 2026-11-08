package com.conix.assistant

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import android.speech.RecognizerIntent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import com.conix.assistant.brain.Providers
import com.conix.assistant.core.CallNotes
import com.conix.assistant.core.KoriCore
import com.conix.assistant.live.Interpreter
import com.conix.assistant.speech.EarsFactory
import com.conix.assistant.speech.ModelManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
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
    core: KoriCore, lang: AppLang, micOk: Boolean,
    onLang: (AppLang) -> Unit, onRequestPerms: () -> Unit, onHeard: (String) -> Unit,
    speakCall: (String, AppLang) -> Boolean
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var heard by remember { mutableStateOf("") }
    var error by remember { mutableStateOf("") }
    val recog = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        val t = r.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
        if (r.resultCode == Activity.RESULT_OK && t != null) { heard = t; onHeard(t) }
    }
    var listening by remember { mutableStateOf(false) }
    fun listen() {
        if (!micOk) { onRequestPerms(); return }
        error = ""
        if (core.prefs.engine == "kori" && ModelManager.isReady(ctx, lang.tag)) {
            if (listening) return
            listening = true
            val e = EarsFactory.create(ctx, core.prefs, lang.tag)
            e.onFatal = { m -> error = m; listening = false }
            e.listen(8000) { t ->
                listening = false
                e.release()
                if (t.isNotBlank()) { heard = t; onHeard(t) } else error = "I didn't hear anything."
            }
            return
        }
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

    val notesTick = KoriBus.notesTick
    val awayUntil = KoriBus.awayUntil
    val awayOn = awayUntil > System.currentTimeMillis()
    var awayMin by remember { mutableIntStateOf(10) }
    var owner by remember { mutableStateOf(core.prefs.ownerName) }
    val notes = remember(notesTick) { CallNotes.all(core.secrets) }

    val interpreter = remember { Interpreter(ctx.applicationContext, core) }
    DisposableEffect(Unit) { onDispose { interpreter.stop() } }
    var interpreting by remember { mutableStateOf(false) }
    var src by remember { mutableStateOf(AppLang.Hindi) }
    var dst by remember { mutableStateOf(AppLang.English) }
    val lines = remember { mutableStateListOf<Pair<String, String>>() }
    var typed by remember { mutableStateOf("") }
    var translateOut by remember { mutableStateOf(false) }
    var outLang by remember { mutableStateOf(AppLang.English) }
    var callInfo by remember { mutableStateOf("") }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        LangChips(lang, onLang)
        Box(
            Modifier.size(130.dp).background(Cx.Accent, CircleShape).clickable { listen() },
            contentAlignment = Alignment.Center
        ) { Icon(Icons.Filled.Mic, "Speak", Modifier.size(56.dp), tint = Color(0xFF00212E)) }
        Text(if (listening) "Listening..." else "Tap to speak", style = MaterialTheme.typography.titleMedium)
        if (heard.isNotEmpty()) Text("Heard: $heard", color = Cx.Muted, textAlign = TextAlign.Center)
        if (error.isNotEmpty()) Text(error, color = Cx.Warn, textAlign = TextAlign.Center)

        CxCard {
            Text("Away mode", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            Text(
                "Kori answers incoming calls on speaker, tells the caller it is an AI assistant and that the call may be transcribed, asks their name and reason, then hangs up and leaves you a note. Needs the Hey Kori listener (it starts automatically).",
                color = Cx.Muted, style = MaterialTheme.typography.bodySmall
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = owner, onValueChange = { owner = it; core.prefs.ownerName = it },
                label = { Text("Your name (what Kori tells callers)") }, singleLine = true, modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(5, 10, 30, 0).forEach { m ->
                    FilterChip(selected = awayMin == m, onClick = { awayMin = m }, label = { Text(if (m == 0) "Until I stop" else "$m min") })
                }
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = {
                    if (!micOk) {
                        onRequestPerms()
                    } else {
                        val until = if (awayMin == 0) Long.MAX_VALUE else System.currentTimeMillis() + awayMin * 60_000L
                        core.prefs.awayUntil = until
                        KoriBus.awayUntil = until
                        try { ContextCompat.startForegroundService(ctx, Intent(ctx, WakeService::class.java)) } catch (_: Exception) { }
                    }
                }) { Text("Start Away mode") }
                OutlinedButton(onClick = { core.prefs.awayUntil = 0L; KoriBus.awayUntil = 0L }) { Text("I'm back") }
            }
            Spacer(Modifier.height(6.dp))
            Text(
                if (awayOn) (if (awayUntil > 4_000_000_000_000L) "Away until you tap I'm back" else "Away until " + SimpleDateFormat("HH:mm", Locale.ENGLISH).format(Date(awayUntil))) else "Away mode is off.",
                color = if (awayOn) Cx.Good else Cx.Muted
            )
            Text(
                "Android does not let apps record or speak into the call audio, so Kori talks through the speaker and listens through the microphone. On some phones the microphone is muted during calls; then Kori texts the caller instead. Notes are transcripts, not recordings.",
                color = Cx.Muted, style = MaterialTheme.typography.bodySmall
            )
            if (notes.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Text("While you were away", style = MaterialTheme.typography.titleSmall)
                notes.take(8).forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
                OutlinedButton(onClick = { CallNotes.clear(core.secrets); KoriBus.notesTick++ }) { Text("Clear notes") }
            }
        }

        CxCard {
            Text("Call helper", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            Text(
                "Put the call on speaker. Live translation listens through the microphone (stop the Hey Kori listener first) and shows what it hears, translated. Type to speak lets Kori say your text into the call.",
                color = Cx.Muted, style = MaterialTheme.typography.bodySmall
            )
            Spacer(Modifier.height(8.dp))
            Text("They speak:", color = Cx.Muted)
            LangChips(src) { src = it }
            Spacer(Modifier.height(4.dp))
            Text("Translate to:", color = Cx.Muted)
            LangChips(dst) { dst = it }
            Spacer(Modifier.height(8.dp))
            Button(onClick = {
                if (interpreting) {
                    interpreter.stop(); interpreting = false
                } else if (!micOk) {
                    onRequestPerms()
                } else {
                    lines.clear()
                    interpreter.start(src, dst) { o, t -> lines.add(0, o to t) }
                    interpreting = true
                }
            }) { Text(if (interpreting) "Stop live translation" else "Start live translation") }
            lines.take(10).forEach { line ->
                Spacer(Modifier.height(6.dp))
                Text(line.first, color = Cx.Muted, style = MaterialTheme.typography.bodySmall)
                Text(line.second)
            }
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = typed, onValueChange = { typed = it },
                label = { Text("Type what Kori should say") }, modifier = Modifier.fillMaxWidth()
            )
            SwitchRow("Translate before speaking", translateOut) { translateOut = it }
            if (translateOut) LangChips(outLang) { outLang = it }
            Spacer(Modifier.height(6.dp))
            Button(onClick = {
                val text = typed.trim()
                if (text.isNotEmpty()) {
                    scope.launch {
                        val out = if (translateOut) core.translate(text, Interpreter.englishName(outLang)) else text
                        val ok = speakCall(out, if (translateOut) outLang else lang)
                        callInfo = if (ok) "Said: $out" else "Voice output for that language is not installed on this phone."
                    }
                }
            }) { Text("Speak into the call") }
            if (callInfo.isNotEmpty()) Text(callInfo, color = Cx.Muted, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
fun ChatScreen(messages: List<ChatLine>, thinking: Boolean, onSend: (String) -> Unit) {
    var input by remember { mutableStateOf("") }
    val listState = rememberLazyListState()
    LaunchedEffect(messages.size) { if (messages.isNotEmpty()) listState.animateScrollToItem(messages.size - 1) }
    Column(Modifier.fillMaxSize()) {
        if (thinking) {
            Text(
                "Kori is thinking...", color = Cx.Muted, style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)
            )
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
                modifier = Modifier.weight(1f), placeholder = { Text("Ask or command Kori") },
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
    val scope = rememberCoroutineScope()
    val tick = KoriBus.resumeTick   // forces a refresh when you return from system settings

    // AI providers
    var editId by remember { mutableStateOf(core.prefs.provider) }
    var primary by remember { mutableStateOf(core.prefs.provider) }
    var model by remember { mutableStateOf(core.prefs.modelOf(core.prefs.provider)) }
    var vmodel by remember { mutableStateOf(core.prefs.visionOf(core.prefs.provider)) }
    var keyInput by remember { mutableStateOf("") }
    var keyTick by remember { mutableIntStateOf(0) }
    var factCount by remember { mutableIntStateOf(core.brain.facts().size) }

    // Own ears
    var engine by remember { mutableStateOf(core.prefs.engine) }
    var dlMsg by remember { mutableStateOf("") }
    var dlBusy by remember { mutableStateOf(false) }

    // Training
    var trainOn by remember { mutableStateOf(core.prefs.trainingLog) }
    var logCount by remember { mutableIntStateOf(core.training.count()) }
    var trainMsg by remember { mutableStateOf("") }

    var safety by remember { mutableStateOf(core.prefs.safety) }
    var callAssist by remember { mutableStateOf(core.prefs.callAssist) }
    var announceWa by remember { mutableStateOf(core.prefs.announceWa) }
    var autoSend by remember { mutableStateOf(core.prefs.autoSendWa) }
    var blocked by remember { mutableStateOf(core.prefs.blockedApps) }
    var showPicker by remember { mutableStateOf(false) }
    var lockMode by remember { mutableStateOf(core.prefs.lockScreenMode) }
    var awaySms by remember { mutableStateOf(core.prefs.awaySms) }

    fun open(i: Intent) { try { ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: Exception) { } }

    fun download(tag: String) {
        scope.launch {
            dlBusy = true
            dlMsg = "Downloading..."
            val r = withContext(Dispatchers.IO) { ModelManager.download(ctx, tag) { pc -> dlMsg = "Downloading... $pc%" } }
            dlMsg = if (r.isEmpty()) "Done. Stop and start the listener to use it." else r
            dlBusy = false
        }
    }

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
            Text("AI providers", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            Text(
                "Add keys for several free providers. Kori uses the primary one (star) and switches to the next by itself when one runs out of quota. Tick = key saved.",
                color = Cx.Muted, style = MaterialTheme.typography.bodySmall
            )
            Spacer(Modifier.height(8.dp))
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Providers.all.forEach { p ->
                    val has = keyTick >= 0 && core.secrets.get("key_${p.id}") != null
                    FilterChip(
                        selected = editId == p.id,
                        onClick = {
                            editId = p.id
                            model = core.prefs.modelOf(p.id)
                            vmodel = core.prefs.visionOf(p.id)
                            keyInput = ""
                        },
                        label = { Text(p.label.substringBefore(" (") + (if (has) " \u2713" else "") + (if (primary == p.id) " \u2605" else "")) }
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = model, onValueChange = { model = it; core.prefs.setModelOf(editId, it) },
                label = { Text("Text model") }, singleLine = true, modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = vmodel, onValueChange = { vmodel = it; core.prefs.setVisionOf(editId, it) },
                label = { Text("Camera model (empty = no images)") }, singleLine = true, modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = keyInput, onValueChange = { keyInput = it },
                label = { Text("API key (paste to save or replace)") },
                singleLine = true, visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(8.dp))
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = {
                    if (keyInput.isNotBlank()) { core.secrets.put("key_$editId", keyInput.trim()); keyInput = ""; keyTick++ }
                }) { Text("Save key") }
                OutlinedButton(onClick = { core.secrets.remove("key_$editId"); keyTick++ }) { Text("Remove key") }
                OutlinedButton(onClick = { primary = editId; core.prefs.provider = editId }) { Text("Make primary") }
            }
            Spacer(Modifier.height(6.dp))
            Text(
                "Get a key: " + Providers.signup(editId) + ". Model names change over time; edit them here if you see a model error. Keys are stored encrypted on this phone.",
                color = Cx.Muted, style = MaterialTheme.typography.bodySmall
            )
            Spacer(Modifier.height(8.dp))
            Text("Memory: $factCount saved facts", color = Cx.Muted)
            OutlinedButton(onClick = { core.brain.clearMemory(); factCount = 0 }) { Text("Clear memory") }
        }

        CxCard {
            Text("Kori's own ears", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            Text(
                "Kori listens with its own microphone and an offline voice model, without Google's recognizer. Download a model once (needs internet), then it works offline. Odia still uses Google.",
                color = Cx.Muted, style = MaterialTheme.typography.bodySmall
            )
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = engine == "kori", onClick = { engine = "kori"; core.prefs.engine = "kori" }, label = { Text("Kori (offline)") })
                FilterChip(selected = engine == "google", onClick = { engine = "google"; core.prefs.engine = "google" }, label = { Text("Google") })
            }
            val enReady = ModelManager.isReady(ctx, "en-IN") && tick >= 0
            val hiReady = ModelManager.isReady(ctx, "hi-IN")
            StatusRow("English (India) model", if (enReady) "Ready" else "Not downloaded (36 MB)", enReady)
            StatusRow("Hindi model", if (hiReady) "Ready" else "Not downloaded (42 MB)", hiReady)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { download("en-IN") }, enabled = !dlBusy && !enReady) { Text("Get English") }
                Button(onClick = { download("hi-IN") }, enabled = !dlBusy && !hiReady) { Text("Get Hindi") }
            }
            if (dlMsg.isNotEmpty()) Text(dlMsg, color = Cx.Muted, style = MaterialTheme.typography.bodySmall)
            Text(
                "Offline models are less accurate than Google's, especially for names and Hindi. If a command is misheard, switch to Google or type it.",
                color = Cx.Muted, style = MaterialTheme.typography.bodySmall
            )
        }

        CxCard {
            Text("Training Kori's own model", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            SwitchRow("Save my commands and Kori's answers", trainOn) { trainOn = it; core.prefs.trainingLog = it }
            Text("$logCount examples saved on this phone", color = Cx.Muted)
            Text(
                "Only what you say to Kori after the wake word (or type) is saved, together with the answer. Ambient sound is never stored. Export the file, train on a free Colab GPU with the included script, then you can delete everything here.",
                color = Cx.Muted, style = MaterialTheme.typography.bodySmall
            )
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { trainMsg = core.training.export() }) { Text("Export to Downloads") }
                OutlinedButton(onClick = { core.training.clear(); logCount = 0; trainMsg = "Training data deleted." }) { Text("Delete all") }
            }
            OutlinedButton(onClick = { logCount = core.training.count() }) { Text("Refresh count") }
            if (trainMsg.isNotEmpty()) Text(trainMsg, color = Cx.Muted, style = MaterialTheme.typography.bodySmall)
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
            Text("Wake word \"Hey Kori\"", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = {
                    if (!micOk) onGrant() else ContextCompat.startForegroundService(ctx, Intent(ctx, WakeService::class.java))
                }) { Text("Start listening") }
                OutlinedButton(onClick = { ctx.stopService(Intent(ctx, WakeService::class.java)) }) { Text("Stop") }
            }
            Spacer(Modifier.height(6.dp))
            Text(
                if (KoriBus.wakeActive) "Listening now with Kori's own microphone. It stays on until you tap Stop." else "Off.",
                color = Cx.Muted, style = MaterialTheme.typography.bodySmall
            )
            Text(
                "No time limit. Android shows a notification while the microphone is on. Only speech that starts with Hey Kori is acted on; everything else is discarded and never saved. Android blocks starting the microphone from the background after a reboot, so tap Start once after restarting the phone.",
                color = Cx.Muted, style = MaterialTheme.typography.bodySmall
            )
            Spacer(Modifier.height(6.dp))
            OutlinedButton(onClick = {
                open(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + ctx.packageName)))
            }) { Text("Allow display over other apps") }
        }

        CxCard {
            Text("Background and lock screen", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            SwitchRow("Allow Kori on the lock screen (calls and questions only)", lockMode) { lockMode = it; core.prefs.lockScreenMode = it }
            SwitchRow("In Away mode, text callers I couldn't hear", awaySms) { awaySms = it; core.prefs.awaySms = it }
            val ignoring = ctx.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(ctx.packageName)
            StatusRow("Unrestricted battery use", if (ignoring) "Allowed" else "Restricted - Android may stop listening", ignoring)
            Spacer(Modifier.height(4.dp))
            Button(onClick = {
                open(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:" + ctx.packageName)))
            }) { Text("Allow background use") }
            Spacer(Modifier.height(4.dp))
            Text(
                "Realme/Oppo: also open App settings > Battery usage > Allow background activity, and turn on Auto-launch. Developer options are not needed.",
                color = Cx.Muted, style = MaterialTheme.typography.bodySmall
            )
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
            Text("Kori AI v0.7.0", color = Cx.Muted)
            Text(
                "Voice ID is experimental and never authorizes actions.",
                color = Cx.Muted, style = MaterialTheme.typography.bodySmall
            )
        }
    }
}
