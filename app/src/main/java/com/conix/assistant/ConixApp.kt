package com.conix.assistant

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChatBubble
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.conix.assistant.voice.SpeakerEmbedder
import com.conix.assistant.voice.VoiceVault
import kotlinx.coroutines.delay

object Cx {
    val Bg = Color(0xFF0A1220)
    val Card = Color(0xFF121C33)
    val Accent = Color(0xFF4FC3F7)
    val Good = Color(0xFF4ADE80)
    val Warn = Color(0xFFFFB74D)
    val Muted = Color(0xFF9AA7BD)
}

enum class Tab(val label: String, val icon: ImageVector) {
    Home("Home", Icons.Filled.Dashboard),
    Voice("Voice", Icons.Filled.Mic),
    Chat("Chat", Icons.Filled.ChatBubble),
    Security("Voice ID", Icons.Filled.Lock),
    Settings("Settings", Icons.Filled.Settings)
}

data class ChatLine(val from: String, val text: String)

private fun hasMic(ctx: Context) =
    ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

private fun hasNotif(ctx: Context) =
    Build.VERSION.SDK_INT < 33 ||
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

@Composable
fun ConixApp(router: CommandRouter, speak: (String, AppLang) -> Boolean) {
    val ctx = LocalContext.current
    var splash by remember { mutableStateOf(true) }
    var tab by remember { mutableStateOf(Tab.Home) }
    var lang by remember { mutableStateOf(AppLang.English) }
    val messages = remember {
        mutableStateListOf(
            ChatLine("conix", "Hello! I am Kori AI. Type or speak a command, e.g. \"open youtube\" or \"search weather in Bhubaneswar\".")
        )
    }
    var micOk by remember { mutableStateOf(hasMic(ctx)) }
    var notifOk by remember { mutableStateOf(hasNotif(ctx)) }
    val vault = remember { VoiceVault(ctx) }
    val embedder = remember { SpeakerEmbedder(ctx.applicationContext) }
    var enrolled by remember { mutableStateOf(vault.isEnrolled()) }

    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        micOk = hasMic(ctx); notifOk = hasNotif(ctx)
    }
    fun requestPerms() {
        val list = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= 33) list.add(Manifest.permission.POST_NOTIFICATIONS)
        permLauncher.launch(list.toTypedArray())
    }
    fun send(text: String) {
        val t = text.trim()
        if (t.isEmpty()) return
        messages.add(ChatLine("you", t))
        val reply = router.handle(t)
        messages.add(ChatLine("conix", reply))
        if (!speak(reply, lang)) {
            messages.add(ChatLine("conix", "(Voice output for ${lang.label} is not installed on this phone. Showing text only.)"))
        }
    }

    LaunchedEffect(Unit) { delay(1600); splash = false }
    LaunchedEffect(tab) { micOk = hasMic(ctx); notifOk = hasNotif(ctx); enrolled = vault.isEnrolled() }

    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Cx.Accent, background = Cx.Bg, surface = Cx.Card,
            onBackground = Color.White, onSurface = Color.White, onPrimary = Color(0xFF00212E)
        )
    ) {
        if (splash) {
            SplashScreen()
        } else {
            val imeUp = WindowInsets.ime.getBottom(LocalDensity.current) > 0
            Scaffold(
                modifier = Modifier.imePadding(),
                containerColor = Cx.Bg,
                bottomBar = {
                    if (!imeUp) NavigationBar(containerColor = Cx.Card) {
                        Tab.values().forEach { t ->
                            NavigationBarItem(
                                selected = tab == t,
                                onClick = { tab = t },
                                icon = { Icon(t.icon, contentDescription = t.label) },
                                label = { Text(t.label) }
                            )
                        }
                    }
                }
            ) { pad ->
                Box(Modifier.padding(pad).fillMaxSize()) {
                    when (tab) {
                        Tab.Home -> DashboardScreen(
                            micOk, notifOk, lang,
                            onGrant = { requestPerms() },
                            onQuick = { send(it); tab = Tab.Chat },
                            enrolled = enrolled
                        )
                        Tab.Voice -> VoiceScreen(
                            lang, micOk,
                            onLang = { lang = it },
                            onRequestPerms = { requestPerms() },
                            onHeard = { send(it) }
                        )
                        Tab.Chat -> ChatScreen(messages, onSend = { send(it) })
                        Tab.Security -> SecurityScreen(vault, embedder, micOk, onRequestPerms = { requestPerms() })
                        Tab.Settings -> SettingsScreen(
                            lang, micOk, notifOk,
                            onLang = { lang = it },
                            onGrant = { requestPerms() },
                            onTestVoice = {
                                if (!speak("Kori AI voice test.", lang))
                                    messages.add(ChatLine("conix", "(Voice output for ${lang.label} is not installed on this phone.)"))
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun SplashScreen() {
    val pulse = rememberInfiniteTransition(label = "pulse")
    val a by pulse.animateFloat(
        initialValue = 0.6f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "alpha"
    )
    Box(Modifier.fillMaxSize().background(Cx.Bg), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Image(
                painterResource(R.drawable.conix_logo), contentDescription = "Kori AI logo",
                modifier = Modifier.size(180.dp).alpha(a), contentScale = ContentScale.Fit
            )
            Spacer(Modifier.height(16.dp))
            Text("Kori AI", style = MaterialTheme.typography.headlineMedium)
            Text("Personal Autonomous AI Assistant", color = Cx.Muted, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
fun CxCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = Cx.Card)
    ) { Column(Modifier.padding(16.dp), content = content) }
}
