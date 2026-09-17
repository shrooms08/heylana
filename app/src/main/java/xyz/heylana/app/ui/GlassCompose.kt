package xyz.heylana.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * The one glass recipe, for Compose screens.
 *
 * Settings is Compose and the overlay is Views, but there is only one glass: this
 * paints [GlassDrawable] behind the content rather than inventing a Compose look
 * of its own. Colours, radii, spacing and type all come from [HeylanaTokens].
 */
@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    radiusDp: Float = HeylanaTokens.RADIUS_CARD_DP,
    primary: Boolean = false,
    content: @Composable ColumnScope.() -> Unit
) {
    val context = LocalContext.current
    val glass = remember(radiusDp, primary) {
        GlassDrawable(
            context,
            cornerRadiusDp = radiusDp,
            blurBehind = false,
            kind = GlassDrawable.Kind.PANEL,
            bandColor = if (primary) HeylanaTokens.bandPrimary else HeylanaTokens.purpleBand
        )
    }
    Column(
        modifier = modifier
            .fillMaxWidth()
            .drawBehind {
                drawIntoCanvas { canvas ->
                    glass.setBounds(0, 0, size.width.toInt(), size.height.toInt())
                    glass.draw(canvas.nativeCanvas)
                }
            }
            .padding((HeylanaTokens.SPACE_5_DP).dp),
        content = content
    )
}

/** A pill of the same glass. Primary is the band at full strength, never a solid fill. */
@Composable
fun GlassButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    primary: Boolean = false,
    enabled: Boolean = true
) {
    val context = LocalContext.current
    val glass = remember(primary) {
        GlassDrawable(
            context,
            cornerRadiusDp = HeylanaTokens.RADIUS_FULL_DP,
            blurBehind = false,
            kind = GlassDrawable.Kind.PILL,
            bandColor = if (primary) HeylanaTokens.bandPrimary else HeylanaTokens.purpleBand,
            selected = primary
        )
    }
    Column(
        modifier = modifier
            .alpha(if (enabled) 1f else DISABLED_ALPHA)
            .drawBehind {
                drawIntoCanvas { canvas ->
                    glass.setBounds(0, 0, size.width.toInt(), size.height.toInt())
                    glass.draw(canvas.nativeCanvas)
                }
            }
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = HeylanaTokens.SPACE_5_DP.dp, vertical = HeylanaTokens.SPACE_3_DP.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // A primary button is the selected chip: white glass, dark text.
        val colour = if (primary && !GlassSpec.TINTED_EXTRAS) GlassSpec.CHIP_SELECTED_TEXT else HeylanaTokens.textPrimary
        Text(text = text, style = glassText(HeylanaTokens.BODY_SP, colour))
    }
}

/** Heylana's text on glass, from the tokens. */
fun glassText(sizeSp: Float, color: Int): TextStyle =
    TextStyle(color = Color(color), fontSize = sizeSp.sp, fontFamily = FontFamily.SansSerif)

private const val DISABLED_ALPHA = 0.45f
