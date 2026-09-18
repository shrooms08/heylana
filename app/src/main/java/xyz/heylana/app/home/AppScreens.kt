package xyz.heylana.app.home

import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import xyz.heylana.app.HeylanaLog
import xyz.heylana.app.overlay.BuddyOverlayService
import xyz.heylana.app.settings.HeylanaSettings
import xyz.heylana.app.ui.app.Backdrop
import xyz.heylana.app.ui.theme.GlassMode
import xyz.heylana.app.wallet.SeedVault

/** Home and everything it opens. */
@Composable
fun AppScreens(
    screen: Screen,
    backdrop: Backdrop,
    settings: HeylanaSettings,
    activity: ComponentActivity,
    seedVault: SeedVault,
    onScreen: (Screen) -> Unit,
    onGlassMode: (GlassMode) -> Unit
) {
    val scope = rememberCoroutineScope()
    val chat = remember { AppChat(activity.applicationContext, settings, scope) }
    DisposableEffect(chat) { onDispose { chat.shutdown() } }
    var muted by remember { mutableStateOf(settings.voiceMuted) }
    var menuOpen by remember { mutableStateOf(false) }
    val voice = remember { VoiceSession(activity.applicationContext, settings, scope, chat) }
    DisposableEffect(voice) { onDispose { voice.shutdown() } }
    // Set when the home mic could not start listening (no microphone yet): the voice
    // screen asks for it and starts.
    var voiceStartOnOpen by remember { mutableStateOf(false) }

    // A press on home's mic opens the voice screen at once. Home stays composed under it
    // until the finger lifts, because the press belongs to home's mic: that is where the
    // release arrives, and a hold ends there.
    var homeHeld by remember { mutableStateOf(false) }

    Box(Modifier.fillMaxSize()) {
    if (screen == Screen.HOME || homeHeld) HomeScreen(
            backdrop = backdrop,
            name = settings.callMe,
            chat = chat,
            muted = muted,
            onMenu = { menuOpen = true },
            onMute = { next ->
                muted = next
                settings.voiceMuted = next
                if (next) chat.silence()
            },
            listening = voice.phase == VoiceSession.Phase.LISTENING,
            micLevel = voice.level,
            onMicDown = {
                val wasListening = voice.phase == VoiceSession.Phase.LISTENING
                val started = !wasListening && voice.start()
                voiceStartOnOpen = !started && !wasListening && !voice.micGranted()
                homeHeld = true
                onScreen(Screen.VOICE)
                started
            },
            onMicUp = { held, started ->
                homeHeld = false
                if (started && MicPress.onRelease(held, true) == MicPress.OnRelease.FINISH) voice.finish()
            },
            onAskAboutScreen = { askAboutScreen(activity, chat) }
        )
    when (screen) {
        Screen.VOICE -> VoiceScreen(
            backdrop = backdrop,
            voice = voice,
            chat = chat,
            startOnOpen = voiceStartOnOpen,
            onBack = {
                voice.cancel()
                onScreen(Screen.HOME)
            },
            onClose = {
                voice.cancel()
                chat.silence()
                onScreen(Screen.HOME)
            }
        )
        else -> Unit
    }
    }
}

/**
 * "Ask about this screen": the app never reads a screen itself, so this starts the buddy
 * (if it can) and says where to ask.
 */
fun askAboutScreen(activity: ComponentActivity, chat: AppChat) {
    if (!Settings.canDrawOverlays(activity)) {
        chat.note(HomeChips.BUDDY_NEEDS_OVERLAY)
        return
    }
    if (!BuddyOverlayService.isRunning) {
        HeylanaLog.state("app: buddy started from the chip")
        BuddyOverlayService.start(activity)
    }
    chat.note(HomeChips.BUDDY_LINE)
}
