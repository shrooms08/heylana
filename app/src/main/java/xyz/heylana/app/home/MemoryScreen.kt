package xyz.heylana.app.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import xyz.heylana.app.HeylanaLog
import xyz.heylana.app.memory.MemoryRecord
import xyz.heylana.app.memory.MemoryState
import xyz.heylana.app.settings.HeylanaSettings
import xyz.heylana.app.ui.app.AccentButton
import xyz.heylana.app.ui.app.BarButton
import xyz.heylana.app.ui.app.FlatPage
import xyz.heylana.app.ui.app.FlatRow
import xyz.heylana.app.ui.app.HoldControl
import xyz.heylana.app.ui.app.Glyph
import xyz.heylana.app.ui.app.InnerTopBar
import xyz.heylana.app.ui.app.SectionHead
import xyz.heylana.app.ui.theme.HeylanaType
import xyz.heylana.app.ui.theme.LocalHeylana
import xyz.heylana.app.wallet.Answer
import xyz.heylana.app.wallet.WalletApi

/** Every word the Memory screen says. */
object MemoryText {
    const val TITLE = "Memory"
    const val LEAD = "Short notes Heylana keeps about you, against your wallet. Never your screen, an address or an amount."
    const val SWITCH = "Keep notes about me"
    const val SWITCH_WHY = "Off keeps nothing, and deletes what was kept."
    const val HOLD_ON = "Hold to turn on."
    const val HOLD_OFF = "On. Hold to turn off: off keeps nothing, and deletes what was kept."

    fun switchLine(on: Boolean): String = if (on) HOLD_OFF else HOLD_ON
    const val KEPT = "What Heylana keeps"
    const val EMPTY = "Nothing kept yet. Say \"remember that…\" to Heylana, or finish a lesson."
    const val OFF = "Memory is off. Nothing is kept."
    const val NO_WALLET = "Connect a wallet to use memory: notes are kept against it."
    const val LOADING = "Reading your notes…"
    const val UNREACHABLE = "Couldn't reach Heylana just now."
    const val WIPE = "Wipe all"
    const val WIPE_AGAIN = "Tap again to wipe all"

    /** "Lesson · 2026-09-18": what kind of note, and when. */
    fun detail(record: MemoryRecord): String = "${kind(record.category)} · ${record.created.take(10)}"

    fun kind(category: String): String = when (category) {
        "fact" -> "You said"
        "preference" -> "Preference"
        "skill_progress" -> "Lesson"
        "learned" -> "Learned"
        else -> "Note"
    }
}

/**
 * Menu, Memory: the switch, then every kept line with a delete each, and Wipe all (a second
 * tap confirms). Read from the worker on open; nothing is cached on the phone but the switch.
 */
@Composable
fun MemoryScreen(settings: HeylanaSettings, onBack: () -> Unit) {
    val palette = LocalHeylana.current
    val api = remember { WalletApi(settings) }
    val scope = rememberCoroutineScope()
    val hasWallet = settings.walletSession != null
    var state by remember { mutableStateOf<MemoryState?>(null) }
    var problem by remember { mutableStateOf<String?>(null) }
    var wipeArmed by remember { mutableStateOf(false) }

    fun take(answer: Answer<MemoryState>, what: String) {
        when (answer) {
            is Answer.Ok -> {
                state = answer.value
                settings.memoryOn = answer.value.on
                problem = null
                HeylanaLog.state("memory: screen $what on=${answer.value.on} records=${answer.value.records.size}")
            }
            else -> {
                problem = MemoryText.UNREACHABLE
                HeylanaLog.state("memory: screen $what failed")
            }
        }
    }

    LaunchedEffect(hasWallet) { if (hasWallet) take(api.memory(), "read") }

    FlatPage {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
            Box(Modifier.padding(vertical = 14.dp)) { InnerTopBar(onBack) }
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Spacer(Modifier.height(10.dp))
                Text(MemoryText.TITLE, style = HeylanaType.title, color = palette.ink)
                Text(MemoryText.LEAD, style = HeylanaType.bodyLight, color = palette.inkSecondary)
                Spacer(Modifier.height(8.dp))
                val current = state
                if (!hasWallet) Text(MemoryText.NO_WALLET, style = HeylanaType.body, color = palette.ink)
                else {
                    val on = current?.on ?: settings.memoryOn
                    FlatRow(MemoryText.SWITCH, subtitle = MemoryText.switchLine(on)) {
                        HoldControl(Glyph.BRAIN, on, MemoryText.SWITCH, enabled = current != null, onFire = {
                            scope.launch { take(api.memoryConsent(!on), if (!on) "on" else "off") }
                        })
                    }
                }
                problem?.let { Text(it, style = HeylanaType.small, color = palette.inkSecondary) }
                Spacer(Modifier.height(6.dp))
                if (hasWallet) SectionHead(MemoryText.KEPT)
                if (hasWallet) when {
                    current == null -> Text(MemoryText.LOADING, style = HeylanaType.small, color = palette.inkSecondary)
                    !current.on -> Text(MemoryText.OFF, style = HeylanaType.small, color = palette.inkSecondary)
                    current.records.isEmpty() -> Text(MemoryText.EMPTY, style = HeylanaType.small, color = palette.inkSecondary)
                    else -> {
                        // Newest first, as it was said.
                        current.records.sortedByDescending { it.created }.forEach { record ->
                            FlatRow(record.content, subtitle = MemoryText.detail(record)) {
                                BarButton(Glyph.TRASH, "Delete") {
                                    wipeArmed = false
                                    scope.launch { take(api.forget(record.id), "delete") }
                                }
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                        AccentButton(if (wipeArmed) MemoryText.WIPE_AGAIN else MemoryText.WIPE, onClick = {
                            if (!wipeArmed) wipeArmed = true
                            else {
                                wipeArmed = false
                                scope.launch { take(api.wipeMemory(), "wipe") }
                            }
                        })
                    }
                }
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}
