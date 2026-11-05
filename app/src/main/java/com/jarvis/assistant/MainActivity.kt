package com.jarvis.assistant

import android.content.Intent
import android.os.Bundle
import android.speech.RecognizerIntent
import android.speech.tts.TextToSpeech
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Locale

data class ChatLine(val from: String, val text: String)

class MainActivity : ComponentActivity(), TextToSpeech.OnInitListener {
    private var tts: TextToSpeech? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        tts = TextToSpeech(this, this)
        setContent { JarvisScreen(
            onSpeak = { text -> speak(text) },
            onCommand = { command -> handleCommand(command) }
        ) }
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) tts?.language = Locale.getDefault()
    }

    private fun speak(text: String) {
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "jarvis_reply")
    }

    private fun handleCommand(raw: String): String {
        val q = raw.trim().lowercase(Locale.ROOT)
        return when {
            q.contains("open youtube") || q.contains("यूट्यूब खोल") || q.contains("ୟୁଟ୍ୟୁବ୍ ଖୋଲ") -> {
                openUrl("https://www.youtube.com")
                "Opening YouTube."
            }
            q.contains("open google") || q.contains("गूगल खोल") || q.contains("ଗୁଗୁଲ୍ ଖୋଲ") -> {
                openUrl("https://www.google.com")
                "Opening Google."
            }
            q.contains("open settings") || q.contains("सेटिंग खोल") || q.contains("ସେଟିଂ") -> {
                startActivity(Intent(android.provider.Settings.ACTION_SETTINGS))
                "Opening Android settings."
            }
            q.startsWith("search ") || q.startsWith("खोजो ") || q.startsWith("ଖୋଜ ") -> {
                val term = raw.substringAfter(' ').trim()
                openUrl("https://www.google.com/search?q=" + java.net.URLEncoder.encode(term, "UTF-8"))
                "Searching the web for $term."
            }
            q.contains("hello") || q.contains("नमस्ते") || q.contains("ନମସ୍କାର") ->
                "Hello. JARVIS is online. Voice identity verification is not enabled yet, so I will not perform private or sensitive actions."
            else ->
                "I received: $raw. The first build supports basic device commands. Research, shopping, calls and messaging will be added as separate permission-controlled tools."
        }
    }

    private fun openUrl(url: String) {
        runCatching {
            startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url)))
        }
    }

    override fun onDestroy() {
        tts?.stop()
        tts?.shutdown()
        super.onDestroy()
    }
}

@Composable
fun JarvisScreen(onSpeak: (String) -> Unit, onCommand: (String) -> String) {
    var input by remember { mutableStateOf("") }
    var wakeWord by remember { mutableStateOf("Hey Jarvis") }
    var language by remember { mutableStateOf("Auto") }
    var lines by remember { mutableStateOf(listOf(ChatLine("JARVIS", "Systems initialized. Type a command or tap the microphone to speak."))) }

    val speechLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val spoken = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
        if (!spoken.isNullOrBlank()) {
            lines = lines + ChatLine("YOU", spoken)
            val response = onCommand(spoken)
            lines = lines + ChatLine("JARVIS", response)
            onSpeak(response)
        }
    }

    fun submit(text: String) {
        if (text.isBlank()) return
        lines = lines + ChatLine("YOU", text)
        val response = onCommand(text)
        lines = lines + ChatLine("JARVIS", response)
        input = ""
        onSpeak(response)
    }

    MaterialTheme(colorScheme = darkColorScheme(
        primary = Color(0xFF4DD0E1),
        background = Color(0xFF07111F),
        surface = Color(0xFF101F31)
    )) {
        Column(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(listOf(Color(0xFF07111F), Color(0xFF102A43)))
            ).padding(18.dp)
        ) {
            Text("J.A.R.V.I.S", color = Color(0xFF8CEBFF), fontSize = 27.sp)
            Text("PERSONAL AI SYSTEM  •  BUILD 0.1", color = Color.LightGray, fontSize = 10.sp)
            Spacer(Modifier.height(22.dp))
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                Box(
                    Modifier.size(142.dp).background(
                        Brush.radialGradient(listOf(Color(0xFF176B87), Color(0xFF0B2035), Color.Transparent)),
                        CircleShape
                    ),
                    contentAlignment = Alignment.Center
                ) {
                    Text("J", color = Color(0xFF9CF3FF), fontSize = 66.sp)
                }
            }
            Spacer(Modifier.height(12.dp))
            Text("READY • VOICE ID LOCK NOT YET ENABLED", color = Color(0xFFFFC66D), fontSize = 11.sp)
            Spacer(Modifier.height(16.dp))
            LazyColumn(Modifier.weight(1f).fillMaxWidth(), reverseLayout = true) {
                items(lines.reversed()) { line ->
                    Column(Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
                        Text(line.from, color = if (line.from == "YOU") Color(0xFF80CBC4) else Color(0xFF8CEBFF), fontSize = 10.sp)
                        Text(line.text, color = Color.White, fontSize = 14.sp)
                    }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                BasicTextField(
                    value = input, onValueChange = { input = it },
                    modifier = Modifier.weight(1f).background(Color(0xFF172B40), RoundedCornerShape(22.dp)).padding(14.dp),
                    singleLine = true, textStyle = androidx.compose.ui.text.TextStyle(color = Color.White, fontSize = 14.sp),
                    decorationBox = { inner -> if (input.isEmpty()) Text("Ask JARVIS...", color = Color.Gray); inner() }
                )
                IconButton(onClick = { submit(input) }) {
                    Icon(Icons.Default.Send, contentDescription = "Send", tint = Color(0xFF8CEBFF))
                }
                IconButton(onClick = {
                    val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                        putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-IN")
                        putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "en-IN")
                        putExtra(RecognizerIntent.EXTRA_PROMPT, "Speak to JARVIS")
                        putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
                    }
                    speechLauncher.launch(intent)
                }) {
                    Icon(Icons.Default.Mic, contentDescription = "Speak", tint = Color(0xFF8CEBFF))
                }
            }
            Spacer(Modifier.height(10.dp))
            Text("Wake phrase setting (activation engine not connected):", color = Color.LightGray, fontSize = 10.sp)
            OutlinedTextField(
                value = wakeWord, onValueChange = { wakeWord = it },
                modifier = Modifier.fillMaxWidth(), singleLine = true,
                label = { Text("Custom wake phrase") }
            )
            Text("Voice identity verification is a required next security milestone. Do not use this build for private actions.",
                color = Color(0xFFFFC66D), fontSize = 10.sp)
        }
    }
}
