package xyz.heylana.app.home

import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
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

    when (screen) {
        else -> HomeScreen(
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
            onMic = { onScreen(Screen.VOICE) },
            onAskAboutScreen = { askAboutScreen(activity, chat) }
        )
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
