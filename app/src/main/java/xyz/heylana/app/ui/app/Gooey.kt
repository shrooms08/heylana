package xyz.heylana.app.ui.app

import android.graphics.ColorMatrixColorFilter
import android.graphics.RenderEffect
import android.graphics.Shader
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import xyz.heylana.app.overlay.GooeySpec
import xyz.heylana.app.ui.HeylanaTokens
import xyz.heylana.app.ui.theme.LocalHeylana

/**
 * A surface arriving like the overlay's box does (liquid-gooey, MIT): its silhouette grows
 * out of a seed — a small round blob at [seedX], [seedY] (fractions of the space) — through
 * the goo filter (a [GooeySpec.BLUR_DP] blur, then alpha × [GooeySpec.CONTRAST] with the
 * library's intercept), so it bridges and swells like liquid, and the real surface fades in
 * over it as it lands. Leaving runs it backwards. Used for the name card, the menu sheet
 * and the chips.
 */
@Composable
fun GooeyReveal(
    visible: Boolean,
    modifier: Modifier = Modifier,
    seedX: Float = 0.5f,
    seedY: Float = 0f,
    radius: Dp = 24.dp,
    content: @Composable BoxScope.() -> Unit
) {
    val progress by animateFloatAsState(
        if (visible) 1f else 0f,
        spring(dampingRatio = HeylanaTokens.SPRING_DAMPING, stiffness = HeylanaTokens.SPRING_STIFFNESS * 0.6f),
        label = "goo"
    )
    if (progress <= 0.001f && !visible) return
    val palette = LocalHeylana.current
    val density = LocalDensity.current
    val effect = remember(density) { gooeyEffect(GooeySpec.BLUR_DP * density.density) }
    val radiusPx = with(density) { radius.toPx() }
    Box(modifier) {
        if (progress < 0.999f) {
            Canvas(
                Modifier.matchParentSize().graphicsLayer {
                    renderEffect = effect.asComposeRenderEffect()
                    alpha = GooeySpec.SILHOUETTE_ALPHA * (1f - smooth(progress, 0.75f, 1f))
                }
            ) {
                val out = FloatArray(5)
                val seed = size.minDimension * 0.18f
                GooeySpec.growFromDisc(
                    size.width * seedX, size.height * seedY, seed,
                    0f, 0f, size.width, size.height, radiusPx, progress, out
                )
                drawRoundRect(
                    palette.inkTertiary, Offset(out[0], out[1]), Size(out[2] - out[0], out[3] - out[1]), CornerRadius(out[4])
                )
                // The droplet left behind at the seed, drawn back into the body as it grows.
                drawCircle(palette.inkTertiary, seed * 0.5f * (1f - progress), Offset(size.width * seedX, size.height * seedY))
            }
        }
        Box(Modifier.matchParentSize().alpha(smooth(progress, 0.55f, 1f)), content = content)
    }
}

/** The goo: blur, then the alpha contrast that turns the soft edge back into a hard one. */
fun gooeyEffect(blurPx: Float): RenderEffect = RenderEffect.createColorFilterEffect(
    ColorMatrixColorFilter(GooeySpec.alphaContrast()),
    RenderEffect.createBlurEffect(blurPx, blurPx, Shader.TileMode.DECAL)
)

private fun smooth(x: Float, from: Float, to: Float): Float {
    val t = ((x - from) / (to - from)).coerceIn(0f, 1f)
    return t * t * (3f - 2f * t)
}
