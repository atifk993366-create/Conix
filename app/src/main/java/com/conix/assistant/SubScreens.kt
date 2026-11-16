package com.conix.assistant

import android.app.role.RoleManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.conix.assistant.access.KoriAccessibility
import com.conix.assistant.core.AuditLog
import com.conix.assistant.core.BackupManager
import com.conix.assistant.core.CallNotes
import com.conix.assistant.core.ChatStore
import com.conix.assistant.core.CloudSync
import com.conix.assistant.core.KoriCore
import com.conix.assistant.core.Policy
import com.conix.assistant.dev.CrashLog
import com.conix.assistant.dialer.AwayWatch
import com.conix.assistant.notif.KoriNotificationListener
import com.conix.assistant.speech.ModelManager
import com.conix.assistant.voice.VoiceVault
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun SubScreen(core: KoriCore, which: String, onClose: () -> Unit) {
    Column(
        Modifier.fillMaxSize().systemBarsPadding().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                when (which) { "calls" -> "Call Assistant"; "memory" -> "Memory & Privacy"; else -> "Diagnostics" },
                style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f)
            )
            OutlinedButton(onClick = onClose) { Text("Close") }
        }
        when (which) {
            "calls" -> CallAssistantBody(core)
            "memory" -> MemoryBody(core)
            else -> DiagnosticsBody(core)
        }
    }
}

// =============================================================== Call Assistant
@Composable
private fun CallAssistantBody(core: KoriCore) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val tick = KoriBus.resumeTick
    var paused by remember { mutableStateOf(core.prefs.automationPaused) }
    var blockPrivate by remember { mutableStateOf(core.prefs.blockPrivate) }
    var silenceUnknown by remember { mutableStateOf(core.prefs.silenceUnknown) }
    var allow by remember { mutableStateOf(core.prefs.allowlist) }
    var block by remember { mutableStateOf(core.prefs.blocklist) }
    var num by remember { mutableStateOf("") }
    var listMsg by remember { mutableStateOf("") }
    var fwd by remember { mutableStateOf(core.prefs.fwdNumber) }
    var cloudUrl by remember { mutableStateOf(core.prefs.cloudUrl) }
    var cloudToken by remember { mutableStateOf("") }
    var cloudOn by remember { mutableStateOf(core.prefs.cloudSync) }
    var cloudMsg by remember { mutableStateOf("") }
    val roleLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { KoriBus.resumeTick++ }
    val rm = if (Build.VERSION.SDK_INT >= 29) ctx.getSystemService(RoleManager::class.java) else null
    val isDialer = tick >= 0 && rm != null && rm.isRoleHeld(RoleManager.ROLE_DIALER)
    val isScreener = tick >= 0 && rm != null && rm.isRoleHeld(RoleManager.ROLE_CALL_SCREENING)

    fun dial(code: String) {
        try {
            ctx.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + Uri.encode(code))).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: Exception) { }
    }

    CxCard {
        Text("Roles", style = MaterialTheme.typography.titleMedium)
        StatusRow("Kori is my phone app", if (isDialer) "Yes" else "No", isDialer)
        StatusRow("Kori screens calls (blocks / silences)", if (isScreener) "Yes" else "No", isScreener)
        Spacer(Modifier.height(6.dp))
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = {
                if (rm != null && rm.isRoleAvailable(RoleManager.ROLE_DIALER)) roleLauncher.launch(rm.createRequestRoleIntent(RoleManager.ROLE_DIALER))
            }) { Text("Phone app role") }
            OutlinedButton(onClick = {
                if (rm != null && rm.isRoleAvailable(RoleManager.ROLE_CALL_SCREENING)) roleLauncher.launch(rm.createRequestRoleIntent(RoleManager.ROLE_CALL_SCREENING))
            }) { Text("Call screening role") }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            "Call screening can only allow, reject or silence a call before it rings. It cannot answer or speak. Answering with a text message is done by Away and Driving mode.",
            color = Cx.Muted, style = MaterialTheme.typography.bodySmall
        )
    }

    CxCard {
        Text("Automation", style = MaterialTheme.typography.titleMedium)
        SwitchRow("Pause Away / Driving call handling", paused) { paused = it; core.prefs.automationPaused = it }
        Text(
            if (paused) "Paused: every call rings normally." else "Active: Away mode ${if (KoriBus.awayUntil > System.currentTimeMillis()) "ON" else "off"}, Driving mode ${if (KoriBus.driving) "ON" else "off"}.",
            color = Cx.Muted, style = MaterialTheme.typography.bodySmall
        )
        Text(
            "A caller who rings again within 3 minutes of being answered by text is treated as urgent and is let through.",
            color = Cx.Muted, style = MaterialTheme.typography.bodySmall
        )
    }

    CxCard {
        Text("Screening rules", style = MaterialTheme.typography.titleMedium)
        SwitchRow("Reject calls with a hidden number", blockPrivate) { blockPrivate = it; core.prefs.blockPrivate = it }
        SwitchRow("Silence callers who are not in my contacts", silenceUnknown) { silenceUnknown = it; core.prefs.silenceUnknown = it }
        Spacer(Modifier.height(6.dp))
        OutlinedTextField(
            value = num, onValueChange = { num = it },
            label = { Text("Phone number") }, singleLine = true, modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = {
                val k = AwayWatch.key(num)
                if (k.length >= 7) { allow = allow + k; block = block - k; core.prefs.allowlist = allow; core.prefs.blocklist = block; num = ""; listMsg = "Added to the allow list." }
                else listMsg = "Enter a full phone number."
            }) { Text("Always allow") }
            OutlinedButton(onClick = {
                val k = AwayWatch.key(num)
                if (k.length >= 7) { block = block + k; allow = allow - k; core.prefs.allowlist = allow; core.prefs.blocklist = block; num = ""; listMsg = "Added to the block list." }
                else listMsg = "Enter a full phone number."
            }) { Text("Block") }
        }
        if (listMsg.isNotEmpty()) Text(listMsg, color = Cx.Muted, style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(6.dp))
        Text("Always allowed (last 10 digits)", color = Cx.Muted)
        if (allow.isEmpty()) Text("-")
        allow.forEach { n ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(n, modifier = Modifier.weight(1f))
                TextButton(onClick = { allow = allow - n; core.prefs.allowlist = allow }) { Text("Remove") }
            }
        }
        Spacer(Modifier.height(6.dp))
        Text("Blocked", color = Cx.Muted)
        if (block.isEmpty()) Text("-")
        block.forEach { n ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(n, modifier = Modifier.weight(1f))
                TextButton(onClick = { block = block - n; core.prefs.blocklist = block }) { Text("Remove") }
            }
        }
    }

    CxCard {
        Text("Carrier call forwarding", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(4.dp))
        Text(
            "To let a cloud AI (or another phone) talk to callers, your carrier must forward calls to its number. These standard codes open the dialer with the code filled in; you press Call. Codes and charges differ per carrier, so test with one call first.",
            color = Cx.Muted, style = MaterialTheme.typography.bodySmall
        )
        Spacer(Modifier.height(6.dp))
        OutlinedTextField(
            value = fwd, onValueChange = { fwd = it; core.prefs.fwdNumber = it },
            label = { Text("Forward to (with country code, e.g. +91...)") }, singleLine = true, modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(6.dp))
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { if (fwd.isNotBlank()) dial("**61*$fwd**30#") }) { Text("If unanswered (30 s)") }
            OutlinedButton(onClick = { if (fwd.isNotBlank()) dial("**67*$fwd#") }) { Text("If busy") }
            OutlinedButton(onClick = { if (fwd.isNotBlank()) dial("**62*$fwd#") }) { Text("If unreachable") }
            Button(onClick = { dial("##002#") }) { Text("Cancel all forwarding") }
        }
    }

    CxCard {
        Text("Cloud AI call agent (optional)", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(4.dp))
        Text(
            "Needs your own telephony number and server (see backend/README_BACKEND.md); numbers, minutes and AI usage cost money. Kori only downloads the summaries.",
            color = Cx.Muted, style = MaterialTheme.typography.bodySmall
        )
        Spacer(Modifier.height(6.dp))
        OutlinedTextField(
            value = cloudUrl, onValueChange = { cloudUrl = it; core.prefs.cloudUrl = it },
            label = { Text("Server address (https://...)") }, singleLine = true, modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(6.dp))
        OutlinedTextField(
            value = cloudToken, onValueChange = { cloudToken = it },
            label = { Text("Token (paste to save)") }, singleLine = true,
            visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth()
        )
        SwitchRow("Download notes when Kori starts", cloudOn) { cloudOn = it; core.prefs.cloudSync = it }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = {
                if (cloudToken.isNotBlank()) { core.secrets.put("cloud_token", cloudToken.trim()); cloudToken = ""; cloudMsg = "Token saved." }
            }) { Text("Save token") }
            Button(onClick = { scope.launch { cloudMsg = withContext(Dispatchers.IO) { CloudSync.pull(ctx) } } }) { Text("Sync now") }
        }
        if (cloudMsg.isNotEmpty()) Text(cloudMsg, color = Cx.Muted, style = MaterialTheme.typography.bodySmall)
    }
}

// =============================================================== Memory & Privacy
@Composable
private fun MemoryBody(core: KoriCore) {
    val ctx = LocalContext.current
    val tick = KoriBus.notesTick
    var memoryOn by remember { mutableStateOf(core.prefs.memoryOn) }
    var facts by remember { mutableStateOf(core.brain.facts()) }
    var newFact by remember { mutableStateOf("") }
    var disabled by remember { mutableStateOf(core.prefs.disabledCategories) }
    var msg by remember { mutableStateOf("") }
    var armed by remember { mutableStateOf(false) }
    val chatCount = KoriBus.messages.items.size
    val noteCount = remember(tick) { CallNotes.all(core.secrets).size }

    CxCard {
        Text("Memory", style = MaterialTheme.typography.titleMedium)
        SwitchRow("Remember things you ask me to remember", memoryOn) { memoryOn = it; core.prefs.memoryOn = it }
        Text(
            "Saved facts are sent to the AI together with your questions so it can answer personally. You can edit or delete them here.",
            color = Cx.Muted, style = MaterialTheme.typography.bodySmall
        )
        Spacer(Modifier.height(6.dp))
        if (facts.isEmpty()) Text("Nothing saved yet.", color = Cx.Muted)
        facts.forEachIndexed { i, f ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = f,
                    onValueChange = { v ->
                        facts = facts.toMutableList().also { it[i] = v }
                        core.brain.replaceFacts(facts.filter { it.isNotBlank() })
                    },
                    modifier = Modifier.weight(1f)
                )
                TextButton(onClick = {
                    facts = facts.toMutableList().also { it.removeAt(i) }
                    core.brain.replaceFacts(facts.filter { it.isNotBlank() })
                }) { Text("Delete") }
            }
        }
        Spacer(Modifier.height(6.dp))
        OutlinedTextField(
            value = newFact, onValueChange = { newFact = it },
            label = { Text("Add a fact") }, singleLine = true, modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(6.dp))
        Button(onClick = {
            if (newFact.isNotBlank()) {
                facts = facts + newFact.trim()
                core.brain.replaceFacts(facts)
                newFact = ""
            }
        }) { Text("Add") }
    }

    CxCard {
        Text("What Kori may do", style = MaterialTheme.typography.titleMedium)
        Text(
            "Switch off a category and Kori refuses those actions, whoever asks.",
            color = Cx.Muted, style = MaterialTheme.typography.bodySmall
        )
        Policy.categories.forEach { (key, label) ->
            SwitchRow(label, key !in disabled) { on ->
                disabled = if (on) disabled - key else disabled + key
                core.prefs.disabledCategories = disabled
            }
        }
    }

    CxCard {
        Text("Stored on this phone", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(4.dp))
        Text("Chat history: $chatCount messages")
        OutlinedButton(onClick = { KoriBus.messages.clear(); ChatStore.clear(); msg = "Chat history cleared." }) { Text("Clear chat history") }
        Spacer(Modifier.height(6.dp))
        Text("Call notes: $noteCount")
        OutlinedButton(onClick = { CallNotes.clear(core.secrets); KoriBus.notesTick++; msg = "Call notes cleared." }) { Text("Clear call notes") }
        Spacer(Modifier.height(6.dp))
        Text("Training examples: ${core.training.count()}")
        OutlinedButton(onClick = { core.training.clear(); msg = "Training data deleted." }) { Text("Delete training data") }
        Spacer(Modifier.height(6.dp))
        Text("Action history: ${AuditLog.recent(ctx, 500).size} entries")
        OutlinedButton(onClick = { AuditLog.clear(ctx); msg = "Action history cleared." }) { Text("Clear action history") }
        if (msg.isNotEmpty()) Text(msg, color = Cx.Muted, style = MaterialTheme.typography.bodySmall)
    }

    CxCard {
        Text("Erase everything", style = MaterialTheme.typography.titleMedium)
        Text(
            "Deletes memory, chat, call notes, training data, action history and your voice profile. API keys and backups stay.",
            color = Cx.Muted, style = MaterialTheme.typography.bodySmall
        )
        SwitchRow("I am sure", armed) { armed = it }
        Button(enabled = armed, onClick = {
            core.brain.clearMemory(); facts = emptyList()
            KoriBus.messages.clear(); ChatStore.clear()
            CallNotes.clear(core.secrets); KoriBus.notesTick++
            core.training.clear(); AuditLog.clear(ctx)
            VoiceVault(ctx).clear()
            armed = false
            msg = "Everything was erased."
        }) { Text("Erase all Kori data") }
    }
}

// =============================================================== Diagnostics
private class Feature(val key: String, val title: String, val status: String, val ok: Boolean)

@Composable
private fun DiagnosticsBody(core: KoriCore) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val tick = KoriBus.resumeTick
    var verified by remember { mutableStateOf(core.prefs.verified) }
    var testMsg by remember { mutableStateOf("") }
    var impMsg by remember { mutableStateOf("") }
    var logTick by remember { mutableIntStateOf(0) }
    val crash = remember { CrashLog.last(ctx) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            val name = ctx.contentResolver.query(uri, null, null, null, null)?.use { c ->
                val i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (c.moveToFirst() && i >= 0) c.getString(i) else ""
            } ?: ""
            val bytes = ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            impMsg = if (bytes == null) "Could not read the file." else core.intents.importFile(name, bytes)
        }
    }

    val rm = if (Build.VERSION.SDK_INT >= 29) ctx.getSystemService(RoleManager::class.java) else null
    val lang = core.prefs.langTag
    val model = core.intents.ensureLoaded()
    val features = listOf(
        Feature("keys", "AI providers", if (core.brain.hasKey()) "At least one key saved" else "No key", core.brain.hasKey()),
        Feature("ears", "Own offline voice model", if (core.prefs.engine == "kori" && ModelManager.isReady(ctx, lang)) "Ready for $lang" else "Not used for $lang", core.prefs.engine == "kori" && ModelManager.isReady(ctx, lang)),
        Feature("wake", "Hey Kori listener", if (KoriBus.wakeActive) "Running" else "Stopped", KoriBus.wakeActive),
        Feature("dialer", "Kori Phone (default dialer)", if (tick >= 0 && rm != null && rm.isRoleHeld(RoleManager.ROLE_DIALER)) "Role held" else "Not the phone app", tick >= 0 && rm != null && rm.isRoleHeld(RoleManager.ROLE_DIALER)),
        Feature("screen", "Call screening role", if (tick >= 0 && rm != null && rm.isRoleHeld(RoleManager.ROLE_CALL_SCREENING)) "Role held" else "Not granted", tick >= 0 && rm != null && rm.isRoleHeld(RoleManager.ROLE_CALL_SCREENING)),
        Feature("acc", "Accessibility service", if (KoriAccessibility.instance != null) "On" else "Off", KoriAccessibility.instance != null),
        Feature("notif", "Notification access", if (KoriNotificationListener.connected) "On" else "Off", KoriNotificationListener.connected),
        Feature("overlay", "Display over other apps", if (android.provider.Settings.canDrawOverlays(ctx)) "Allowed" else "Not allowed", android.provider.Settings.canDrawOverlays(ctx)),
        Feature("battery", "Unrestricted battery use", if (ctx.getSystemService(android.os.PowerManager::class.java).isIgnoringBatteryOptimizations(ctx.packageName)) "Allowed" else "Restricted", ctx.getSystemService(android.os.PowerManager::class.java).isIgnoringBatteryOptimizations(ctx.packageName)),
        Feature("backup", "Backup folder (/Kori)", if (BackupManager.hasAccess()) (if (BackupManager.hasBackup()) "Access allowed, backup exists" else "Access allowed, no backup yet") else "All-files access needed", BackupManager.hasBackup()),
        Feature("intent", "On-device intent model", if (model != null) "${model.version} (${core.intents.source})" else core.intents.source, model != null),
        Feature("dev", "Develop & Fix (GitHub)", if (core.prefs.repo.isNotBlank() && core.secrets.get("github_token") != null) "Repo and token set" else "Not set up", core.prefs.repo.isNotBlank() && core.secrets.get("github_token") != null)
    )

    val pi = try { ctx.packageManager.getPackageInfo(ctx.packageName, 0) } catch (e: Exception) { null }
    CxCard {
        Text("This build", style = MaterialTheme.typography.titleMedium)
        Text("Kori ${pi?.versionName ?: "?"}", color = Cx.Muted)
        Text("Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}), ${Build.MANUFACTURER} ${Build.MODEL}", color = Cx.Muted)
        Spacer(Modifier.height(4.dp))
        Text(
            "A feature is only done when it is built (in this app), installed (green below) and verified (you tested it on your phone).",
            color = Cx.Muted, style = MaterialTheme.typography.bodySmall
        )
    }

    CxCard {
        Text("Feature checklist", style = MaterialTheme.typography.titleMedium)
        features.forEach { f ->
            StatusRow(f.title, f.status, f.ok)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = f.key in verified, onCheckedChange = { on ->
                    verified = if (on) verified + f.key else verified - f.key
                    core.prefs.verified = verified
                })
                Text("I tested this on my phone", style = MaterialTheme.typography.bodySmall, color = Cx.Muted)
            }
        }
    }

    CxCard {
        Text("On-device intent model", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(4.dp))
        Text(
            if (model != null) "Version ${model.version}, ${model.labels.size} intents, acts above ${(model.threshold * 100).toInt()}% confidence.\nSource: ${core.intents.source}\nSHA-256: ${core.intents.sha}..." else core.intents.source,
            style = MaterialTheme.typography.bodySmall, color = Cx.Muted
        )
        Spacer(Modifier.height(6.dp))
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { scope.launch { testMsg = withContext(Dispatchers.Default) { core.intents.selfTest() } } }) { Text("Run self-test") }
            OutlinedButton(onClick = { picker.launch(arrayOf("application/json", "*/*")) }) { Text("Import model or test file") }
        }
        if (testMsg.isNotEmpty()) Text(testMsg, style = MaterialTheme.typography.bodySmall)
        if (impMsg.isNotEmpty()) Text(impMsg, style = MaterialTheme.typography.bodySmall, color = Cx.Muted)
        Text(
            "Train a new one with the notebooks in training/ and import kori_intent_model.json together with kori_intent_tests.json from the same run.",
            style = MaterialTheme.typography.bodySmall, color = Cx.Muted
        )
    }

    if (crash != null) {
        CxCard {
            Text("Last crash", style = MaterialTheme.typography.titleMedium)
            Text(crash.take(700), style = MaterialTheme.typography.bodySmall, color = Cx.Warn)
        }
    }

    CxCard {
        Text("Action history", style = MaterialTheme.typography.titleMedium)
        val log = remember(logTick) { AuditLog.recent(ctx, 25) }
        if (log.isEmpty()) Text("Nothing yet.", color = Cx.Muted)
        log.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { AuditLog.clear(ctx); logTick++ }) { Text("Clear") }
            Button(onClick = {
                val report = buildString {
                    append("Kori ").append(pi?.versionName ?: "?").append(" | Android ").append(Build.VERSION.RELEASE).append(" | ")
                    append(Build.MANUFACTURER).append(' ').append(Build.MODEL).append('\n')
                    features.forEach { append(if (it.ok) "[ok] " else "[--] ").append(it.title).append(": ").append(it.status)
                        .append(if (it.key in verified) " (verified)" else "").append('\n') }
                    if (crash != null) append("\nLast crash:\n").append(crash.take(1200)).append('\n')
                }
                ctx.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Kori report", report))
            }) { Text("Copy report") }
        }
    }
}
