package xyz.heylana.app.ui.app

import android.graphics.RenderEffect
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.os.Build
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import xyz.heylana.app.ui.GlassSpec
import xyz.heylana.app.ui.theme.HeylanaPalette
import xyz.heylana.app.ui.theme.LocalHeylana

/**
 * What the glass refracts: everything drawn behind the surfaces on a screen — the ground,
 * the orb, the aurora — recorded into one [GraphicsLayer] each frame by [backdropSource].
 * Because the app owns its pixels, each surface bends the real content behind it, not a
 * stand-in (the overlay has to refract its own backing; here it does not).
 */
@Stable
class Backdrop(val layer: GraphicsLayer) {
    /** Moves on each frame the backdrop animates, so the surfaces redraw with it. */
    var frame by mutableLongStateOf(0L)
}

@Composable
fun rememberBackdrop(): Backdrop {
    val layer = rememberGraphicsLayer()
    return remember(layer) { Backdrop(layer) }
}

/** Put on the full-screen box that holds what is behind the glass: records it for the surfaces. */
fun Modifier.backdropSource(backdrop: Backdrop): Modifier = drawWithContent {
    backdrop.layer.record { this@drawWithContent.drawContent() }
    drawLayer(backdrop.layer)
}

/**
 * One piece of clear glass: the content behind it blurred ([GlassSpec.BLUR_BEHIND_DP]) and
 * refracted by the same ClearGlass shader and numbers as the overlay's panels (band, pull,
 * magnification, rim, lens line, the 1px hairline), then the export's fill, hairline and
 * top highlight on top, and a soft shadow outside. Below API 33 the refraction is left out
 * and the rest stands. [lit] gives it the brighter purple rim of a selected row.
 */
@Composable
fun GlassSurface(
    backdrop: Backdrop?,
    modifier: Modifier = Modifier,
    radius: Dp = 24.dp,
    lit: Boolean = false,
    brighterRim: Boolean = false,
    content: @Composable BoxScope.() -> Unit
) {
    val palette = LocalHeylana.current
    var origin by remember { mutableStateOf(Offset.Zero) }
    val density = LocalDensity.current
    val radiusPx = with(density) { radius.toPx() }
    Box(
        modifier
            .glassShadow(palette, radiusPx)
            .onGloballyPositioned { origin = it.positionInRoot() }
    ) {
        if (backdrop != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Refraction(backdrop, origin, radiusPx, density.density)
        }
        Box(
            Modifier.matchParentSize().drawBehind {
                val r = CornerRadius(radiusPx.coerceAtMost(size.minDimension / 2f))
                drawRoundRect(palette.glassFill, cornerRadius = r)
                val rim = when {
                    lit -> palette.glassRimLit
                    brighterRim -> palette.glassHighlight
                    else -> palette.glassHairline
                }
                drawRoundRect(rim, cornerRadius = r, style = Stroke(width = 1.dp.toPx()))
                // The light along the top edge: an inset line, bright in the middle.
                val inset = r.x.coerceAtLeast(1f)
                drawLine(
                    palette.glassHighlight,
                    Offset(inset, 1.dp.toPx()),
                    Offset(size.width - inset, 1.dp.toPx()),
                    strokeWidth = 1.dp.toPx()
                )
            }
        )
        content()
    }
}

@Composable
private fun BoxScope.Refraction(backdrop: Backdrop, origin: Offset, radiusPx: Float, density: Float) {
    val shader = remember { RuntimeShader(GlassSpec.AGSL) }
    var effectFor by remember { mutableStateOf<Pair<Size, android.graphics.RenderEffect?>>(Size.Zero to null) }
    Box(
        Modifier
            .matchParentSize()
            .graphicsLayer {
                if (size.width <= 0f || size.height <= 0f) return@graphicsLayer
                if (effectFor.first != size) effectFor = size to glassEffect(shader, size, radiusPx, density)
                renderEffect = effectFor.second?.asComposeRenderEffect()
            }
            .drawBehind {
                backdrop.frame
                translate(-origin.x, -origin.y) { drawLayer(backdrop.layer) }
            }
    )
}

/** Blur first, then the ClearGlass lens over it: the same numbers as a panel in the overlay. */
private fun glassEffect(shader: RuntimeShader, size: Size, radiusPx: Float, density: Float): RenderEffect? {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return null
    val surface = GlassSpec.PANEL
    shader.setFloatUniform("origin", 0f, 0f)
    shader.setFloatUniform("size", size.width, size.height)
    shader.setFloatUniform("corner", radiusPx.coerceAtMost(size.minDimension / 2f))
    // A chip is 44dp tall: the panel's 30dp band would be all band, so it never takes more
    // than 40% of the surface's short side.
    val shortSide = size.minDimension
    shader.setFloatUniform("edge", (surface.edgeDp * density).coerceAtMost(shortSide * 0.4f))
    shader.setFloatUniform("strength", (surface.strengthDp * density).coerceAtMost(shortSide * 0.4f))
    shader.setFloatUniform("magnification", surface.magnification)
    shader.setFloatUniform("bandBlur", GlassSpec.BAND_BLUR_DP * density)
    shader.setFloatUniform("specCentre", size.width * 0.38f, 4f * density)
    shader.setFloatUniform("specSize", size.width * 0.30f, 3.5f * density)
    shader.setFloatUniform("gradientReach", GlassSpec.GRADIENT_REACH_DP * density)
    shader.setFloatUniform("streakCentre", 0f)
    shader.setFloatUniform("streakWidth", 1f)
    shader.setFloatUniform("streakTrailBehind", 0f)
    shader.setFloatUniform("streakTrailWidth", 1f)
    shader.setFloatUniform("streakStrength", 0f)
    val blur = GlassSpec.BLUR_BEHIND_DP * density
    val lens = RenderEffect.createRuntimeShaderEffect(shader, "layer")
    return RenderEffect.createChainEffect(lens, RenderEffect.createBlurEffect(blur, blur, Shader.TileMode.CLAMP))
}

/** The export's shadow: 0 10 28 at 55% black (softer in light glass), outside the shape. */
private fun Modifier.glassShadow(palette: HeylanaPalette, radiusPx: Float): Modifier = drawBehind {
    val r = CornerRadius(radiusPx.coerceAtMost(size.minDimension / 2f))
    val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.TRANSPARENT
        setShadowLayer(14.dp.toPx(), 0f, 10.dp.toPx(), palette.glassShadow.toArgbInt())
    }
    drawIntoCanvas { it.nativeCanvas.drawRoundRect(0f, 0f, size.width, size.height, r.x, r.y, paint) }
}

private fun Color.toArgbInt(): Int = android.graphics.Color.argb(
    (alpha * 255).toInt(), (red * 255).toInt(), (green * 255).toInt(), (blue * 255).toInt()
)

/** A full-screen page with its ground drawn and recorded for the glass on it. */
@Composable
fun GlassPage(backdrop: Backdrop, background: @Composable BoxScope.() -> Unit, content: @Composable BoxScope.() -> Unit) {
    val palette = LocalHeylana.current
    Box(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize().backdropSource(backdrop).drawBehind { drawRect(palette.ground) }) { background() }
        content()
    }
}
