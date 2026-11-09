package com.conix.assistant

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.conix.assistant.core.KoriCore
import com.conix.assistant.dev.CrashLog
import com.conix.assistant.dev.DevAgent
import com.conix.assistant.dev.Installer
import com.conix.assistant.dev.Proposal
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File

/** "Develop & Fix": Kori reads its own code on GitHub, proposes a fix or feature, builds it and installs it. */
@Composable
fun DevScreen(core: KoriCore, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val agent = remember { DevAgent(core, ctx.applicationContext) }
    var repo by remember { mutableStateOf(core.prefs.repo) }
    var token by remember { mutableStateOf("") }
    var tokenSaved by remember { mutableStateOf(core.secrets.get("github_token") != null) }
    var autoFix by remember { mutableStateOf(core.prefs.autoFix) }
    var request by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var proposal by remember { mutableStateOf<Proposal?>(null) }
    var branch by remember { mutableStateOf("") }
    var runId by remember { mutableLongStateOf(0L) }
    var runInfo by remember { mutableStateOf("") }
    var apk by remember { mutableStateOf<File?>(null) }
    val lines = remember { mutableStateListOf<String>() }
    val crash = remember { CrashLog.last(ctx) }
    val log: (String) -> Unit = { m -> scope.launch(Dispatchers.Main) { lines.add(0, m) } }

    fun run(block: suspend () -> Unit) {
        scope.launch {
            busy = true
            try { block() } catch (e: Exception) { lines.add(0, "Error: ${e.message}") }
            busy = false
        }
    }

    Column(
        Modifier.fillMaxSize().systemBarsPadding().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Text("Develop & Fix", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
            OutlinedButton(onClick = onClose) { Text("Close") }
        }
        Text(
            "Kori reads its own source code on GitHub, asks the AI for a fix or a new feature, pushes it to a new branch, builds it in the cloud and installs it. Android always asks you to confirm the install. Every change stays in git history, so you can undo it.",
            color = Cx.Muted, style = MaterialTheme.typography.bodySmall
        )

        CxCard {
            Text("GitHub access", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(6.dp))
            OutlinedTextField(
                value = repo, onValueChange = { repo = it; core.prefs.repo = it },
                label = { Text("Repository (owner/name)") }, singleLine = true, modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = token, onValueChange = { token = it },
                label = { Text(if (tokenSaved) "Token saved - paste to replace" else "GitHub token") },
                singleLine = true, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(8.dp))
            Button(onClick = {
                if (token.isNotBlank()) { core.secrets.put("github_token", token.trim()); token = ""; tokenSaved = true }
            }) { Text("Save token") }
            Spacer(Modifier.height(6.dp))
            Text(
                "Create a token at github.com/settings/tokens with access to this repo (Contents: read/write, Actions: read/write). Keep the repository private: the app's signing key is inside it.",
                color = Cx.Muted, style = MaterialTheme.typography.bodySmall
            )
        }

        if (crash != null) {
            CxCard {
                Text("Last crash", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(4.dp))
                Text(crash.take(500), style = MaterialTheme.typography.bodySmall, color = Cx.Warn)
                Spacer(Modifier.height(8.dp))
                Button(enabled = !busy, onClick = {
                    run {
                        proposal = agent.propose("Fix this crash in the app.", crash, log)
                        log("Proposal ready.")
                    }
                }) { Text("Diagnose and propose a fix") }
            }
        }
        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Text("Fix crashes automatically", modifier = Modifier.weight(1f))
            Switch(checked = autoFix, onCheckedChange = { autoFix = it; core.prefs.autoFix = it })
        }

        CxCard {
            Text("Develop", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(6.dp))
            OutlinedTextField(
                value = request, onValueChange = { request = it },
                label = { Text("Describe a bug to fix or a feature to add") }, modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(8.dp))
            Button(enabled = !busy && request.isNotBlank(), onClick = {
                run {
                    proposal = agent.propose(request, null, log)
                    log("Proposal ready.")
                }
            }) { Text("Propose changes") }
            if (busy) {
                Spacer(Modifier.height(6.dp))
                Text("Working... this can take a minute.", color = Cx.Muted)
            }
        }

        proposal?.let { p ->
            CxCard {
                Text("Proposed change", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(4.dp))
                Text(p.summary)
                Spacer(Modifier.height(6.dp))
                p.edits.forEach { e ->
                    Text("${e.path}: ${e.find.length} -> ${e.replace.length} characters", style = MaterialTheme.typography.bodySmall, color = Cx.Muted)
                }
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(enabled = !busy, onClick = {
                        run {
                            branch = agent.apply(p, log)
                            proposal = null
                            runId = 0L
                            apk = null
                            log("Build started on $branch. Tap Check build in a minute.")
                        }
                    }) { Text("Apply and build") }
                    OutlinedButton(onClick = { proposal = null }) { Text("Discard") }
                }
            }
        }

        if (branch.isNotEmpty()) {
            CxCard {
                Text("Build", style = MaterialTheme.typography.titleMedium)
                Text("Branch: $branch", style = MaterialTheme.typography.bodySmall, color = Cx.Muted)
                if (runInfo.isNotEmpty()) Text(runInfo)
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(enabled = !busy, onClick = {
                        run {
                            val r = agent.latestRun(branch)
                            if (r == null) { runInfo = "No build found yet." } else {
                                runId = r.id
                                runInfo = "Build ${r.status} ${r.conclusion}".trim()
                            }
                        }
                    }) { Text("Check build") }
                    Button(enabled = !busy && runId != 0L, onClick = {
                        run {
                            log("Downloading the build...")
                            val f = agent.downloadBuild(runId)
                            apk = f
                            log(Installer.install(ctx, f))
                        }
                    }) { Text("Install") }
                }
                Spacer(Modifier.height(6.dp))
                OutlinedButton(enabled = !busy, onClick = { run { log(agent.merge(branch)) } }) { Text("Merge into main") }
                Spacer(Modifier.height(4.dp))
                Text(
                    "Install only after the build says completed success. Merge into main once you are happy with the result.",
                    style = MaterialTheme.typography.bodySmall, color = Cx.Muted
                )
            }
        }

        if (lines.isNotEmpty()) {
            CxCard {
                Text("Progress", style = MaterialTheme.typography.titleMedium)
                lines.take(14).forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
            }
        }
    }
}
