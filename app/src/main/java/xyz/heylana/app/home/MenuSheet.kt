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
import androidx.compose.animation.core.spring
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
import xyz.heylana.app.R
import xyz.heylana.app.ui.HeylanaTokens
import xyz.heylana.app.ui.app.AccentButton
import xyz.heylana.app.ui.app.Backdrop
import xyz.heylana.app.ui.app.GlassRow
import xyz.heylana.app.ui.app.GlassSurface
import xyz.heylana.app.ui.app.GlassSwitch
import xyz.heylana.app.ui.app.GooeyReveal
import xyz.heylana.app.ui.app.Glyph
import xyz.heylana.app.ui.app.Icon
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

    /** "12 of 30 talks", or the plan's end date, or unlimited. */
    fun planLine(standing: Standing?): String = when {
        standing == null -> CHECKING
        standing.limit != null -> "${standing.used} of ${standing.limit} talks"
        else -> PlanText.until(standing) ?: "Unlimited talks"
    }

    /** How full the talks bar is, 0 to 1, or null when there is no limit. */
    fun planFill(standing: Standing?): Float? {
        val limit = standing?.limit ?: return null
        if (limit <= 0) return 1f
        return (standing.used.toFloat() / limit).coerceIn(0f, 1f)
    }

    fun showGoPro(standing: Standing?): Boolean = standing?.plan == "free"

    /** "3 active". */
    fun skillsActive(active: Int): String = "$active active"

    /** The footer's second line: the short wallet, or that there is none. */
    fun walletLine(shortWallet: String?): String = shortWallet?.let { "$it · Seed Vault" } ?: NO_WALLET

    /** The footer's avatar letter. */
    fun initial(name: String): String = name.trim().firstOrNull()?.uppercaseChar()?.toString() ?: "H"
}

/**
 * The menu, frame 3 of the export: a glass sheet that grows in from the left edge like
 * liquid over a dimmed home. Start buddy, the plan, and the ways into the inner screens;
 * the name and the short wallet at the bottom.
 */
@Composable
fun MenuSheet(
    backdrop: Backdrop,
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
    val shown by animateFloatAsState(
        if (open) 1f else 0f,
        spring(dampingRatio = HeylanaTokens.SPRING_DAMPING, stiffness = HeylanaTokens.SPRING_STIFFNESS * 0.6f),
        label = "menu"
    )
    if (shown <= 0.001f && !open) return

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val width = maxWidth * MENU_WIDTH
        // The dim over home; a tap on it closes the menu.
        Box(Modifier.fillMaxSize().alpha(shown).background(palette.dim).tap(onClick = onClose))
        GooeyReveal(
            open,
            Modifier.width(width).fillMaxHeight().graphicsLayer { translationX = -(1f - shown) * 40.dp.toPx() },
            seedX = 0f,
            seedY = 0.5f,
            radius = 28.dp
        ) {
            GlassSurface(backdrop, Modifier.fillMaxSize().tap { }, radius = 28.dp) {
                Column(
                    Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()
                        .padding(horizontal = 16.dp, vertical = 18.dp)
                ) {
                    Row(Modifier.padding(start = 8.dp, bottom = 18.dp), verticalAlignment = Alignment.CenterVertically) {
                        Image(painterResource(R.drawable.ic_heylana_mark), null, Modifier.size(28.dp), colorFilter = ColorFilter.tint(palette.ink))
                        Spacer(Modifier.width(10.dp))
                        Text("heylana", style = HeylanaType.wordmark, color = palette.ink)
                    }
                    Column(
                        Modifier.weight(1f).verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        // Start buddy.
                        GlassSurface(backdrop, Modifier.fillMaxWidth(), radius = 24.dp) {
                            Column(Modifier.padding(horizontal = 18.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Glyph.POWER, palette.accentSoft, size = 18.dp)
                                    Spacer(Modifier.width(12.dp))
                                    Text("Start buddy", Modifier.weight(1f), style = HeylanaType.body, color = palette.ink)
                                    GlassSwitch(buddyOn, onBuddy)
                                }
                                Text(MenuText.BUDDY_WHY, style = HeylanaType.small, color = palette.inkSecondary)
                            }
                        }

                        // Plan.
                        GlassSurface(backdrop, Modifier.fillMaxWidth(), radius = 24.dp) {
                            Column(Modifier.padding(horizontal = 18.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Glyph.STAR, palette.accentSoft, size = 18.dp)
                                    Spacer(Modifier.width(12.dp))
                                    Text("Plan", Modifier.weight(1f), style = HeylanaType.body, color = palette.ink)
                                    Text(MenuText.planName(standing), style = HeylanaType.label, color = palette.inkSecondary)
                                }
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    val fill = MenuText.planFill(standing)
                                    if (fill != null) {
                                        Box(Modifier.weight(1f).height(4.dp).clip(CircleShape).background(palette.switchOff)) {
                                            Box(Modifier.fillMaxWidth(fill).fillMaxHeight().clip(CircleShape).background(palette.accent))
                                        }
                                        Spacer(Modifier.width(12.dp))
                                    }
                                    Text(MenuText.planLine(standing), style = HeylanaType.small, color = palette.inkSecondary)
                                }
                                if (MenuText.showGoPro(standing)) AccentButton("Go Pro", onGoPro, height = 44.dp)
                            }
                        }

                        GlassRow(backdrop, "Skill market", glyph = Glyph.BAG, onClick = { onScreen(Screen.SKILLS) }) {
                            Box(
                                Modifier.clip(RoundedCornerShape(999.dp)).background(palette.accent.copy(alpha = 0.18f))
                                    .padding(horizontal = 10.dp, vertical = 3.dp)
                            ) { Text(MenuText.skillsActive(skillsActive), style = HeylanaType.tiny, color = palette.accentSoft) }
                            Icon(Glyph.CHEVRON, palette.inkTertiary, size = 16.dp)
                        }
                        GlassRow(backdrop, "Advanced", glyph = Glyph.SLIDERS, onClick = { onScreen(Screen.ADVANCED) }) {
                            Icon(Glyph.CHEVRON, palette.inkTertiary, size = 16.dp)
                        }
                        GlassRow(backdrop, "Privacy", glyph = Glyph.SHIELD, onClick = { onScreen(Screen.PRIVACY) }) {
                            Icon(Glyph.CHEVRON, palette.inkTertiary, size = 16.dp)
                        }
                        GlassRow(backdrop, "Settings", glyph = Glyph.GEAR, onClick = { onScreen(Screen.SETTINGS) }) {
                            Icon(Glyph.CHEVRON, palette.inkTertiary, size = 16.dp)
                        }
                    }

                    // Who is signed in.
                    Row(Modifier.padding(start = 6.dp, top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            Modifier.size(40.dp).clip(CircleShape).background(palette.tileFill),
                            contentAlignment = Alignment.Center
                        ) { Text(MenuText.initial(name), style = HeylanaType.bodyMedium, color = palette.ink) }
                        Spacer(Modifier.width(12.dp))
                        Column {
                            Text(name.ifBlank { "Heylana" }, style = HeylanaType.label, color = palette.ink)
                            Text(MenuText.walletLine(shortWallet), style = HeylanaType.tiny, color = palette.inkSecondary)
                        }
                    }
                }
            }
        }
    }
}

/** How much of the screen's width the menu takes. */
private const val MENU_WIDTH = 0.84f
