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

/** Clear glass on black (the design export), or on a light ground. Settings → Glass mode. */
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
    /** Words on the accent. */
    val onAccent: Color,
    /** The glass: its fill, its 1px hairline, the light along its top edge, its shadow. */
    val glassFill: Color,
    val glassHairline: Color,
    val glassHighlight: Color,
    val glassShadow: Color,
    /** A lit rim, for the selected provider row and the wallet button. */
    val glassRimLit: Color,
    /** Fields inside glass, and the small icon tiles. */
    val fieldFill: Color,
    val tileFill: Color,
    val tileHairline: Color,
    /** The menu's dim over home. */
    val dim: Color,
    /** The orb's aurora, and the glow that rises behind the voice screen. */
    val aurora: List<Color>,
    /** The orb's specular, and the faint mark inside it. */
    val orbSpecular: Color,
    val orbMark: Color,
    val orbShade: Color,
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
    onAccent = Color(0xFFFFFFFF),
    glassFill = Color(0x12FFFFFF),
    glassHairline = Color(0x21FFFFFF),
    glassHighlight = Color(0x38FFFFFF),
    glassShadow = Color(0x8C000000),
    glassRimLit = Color(0x8C8F5BFF),
    fieldFill = Color(0x0FFFFFFF),
    tileFill = Color(0x14FFFFFF),
    tileHairline = Color(0x24FFFFFF),
    dim = Color(0x99000000),
    aurora = auroraColours,
    orbSpecular = Color(0x47FFFFFF),
    orbMark = Color(0x38FFFFFF),
    orbShade = Color(0x66000000),
    switchOff = Color(0x33FFFFFF),
    good = c(HeylanaTokens.ack),
    warn = Color(0xFFFFB86B)
)

val LightGlass = HeylanaPalette(
    mode = GlassMode.LIGHT,
    ground = Color(0xFFF3F1F8),
    ink = Color(0xFF14121C),
    inkSecondary = Color(0xB314121C),
    inkTertiary = Color(0x8C14121C),
    accent = c(HeylanaTokens.accent),
    accentSoft = Color(0xFF6B3BFF),
    onAccent = Color(0xFFFFFFFF),
    glassFill = Color(0x99FFFFFF),
    glassHairline = Color(0x1F14121C),
    glassHighlight = Color(0xE6FFFFFF),
    glassShadow = Color(0x2414121C),
    glassRimLit = Color(0xA68F5BFF),
    fieldFill = Color(0x0D14121C),
    tileFill = Color(0x0F14121C),
    tileHairline = Color(0x1A14121C),
    dim = Color(0x6614121C),
    aurora = auroraColours,
    orbSpecular = Color(0x59FFFFFF),
    orbMark = Color(0x47FFFFFF),
    orbShade = Color(0x33000000),
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
