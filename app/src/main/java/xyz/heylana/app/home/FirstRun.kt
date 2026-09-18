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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import xyz.heylana.app.settings.HeylanaSettings
import xyz.heylana.app.overlay.BuddyOverlayService
import xyz.heylana.app.ui.app.AccentButton
import xyz.heylana.app.ui.app.FlatField
import xyz.heylana.app.ui.app.FlatPage
import xyz.heylana.app.ui.app.FlatRow
import xyz.heylana.app.ui.app.FlatSurface
import xyz.heylana.app.ui.app.FlatSwitch
import xyz.heylana.app.ui.app.Glyph
import xyz.heylana.app.ui.app.Icon
import xyz.heylana.app.ui.app.LibraryOrb
import xyz.heylana.app.ui.app.OrbMode
import xyz.heylana.app.ui.app.SectionHead
import xyz.heylana.app.ui.app.tap
import xyz.heylana.app.ui.theme.HeylanaType
import xyz.heylana.app.ui.theme.LocalHeylana
import xyz.heylana.app.wallet.Answer
import xyz.heylana.app.wallet.Cluster
import xyz.heylana.app.wallet.SeedVault
import xyz.heylana.app.wallet.SignIn
import xyz.heylana.app.wallet.WalletApi
import xyz.heylana.app.wallet.cleanName

/**
 * First run, frame 6 of the export: sign in with the wallet — Seed Vault signs one message,
 * no transaction — then the name card grows out of the button (gooey) for "What should I
 * call you?". With no wallet app, it can go on without one.
 */
@Composable
fun SignInScreen(
    settings: HeylanaSettings,
    seedVault: SeedVault,
    onNamed: () -> Unit
) {
    val palette = LocalHeylana.current
    val scope = rememberCoroutineScope()
    val api = remember { WalletApi(settings) }
    var signedIn by remember { mutableStateOf(settings.walletSession != null) }
    var working by remember { mutableStateOf(false) }
    var line by remember { mutableStateOf("") }
    var withoutWallet by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf(settings.callMe) }
    var cluster by remember { mutableStateOf(Cluster.MAINNET) }

    // Which Solana the worker is on decides which network Seed Vault is asked for.
    LaunchedEffect(Unit) {
        (api.me() as? Answer.Ok)?.let { cluster = it.value.cluster }
    }
    // Signed in: the name the wallet already has, if any.
    LaunchedEffect(signedIn) {
        if (signedIn && name.isBlank()) (api.profile() as? Answer.Ok)?.let { name = it.value.suggestion }
    }

    FlatPage {
        Column(
            Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().verticalScroll(rememberScrollState())
                .padding(horizontal = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(Modifier.height(70.dp))
            LibraryOrb(OrbMode.IDLE, diameter = 150.dp)
            Spacer(Modifier.height(40.dp))
            Text("Let's get you set up", style = HeylanaType.display.copy(fontSize = HeylanaType.display.fontSize * 0.9f), color = palette.ink, textAlign = TextAlign.Center)
            Spacer(Modifier.height(8.dp))
            Text(
                "No email, no password. Your wallet is the account.", style = HeylanaType.bodyLight,
                color = palette.inkSecondary, textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(24.dp))
            FlatSurface(Modifier.fillMaxWidth().height(60.dp).tap(enabled = !working && !signedIn) {
                    working = true
                    line = ""
                    scope.launch {
                        when (val result = SignIn.connect(settings, seedVault, api, cluster)) {
                            is SignIn.Result.Done -> {
                                signedIn = true
                                if (result.welcomeGranted) line = "20 welcome talks added"
                            }
                            is SignIn.Result.Stopped -> {
                                line = result.words
                                withoutWallet = withoutWallet || result.noWallet
                            }
                        }
                        working = false
                    }
                },
                radius = 30.dp,
                fill = palette.surfaceHigh
            ) {
                Row(Modifier.align(Alignment.Center), verticalAlignment = Alignment.CenterVertically) {
                    Icon(if (signedIn) Glyph.CHECK else Glyph.WALLET, if (signedIn) palette.good else palette.ink, size = 22.dp)
                    Spacer(Modifier.width(12.dp))
                    Text(
                        when {
                            signedIn -> "Wallet connected"
                            working -> "Waiting for Seed Vault…"
                            else -> "Sign in with wallet"
                        },
                        style = HeylanaType.bodyMedium, color = palette.ink
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
            Text(
                line.ifEmpty { "Seed Vault signs one message to prove the wallet is yours — no transaction, no funds moved." },
                style = HeylanaType.small, color = palette.inkTertiary, textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 12.dp)
            )
            if (!signedIn && !withoutWallet) {
                Spacer(Modifier.height(10.dp))
                Text(
                    "Continue without a wallet", style = HeylanaType.label, color = palette.accentSoft,
                    modifier = Modifier.tap { withoutWallet = true }.padding(8.dp)
                )
            }
            Spacer(Modifier.height(14.dp))
            AnimatedVisibility(signedIn || withoutWallet, enter = fadeIn(), exit = fadeOut()) {
                FlatSurface(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        SectionHead("What should I call you?")
                        FlatField(name, { name = it.take(MAX_NAME) }, "Your name", onAction = {})
                        // Opt-in, off until switched on: memory lives with the wallet, so only with one.
                        var keepNotes by remember { mutableStateOf(false) }
                        if (settings.walletSession != null) {
                            FlatRow(
                                "Remember what I tell you",
                                subtitle = FirstRunText.MEMORY_OPT_IN,
                                card = false
                            ) { FlatSwitch(keepNotes, { keepNotes = it }) }
                        }
                        var saving by remember { mutableStateOf(false) }
                        AccentButton(if (saving) "Saving…" else "Continue", enabled = !saving && cleanName(name).isNotEmpty(), onClick = {
                            saving = true
                            scope.launch {
                                val clean = cleanName(name)
                                if (settings.walletSession != null) {
                                    when (val saved = api.saveProfile(clean)) {
                                        is Answer.Ok -> settings.callMe = saved.value.callMe
                                        else -> settings.callMe = clean
                                    }
                                    val consent = api.memoryConsent(keepNotes)
                                    settings.memoryOn = consent is Answer.Ok && consent.value.on
                                    xyz.heylana.app.HeylanaLog.state("memory: first run on=${settings.memoryOn}")
                                } else {
                                    settings.callMe = clean
                                }
                                saving = false
                                onNamed()
                            }
                        })
                    }
                }
            }
            Spacer(Modifier.weight(1f, fill = true))
            Spacer(Modifier.height(24.dp))
            Row(verticalAlignment = Alignment.Top) {
                Icon(Glyph.SHIELD, palette.inkTertiary, size = 16.dp)
                Spacer(Modifier.width(10.dp))
                Text(
                    "Reads your screen only when you ask. Every transaction is approved in Seed Vault.",
                    style = HeylanaType.small, color = palette.inkTertiary
                )
            }
            Spacer(Modifier.height(20.dp))
        }
    }
}

private const val MAX_NAME = 40

object FirstRunText {
    /** The one sentence memory is asked for with. */
    const val MEMORY_OPT_IN =
        "Heylana keeps short notes you ask it to, for this wallet only, and never what's on your screen."
}

/**
 * The four things Heylana asks for, each with its one-line why and the system's live
 * answer. A row opens the right system page; the screen re-checks every time it resumes.
 */
@Composable
fun PermissionsScreen(rows: List<PermissionRow>, required: Boolean, onRow: (PermissionTarget) -> Unit, onDone: () -> Unit) {
    val palette = LocalHeylana.current
    FlatPage {
        Column(
            Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(horizontal = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Spacer(Modifier.height(64.dp))
            Text("A few switches", style = HeylanaType.title, color = palette.ink)
            Text("Heylana asks for these once. Each opens the right page in Settings.", style = HeylanaType.bodyLight, color = palette.inkSecondary)
            Spacer(Modifier.height(12.dp))
            rows.forEach { row ->
                FlatRow(row.title, subtitle = row.why,
                    glyph = when (row.target) {
                        PermissionTarget.OVERLAY -> Glyph.LAYERS
                        PermissionTarget.SCREEN_READING -> Glyph.EYE
                        PermissionTarget.NOTIFICATIONS -> Glyph.BELL
                        PermissionTarget.MICROPHONE -> Glyph.MIC
                    },
                    onClick = { onRow(row.target) }
                ) {
                    if (row.on) {
                        Icon(Glyph.CHECK, palette.good, size = 20.dp)
                    } else {
                        Text(if (row.optional) "Optional" else "Allow", style = HeylanaType.label, color = palette.accentSoft)
                    }
                }
            }
            Spacer(Modifier.weight(1f))
            // The promise the notification makes too; the code keeps it (CLAUDE.md, events).
            Row(Modifier.padding(bottom = 16.dp), verticalAlignment = Alignment.Top) {
                Icon(Glyph.SHIELD, palette.inkTertiary, size = 16.dp)
                Spacer(Modifier.width(10.dp))
                Text(
                    "Heylana ${BuddyOverlayService.PRIVACY_LINE}.",
                    style = HeylanaType.small, color = palette.inkTertiary
                )
            }
            AccentButton(if (required) "Continue" else "Continue for now", onDone, height = 54.dp)
            Spacer(Modifier.height(24.dp))
        }
    }
}
