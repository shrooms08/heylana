package xyz.heylana.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import xyz.heylana.app.ui.HeylanaTokens

/** Flat and dark (the default), or the same flat screens on white. Settings → Appearance. */
enum class GlassMode { DARK, LIGHT }

/**
 * Every colour the app draws with, and the only place one is written down. Screens and
 * components read these through [LocalHeylana]; nothing else holds a colour value.
 */
@Immutable
data class HeylanaPalette(
    val mode: GlassMode,
    /** The page. */
    val ground: Color,
    /** Words: primary (foreground), second-rank (text-2), and muted (labels, hints). */
    val ink: Color,
    val inkSecondary: Color,
    val inkTertiary: Color,
    /** Heylana's one accent, its hover and deeper shades, and accent words on the page. */
    val accent: Color,
    val accentHover: Color,
    val accentText: Color,
    /** The accent at 14%: soft backgrounds, a selected row. */
    val accentSoft: Color,
    /** The accent's shaded side: the far edge of the voice screen's mic. */
    val accentDeep: Color,
    /** Words on the accent: never white. */
    val onAccent: Color,
    /** Flat surfaces: cards and rows (6%), chips and the message bar (the chip fill), the menu drawer. */
    val surface: Color,
    val surfaceHigh: Color,
    val drawer: Color,
    /** Edges: a hairline (white 9%), a stronger one (13%), and a solid one for chips. */
    val hairline: Color,
    val borderStrong: Color,
    val borderSolid: Color,
    /** The menu's dim over home. */
    val dim: Color,
    /** The voice screen's wave and glow: the accent's family. */
    val aurora: List<Color>,
    /** The orb's dots, and the faint cast on its outermost ring. */
    val orbInk: Color,
    val orbCast: Color,
    /** The two fixed glows under every in-app screen: top-right and bottom-left. */
    val glowTop: Color,
    val glowBottom: Color,
    /** A switch that is off. */
    val switchOff: Color,
    /** Status only, never decoration: done, needs attention, failed. */
    val good: Color,
    val warn: Color,
    /** [warn] at 14%: the fill of a badge that needs noticing (Devnet). */
    val warnSoft: Color,
    val danger: Color
)

private fun c(argb: Int) = Color(argb)

private val accent = c(HeylanaTokens.accent)

private val auroraColours = HeylanaTokens.auroraStops.map { c(it) }

val DarkGlass = HeylanaPalette(
    mode = GlassMode.DARK,
    ground = c(HeylanaTokens.bg),
    ink = c(HeylanaTokens.textPrimary),
    inkSecondary = c(HeylanaTokens.text2),
    inkTertiary = c(HeylanaTokens.textSecondary),
    accent = accent,
    accentHover = c(HeylanaTokens.accentHover),
    accentText = c(HeylanaTokens.accentText),
    accentSoft = c(HeylanaTokens.accentSoft),
    accentDeep = Color(0xFF2F5FD6),
    onAccent = c(HeylanaTokens.onAccent),
    surface = Color(0x0FFFFFFF),
    surfaceHigh = c(HeylanaTokens.chipFill),
    drawer = Color(0xFF121218),
    hairline = c(HeylanaTokens.borderHairline),
    borderStrong = c(HeylanaTokens.borderStrong),
    borderSolid = c(HeylanaTokens.borderSolid),
    dim = Color(0x99000000),
    aurora = auroraColours,
    orbInk = c(HeylanaTokens.orbInk),
    orbCast = accent,
    glowTop = accent.copy(alpha = HeylanaTokens.APP_GLOW_TOP_ALPHA),
    glowBottom = accent.copy(alpha = HeylanaTokens.APP_GLOW_BOTTOM_ALPHA),
    switchOff = c(HeylanaTokens.borderStrong),
    good = c(HeylanaTokens.success),
    warn = c(HeylanaTokens.warn),
    warnSoft = c(HeylanaTokens.warnSoft),
    danger = c(HeylanaTokens.error)
)

/** The same flat screens on white: the accent darkened until its words read on white. */
val LightGlass = HeylanaPalette(
    mode = GlassMode.LIGHT,
    ground = Color(0xFFFFFFFF),
    ink = Color(0xFF14121C),
    inkSecondary = Color(0xFF3E3E4A),
    inkTertiary = Color(0xFF6A6A76),
    accent = accent,
    accentHover = c(HeylanaTokens.accentHover),
    accentText = Color(0xFF2A56C6),
    accentSoft = c(HeylanaTokens.accentSoft),
    accentDeep = Color(0xFF2F5FD6),
    onAccent = c(HeylanaTokens.onAccent),
    surface = Color(0x0F14121C),
    surfaceHigh = Color(0xFFF1F1F5),
    drawer = Color(0xFFF4F4F7),
    hairline = Color(0x1714121C),
    borderStrong = Color(0x2114121C),
    borderSolid = Color(0xFFDADAE2),
    dim = Color(0x6614121C),
    aurora = auroraColours,
    orbInk = Color(0xFF14121C),
    orbCast = accent,
    glowTop = accent.copy(alpha = HeylanaTokens.APP_GLOW_TOP_ALPHA),
    glowBottom = accent.copy(alpha = HeylanaTokens.APP_GLOW_BOTTOM_ALPHA),
    switchOff = Color(0x2914121C),
    good = Color(0xFF15A77A),
    warn = Color(0xFFC96A00),
    warnSoft = Color(0x24C96A00),
    danger = Color(0xFFD13B2E)
)

fun paletteFor(mode: GlassMode): HeylanaPalette = if (mode == GlassMode.LIGHT) LightGlass else DarkGlass

val LocalHeylana = staticCompositionLocalOf { DarkGlass }

/** The app's theme: the glass palette and Outfit. [mode] comes from Settings → Glass mode. */
@Composable
fun HeylanaTheme(
    mode: GlassMode = GlassMode.DARK,
    content: @Composable () -> Unit
) {
    val palette = paletteFor(mode)
    val scheme = if (mode == GlassMode.LIGHT) {
        lightColorScheme(
            primary = palette.accent, onPrimary = palette.onAccent, background = palette.ground,
            surface = palette.ground, onBackground = palette.ink, onSurface = palette.ink
        )
    } else {
        darkColorScheme(
            primary = palette.accent, onPrimary = palette.onAccent, background = palette.ground,
            surface = palette.ground, onBackground = palette.ink, onSurface = palette.ink
        )
    }
    CompositionLocalProvider(LocalHeylana provides palette) {
        MaterialTheme(colorScheme = scheme, typography = HeylanaTypography, content = content)
    }
}
