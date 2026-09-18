package xyz.heylana.app.home

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import xyz.heylana.app.Features
import xyz.heylana.app.R
import xyz.heylana.app.ui.app.AccentButton
import xyz.heylana.app.ui.app.FlatRow
import xyz.heylana.app.ui.app.FlatSwitch
import xyz.heylana.app.ui.app.Glyph
import xyz.heylana.app.ui.app.SectionHead
import xyz.heylana.app.ui.app.tap
import xyz.heylana.app.ui.theme.HeylanaType
import xyz.heylana.app.ui.theme.LocalHeylana
import xyz.heylana.app.wallet.PlanText
import xyz.heylana.app.wallet.Standing

/** Every word the menu says, away from Compose so it can be tested. */
object MenuText {
    const val BUDDY_WHY = "Heylana can then see your screen when you ask"
    const val NO_WALLET = "No wallet connected"
    const val CHECKING = "Checking your plan…"

    /** "Free", "Pro", "Judge". */
    fun planName(standing: Standing?): String = standing?.let { PlanText.name(it.plan) } ?: ""

    /** What the plan gives: "30 talks a month", "Unlimited talks", "Unlimited until Nov 9". */
    fun planLine(standing: Standing?): String = standing?.let { PlanText.summary(it) } ?: CHECKING

    /** How full the talks bar is, 0 to 1, or null when there is no limit. */
    fun planFill(standing: Standing?): Float? {
        val limit = standing?.limit ?: return null
        if (limit <= 0) return 1f
        return (standing.used.toFloat() / limit).coerceIn(0f, 1f)
    }

    fun showGoPro(standing: Standing?): Boolean = standing?.plan == "free"

    /** "3 active": the Skill market's row, shown only while [Features.SKILL_MARKET] is on. */
    fun skillsActive(active: Int): String = "$active active"

    /** The footer's second line: the short wallet, or that there is none. */
    fun walletLine(shortWallet: String?): String = shortWallet?.let { "$it · Seed Vault" } ?: NO_WALLET

    /** The footer's avatar letter. */
    fun initial(name: String): String = name.trim().firstOrNull()?.uppercaseChar()?.toString() ?: "H"
}

/**
 * The menu: a full-height drawer from the left over a dimmed Home, a flat list in three
 * sections — Buddy (Start buddy), Account (Plan), More (Memory, Advanced, Privacy,
 * Settings) — and the profile row at the bottom.
 */
@Composable
fun MenuSheet(
    open: Boolean,
    buddyOn: Boolean,
    standing: Standing?,
    skillsActive: Int,
    name: String,
    shortWallet: String?,
    onBuddy: (Boolean) -> Unit,
    onGoPro: () -> Unit,
    onScreen: (Screen) -> Unit,
    onClose: () -> Unit
) {
    val palette = LocalHeylana.current
    val shown by animateFloatAsState(if (open) 1f else 0f, tween(DRAWER_MS), label = "menu")
    if (shown <= 0.001f && !open) return

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val width = maxWidth * MENU_WIDTH
        // The dim over home; a tap on it closes the menu.
        Box(Modifier.fillMaxSize().alpha(shown).background(palette.dim).tap(onClick = onClose))
        Box(
            Modifier.width(width).fillMaxHeight()
                .graphicsLayer { translationX = -(1f - shown) * size.width }
                .background(palette.drawer)
                .tap { }
        ) {
            Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(horizontal = 12.dp, vertical = 12.dp)) {
                Row(Modifier.padding(start = 12.dp, top = 8.dp, bottom = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                    Image(painterResource(R.drawable.ic_heylana_mark), null, Modifier.size(26.dp), colorFilter = ColorFilter.tint(palette.ink))
                    Spacer(Modifier.width(10.dp))
                    Text("heylana", style = HeylanaType.wordmark, color = palette.ink)
                }
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                    MenuSection("Buddy")
                    FlatRow("Start buddy", subtitle = MenuText.BUDDY_WHY, glyph = Glyph.POWER, card = false) {
                        FlatSwitch(buddyOn, onBuddy)
                    }

                    MenuSection("Account")
                    FlatRow(
                        "Plan",
                        subtitle = MenuText.planLine(standing),
                        glyph = Glyph.STAR,
                        card = false
                    ) { Text(MenuText.planName(standing), style = HeylanaType.label, color = palette.inkSecondary) }
                    MenuText.planFill(standing)?.let { fill ->
                        Box(Modifier.padding(start = 56.dp, end = 16.dp, bottom = 8.dp).fillMaxWidth().height(4.dp).clip(CircleShape).background(palette.switchOff)) {
                            Box(Modifier.fillMaxWidth(fill).fillMaxHeight().clip(CircleShape).background(palette.accent))
                        }
                    }
                    if (MenuText.showGoPro(standing)) {
                        Box(Modifier.padding(start = 56.dp, end = 16.dp, top = 4.dp, bottom = 8.dp)) { AccentButton("Go Pro", onGoPro, height = 40.dp) }
                    }
                    if (Features.SKILL_MARKET) FlatRow("Skill market", glyph = Glyph.BAG, card = false, onClick = { onScreen(Screen.SKILLS) }) {
                        Text(MenuText.skillsActive(skillsActive), style = HeylanaType.small, color = palette.inkSecondary)
                    }

                    MenuSection("More")
                    FlatRow("Memory", glyph = Glyph.LAYERS, card = false, onClick = { onScreen(Screen.MEMORY) })
                    FlatRow("Advanced", glyph = Glyph.SLIDERS, card = false, onClick = { onScreen(Screen.ADVANCED) })
                    FlatRow("Privacy", glyph = Glyph.SHIELD, card = false, onClick = { onScreen(Screen.PRIVACY) })
                    FlatRow("Settings", glyph = Glyph.GEAR, card = false, onClick = { onScreen(Screen.SETTINGS) })
                }

                // Who is signed in.
                Row(Modifier.fillMaxWidth().padding(start = 12.dp, top = 12.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier.size(40.dp).clip(CircleShape).background(palette.surfaceHigh),
                        contentAlignment = Alignment.Center
                    ) { Text(MenuText.initial(name), style = HeylanaType.bodyMedium, color = palette.ink) }
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text(name.ifBlank { "Heylana" }, style = HeylanaType.body, color = palette.ink)
                        Text(MenuText.walletLine(shortWallet), style = HeylanaType.small, color = palette.inkSecondary)
                    }
                }
            }
        }
    }
}

/** A section's heading in the drawer. */
@Composable
private fun MenuSection(title: String) {
    SectionHead(title, Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp))
}

/** How much of the screen's width the drawer takes, and how long it takes to slide. */
private const val MENU_WIDTH = 0.84f
private const val DRAWER_MS = 240
