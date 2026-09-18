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
    /** Words: primary, then the two quieter steps the export uses (62% and 50%). */
    val ink: Color,
    val inkSecondary: Color,
    val inkTertiary: Color,
    /** Heylana purple, and the lighter purple the export uses for icons and labels. */
    val accent: Color,
    val accentSoft: Color,
    /** The accent's shaded side: the far edge of the voice screen's mic. */
    val accentDeep: Color,
    /** Words on the accent. */
    val onAccent: Color,
    /** Flat surfaces: cards and rows (6%), chips and the message bar (8%), the menu drawer. */
    val surface: Color,
    val surfaceHigh: Color,
    val drawer: Color,
    /** The menu's dim over home. */
    val dim: Color,
    /** The orb's aurora tint, and the glow that rises behind the voice screen. */
    val aurora: List<Color>,
    /** A switch that is off. */
    val switchOff: Color,
    /** A row that is done, and one that is not. */
    val good: Color,
    val warn: Color
)

private fun c(argb: Int) = Color(argb)

private val auroraColours = listOf(
    c(HeylanaTokens.accent), Color(0xFFE250BE), Color(0xFFFF8A40), c(HeylanaTokens.auroraStops[2])
)

val DarkGlass = HeylanaPalette(
    mode = GlassMode.DARK,
    ground = Color(0xFF000000),
    ink = Color(0xFFFFFFFF),
    inkSecondary = Color(0x9EFFFFFF),
    inkTertiary = Color(0x80FFFFFF),
    accent = c(HeylanaTokens.accent),
    accentSoft = Color(0xFFC9B2FF),
    accentDeep = Color(0xFF5B2BC9),
    onAccent = Color(0xFFFFFFFF),
    surface = Color(0x0FFFFFFF),
    surfaceHigh = Color(0x14FFFFFF),
    drawer = Color(0xFF131315),
    dim = Color(0x99000000),
    aurora = auroraColours,
    switchOff = Color(0x33FFFFFF),
    good = c(HeylanaTokens.ack),
    warn = Color(0xFFFFB86B)
)

val LightGlass = HeylanaPalette(
    mode = GlassMode.LIGHT,
    ground = Color(0xFFFFFFFF),
    ink = Color(0xFF14121C),
    inkSecondary = Color(0xB314121C),
    inkTertiary = Color(0x8C14121C),
    accent = c(HeylanaTokens.accent),
    accentSoft = Color(0xFF6B3BFF),
    accentDeep = Color(0xFF5B2BC9),
    onAccent = Color(0xFFFFFFFF),
    surface = Color(0x0F14121C),
    surfaceHigh = Color(0x1414121C),
    drawer = Color(0xFFF4F4F7),
    dim = Color(0x6614121C),
    aurora = auroraColours,
    switchOff = Color(0x2914121C),
    good = Color(0xFF15A77A),
    warn = Color(0xFFC96A00)
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
