package xyz.heylana.app.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.background
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import xyz.heylana.app.BuildConfig
import xyz.heylana.app.HeylanaLog
import xyz.heylana.app.settings.HeylanaSettings
import xyz.heylana.app.skills.SkillCap
import xyz.heylana.app.skills.SkillFile
import xyz.heylana.app.skills.SkillIndex
import xyz.heylana.app.skills.SkillStore
import xyz.heylana.app.skills.SkillsText
import xyz.heylana.app.ui.app.FlatPage
import xyz.heylana.app.ui.app.FlatRow
import xyz.heylana.app.ui.app.FlatSwitch
import xyz.heylana.app.ui.app.Glyph
import xyz.heylana.app.ui.app.InnerTopBar
import xyz.heylana.app.ui.app.SectionHead
import xyz.heylana.app.ui.app.tap
import xyz.heylana.app.ui.theme.HeylanaType
import xyz.heylana.app.ui.theme.LocalHeylana
import xyz.heylana.app.wallet.Answer
import xyz.heylana.app.wallet.WalletApi

/** The skill market's words and icons, away from Compose so they can be tested. */
object MarketText {
    const val TITLE = "Skill market"
    const val SUBTITLE = "Small abilities you can switch on."
    const val INSTALLED = "Installed"
    const val GET = "Get"
    const val LOADING = "Looking for more…"

    fun glyph(id: String): Glyph = when (id) {
        "seed-vault-wallet" -> Glyph.WALLET
        "kamino" -> Glyph.BALANCE
        "seed-vault-signing" -> Glyph.LOCK
        "dapp-store" -> Glyph.BAG
        "jupiter" -> Glyph.SWAP
        "x402" -> Glyph.SHIELD
        "youtube", "spotify" -> Glyph.PLAY
        "chrome" -> Glyph.EYE
        else -> Glyph.DOC
    }

    /** The index entries not here yet: what "Get" is offered for. */
    fun toGet(index: List<SkillIndex.Entry>, have: Set<String>): List<SkillIndex.Entry> =
        index.filter { it.id !in have }
}

/**
 * The skill market, frame 4 of the export: the skills here (built in or installed), each
 * with its switch, and the public index's others with Get. The counter in the top bar is
 * the plan's cap at work: "3 of 3 active". Real skills only.
 */
@Composable
fun SkillMarketScreen(settings: HeylanaSettings, onBack: () -> Unit, onChanged: () -> Unit) {
    val palette = LocalHeylana.current
    val context = LocalContext.current
    val store = remember { SkillStore(context, settings) }
    val scope = rememberCoroutineScope()
    var version by remember { mutableIntStateOf(0) }
    val rows = remember(version) { store.rows() }
    val cap = remember(version) { store.cap() }
    var index by remember { mutableStateOf<List<SkillIndex.Entry>?>(null) }
    var status by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        // The plan may have changed; then the index, a plain GET with no Heylana headers.
        val answer = WalletApi(settings).me()
        if (answer is Answer.Ok) {
            settings.skillsCap = answer.value.skillsCap
            version++
        }
        val url = BuildConfig.SKILLS_INDEX_URL
        when (val fetched = SkillIndex.fetch(url, SkillIndex.MAX_INDEX_BYTES, BuildConfig.DEBUG)) {
            is SkillIndex.Fetched.Ok -> {
                index = SkillIndex.parse(fetched.text, url, BuildConfig.DEBUG)
                HeylanaLog.state("skills: index loaded entries=${index?.size}")
            }
            is SkillIndex.Fetched.Failed -> {
                HeylanaLog.state("skills: index failed why=${fetched.why}")
                index = emptyList()
                status = SkillsText.INDEX_FAILED
            }
        }
    }

    FlatPage {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
            Box(Modifier.padding(vertical = 14.dp)) {
                InnerTopBar(onBack) {
                    Text(SkillCap.headline(rows, cap), style = HeylanaType.label, color = palette.inkSecondary)
                }
            }
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Spacer(Modifier.height(10.dp))
                Text(MarketText.TITLE, style = HeylanaType.title, color = palette.ink)
                Text(MarketText.SUBTITLE, style = HeylanaType.bodyLight, color = palette.inkSecondary)
                Spacer(Modifier.height(18.dp))

                rows.forEach { row ->
                    val note = SkillsText.note(row, cap)
                    FlatRow(row.skill.name,
                        Modifier.alpha(if (row.greyed) GREYED else 1f),
                        subtitle = if (row.greyed && note != null) note else row.skill.summary,
                        glyph = MarketText.glyph(row.skill.id)
                    ) {
                        Column(horizontalAlignment = androidx.compose.ui.Alignment.End, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            FlatSwitch(row.switchedOn, { on ->
                                store.setOn(row.skill.id, on)
                                HeylanaLog.state("skills: switched id=${row.skill.id} on=$on")
                                version++
                                onChanged()
                            }, enabled = row.state != SkillCap.State.OFF_FULL)
                            if (!row.skill.builtIn) {
                                Text("Remove", Modifier.tap {
                                    store.remove(row.skill.id)
                                    version++
                                    onChanged()
                                }, style = HeylanaType.tiny, color = palette.inkTertiary)
                            } else {
                                Text(MarketText.INSTALLED, style = HeylanaType.tiny, color = palette.inkTertiary)
                            }
                        }
                    }
                }

                val entries = index
                val have = rows.map { it.skill.id }.toSet()
                if (entries == null) {
                    Text(MarketText.LOADING, style = HeylanaType.small, color = palette.inkTertiary)
                } else {
                    val more = MarketText.toGet(entries, have)
                    if (more.isNotEmpty()) {
                        Spacer(Modifier.height(8.dp))
                        SectionHead("More skills")
                    } else if (entries.isNotEmpty()) {
                        Text(SkillsText.ALL_INSTALLED, style = HeylanaType.small, color = palette.inkTertiary)
                    }
                    more.forEach { entry ->
                        FlatRow(entry.name, subtitle = entry.summary, glyph = MarketText.glyph(entry.id)) {
                            Box(
                                Modifier.clip(RoundedCornerShape(999.dp)).background(palette.accent)
                                    .alpha(if (busy) 0.5f else 1f)
                                    .tap(!busy) {
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
                                            onChanged()
                                        }
                                    }
                                    .padding(horizontal = 16.dp, vertical = 7.dp)
                            ) { Text(MarketText.GET, style = HeylanaType.label, color = palette.onAccent) }
                        }
                    }
                }
                if (status.isNotEmpty()) Text(status, style = HeylanaType.small, color = palette.inkSecondary)
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

private const val GREYED = 0.45f
