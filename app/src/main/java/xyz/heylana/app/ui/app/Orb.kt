package xyz.heylana.app.ui.app

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import xyz.heylana.app.R
import xyz.heylana.app.ui.theme.LocalHeylana
import kotlin.math.cos
import kotlin.math.sin

/** What the home orb is doing. */
enum class OrbMode { IDLE, THINKING, LISTENING, SPEAKING }

/**
 * The home orb, after the export's frame 1: an aurora of the four Heylana colours drifting
 * inside a sphere, seen through a fine grid of particles (the 2c orbs' dots, at the size of
 * a hero), a specular up and to the left, the mark faint in the middle.
 *
 * Idle breathes on a 4.4s cycle ([OrbMotion.BREATH_MS]); thinking spins the aurora every
 * 3.4s and runs the gapped ring round it every 1.2s (frame 1b); listening and speaking
 * swell with [level].
 */
@Composable
fun HomeOrb(mode: OrbMode, modifier: Modifier = Modifier, diameter: Dp = 236.dp, level: Float = 0f) {
    val palette = LocalHeylana.current
    val motion = rememberInfiniteTransition(label = "orb")
    val breath by motion.animateFloat(
        0f, 1f, infiniteRepeatable(tween(OrbMotion.BREATH_MS / 2, easing = LinearEasing), RepeatMode.Reverse), label = "breath"
    )
    val drift by motion.animateFloat(
        0f, 1f, infiniteRepeatable(tween(if (mode == OrbMode.THINKING) OrbMotion.THINKING_SPIN_MS else OrbMotion.DRIFT_MS, easing = LinearEasing)),
        label = "drift"
    )
    val ring by motion.animateFloat(
        0f, 360f, infiniteRepeatable(tween(OrbMotion.RING_MS, easing = LinearEasing)), label = "ring"
    )
    val swell = OrbMotion.scale(breath, if (mode == OrbMode.LISTENING || mode == OrbMode.SPEAKING) level else 0f)
    Box(modifier.size(diameter), contentAlignment = Alignment.Center) {
        Canvas(
            Modifier
                .size(diameter)
                .scale(swell)
                .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
        ) {
            drawAurora(drift, palette.aurora)
            drawParticles()
            drawSphereShading(palette.orbSpecular, palette.orbShade)
        }
        Image(
            painter = painterResource(R.drawable.ic_heylana_mark),
            contentDescription = null,
            colorFilter = ColorFilter.tint(palette.orbMark),
            modifier = Modifier.size(diameter * 0.35f).scale(swell)
        )
        if (mode == OrbMode.THINKING) {
            Canvas(Modifier.size(diameter + 12.dp)) {
                rotate(ring) {
                    drawArc(
                        palette.orbSpecular.copy(alpha = 0.85f), startAngle = 0f, sweepAngle = OrbMotion.RING_SWEEP_DEG,
                        useCenter = false, style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round)
                    )
                }
            }
        }
    }
}

/** The numbers that move the orb. */
object OrbMotion {
    const val BREATH_MS = 4_400
    const val DRIFT_MS = 14_000
    const val THINKING_SPIN_MS = 3_400
    const val RING_MS = 1_200
    /** The gapped ring: most of the way round, a gap left. */
    const val RING_SWEEP_DEG = 300f
    const val BREATH_SCALE = 0.035f
    const val LEVEL_SCALE = 0.10f

    /** Breath (0 to 1 and back) and a voice level on top of it. */
    fun scale(breath: Float, level: Float): Float = 1f + BREATH_SCALE * breath + LEVEL_SCALE * level.coerceIn(0f, 1f)
}

/** Four soft blobs of colour, one per aurora stop, circling slowly inside the sphere. */
private fun DrawScope.drawAurora(turn: Float, colours: List<androidx.compose.ui.graphics.Color>) {
    val r = size.minDimension / 2f
    val centre = Offset(size.width / 2f, size.height / 2f)
    val sphere = Path().apply { addOval(androidx.compose.ui.geometry.Rect(centre, r)) }
    clipPath(sphere) {
        drawCircle(colours[0].copy(alpha = 0.30f), r, centre)
        colours.forEachIndexed { i, colour ->
            val a = (turn + i / colours.size.toFloat()) * 2f * Math.PI.toFloat()
            val at = Offset(centre.x + cos(a) * r * 0.48f, centre.y + sin(a) * r * 0.48f)
            drawCircle(Brush.radialGradient(listOf(colour.copy(alpha = 0.72f), colour.copy(alpha = 0f)), at, r * 0.78f), r, centre)
        }
    }
}

/**
 * The particles: the aurora is kept only where the dots are. A tile holding one dot is
 * repeated across the whole sphere and drawn with DstIn, which clears everything between
 * the dots (drawing the dots alone would leave the gaps untouched).
 */
private fun DrawScope.drawParticles() {
    val step = 2.8.dp.toPx()
    val tileSize = step.toInt().coerceAtLeast(2)
    val tile = androidx.compose.ui.graphics.ImageBitmap(tileSize, tileSize)
    val canvas = androidx.compose.ui.graphics.Canvas(tile)
    val paint = androidx.compose.ui.graphics.Paint().apply { color = androidx.compose.ui.graphics.Color.Black; isAntiAlias = true }
    canvas.drawCircle(Offset(tileSize / 2f, tileSize / 2f), tileSize * 0.42f, paint)
    val shader = androidx.compose.ui.graphics.ImageShader(
        tile, androidx.compose.ui.graphics.TileMode.Repeated, androidx.compose.ui.graphics.TileMode.Repeated
    )
    drawRect(androidx.compose.ui.graphics.ShaderBrush(shader), blendMode = BlendMode.DstIn)
}

/** A specular up and to the left, and a shade toward the rim, so the grid reads as a sphere. */
private fun DrawScope.drawSphereShading(specular: androidx.compose.ui.graphics.Color, shade: androidx.compose.ui.graphics.Color) {
    val r = size.minDimension / 2f
    val centre = Offset(size.width / 2f, size.height / 2f)
    drawCircle(
        Brush.radialGradient(listOf(specular, specular.copy(alpha = 0f)), Offset(centre.x - r * 0.32f, centre.y - r * 0.44f), r * 0.84f),
        r, centre, blendMode = BlendMode.SrcAtop
    )
    drawCircle(
        Brush.radialGradient(listOf(shade.copy(alpha = 0f), shade.copy(alpha = 0f), shade), centre, r),
        r, centre, blendMode = BlendMode.SrcAtop
    )
}
