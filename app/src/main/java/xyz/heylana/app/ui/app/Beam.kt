package xyz.heylana.app.ui.app

import android.os.Build
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.unit.Dp
import xyz.heylana.app.ui.BeamShader
import xyz.heylana.app.ui.BorderBeam

/**
 * The border beam from the overlay (border-beam, MIT): the aurora laps the surface's rim
 * once every [BorderBeam.LAP_MS] while Heylana is thinking or listening. [strength] 0 is off.
 */
@Composable
fun Modifier.beam(strength: Float, radius: Dp): Modifier {
    if (strength <= 0f || Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return this
    val shader = remember { BeamShader() }
    val phase by rememberInfiniteTransition(label = "beam").animateFloat(
        0f, 1f, infiniteRepeatable(tween(BorderBeam.LAP_MS.toInt(), easing = LinearEasing)), label = "lap"
    )
    return drawWithContent {
        drawContent()
        val glow = BorderBeam.PANEL_GLOW_DP * density
        shader.set(0f, 0f, size.width, size.height, radius.toPx().coerceAtMost(size.minDimension / 2f), phase, strength, glow)
        drawIntoCanvas { it.nativeCanvas.drawRect(-glow * 2, -glow * 2, size.width + glow * 2, size.height + glow * 2, shader.paint) }
    }
}
