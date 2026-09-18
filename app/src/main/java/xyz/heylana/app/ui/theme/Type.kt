package xyz.heylana.app.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import xyz.heylana.app.R

/** Outfit, the one variable font, at the three weights the design uses. Nothing else. */
@OptIn(androidx.compose.ui.text.ExperimentalTextApi::class)
val Outfit = FontFamily(
    Font(R.font.outfit_variable, FontWeight.Light, variationSettings = FontVariation.Settings(FontVariation.weight(300))),
    Font(R.font.outfit_variable, FontWeight.Normal, variationSettings = FontVariation.Settings(FontVariation.weight(400))),
    Font(R.font.outfit_variable, FontWeight.Medium, variationSettings = FontVariation.Settings(FontVariation.weight(500)))
)

/** The export's type, by name: sizes and weights from Heylana_App_Screens. */
object HeylanaType {
    /** "What do you need?", "00:12": 32sp light, tight. */
    val display = TextStyle(fontFamily = Outfit, fontWeight = FontWeight.Light, fontSize = 32.sp, lineHeight = 37.sp, letterSpacing = (-0.64).sp)
    /** Screen titles: "Skill market", "Use my own key". */
    val title = TextStyle(fontFamily = Outfit, fontWeight = FontWeight.Normal, fontSize = 30.sp, lineHeight = 36.sp, letterSpacing = (-0.3).sp)
    /** Row titles and the message bar. */
    val body = TextStyle(fontFamily = Outfit, fontWeight = FontWeight.Normal, fontSize = 16.sp, lineHeight = 22.sp)
    val bodyLight = TextStyle(fontFamily = Outfit, fontWeight = FontWeight.Light, fontSize = 16.sp, lineHeight = 22.sp)
    val bodyMedium = TextStyle(fontFamily = Outfit, fontWeight = FontWeight.Medium, fontSize = 16.sp, lineHeight = 22.sp)
    /** Subtitles, notes, row summaries. */
    val small = TextStyle(fontFamily = Outfit, fontWeight = FontWeight.Light, fontSize = 13.sp, lineHeight = 19.sp)
    /** Chips, the pill's name, buttons. */
    val label = TextStyle(fontFamily = Outfit, fontWeight = FontWeight.Medium, fontSize = 13.sp, lineHeight = 17.sp)
    /** Section heads: "PROVIDER", "API KEY", "LISTENING". */
    val caps = TextStyle(fontFamily = Outfit, fontWeight = FontWeight.Medium, fontSize = 13.sp, lineHeight = 17.sp, letterSpacing = 1.5.sp)
    val tiny = TextStyle(fontFamily = Outfit, fontWeight = FontWeight.Normal, fontSize = 11.sp, lineHeight = 13.sp)
}

val HeylanaTypography = Typography(
    displaySmall = HeylanaType.display,
    headlineMedium = HeylanaType.title,
    titleMedium = HeylanaType.bodyMedium,
    bodyLarge = HeylanaType.body,
    bodyMedium = HeylanaType.small,
    labelLarge = HeylanaType.label,
    labelMedium = HeylanaType.caps
)
