package xyz.heylana.app.home

import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import android.content.Intent
import xyz.heylana.app.Features
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import xyz.heylana.app.settings.SettingsActivity
import xyz.heylana.app.skills.SkillCap
import xyz.heylana.app.skills.SkillStore
import xyz.heylana.app.wallet.Answer
import xyz.heylana.app.wallet.Standing
import xyz.heylana.app.wallet.WalletApi
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
import xyz.heylana.app.ui.theme.GlassMode
import xyz.heylana.app.wallet.SeedVault

/** Home and everything it opens. */
@Composable
fun AppScreens(
    screen: Screen,
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

    // The menu's plan and the buddy's switch: read again whenever the menu opens.
    var standing by remember { mutableStateOf<Standing?>(null) }
    var buddyOn by remember { mutableStateOf(BuddyOverlayService.isRunning) }
    var skillsVersion by remember { mutableIntStateOf(0) }
    LaunchedEffect(menuOpen, screen) {
        buddyOn = BuddyOverlayService.isRunning
        if (!menuOpen) return@LaunchedEffect
        when (val answer = WalletApi(settings).me()) {
            is Answer.Ok -> {
                // The debug switch shows a Judge or Pro account as Free, so the Free plan
                // card, its usage bar and the Go Pro sheet can be checked without one.
                standing = if (xyz.heylana.app.BuildConfig.DEBUG && settings.simulateFreePlan) {
                    xyz.heylana.app.wallet.PlanText.asFree(answer.value)
                } else {
                    answer.value
                }
                settings.skillsCap = answer.value.skillsCap
                answer.value.voice?.let { settings.rememberVoice(it.provider, it.skylar, it.archie, it.ears) }
                HeylanaLog.state("app: plan ${answer.value.plan}")
            }
            else -> HeylanaLog.state("app: plan not heard")
        }
    }

    // A press on home's mic opens the voice screen at once. Home stays composed under it
    // until the finger lifts, because the press belongs to home's mic: that is where the
    // release arrives, and a hold ends there.
    var homeHeld by remember { mutableStateOf(false) }

    // What Heylana caught this week: asked for once, shown at most once a week.
    val week = remember { WeekModel(activity.applicationContext, settings, WalletApi(settings), scope) }
    LaunchedEffect(Unit) { week.load() }

    Box(Modifier.fillMaxSize()) {
    if (screen == Screen.HOME || homeHeld) HomeScreen(
            name = settings.callMe,
            chat = chat,
            muted = muted,
            onMenu = {
                HeylanaLog.state("app: menu opened")
                menuOpen = true
            },
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
            week = week,
            watching = buddyOn && settings.watchSigning,
            onAskAboutScreen = { askAboutScreen(activity, chat) },
            onLearn = { onScreen(Screen.LEARN) }
        )
    when (screen) {
        Screen.VOICE -> VoiceScreen(
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
        Screen.SKILLS -> if (Features.SKILL_MARKET) {
            SkillMarketScreen(settings, onBack = { onScreen(Screen.HOME) }, onChanged = { skillsVersion++ })
        } else {
            LaunchedEffect(Unit) { onScreen(Screen.HOME) }
        }
        Screen.ADVANCED -> AdvancedScreen(settings, onBack = { onScreen(Screen.HOME) })
        Screen.LEARN -> LearnScreen(
            notes = chat.lessons.notes,
            onPick = { note ->
                HeylanaLog.state("app: lesson picked topic=${note.id}")
                onScreen(Screen.HOME)
                chat.startLesson(note)
            },
            onBack = { onScreen(Screen.HOME) }
        )
        Screen.MEMORY -> MemoryScreen(settings, onBack = { onScreen(Screen.HOME) })
        Screen.PRIVACY -> PrivacyScreen(settings.voiceProvider, onBack = { onScreen(Screen.HOME) }, assemblyai = settings.assemblyListening)
        Screen.SETTINGS -> AppSettingsScreen(
            onSeedWeek = {
                week.seed()
                onScreen(Screen.HOME)
            },
            settings = settings,
            buddyOn = buddyOn,
            onGlassMode = onGlassMode,
            onSample = { chat.sample(it) },
            onStanding = { standing = it },
            onStopBuddy = {
                HeylanaLog.state("app: buddy stopped from settings")
                BuddyOverlayService.stop(activity)
                buddyOn = false
            },
            onScreen = onScreen,
            onBack = { onScreen(Screen.HOME) }
        )
        else -> Unit
    }

    if (screen == Screen.HOME) {
        BackHandler(enabled = menuOpen) { menuOpen = false }
        // Counted only for the Skill market's row, and only while it is on.
        val skillsActive = remember(menuOpen, skillsVersion) {
            if (!Features.SKILL_MARKET) 0
            else SkillStore(activity, settings).rows().count { it.state == SkillCap.State.ACTIVE }
        }
        MenuSheet(
            open = menuOpen,
            buddyOn = buddyOn,
            standing = standing,
            skillsActive = skillsActive,
            name = settings.callMe,
            shortWallet = settings.walletSession?.shortAddress,
            onBuddy = { on ->
                if (on) {
                    if (!Settings.canDrawOverlays(activity)) {
                        menuOpen = false
                        chat.note(HomeChips.BUDDY_NEEDS_OVERLAY)
                    } else {
                        HeylanaLog.state("app: buddy started from the menu")
                        BuddyOverlayService.start(activity)
                        buddyOn = true
                    }
                } else {
                    HeylanaLog.state("app: buddy stopped from the menu")
                    BuddyOverlayService.stop(activity)
                    buddyOn = false
                }
            },
            onGoPro = {
                menuOpen = false
                activity.startActivity(
                    Intent(activity, SettingsActivity::class.java).putExtra(SettingsActivity.EXTRA_GO_PRO, true)
                )
            },
            onPlan = {
                menuOpen = false
                // The plan itself: what you are on, how much of it is left, and Go Pro to tap.
                activity.startActivity(Intent(activity, SettingsActivity::class.java))
            },
            onScreen = { next ->
                menuOpen = false
                onScreen(next)
            },
            onClose = { menuOpen = false }
        )
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
