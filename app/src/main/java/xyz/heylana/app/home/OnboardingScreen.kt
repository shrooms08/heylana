package xyz.heylana.app.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import xyz.heylana.app.HeylanaLog
import xyz.heylana.app.brain.SpokenName
import xyz.heylana.app.settings.HeylanaSettings
import xyz.heylana.app.ui.app.AccentButton
import xyz.heylana.app.ui.app.FlatField
import xyz.heylana.app.ui.app.FlatPage
import xyz.heylana.app.ui.app.FlatRow
import xyz.heylana.app.ui.app.Glyph
import xyz.heylana.app.ui.app.Icon
import xyz.heylana.app.ui.app.LibraryOrb
import xyz.heylana.app.ui.app.OrbMode
import xyz.heylana.app.ui.app.tap
import xyz.heylana.app.ui.theme.HeylanaType
import xyz.heylana.app.ui.theme.LocalHeylana
import xyz.heylana.app.voice.HeylanaVoice
import xyz.heylana.app.wallet.Answer
import xyz.heylana.app.wallet.Cluster
import xyz.heylana.app.wallet.MAX_NAME
import xyz.heylana.app.wallet.SeedVault
import xyz.heylana.app.wallet.SignIn
import xyz.heylana.app.wallet.WalletApi
import xyz.heylana.app.wallet.cleanName
import xyz.heylana.app.overlay.BuddyOverlayService

/**
 * The first run she speaks. [OnboardingFlow] holds the order and the words; this draws them
 * and plays them, and never waits on the audio — every step works with the voice silent, so
 * no network and no voice allowance still leaves a first run that reaches Home.
 *
 * A touch anywhere stops whatever she is saying: the listener sits on the gesture's first
 * pass, so it fires before any button underneath has had it.
 */
@Composable
fun OnboardingScreen(
    settings: HeylanaSettings,
    seedVault: SeedVault,
    permissions: PermissionState,
    onPermissionRow: (PermissionTarget) -> Unit,
    onDone: () -> Unit
) {
    val palette = LocalHeylana.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val api = remember { WalletApi(settings) }
    val voice = remember { HeylanaVoice(context, settings, scope, onSpeaking = {}) }
    DisposableEffect(Unit) { onDispose { voice.stop() } }

    var step by remember { mutableStateOf(OnboardingStep.WELCOME) }
    var shown by remember { mutableStateOf(OnboardingText.WELCOME) }
    // What the permissions were last time she looked, so a grant speaks exactly once.
    var seen by remember { mutableStateOf(permissions) }

    fun say(line: String) {
        shown = line
        voice.stop()
        voice.speak(line)
    }

    fun go(next: OnboardingStep) {
        step = next
        HeylanaLog.state("onboarding: step=${next.name.lowercase()}")
        OnboardingFlow.arrivingAt(next)?.let { moment ->
            OnboardingFlow.line(moment)?.let { say(it) }
        }
    }

    LaunchedEffect(Unit) {
        HeylanaLog.state("onboarding: started")
        say(OnboardingText.WELCOME)
    }

    // Granted while the rows are up: screen reading, then the overlay — the orb's moment.
    // "That's me" is said as the buddy is started, so there is really an orb on the edge
    // of the screen to mean; starting it needs the permission that has just been given.
    LaunchedEffect(permissions) {
        if (step == OnboardingStep.PERMISSIONS) {
            OnboardingFlow.granted(seen, permissions).forEach { moment ->
                if (moment == Moment.ORB) {
                    runCatching { BuddyOverlayService.start(context) }
                        .onFailure { HeylanaLog.state("onboarding: buddy would not start yet") }
                }
                OnboardingFlow.line(moment)?.let { say(it) }
            }
        }
        seen = permissions
    }

    FlatPage {
        Column(
            Modifier.fillMaxSize()
                // Before anything else gets the touch: she stops talking the moment you act.
                .pointerInput(Unit) {
                    awaitPointerEventScope {
                        while (true) {
                            awaitPointerEvent(PointerEventPass.Initial)
                            voice.stop()
                        }
                    }
                }
                .statusBarsPadding().navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 26.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(Modifier.height(44.dp))
            LibraryOrb(if (step == OnboardingStep.WELCOME) OrbMode.SPEAKING else OrbMode.IDLE, diameter = 120.dp)
            Spacer(Modifier.height(26.dp))
            Text(
                shown, style = HeylanaType.bodyLight, color = palette.ink,
                textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(26.dp))

            when (step) {
                OnboardingStep.WELCOME -> AccentButton("Let's go", { go(OnboardingStep.PERMISSIONS) }, Modifier.fillMaxWidth(), height = 54.dp)

                OnboardingStep.PERMISSIONS -> PermissionRows(permissions, onPermissionRow) { go(OnboardingStep.WALLET) }

                OnboardingStep.WALLET -> WalletStep(settings, seedVault, api, scope,
                    onConnected = { say(OnboardingText.WALLET) },
                    onNext = { go(OnboardingStep.NAME) })

                OnboardingStep.NAME -> NameStep(settings, api, scope, voice,
                    onSay = { say(it) },
                    onNext = { go(OnboardingStep.SIGNING) })

                OnboardingStep.SIGNING -> AccentButton("Got it", { go(OnboardingStep.HANDOVER) }, Modifier.fillMaxWidth(), height = 54.dp)

                OnboardingStep.HANDOVER -> AccentButton("Start using Heylana", {
                    voice.stop()
                    onDone()
                }, Modifier.fillMaxWidth(), height = 54.dp)

                OnboardingStep.DONE -> Unit
            }

            Spacer(Modifier.height(18.dp))
            // Always there, from the first screen: skipping leaves a working app.
            Text(
                if (step == OnboardingStep.HANDOVER) "" else "Skip",
                style = HeylanaType.label, color = palette.inkTertiary,
                modifier = Modifier.tap(enabled = step != OnboardingStep.HANDOVER) {
                    HeylanaLog.state("onboarding: skipped at=${step.name.lowercase()}")
                    voice.stop()
                    onDone()
                }.padding(10.dp)
            )
            Spacer(Modifier.height(24.dp))
        }
    }
}

/** The permission rows, with the same one-line reason each has always had. */
@Composable
private fun PermissionRows(state: PermissionState, onRow: (PermissionTarget) -> Unit, onNext: () -> Unit) {
    val palette = LocalHeylana.current
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        PermissionsModel.rowsFor(state).forEach { row ->
            FlatRow(
                row.title, subtitle = row.why,
                glyph = when (row.target) {
                    PermissionTarget.OVERLAY -> Glyph.LAYERS
                    PermissionTarget.SCREEN_READING -> Glyph.EYE
                    PermissionTarget.NOTIFICATIONS -> Glyph.BELL
                    PermissionTarget.MICROPHONE -> Glyph.MIC
                },
                onClick = { onRow(row.target) }
            ) {
                if (row.on) Icon(Glyph.CHECK, palette.good, size = 20.dp)
                else Text(if (row.optional) "Optional" else "Allow", style = HeylanaType.label, color = palette.accentText)
            }
        }
        Spacer(Modifier.height(6.dp))
        AccentButton("Continue", onNext, Modifier.fillMaxWidth(), height = 54.dp)
    }
}

/** Sign in with the wallet, or go on without one; her line is said the moment it connects. */
@Composable
private fun WalletStep(
    settings: HeylanaSettings,
    seedVault: SeedVault,
    api: WalletApi,
    scope: kotlinx.coroutines.CoroutineScope,
    onConnected: () -> Unit,
    onNext: () -> Unit
) {
    val palette = LocalHeylana.current
    var signedIn by remember { mutableStateOf(settings.walletSession != null) }
    var working by remember { mutableStateOf(false) }
    var line by remember { mutableStateOf("") }
    var cluster by remember { mutableStateOf(Cluster.MAINNET) }
    LaunchedEffect(Unit) { (api.me() as? Answer.Ok)?.let { cluster = it.value.cluster } }

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        AccentButton(
            when {
                signedIn -> "Wallet connected"
                working -> "Waiting for Seed Vault…"
                else -> "Sign in with wallet"
            },
            {
                working = true
                line = ""
                scope.launch {
                    when (val result = SignIn.connect(settings, seedVault, api, cluster)) {
                        is SignIn.Result.Done -> {
                            signedIn = true
                            onConnected()
                        }
                        is SignIn.Result.Stopped -> line = result.words
                    }
                    working = false
                }
            },
            Modifier.fillMaxWidth(), enabled = !working && !signedIn, height = 54.dp
        )
        Text(
            line.ifEmpty { "Seed Vault signs one message to prove the wallet is yours — no transaction, no funds moved." },
            style = HeylanaType.small, color = palette.inkTertiary, textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth()
        )
        Text(
            if (signedIn) "Continue" else "Continue without a wallet",
            style = HeylanaType.label, color = palette.accentText,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().tap { onNext() }.padding(8.dp)
        )
    }
}

/**
 * What to call them, and how to say it. The name on screen is never changed by this step —
 * only [HeylanaSettings.spokenName], which nothing but the voice ever reads.
 */
@Composable
private fun NameStep(
    settings: HeylanaSettings,
    api: WalletApi,
    scope: kotlinx.coroutines.CoroutineScope,
    voice: HeylanaVoice,
    onSay: (String) -> Unit,
    onNext: () -> Unit
) {
    val palette = LocalHeylana.current
    var name by remember { mutableStateOf(settings.callMe) }
    var asked by remember { mutableStateOf(false) }
    var fixing by remember { mutableStateOf(false) }
    var respelling by remember { mutableStateOf("") }
    var tries by remember { mutableStateOf(0) }
    var saving by remember { mutableStateOf(false) }

    // The Seeker's own name for the wallet, when the worker has one.
    LaunchedEffect(Unit) {
        if (name.isBlank()) (api.profile() as? Answer.Ok)?.let { name = it.value.suggestion }
    }
    // Once there is a name to try, she says it and asks whether she got it right.
    LaunchedEffect(name) {
        if (!asked && cleanName(name).isNotEmpty()) {
            asked = true
            onSay(OnboardingText.nameCheck(cleanName(name)))
        }
    }

    fun keep(spoken: String, then: () -> Unit) {
        saving = true
        scope.launch {
            val clean = cleanName(name)
            settings.spokenName = spoken
            if (settings.walletSession != null) {
                when (val saved = api.saveProfile(clean)) {
                    is Answer.Ok -> settings.callMe = saved.value.callMe
                    else -> settings.callMe = clean
                }
            } else {
                settings.callMe = clean
            }
            HeylanaLog.state("onboarding: name kept spoken=${spoken.isNotEmpty()} tries=$tries")
            saving = false
            then()
        }
    }

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        FlatField(name, { name = it.take(MAX_NAME) }, "Your name", onAction = {})

        if (!fixing) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                AccentButton(
                    if (saving) "Saving…" else "Yes",
                    { keep(settings.spokenName) { onNext() } },
                    Modifier.weight(1f), enabled = !saving && cleanName(name).isNotEmpty(), height = 52.dp
                )
                Box(Modifier.weight(1f)) {
                    Text(
                        "Not quite", style = HeylanaType.bodyMedium, color = palette.accentText,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().tap(enabled = !saving) { fixing = true }.padding(14.dp)
                    )
                }
            }
        } else {
            Text(
                "Type how it sounds, or hold the mic on Home later. For example: ${OnboardingText.RESPELL_HINT}",
                style = HeylanaType.small, color = palette.inkSecondary
            )
            FlatField(respelling, { respelling = SpokenName.cleanRespelling(it) }, OnboardingText.RESPELL_HINT, onAction = {})
            Text(OnboardingText.RESPELL_HOW, style = HeylanaType.small, color = palette.inkTertiary)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                AccentButton(
                    "Say it back",
                    {
                        tries += 1
                        onSay(OnboardingText.readBack(respelling))
                    },
                    Modifier.weight(1f), enabled = respelling.isNotBlank() && tries < PRONUNCIATION_TRIES, height = 52.dp
                )
                Box(Modifier.weight(1f)) {
                    Text(
                        if (saving) "Saving…" else "Keep it",
                        style = HeylanaType.bodyMedium, color = palette.accentText, textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth()
                            .tap(enabled = !saving && cleanName(name).isNotEmpty()) { keep(respelling) { onNext() } }
                            .padding(14.dp)
                    )
                }
            }
            if (tries >= PRONUNCIATION_TRIES) {
                Text(OnboardingText.GOOD_ENOUGH, style = HeylanaType.small, color = palette.inkTertiary)
            }
        }
    }
}
