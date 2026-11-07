package com.conix.assistant

import android.Manifest
import android.content.Context
import android.content.ContextWrapper
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
import androidx.fragment.app.FragmentActivity
import com.conix.assistant.core.Gate
import com.conix.assistant.core.KoriCore
import com.conix.assistant.voice.SpeakerEmbedder
import com.conix.assistant.voice.VoiceVault
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

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

internal fun Context.findActivity(): FragmentActivity? {
    var c: Context = this
    while (c is ContextWrapper) {
        if (c is FragmentActivity) return c
        c = c.baseContext
    }
    return null
}

@Composable
fun ConixApp(core: KoriCore, speak: (String, AppLang) -> Boolean, speakCall: (String, AppLang) -> Boolean) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var splash by remember { mutableStateOf(true) }
    var tab by remember { mutableStateOf(Tab.Home) }
    var lang by remember { mutableStateOf(AppLang.values().firstOrNull { it.tag == core.prefs.langTag } ?: AppLang.English) }
    val messages = KoriBus.messages
    var thinking by remember { mutableStateOf(false) }
    var micOk by remember { mutableStateOf(hasMic(ctx)) }
    var notifOk by remember { mutableStateOf(hasNotif(ctx)) }
    var brainReady by remember { mutableStateOf(core.brain.hasKey()) }
    val vault = remember { VoiceVault(ctx) }
    val embedder = remember { SpeakerEmbedder(ctx.applicationContext) }
    var enrolled by remember { mutableStateOf(vault.isEnrolled()) }

    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        micOk = hasMic(ctx); notifOk = hasNotif(ctx)
    }
    val commLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { }
    fun requestPerms() {
        val list = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= 33) list.add(Manifest.permission.POST_NOTIFICATIONS)
        permLauncher.launch(list.toTypedArray())
    }
    fun requestComm() {
        commLauncher.launch(
            arrayOf(
                Manifest.permission.READ_CONTACTS, Manifest.permission.CALL_PHONE,
                Manifest.permission.SEND_SMS, Manifest.permission.READ_SMS,
                Manifest.permission.READ_PHONE_STATE, Manifest.permission.READ_CALL_LOG,
                Manifest.permission.ANSWER_PHONE_CALLS
            )
        )
    }
    fun send(text: String, typed: Boolean = true) {
        val t = text.trim()
        if (t.isEmpty() || thinking) return
        messages.add(ChatLine("you", t))
        thinking = true
        scope.launch {
            val reply = try { core.handle(t, typed) } catch (e: Exception) { "Error: ${e.message}" }
            messages.add(ChatLine("conix", reply))
            if (!speak(reply.substringBefore("\n\nSources:").take(400), lang)) {
                messages.add(ChatLine("conix", "(Voice output for ${lang.label} is not installed on this phone. Showing text only.)"))
            }
            thinking = false
        }
    }

    LaunchedEffect(Unit) { delay(1600); splash = false }
    LaunchedEffect(tab, KoriBus.resumeTick) {
        micOk = hasMic(ctx); notifOk = hasNotif(ctx)
        enrolled = vault.isEnrolled(); brainReady = core.brain.hasKey()
    }
    LaunchedEffect(lang) { core.prefs.langTag = lang.tag }
    LaunchedEffect(Unit) { KoriBus.awayUntil = core.prefs.awayUntil }

    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Cx.Accent, background = Cx.Bg, surface = Cx.Card,
            onBackground = Color.White, onSurface = Color.White, onPrimary = Color(0xFF00212E)
        )
    ) {
        if (splash) {
            SplashScreen()
        } else if (KoriBus.cameraRequest != null) {
            CameraScreen(
                core, KoriBus.cameraRequest ?: "",
                onClose = { KoriBus.cameraRequest = null },
                speak = { t -> speak(t, lang) }
            )
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
                            enrolled = enrolled,
                            brainReady = brainReady,
                            wakeOn = KoriBus.wakeActive
                        )
                        Tab.Voice -> VoiceScreen(
                            core, lang, micOk,
                            onLang = { lang = it },
                            onRequestPerms = { requestPerms() },
                            onHeard = { send(it, false); tab = Tab.Chat },
                            speakCall = speakCall
                        )
                        Tab.Chat -> ChatScreen(messages, thinking, onSend = { send(it) })
                        Tab.Security -> SecurityScreen(vault, embedder, micOk, onRequestPerms = { requestPerms() })
                        Tab.Settings -> SettingsScreen(
                            core, lang, micOk, notifOk,
                            onLang = { lang = it },
                            onGrant = { requestPerms() },
                            onGrantComm = { requestComm() },
                            onTestVoice = {
                                if (!speak("Kori voice test.", lang))
                                    messages.add(ChatLine("conix", "(Voice output for ${lang.label} is not installed on this phone.)"))
                            }
                        )
                    }
                }
            }
        }

        val pending = KoriBus.pending
        if (pending != null) {
            AlertDialog(
                onDismissRequest = { KoriBus.pending = null },
                title = { Text(pending.title) },
                text = { Text(pending.detail + if (pending.needsUnlock) "\n\nYou will be asked to unlock your phone." else "") },
                confirmButton = {
                    TextButton(onClick = {
                        KoriBus.pending = null
                        val act = ctx.findActivity()
                        if (!pending.needsUnlock) {
                            messages.add(ChatLine("conix", pending.run()))
                        } else if (act == null) {
                            messages.add(ChatLine("conix", "Could not ask for phone unlock."))
                        } else {
                            Gate.authenticate(act, pending.title) { ok ->
                                messages.add(
                                    ChatLine("conix", if (ok) pending.run() else "Cancelled - phone unlock was not completed (set a screen lock if you haven't).")
                                )
                            }
                        }
                    }) { Text("Confirm") }
                },
                dismissButton = {
                    TextButton(onClick = {
                        KoriBus.pending = null
                        messages.add(ChatLine("conix", "Cancelled."))
                    }) { Text("Cancel") }
                }
            )
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
