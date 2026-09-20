package xyz.heylana.app.settings

import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.ui.graphics.Color
import java.time.Instant
import kotlinx.coroutines.launch
import xyz.heylana.app.wallet.BuildText
import xyz.heylana.app.wallet.ConfirmPoll
import xyz.heylana.app.wallet.SimulationResult
import xyz.heylana.app.wallet.PayOutcome
import xyz.heylana.app.wallet.PlanText
import xyz.heylana.app.wallet.ProPayment
import xyz.heylana.app.wallet.Quote
import xyz.heylana.app.wallet.Standing
import xyz.heylana.app.BuildConfig
import xyz.heylana.app.ui.GlassButton
import xyz.heylana.app.ui.GlassCard
import xyz.heylana.app.ui.HeylanaTokens
import xyz.heylana.app.ui.glassText
import xyz.heylana.app.wallet.Answer
import xyz.heylana.app.wallet.Cluster
import xyz.heylana.app.wallet.MAX_NAME
import xyz.heylana.app.wallet.SeedVault
import xyz.heylana.app.wallet.WalletApi
import xyz.heylana.app.wallet.WalletProblem
import xyz.heylana.app.wallet.WalletSession
import xyz.heylana.app.voice.HeylanaVoice
import xyz.heylana.app.ui.theme.HeylanaTheme

/**
 * The settings screen.
 *
 * Nothing here is needed to use Heylana any more: the keys live in the proxy.
 * What is left is the voice, what leaves the phone, and an advanced drawer for
 * people who want to point the app somewhere else or pay for their own answers.
 */
class SettingsActivity : ComponentActivity() {

    /** Only here to say one short line when a voice is picked. */
    private var sample: HeylanaVoice? = null

    /** Created with the activity, as Mobile Wallet Adapter requires. */
    private lateinit var seedVault: SeedVault
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val settings = HeylanaSettings.get(this)
        seedVault = SeedVault(this)
        sample = HeylanaVoice(
            context = this,
            settings = settings,
            scope = scope,
            onSpeaking = { }
        )
        setContent {
            HeylanaTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    SettingsScreen(
                        settings = settings,
                        seedVault = seedVault,
                        onSample = {
                            // A new pick replaces the sample still playing rather than queueing behind it.
                            sample?.stop()
                            sample?.speak(SAMPLE_LINE)
                        },
                        onDone = { finish() },
                        startGoPro = intent.getBooleanExtra(EXTRA_GO_PRO, false),
                        modifier = Modifier.padding(innerPadding)
                    )
                }
            }
        }
    }

    override fun onDestroy() {
        sample?.shutdown()
        sample = null
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        /** Three words, so hearing a voice costs as little as it can. */
        private const val SAMPLE_LINE = "Hi, I'm Heylana."

        /** Opens straight onto the Go Pro sheet: the app's menu sends people here to pay. */
        const val EXTRA_GO_PRO = "go_pro"
    }
}

@Composable
private fun SettingsScreen(
    settings: HeylanaSettings,
    seedVault: SeedVault,
    onSample: () -> Unit,
    onDone: () -> Unit,
    startGoPro: Boolean = false,
    modifier: Modifier = Modifier
) {
    var showSpokenText by remember { mutableStateOf(settings.showTextForVoice) }
    var advanced by remember { mutableStateOf(false) }

    val api = remember { WalletApi(settings) }
    var session by remember { mutableStateOf(settings.walletSession) }
    var standing by remember { mutableStateOf<Standing?>(null) }
    var planProblem by remember { mutableStateOf("") }
    var goPro by remember { mutableStateOf(startGoPro) }

    // The buddy picks skills without asking the worker, so it keeps the plan's cap.
    LaunchedEffect(standing) {
        standing?.let { settings.skillsCap = it.skillsCap }
        // The voice follows the worker too: the privacy line and the picker's names.
        standing?.voice?.let { settings.rememberVoice(it.provider, it.skylar, it.archie, it.ears) }
    }

    // Whenever the wallet changes: claim any payment still waiting, then ask where we stand.
    LaunchedEffect(session) {
        if (session != null) settlePendingPayment(settings, api)
        when (val answer = api.me()) {
            is Answer.Ok -> {
                standing = answer.value
                planProblem = ""
            }
            is Answer.Refused -> planProblem = WalletProblem.fromWorker(answer.reason).words
            is Answer.Unreachable -> planProblem = WalletProblem.UNREACHABLE.words
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(text = "Settings", style = MaterialTheme.typography.headlineMedium)

        WalletCard(
            settings = settings,
            seedVault = seedVault,
            api = api,
            session = session,
            cluster = standing?.cluster ?: Cluster.MAINNET,
            onSession = { session = it },
            onStanding = { standing = it }
        )

        PlanCard(
            standing = standing,
            problem = planProblem,
            connected = session != null,
            onGoPro = { goPro = true }
        )

        val payer = session
        if (goPro && payer != null) {
            GoProSheet(
                settings = settings,
                api = api,
                seedVault = seedVault,
                session = payer,
                cluster = standing?.cluster ?: Cluster.MAINNET,
                onPaid = {
                    standing = it
                    goPro = false
                },
                onClose = { goPro = false }
            )
        }

        val context = LocalContext.current
        if (xyz.heylana.app.Features.SKILL_MARKET) GlassButton(
            text = "Skills",
            onClick = { context.startActivity(Intent(context, xyz.heylana.app.skills.SkillsActivity::class.java)) },
            modifier = Modifier.fillMaxWidth()
        )

        // What /me just said wins over what was kept from last time.
        val heardVoice = standing?.voice
        VoiceCard(
            settings = settings,
            skylarName = heardVoice?.skylar ?: settings.voiceName(HeylanaSettings.VOICE_SKYLAR),
            archieName = heardVoice?.archie ?: settings.voiceName(HeylanaSettings.VOICE_ARCHIE),
            onSample = onSample
        )

        var darkerGlass by remember { mutableStateOf(settings.darkerGlass) }
        SwitchCard(
            title = "Darker glass",
            detail = "Off by default. Adds a dark tint under Heylana's glass so its words " +
                "stay easy to read if you mostly use light apps.",
            checked = darkerGlass,
            onCheckedChange = {
                darkerGlass = it
                settings.darkerGlass = it
                xyz.heylana.app.ui.GlassSpec.darkerGlass = it
            }
        )

        SwitchCard(
            title = "Show spoken answers as text",
            detail = "Off by default. When you ask by holding the buddy, Heylana answers " +
                "out loud without showing the words.",
            checked = showSpokenText,
            onCheckedChange = {
                showSpokenText = it
                settings.showTextForVoice = it
            }
        )

        PrivacyCard(provider = standing?.voice?.provider ?: settings.voiceProvider, assemblyai = settings.assemblyListening)

        OutlinedButton(
            onClick = { advanced = !advanced },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(text = if (advanced) "Hide advanced" else "Advanced")
        }

        if (advanced) {
            AdvancedSection(settings = settings, api = api, onStanding = { standing = it })
        }

        if (BuildConfig.DEBUG) {
            DebugSection(settings = settings)
        }

        OutlinedButton(onClick = onDone, modifier = Modifier.fillMaxWidth()) {
            Text(text = "Back")
        }

        VersionLine()
    }
}

/**
 * "Heylana 0.9.0 (1)". In debug builds a long press crashes the app on purpose, so a
 * crash report can be checked end to end; the message carries a made-up address
 * that has to arrive in Sentry as [address].
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun VersionLine() {
    val text = "Heylana ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})"
    val style = MaterialTheme.typography.bodySmall
    if (!BuildConfig.DEBUG) {
        Text(text = text, style = style)
        return
    }
    Text(
        text = text,
        style = style,
        modifier = Modifier.combinedClickable(
            onClick = {},
            onLongClick = { throw TestCrash() }
        )
    )
}

/** Debug builds only. The address is made up, and must not survive the scrubbing. */
private class TestCrash : RuntimeException(
    "Heylana test crash. This address must arrive as [address]: 7c2y8xXRFYVamzNJ11hX3sicHexPHNuDwpiJ6sEnSxSv"
)

/**
 * Which voice reads the answers out. Picking one says a short line in it, so the
 * choice is made by ear rather than by name.
 */
@Composable
private fun VoiceCard(settings: HeylanaSettings, skylarName: String, archieName: String, onSample: () -> Unit) {
    var chosen by remember { mutableStateOf(settings.voice) }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(text = "Voice", style = MaterialTheme.typography.titleMedium)
            Spacer(modifier = Modifier.height(8.dp))

            val pick: (String) -> Unit = { voice ->
                chosen = voice
                settings.voice = voice
                onSample()
            }

            ChoiceRow(
                label = skylarName,
                detail = "Warm and clear. Heylana's own voice.",
                selected = chosen == HeylanaSettings.VOICE_SKYLAR,
                onSelect = { pick(HeylanaSettings.VOICE_SKYLAR) }
            )
            ChoiceRow(
                label = archieName,
                detail = "Warm and friendly.",
                selected = chosen == HeylanaSettings.VOICE_ARCHIE,
                onSelect = { pick(HeylanaSettings.VOICE_ARCHIE) }
            )
        }
    }
}

/** One choice out of a short list, with a line of explanation under it. */
@Composable
private fun ChoiceRow(
    label: String,
    detail: String,
    selected: Boolean,
    onSelect: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(selected = selected, onClick = onSelect)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(selected = selected, onClick = onSelect)
        Spacer(modifier = Modifier.fillMaxWidth(0.03f))
        Column {
            Text(text = label, style = MaterialTheme.typography.bodyLarge)
            Text(text = detail, style = MaterialTheme.typography.bodySmall)
        }
    }
}

/**
 * Connect a wallet: Seed Vault asks to connect, then to sign a sign-in message.
 * Never a transaction. Afterwards the short address and Disconnect.
 */
@Composable
private fun WalletCard(
    settings: HeylanaSettings,
    seedVault: SeedVault,
    api: WalletApi,
    session: WalletSession?,
    cluster: Cluster,
    onSession: (WalletSession?) -> Unit,
    onStanding: (Standing) -> Unit
) {
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var line by remember { mutableStateOf("") }
    var callMe by remember { mutableStateOf(settings.callMe) }
    var askName by remember { mutableStateOf(false) }

    // The name lives with the wallet's profile; keep the phone's copy current.
    LaunchedEffect(session) {
        if (session == null) return@LaunchedEffect
        val answer = api.profile()
        if (answer is Answer.Ok && !askName) {
            settings.callMe = answer.value.callMe
            callMe = settings.callMe
        }
    }

    GlassCard {
        Text(text = "Wallet", style = glassText(HeylanaTokens.TITLE_SP, HeylanaTokens.textPrimary))
        Spacer(modifier = Modifier.height(HeylanaTokens.SPACE_2_DP.dp))

        if (session == null) {
            Text(
                text = "Connect with Seed Vault to keep your plan with your wallet. " +
                    "Connecting only signs a message; it never moves funds.",
                style = glassText(HeylanaTokens.LABEL_SP, HeylanaTokens.textSecondary)
            )
            Spacer(modifier = Modifier.height(HeylanaTokens.SPACE_4_DP.dp))
            GlassButton(
                text = if (busy) "Waiting for Seed Vault\u2026" else "Connect wallet",
                primary = true,
                enabled = !busy,
                onClick = {
                    busy = true
                    line = ""
                    scope.launch {
                        line = connect(settings, seedVault, api, cluster) { connected, standing ->
                            onSession(connected)
                            onStanding(standing)
                            askName = true
                        }
                        busy = false
                    }
                }
            )
        } else {
            if (callMe.isNotBlank()) {
                Text(text = callMe, style = glassText(HeylanaTokens.BODY_SP, HeylanaTokens.textPrimary))
                Text(
                    text = session.shortAddress,
                    style = glassText(HeylanaTokens.LABEL_SP, HeylanaTokens.textSecondary)
                )
            } else {
                Text(
                    text = session.shortAddress,
                    style = glassText(HeylanaTokens.BODY_SP, HeylanaTokens.textPrimary)
                )
            }
            Spacer(modifier = Modifier.height(HeylanaTokens.SPACE_4_DP.dp))
            GlassButton(
                text = "Disconnect",
                onClick = {
                    settings.walletSession = null
                    settings.callMe = ""
                    callMe = ""
                    onSession(null)
                    line = ""
                }
            )
        }

        if (line.isNotEmpty()) {
            Spacer(modifier = Modifier.height(HeylanaTokens.SPACE_3_DP.dp))
            Text(text = line, style = glassText(HeylanaTokens.LABEL_SP, HeylanaTokens.textSecondary))
        }
    }

    if (askName) {
        NameSheet(settings = settings, api = api) { chosen ->
            callMe = chosen
            askName = false
        }
    }
}

/**
 * Straight after a wallet connects: what should Heylana call them? One field,
 * prefilled with what they chose before, else their .skr name, else empty.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NameSheet(settings: HeylanaSettings, api: WalletApi, onDone: (String) -> Unit) {
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf("") }
    var ready by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var line by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        val answer = api.profile()
        if (answer is Answer.Ok) name = answer.value.suggestion
        ready = true
    }

    ModalBottomSheet(
        onDismissRequest = { if (!saving) onDone(settings.callMe) },
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = Color.Transparent,
        dragHandle = null
    ) {
        GlassCard(
            modifier = Modifier.padding(
                horizontal = HeylanaTokens.SPACE_4_DP.dp,
                vertical = HeylanaTokens.SPACE_5_DP.dp
            )
        ) {
            Text(
                text = "What should I call you?",
                style = glassText(HeylanaTokens.TITLE_SP, HeylanaTokens.textPrimary)
            )
            Spacer(modifier = Modifier.height(HeylanaTokens.SPACE_4_DP.dp))
            OutlinedTextField(
                value = name,
                onValueChange = { name = it.take(MAX_NAME) },
                label = { Text(text = "Name") },
                singleLine = true,
                textStyle = glassText(HeylanaTokens.BODY_SP, HeylanaTokens.textPrimary),
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(modifier = Modifier.height(HeylanaTokens.SPACE_4_DP.dp))
            GlassButton(
                text = if (saving) "Saving\u2026" else "Save",
                primary = true,
                enabled = ready && !saving,
                onClick = {
                    saving = true
                    line = ""
                    scope.launch {
                        when (val answer = api.saveProfile(name)) {
                            is Answer.Ok -> {
                                settings.callMe = answer.value.callMe
                                onDone(answer.value.callMe)
                            }
                            is Answer.Refused -> line = WalletProblem.fromWorker(answer.reason).words
                            is Answer.Unreachable -> line = WalletProblem.UNREACHABLE.words
                        }
                        saving = false
                    }
                }
            )
            if (line.isNotEmpty()) {
                Spacer(modifier = Modifier.height(HeylanaTokens.SPACE_3_DP.dp))
                Text(text = line, style = glassText(HeylanaTokens.LABEL_SP, HeylanaTokens.textSecondary))
            }
        }
    }
}

/** One full connection: the wallet signs, the worker checks, the session is kept. */
private suspend fun connect(
    settings: HeylanaSettings,
    seedVault: SeedVault,
    api: WalletApi,
    cluster: Cluster,
    onConnected: (WalletSession, Standing) -> Unit
): String {
    val signedIn = when (val trip = seedVault.connect(api, cluster)) {
        is SeedVault.Trip.Done -> trip.value
        SeedVault.Trip.NoWallet -> return WalletProblem.NO_WALLET.words
        is SeedVault.Trip.Stopped -> return trip.problem.words
    }
    return when (val verified = api.verify(signedIn.pubkey, signedIn.nonce, signedIn.signature)) {
        is Answer.Ok -> {
            val session = WalletSession(signedIn.pubkey, verified.value.session)
            settings.walletSession = session
            onConnected(session, verified.value.standing)
            if (verified.value.welcomeGranted) "20 welcome talks added" else ""
        }
        is Answer.Refused -> WalletProblem.fromWorker(verified.reason).words
        is Answer.Unreachable -> WalletProblem.UNREACHABLE.words
    }
}

/** A payment sent earlier that had not confirmed in time: ask once more, quietly. */
private suspend fun settlePendingPayment(settings: HeylanaSettings, api: WalletApi) {
    val (reference, signature) = settings.pendingPayment ?: return
    // A blank signature means the wallet gave none: the worker finds it by its reference.
    when (val answer = api.confirm(reference, signature.ifBlank { null })) {
        is Answer.Ok -> settings.pendingPayment = null
        is Answer.Refused -> if (!ConfirmPoll.keepWaiting(answer)) settings.pendingPayment = null
        is Answer.Unreachable -> Unit
    }
}

/** Free, Pro or Judge; what the plan gives and what has been used; and how to go Pro. */
@Composable
private fun PlanCard(standing: Standing?, problem: String, connected: Boolean, onGoPro: () -> Unit) {
    GlassCard {
        Text(text = "Plan", style = glassText(HeylanaTokens.TITLE_SP, HeylanaTokens.textPrimary))
        Spacer(modifier = Modifier.height(HeylanaTokens.SPACE_2_DP.dp))

        if (standing == null) {
            Text(
                text = problem.ifEmpty { "Checking your plan\u2026" },
                style = glassText(HeylanaTokens.LABEL_SP, HeylanaTokens.textSecondary)
            )
            return@GlassCard
        }

        Text(
            text = PlanText.name(standing.plan),
            style = glassText(HeylanaTokens.BODY_SP, HeylanaTokens.textPrimary)
        )
        Spacer(modifier = Modifier.height(HeylanaTokens.SPACE_1_DP.dp))
        val lines = listOfNotNull(
            PlanText.summary(standing),
            PlanText.used(standing),
            PlanText.until(standing).takeIf { standing.plan == "pro" }
        )
        lines.forEach {
            Text(text = xyz.heylana.app.ui.theme.monoNumbers(it), style = glassText(HeylanaTokens.LABEL_SP, HeylanaTokens.textSecondary))
        }

        if (standing.plan == "free") {
            Spacer(modifier = Modifier.height(HeylanaTokens.SPACE_4_DP.dp))
            if (connected) {
                GlassButton(text = PlanText.GO_PRO, primary = true, onClick = onGoPro)
            } else {
                Text(
                    text = "Connect a wallet above to go Pro.",
                    style = glassText(HeylanaTokens.LABEL_SP, HeylanaTokens.textSecondary)
                )
            }
        }
    }
}

/**
 * Go Pro: pick USDC or SKR, see exactly what will be sent, Pay. Seed Vault shows
 * the transfer for approval; then this waits up to a minute for it to land.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GoProSheet(
    settings: HeylanaSettings,
    api: WalletApi,
    seedVault: SeedVault,
    session: WalletSession,
    cluster: Cluster,
    onPaid: (Standing) -> Unit,
    onClose: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val payment = remember { ProPayment(api, seedVault) }
    var currency by remember { mutableStateOf(CURRENCY_USDC) }
    var period by remember { mutableStateOf(PlanText.PERIOD_MONTH) }
    var quote by remember { mutableStateOf<Quote?>(null) }
    var line by remember { mutableStateOf("") }
    var paying by remember { mutableStateOf(false) }
    var refresh by remember { mutableStateOf(0) }
    // The payment is built and simulated before Pay can be tapped; null while it is checked.
    var simulation by remember { mutableStateOf<SimulationResult?>(null) }

    LaunchedEffect(currency, period, refresh) {
        quote = null
        simulation = null
        line = "Getting the price\u2026"
        when (val answer = api.quote(currency, period)) {
            is Answer.Ok -> {
                quote = answer.value
                line = BuildText.SIMULATING
                simulation = when (val built = api.build(id = null, reference = answer.value.reference, cluster = cluster, final = false)) {
                    is Answer.Ok -> built.value.simulation
                    is Answer.Refused -> SimulationResult.Failed(built.reason, built.detail.ifBlank { WalletProblem.fromWorker(built.reason).words })
                    is Answer.Unreachable -> SimulationResult.Failed("unreachable", WalletProblem.UNREACHABLE.words)
                }
                line = ""
            }
            is Answer.Refused -> line = WalletProblem.fromWorker(answer.reason).words
            is Answer.Unreachable -> line = WalletProblem.UNREACHABLE.words
        }
    }

    ModalBottomSheet(
        onDismissRequest = { if (!paying) onClose() },
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = Color.Transparent,
        dragHandle = null
    ) {
        GlassCard(
            modifier = Modifier.padding(
                horizontal = HeylanaTokens.SPACE_4_DP.dp,
                vertical = HeylanaTokens.SPACE_5_DP.dp
            )
        ) {
            Text(text = "Go Pro", style = glassText(HeylanaTokens.TITLE_SP, HeylanaTokens.textPrimary))
            Spacer(modifier = Modifier.height(HeylanaTokens.SPACE_2_DP.dp))
            Text(
                text = PlanText.periodDetail(period),
                style = glassText(HeylanaTokens.LABEL_SP, HeylanaTokens.textSecondary)
            )
            Spacer(modifier = Modifier.height(HeylanaTokens.SPACE_4_DP.dp))

            // A month or a year; the year says what it saves.
            Row(horizontalArrangement = Arrangement.spacedBy(HeylanaTokens.SPACE_2_DP.dp)) {
                GlassButton(
                    text = PlanText.periodLabel(PlanText.PERIOD_MONTH),
                    primary = period == PlanText.PERIOD_MONTH,
                    enabled = !paying,
                    onClick = { period = PlanText.PERIOD_MONTH }
                )
                GlassButton(
                    text = PlanText.periodLabel(PlanText.PERIOD_YEAR),
                    primary = period == PlanText.PERIOD_YEAR,
                    enabled = !paying,
                    onClick = { period = PlanText.PERIOD_YEAR }
                )
            }
            PlanText.yearSaving()?.let {
                Spacer(modifier = Modifier.height(HeylanaTokens.SPACE_1_DP.dp))
                Text(
                    text = xyz.heylana.app.ui.theme.monoNumbers("$it a year"),
                    style = glassText(HeylanaTokens.LABEL_SP, HeylanaTokens.success)
                )
            }
            Spacer(modifier = Modifier.height(HeylanaTokens.SPACE_3_DP.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(HeylanaTokens.SPACE_2_DP.dp)) {
                GlassButton(
                    text = "USDC",
                    primary = currency == CURRENCY_USDC,
                    enabled = !paying,
                    onClick = { currency = CURRENCY_USDC }
                )
                GlassButton(
                    text = "SKR",
                    primary = currency == CURRENCY_SKR,
                    enabled = !paying && cluster.hasSkr,
                    onClick = { if (cluster.hasSkr) currency = CURRENCY_SKR }
                )
            }
            PlanText.skrMissing(cluster)?.let {
                Spacer(modifier = Modifier.height(HeylanaTokens.SPACE_2_DP.dp))
                Text(text = it, style = glassText(HeylanaTokens.LABEL_SP, HeylanaTokens.textSecondary))
            }
            Spacer(modifier = Modifier.height(HeylanaTokens.SPACE_4_DP.dp))

            quote?.let { shown ->
                Text(text = xyz.heylana.app.ui.theme.monoNumbers(PlanText.send(shown)), style = glassText(HeylanaTokens.BODY_SP, HeylanaTokens.textPrimary))
                PlanText.worth(shown)?.let {
                    Text(text = xyz.heylana.app.ui.theme.monoNumbers(it), style = glassText(HeylanaTokens.LABEL_SP, HeylanaTokens.textSecondary))
                }
                Text(
                    text = "Plus a small network fee, paid in SOL.",
                    style = glassText(HeylanaTokens.LABEL_SP, HeylanaTokens.textSecondary)
                )
                when (val sim = simulation) {
                    SimulationResult.Passed -> Text(
                        text = "\u2713 ${BuildText.PASSED}",
                        style = glassText(HeylanaTokens.LABEL_SP, HeylanaTokens.success)
                    )
                    is SimulationResult.Failed -> Text(
                        text = BuildText.failed(sim.words),
                        style = glassText(HeylanaTokens.LABEL_SP, HeylanaTokens.error)
                    )
                    null -> Unit
                }
                Spacer(modifier = Modifier.height(HeylanaTokens.SPACE_4_DP.dp))
            }

            Row(horizontalArrangement = Arrangement.spacedBy(HeylanaTokens.SPACE_2_DP.dp)) {
                GlassButton(
                    text = if (paying) "Paying\u2026" else "Pay",
                    primary = true,
                    // Only a payment whose simulation passed can be paid.
                    enabled = quote != null && simulation == SimulationResult.Passed && !paying,
                    onClick = {
                        val shown = quote ?: return@GlassButton
                        if (quoteExpired(shown)) {
                            refresh++
                            line = "The price was refreshed. Check it and tap Pay again."
                            return@GlassButton
                        }
                        paying = true
                        line = "Approve the payment in Seed Vault."
                        scope.launch {
                            val outcome = payment.pay(session, shown, cluster) { reference, signature ->
                                settings.pendingPayment = reference to signature
                                line = "Sent. Waiting for Solana to confirm it\u2026"
                            }
                            paying = false
                            when (outcome) {
                                is PayOutcome.Paid -> {
                                    settings.pendingPayment = null
                                    onPaid(outcome.standing)
                                }
                                is PayOutcome.Stopped -> {
                                    // A payment that is merely slow stays claimable later.
                                    if (outcome.problem != WalletProblem.TOOK_TOO_LONG) {
                                        settings.pendingPayment = null
                                    }
                                    line = outcome.line
                                }
                            }
                        }
                    }
                )
                GlassButton(text = "Cancel", enabled = !paying, onClick = onClose)
            }

            if (line.isNotEmpty()) {
                Spacer(modifier = Modifier.height(HeylanaTokens.SPACE_3_DP.dp))
                Text(text = line, style = glassText(HeylanaTokens.LABEL_SP, HeylanaTokens.textSecondary))
            }
        }
    }
}

/** A quote about to lapse is fetched again rather than paid. */
private fun quoteExpired(quote: Quote): Boolean =
    runCatching { Instant.parse(quote.expiresAt).toEpochMilli() - System.currentTimeMillis() < QUOTE_MARGIN_MS }
        .getOrDefault(true)

private const val CURRENCY_USDC = "usdc"
private const val CURRENCY_SKR = "skr"
private const val QUOTE_MARGIN_MS = 30_000L

/** For hackathon judges: a code that makes this wallet (or phone) Judge until judging ends. */
@Composable
private fun JudgeCodeCard(api: WalletApi, onStanding: (Standing) -> Unit) {
    val scope = rememberCoroutineScope()
    var code by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var line by remember { mutableStateOf("") }

    GlassCard {
        Text(text = "Judge code", style = glassText(HeylanaTokens.TITLE_SP, HeylanaTokens.textPrimary))
        Spacer(modifier = Modifier.height(HeylanaTokens.SPACE_2_DP.dp))
        Text(
            text = "Judging Heylana? Enter your code. Connect your wallet first to keep it with the wallet.",
            style = glassText(HeylanaTokens.LABEL_SP, HeylanaTokens.textSecondary)
        )
        Spacer(modifier = Modifier.height(HeylanaTokens.SPACE_3_DP.dp))
        OutlinedTextField(
            value = code,
            onValueChange = { code = it },
            label = { Text(text = "Code") },
            singleLine = true,
            textStyle = glassText(HeylanaTokens.BODY_SP, HeylanaTokens.textPrimary),
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(modifier = Modifier.height(HeylanaTokens.SPACE_3_DP.dp))
        GlassButton(
            text = if (busy) "Checking\u2026" else "Use code",
            primary = true,
            enabled = !busy && code.isNotBlank(),
            onClick = {
                busy = true
                line = ""
                scope.launch {
                    line = when (val answer = api.judge(code.trim())) {
                        is Answer.Ok -> {
                            onStanding(answer.value)
                            code = ""
                            PlanText.until(answer.value) ?: ""
                        }
                        is Answer.Refused ->
                            if (answer.reason == "bad_code") "That code isn't right."
                            else WalletProblem.fromWorker(answer.reason).words
                        is Answer.Unreachable -> WalletProblem.UNREACHABLE.words
                    }
                    busy = false
                }
            }
        )
        if (line.isNotEmpty()) {
            Spacer(modifier = Modifier.height(HeylanaTokens.SPACE_3_DP.dp))
            Text(text = line, style = glassText(HeylanaTokens.LABEL_SP, HeylanaTokens.textSecondary))
        }
    }
}

/** The plain-words version of what goes where. The app's Privacy screen has the full list. */
@Composable
private fun PrivacyCard(provider: String, assemblyai: Boolean) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(text = "What leaves the phone", style = MaterialTheme.typography.titleMedium)
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Heylana reads the screen only when you ask, and watches for your " +
                    "tap only while it is pointing at something.",
                style = MaterialTheme.typography.bodySmall
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = VoiceCopy.privacyLine(provider, assemblyai),
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}

/**
 * Two things almost nobody needs: sending the questions somewhere else, and
 * paying for them yourself.
 */
@Composable
private fun AdvancedSection(
    settings: HeylanaSettings,
    api: WalletApi,
    onStanding: (Standing) -> Unit
) {
    JudgeCodeCard(api = api, onStanding = onStanding)

    var proxyUrl by remember { mutableStateOf(settings.proxyUrlOverride) }
    var useOwnKey by remember { mutableStateOf(settings.useOwnKey) }
    var savedKeyMask by remember { mutableStateOf(settings.maskedApiKey()) }
    var keyInput by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("") }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(text = "Where Heylana asks", style = MaterialTheme.typography.titleMedium)
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "Built in: ${BuildConfig.PROXY_URL.ifEmpty { "not set" }}",
                style = MaterialTheme.typography.bodySmall
            )
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedTextField(
                value = proxyUrl,
                onValueChange = { proxyUrl = it },
                label = { Text(text = "Use this address instead") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "Leave empty to use the built-in one.",
                style = MaterialTheme.typography.bodySmall
            )
        }
    }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.fillMaxWidth(0.8f)) {
                    Text(text = "Use my own key", style = MaterialTheme.typography.titleMedium)
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Off by default. On, your questions still go through Heylana's " +
                            "server, which asks Anthropic with your key for that question only " +
                            "and never keeps it; you pay for them. The voice and the ears are unchanged.",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                Spacer(modifier = Modifier.fillMaxWidth(0.05f))
                Switch(
                    checked = useOwnKey,
                    onCheckedChange = {
                        useOwnKey = it
                        settings.useOwnKey = it
                    }
                )
            }

            if (useOwnKey || savedKeyMask != null) {
                Spacer(modifier = Modifier.height(12.dp))
                val mask = savedKeyMask
                if (mask != null && keyInput.isEmpty()) {
                    Text(
                        text = "Key saved · $mask",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                }
                OutlinedTextField(
                    value = keyInput,
                    onValueChange = { keyInput = it },
                    label = { Text(text = "Paste your key") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Password,
                        imeAction = ImeAction.Done
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Stored encrypted on this phone only.",
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }

    Button(
        onClick = {
            settings.proxyUrlOverride = proxyUrl
            proxyUrl = settings.proxyUrlOverride
            if (keyInput.isNotBlank()) {
                settings.apiKey = keyInput
                keyInput = ""
                savedKeyMask = settings.maskedApiKey()
            }
            settings.useOwnKey = useOwnKey
            useOwnKey = settings.useOwnKey
            status = if (useOwnKey && !settings.hasApiKey) {
                "Saved, but there is still no key to use."
            } else {
                "Saved."
            }
        },
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(text = "Save")
    }

    if (status.isNotEmpty()) {
        Text(text = status, style = MaterialTheme.typography.bodyMedium)
    }
}

/** Debug builds only: things that exist to make a fallback happen on purpose. */
@Composable
private fun DebugSection(settings: HeylanaSettings) {
    var warmUp by remember { mutableStateOf(settings.warmUpConnection) }
    val context = LocalContext.current

    var phoneEars by remember { mutableStateOf(settings.forcePhoneEars) }

    var saveTts by remember { mutableStateOf(settings.saveTtsStream) }
    var simulateFree by remember { mutableStateOf(settings.simulateFreePlan) }

    SwitchCard(
        title = "Simulate Free plan",
        detail = "Debug builds only. Skills count against the Free plan's 3 whatever the " +
            "plan really is, so the greyed skills can be seen on a Judge account.",
        checked = simulateFree,
        onCheckedChange = {
            simulateFree = it
            settings.simulateFreePlan = it
        }
    )

    SwitchCard(
        title = "Save last tts stream",
        detail = "Debug builds only. Keeps the raw audio of the last spoken answer as " +
            "tts_capture.pcm, so a stream that sounds wrong can be listened to " +
            "somewhere else. Logcat prints where it went.",
        checked = saveTts,
        onCheckedChange = {
            saveTts = it
            settings.saveTtsStream = it
        }
    )

    SwitchCard(
        title = "Force phone ears",
        detail = "Debug builds only. Skips Deepgram and listens with the phone's own " +
            "recogniser, so the fallback can be heard on purpose.",
        checked = phoneEars,
        onCheckedChange = {
            phoneEars = it
            settings.forcePhoneEars = it
        }
    )

    SwitchCard(
        title = "Warm up the connection",
        detail = "Debug builds only. On by default: touching the buddy opens the " +
            "connection early so the answer arrives sooner. Turn it off to see the " +
            "difference.",
        checked = warmUp,
        onCheckedChange = {
            warmUp = it
            settings.warmUpConnection = it
        }
    )

    OutlinedButton(
        onClick = {
            // Debug builds only, so the class is named rather than imported.
            context.startActivity(
                Intent().setComponent(ComponentName(context, DEBUG_STATES_ACTIVITY))
            )
        },
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(text = "Debug states")
    }
}

@Composable
private fun SwitchCard(
    title: String,
    detail: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.fillMaxWidth(0.8f)) {
                Text(text = title, style = MaterialTheme.typography.titleMedium)
                Spacer(modifier = Modifier.height(4.dp))
                Text(text = detail, style = MaterialTheme.typography.bodySmall)
            }
            Spacer(modifier = Modifier.fillMaxWidth(0.05f))
            Switch(checked = checked, onCheckedChange = onCheckedChange)
        }
    }
}

const val DEBUG_STATES_ACTIVITY = "xyz.heylana.app.debug.DebugStatesActivity"
