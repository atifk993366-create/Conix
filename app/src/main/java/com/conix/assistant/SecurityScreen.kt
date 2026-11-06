package com.conix.assistant

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.conix.assistant.voice.ENROLL_SAMPLES
import com.conix.assistant.voice.Recorder
import com.conix.assistant.voice.SpeakerEmbedder
import com.conix.assistant.voice.VoiceVault
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val PROMPTS = listOf(
    "Say: \"Hey Conix, what is the weather today?\"",
    "Say: \"Conix, mera latest message padho.\"",
    "Say: \"Conix, ମୋତେ ଆଜିର ଖବର କୁହ.\"",
    "Say: \"Conix, open WhatsApp and play some music.\"",
    "Say: \"Conix, set an alarm for seven in the morning.\""
)

private fun fmt(x: Float) = String.format("%.2f", x)

private fun buildProfile(vault: VoiceVault, embedder: SpeakerEmbedder): String {
    val embs = ArrayList<FloatArray>()
    for (i in 0 until ENROLL_SAMPLES) {
        val pcm = vault.loadSample(i) ?: return "Sample ${i + 1} is missing. Reset and enroll again."
        val e = embedder.embed(pcm) ?: return "Could not process sample ${i + 1}: ${embedder.error ?: "unknown error"}"
        embs.add(e)
    }
    val c = VoiceVault.centroid(embs)
    val worst = embs.minOf { VoiceVault.dot(it, c) }
    vault.saveProfile(embs)
    return if (worst < 0.35f)
        "Profile saved, but your samples look inconsistent (lowest match ${fmt(worst)}). Reset and re-record in a quiet place."
    else
        "Profile saved. Sample consistency: lowest match ${fmt(worst)}."
}

@Composable
fun SecurityScreen(vault: VoiceVault, embedder: SpeakerEmbedder, micOk: Boolean, onRequestPerms: () -> Unit) {
    val scope = rememberCoroutineScope()
    var count by remember { mutableIntStateOf(vault.sampleCount()) }
    var enrolled by remember { mutableStateOf(vault.isEnrolled()) }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }
    var score by remember { mutableStateOf<Float?>(null) }
    var threshold by remember { mutableFloatStateOf(vault.threshold) }
    var modelMsg by remember { mutableStateOf("Checking speaker model...") }

    LaunchedEffect(Unit) {
        modelMsg = withContext(Dispatchers.Default) {
            if (embedder.ensureLoaded()) "Speaker model ready: ${embedder.modelName}"
            else "Speaker model problem: ${embedder.error}"
        }
    }

    fun enrollOne() {
        if (!micOk) { onRequestPerms(); return }
        scope.launch {
            busy = true
            try {
                status = "Recording 5 seconds - speak now..."
                val pcm = Recorder.record(5)
                val issue = Recorder.checkQuality(pcm)
                if (issue != null) {
                    status = issue
                } else {
                    vault.saveSample(count, pcm)
                    count += 1
                    if (count >= ENROLL_SAMPLES) {
                        status = "Building your voice profile..."
                        status = withContext(Dispatchers.Default) { buildProfile(vault, embedder) }
                        enrolled = vault.isEnrolled()
                    } else {
                        status = "Sample $count saved."
                    }
                }
            } catch (e: Exception) {
                status = "Error: ${e.message}"
            }
            busy = false
        }
    }

    fun testVoice() {
        if (!micOk) { onRequestPerms(); return }
        scope.launch {
            busy = true
            try {
                status = "Recording 4 seconds - say anything..."
                val pcm = Recorder.record(4)
                val issue = Recorder.checkQuality(pcm, 3)
                if (issue != null) {
                    status = issue
                } else {
                    val s = withContext(Dispatchers.Default) {
                        val prof = vault.loadProfile()
                        val e = embedder.embed(pcm)
                        if (prof == null || e == null) null else VoiceVault.dot(e, VoiceVault.centroid(prof))
                    }
                    if (s == null) {
                        status = "Could not score: ${embedder.error ?: "no profile"}"
                    } else {
                        score = s
                        status = if (s >= threshold) "MATCH - would be accepted" else "NO MATCH - would be rejected"
                    }
                }
            } catch (e: Exception) {
                status = "Error: ${e.message}"
            }
            busy = false
        }
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text("Voice ID", style = MaterialTheme.typography.headlineSmall)
        Text(modelMsg, color = Cx.Muted, style = MaterialTheme.typography.bodySmall)

        CxCard {
            Text("1. Register your voice", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(6.dp))
            if (enrolled) {
                Text("Voice profile saved (encrypted on this phone).", color = Cx.Good)
            } else {
                Text("Samples: $count / $ENROLL_SAMPLES", color = Cx.Muted)
                Spacer(Modifier.height(4.dp))
                Text(PROMPTS[count.coerceAtMost(ENROLL_SAMPLES - 1)])
                Spacer(Modifier.height(8.dp))
                Button(onClick = { enrollOne() }, enabled = !busy) { Text("Record sample (5 s)") }
            }
            Spacer(Modifier.height(8.dp))
            OutlinedButton(
                onClick = {
                    vault.clear(); count = 0; enrolled = false; score = null
                    status = "Voice data deleted."
                },
                enabled = !busy
            ) { Text("Delete my voice data") }
        }

        if (enrolled) {
            CxCard {
                Text("2. Test and calibrate", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(6.dp))
                Button(onClick = { testVoice() }, enabled = !busy) { Text("Test my voice (4 s)") }
                Spacer(Modifier.height(8.dp))
                score?.let { Text("Last score: ${fmt(it)}", style = MaterialTheme.typography.titleMedium) }
                Spacer(Modifier.height(8.dp))
                Text("Threshold: ${fmt(threshold)}", color = Cx.Muted)
                Slider(
                    value = threshold,
                    onValueChange = { threshold = it },
                    onValueChangeFinished = { vault.threshold = threshold },
                    valueRange = 0.2f..0.9f
                )
                Text(
                    "Test with your voice a few times, then let another person speak. Set the threshold above their scores and below yours.",
                    color = Cx.Muted, style = MaterialTheme.typography.bodySmall
                )
            }
        }

        if (status.isNotEmpty()) {
            CxCard { Text(status) }
        }
        Text(
            "Testing mode: Voice ID does not authorize any action yet. Voice matching is not foolproof, so sensitive actions will also need phone unlock.",
            color = Cx.Muted, style = MaterialTheme.typography.bodySmall
        )
    }
}
