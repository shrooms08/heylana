package xyz.heylana.app.ui.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import xyz.heylana.app.ui.HeylanaTokens
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import xyz.heylana.app.ui.theme.LocalHeylana

/**
 * The app's one material: a flat surface. White 6% on black by default, 16dp corners,
 * and nothing else — no border, blur, rim, shadow, streak or beam. The overlay keeps its
 * liquid glass; the app does not use it.
 */
@Composable
fun FlatSurface(
    modifier: Modifier = Modifier,
    radius: Dp = SURFACE_RADIUS,
    fill: Color? = null,
    content: @Composable BoxScope.() -> Unit
) {
    val palette = LocalHeylana.current
    Box(modifier.clip(RoundedCornerShape(radius)).background(fill ?: palette.surface), content = content)
}

/** A page: the ground, anything drawn behind (the orb, the voice glow), and the content. */
@Composable
fun FlatPage(background: @Composable BoxScope.() -> Unit = {}, content: @Composable BoxScope.() -> Unit) {
    val palette = LocalHeylana.current
    Box(
        Modifier.fillMaxSize().background(palette.ground).drawBehind {
            drawIntoCanvas { canvas ->
                val w = size.width
                val h = size.height
                // Two fixed glows, so the page reads blue-black rather than flat black.
                AppGlow.draw(
                    canvas.nativeCanvas, w, 0f, w * HeylanaTokens.APP_GLOW_TOP_RX, h * HeylanaTokens.APP_GLOW_TOP_RY,
                    palette.glowTop.toArgb()
                )
                AppGlow.draw(
                    canvas.nativeCanvas, 0f, h, w * HeylanaTokens.APP_GLOW_BOTTOM_RX, h * HeylanaTokens.APP_GLOW_BOTTOM_RY,
                    palette.glowBottom.toArgb()
                )
            }
        }
    ) {
        background()
        content()
    }
}

/**
 * One of the page's two glows: [argb] at the centre ([cx], [cy]), fading over an ellipse
 * of radii [rx] × [ry] along a smooth curve rather than a straight ramp, and dithered, so
 * at a few percent over near-black it shows no ring or band — only a blue cast.
 */
internal object AppGlow {
    /** Where along the radius each fall-off step sits, and how much of the colour is left there. */
    private val STOPS = floatArrayOf(0f, 0.2f, 0.4f, 0.6f, 0.8f, 1f)
    private val LEFT = floatArrayOf(1f, 0.86f, 0.58f, 0.3f, 0.1f, 0f)

    private val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { isDither = true }

    fun draw(canvas: android.graphics.Canvas, cx: Float, cy: Float, rx: Float, ry: Float, argb: Int) {
        if (rx <= 0f || ry <= 0f) return
        val alpha = android.graphics.Color.alpha(argb)
        val colours = IntArray(STOPS.size) { i ->
            android.graphics.Color.argb((alpha * LEFT[i]).toInt(), android.graphics.Color.red(argb),
                android.graphics.Color.green(argb), android.graphics.Color.blue(argb))
        }
        paint.shader = android.graphics.RadialGradient(0f, 0f, 1f, colours, STOPS, android.graphics.Shader.TileMode.CLAMP)
        canvas.save()
        canvas.translate(cx, cy)
        canvas.scale(rx, ry)
        canvas.drawCircle(0f, 0f, 1f, paint)
        canvas.restore()
    }
}

/** Cards, rows and fields. */
val SURFACE_RADIUS = 16.dp
