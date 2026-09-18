package xyz.heylana.app.skills

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import xyz.heylana.app.BuildConfig
import xyz.heylana.app.HeylanaLog
import xyz.heylana.app.settings.HeylanaSettings
import xyz.heylana.app.ui.GlassButton
import xyz.heylana.app.ui.GlassCard
import xyz.heylana.app.ui.HeylanaTokens
import xyz.heylana.app.ui.glassText
import xyz.heylana.app.ui.theme.HeylanaTheme
import xyz.heylana.app.wallet.Answer
import xyz.heylana.app.wallet.WalletApi

/**
 * The skills: which are on, how many the plan allows, and more from the public index.
 * Built-ins can be switched off but not removed; installed ones can be removed.
 */
class SkillsActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // The Skill market is on the roadmap: nothing opens this while it is off.
        if (!xyz.heylana.app.Features.SKILL_MARKET) {
            finish()
            return
        }
        enableEdgeToEdge()
        val settings = HeylanaSettings.get(this)
        setContent {
            HeylanaTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    SkillsScreen(settings = settings, onBack = { finish() }, modifier = Modifier.padding(innerPadding))
                }
            }
        }
    }
}

@Composable
private fun SkillsScreen(settings: HeylanaSettings, onBack: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val store = remember { SkillStore(context, settings) }
    val scope = rememberCoroutineScope()
    // Bumped after every change, so the rows are read again from the store.
    var version by remember { mutableIntStateOf(0) }
    val rows = remember(version) { store.rows() }
    val cap = remember(version) { store.cap() }

    var index by remember { mutableStateOf<List<SkillIndex.Entry>?>(null) }
    var status by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }

    // The plan may have changed since Settings last heard it.
    LaunchedEffect(Unit) {
        val answer = WalletApi(settings).me()
        if (answer is Answer.Ok) {
            settings.skillsCap = answer.value.skillsCap
            answer.value.voice?.let { settings.rememberVoice(it.provider, it.skylar, it.archie) }
            version++
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(HeylanaTokens.SPACE_5_DP.dp),
        verticalArrangement = Arrangement.spacedBy(HeylanaTokens.SPACE_4_DP.dp)
    ) {
        Text(text = "Skills", style = glassText(HeylanaTokens.DISPLAY_SP, HeylanaTokens.textPrimary))
        Text(text = SkillsText.INTRO, style = glassText(HeylanaTokens.BODY_SP, HeylanaTokens.textSecondary))
        Text(text = SkillCap.headline(rows, cap), style = glassText(HeylanaTokens.TITLE_SP, HeylanaTokens.textPrimary))

        rows.forEach { row ->
            SkillRow(
                row = row,
                cap = cap,
                onSwitch = { on ->
                    store.setOn(row.skill.id, on)
                    HeylanaLog.state("skills: switched id=${row.skill.id} on=$on")
                    version++
                },
                onRemove = {
                    store.remove(row.skill.id)
                    version++
                }
            )
        }

        GlassButton(
            text = if (busy) "Loading\u2026" else "Get more",
            primary = true,
            enabled = !busy,
            onClick = {
                busy = true
                status = ""
                scope.launch {
                    val url = BuildConfig.SKILLS_INDEX_URL
                    when (val fetched = SkillIndex.fetch(url, SkillIndex.MAX_INDEX_BYTES, BuildConfig.DEBUG)) {
                        is SkillIndex.Fetched.Ok -> {
                            val entries = SkillIndex.parse(fetched.text, url, BuildConfig.DEBUG)
                            HeylanaLog.state("skills: index loaded entries=${entries.size}")
                            index = entries
                            if (entries.isEmpty()) status = SkillsText.INDEX_EMPTY
                        }
                        is SkillIndex.Fetched.Failed -> {
                            HeylanaLog.state("skills: index failed why=${fetched.why}")
                            status = SkillsText.INDEX_FAILED
                        }
                    }
                    busy = false
                }
            }
        )

        val have = rows.map { it.skill.id }.toSet()
        index?.let { entries ->
            val listed = SkillsText.listing(entries, have)
            if (listed.everythingInstalled) {
                Text(text = SkillsText.ALL_INSTALLED, style = glassText(HeylanaTokens.LABEL_SP, HeylanaTokens.textSecondary))
            }
            listed.entries.forEach { (entry, installed) ->
                GlassCard {
                    Text(text = entry.name, style = glassText(HeylanaTokens.BODY_SP, HeylanaTokens.textPrimary))
                    Text(text = entry.summary, style = glassText(HeylanaTokens.LABEL_SP, HeylanaTokens.textSecondary))
                    Spacer(modifier = Modifier.height(HeylanaTokens.SPACE_3_DP.dp))
                    if (installed) {
                        Text(text = SkillsText.INSTALLED, style = glassText(HeylanaTokens.LABEL_SP, HeylanaTokens.textSecondary))
                        return@GlassCard
                    }
                    GlassButton(
                        text = "Install",
                        enabled = !busy,
                        onClick = {
                            busy = true
                            status = ""
                            scope.launch {
                                status = when (val fetched = SkillIndex.fetch(entry.url, SkillFile.MAX_FILE_BYTES, BuildConfig.DEBUG)) {
                                    is SkillIndex.Fetched.Ok -> when (val result = store.install(fetched.text, entry.id)) {
                                        is SkillStore.Installed.Ok -> SkillsText.installed(result.skill.name)
                                        is SkillStore.Installed.Refused -> SkillsText.refused(result.reason)
                                    }
                                    is SkillIndex.Fetched.Failed -> {
                                        HeylanaLog.state("skills: download failed id=${entry.id} why=${fetched.why}")
                                        SkillsText.DOWNLOAD_FAILED
                                    }
                                }
                                busy = false
                                version++
                            }
                        }
                    )
                }
            }
        }

        if (status.isNotEmpty()) {
            Text(text = status, style = glassText(HeylanaTokens.LABEL_SP, HeylanaTokens.textSecondary))
        }

        GlassButton(text = "Back", onClick = onBack)
    }
}

@Composable
private fun SkillRow(row: SkillCap.Row, cap: Int, onSwitch: (Boolean) -> Unit, onRemove: () -> Unit) {
    GlassCard(modifier = Modifier.alpha(if (row.greyed) GREYED_ALPHA else 1f)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(text = row.skill.name, style = glassText(HeylanaTokens.BODY_SP, HeylanaTokens.textPrimary))
                Spacer(modifier = Modifier.height(HeylanaTokens.SPACE_1_DP.dp))
                Text(text = row.skill.summary, style = glassText(HeylanaTokens.LABEL_SP, HeylanaTokens.textSecondary))
                SkillsText.note(row, cap)?.let {
                    Spacer(modifier = Modifier.height(HeylanaTokens.SPACE_1_DP.dp))
                    Text(text = it, style = glassText(HeylanaTokens.LABEL_SP, HeylanaTokens.textSecondary))
                }
            }
            Spacer(modifier = Modifier.width(HeylanaTokens.SPACE_3_DP.dp))
            Switch(
                checked = row.switchedOn,
                enabled = row.state != SkillCap.State.OFF_FULL,
                onCheckedChange = onSwitch
            )
        }
        if (!row.skill.builtIn) {
            Spacer(modifier = Modifier.height(HeylanaTokens.SPACE_3_DP.dp))
            GlassButton(text = "Remove", onClick = onRemove)
        }
    }
}

/** Every word the Skills screen says, away from Compose so it can be tested. */
object SkillsText {
    const val INTRO = "Skills are reference notes for one app each. They never act for you."
    const val INDEX_FAILED = "Couldn't load more skills. Check the connection and try again."
    const val INDEX_EMPTY = "No skills to add right now."
    const val ALL_INSTALLED = "Everything in the index is installed"
    const val INSTALLED = "Installed"

    /** Every index entry, in index order, marked if it is already here (built in or installed). */
    data class Listing(val entries: List<Pair<SkillIndex.Entry, Boolean>>) {
        val everythingInstalled: Boolean get() = entries.isNotEmpty() && entries.all { it.second }
    }

    fun listing(index: List<SkillIndex.Entry>, have: Set<String>): Listing =
        Listing(index.map { it to (it.id in have) })
    const val DOWNLOAD_FAILED = "Couldn't download that skill. Try again."

    fun installed(name: String) = "$name installed."

    fun refused(reason: String): String = when (reason) {
        "body_too_long", "too_big" -> "That skill is too long to install."
        "built_in" -> "That skill is already built in."
        else -> "That skill couldn't be installed."
    }

    /** "Built in", or why a greyed skill is not loading. */
    fun note(row: SkillCap.Row, cap: Int): String? = when (row.state) {
        SkillCap.State.OVER_CAP -> "Over your plan's $cap skills. Switch another off to use it."
        SkillCap.State.OFF_FULL -> "Your plan's $cap skills are in use."
        else -> if (row.skill.builtIn) "Built in" else null
    }
}

private const val GREYED_ALPHA = 0.45f
