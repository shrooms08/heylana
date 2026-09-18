package xyz.heylana.app.ui.app

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import xyz.heylana.app.orbs.Dot
import xyz.heylana.app.orbs.OrbEngine
import xyz.heylana.app.orbs.OrbState
import xyz.heylana.app.ui.HeylanaTokens
import xyz.heylana.app.ui.theme.GlassMode
import xyz.heylana.app.ui.theme.LocalHeylana
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.floor

/** What the app's orb is doing. */
enum class OrbMode { IDLE, THINKING, LISTENING, SPEAKING }

/**
 * Debug builds only (`-e orb_t 3.3 --ez orb_mono true -e orb_mode thinking` on launch): pins
 * every orb's clock to one engine instant, drops the tint and forces Home's state, so a
 * frame on the phone can be compared with the library's own render of the same instant.
 */
object OrbDebug {
    @Volatile var frozenT: Double? = null
    @Volatile var mono: Boolean = false
    @Volatile var forcedMode: OrbMode? = null
}

/**
 * How the app draws a thinking-orbs frame, the library's way (its SwiftUI port's
 * `displaySize`): the frame is computed at the 64px tuning by the ported engine
 * ([OrbEngine], held to the library's golden vectors) and scaled inside the canvas, so
 * density, dot sizes, depth and alpha are the library's at any size. Each dot's colour is
 * the library's ink ramp: grey quantised to 8 bits and mirrored on a dark page, or, with a
 * tint, the tint faded toward the page with depth (`inkColor` in core.ts). The app's tint
 * is the aurora, by the dot's angle around the centre, turning slowly.
 */
object AppOrb {

    /** The engine's tuning the geometry is computed at. */
    const val GEOMETRY_SIZE = 64

    /** Idle is the library's breathing ring, at half its preset pace: at rest, not busy. */
    const val IDLE_SPEED = 0.5

    /** How far a loud voice swells the orb, and speeds it up. */
    const val LEVEL_SWELL = 0.12f
    const val LEVEL_SPEED = 0.8

    /** One turn of the aurora around the orb. */
    const val AURORA_TURN_S = 12.0

    fun stateFor(mode: OrbMode): OrbState = when (mode) {
        OrbMode.IDLE -> OrbState.BREATHING
        OrbMode.THINKING -> OrbState.WORKING
        OrbMode.LISTENING -> OrbState.LISTENING
        OrbMode.SPEAKING -> OrbState.COMPOSING
    }

    fun speedFor(mode: OrbMode): Double = if (mode == OrbMode.IDLE) IDLE_SPEED else 1.0

    /** JavaScript's Math.round, as the library quantises with. */
    private fun jsRound(x: Double): Int = floor(x + 0.5).toInt()

    /** The library's grey ink: 0 is darkest on paper, mirrored on a dark page. */
    fun grey(white: Double, dark: Boolean): Int {
        val w = white.coerceIn(0.0, 1.0)
        return jsRound((if (dark) 1 - w else w) * 255)
    }

    /** The library's tinted ink for one channel [c] (0–255). */
    fun tinted(c: Int, white: Double, dark: Boolean): Int {
        val w = white.coerceIn(0.0, 1.0)
        return jsRound(if (dark) c * (1 - w) else c + (255 - c) * w)
    }

    /** The aurora stops around the circle, blended and wrapping; [turn] in turns. */
    fun auroraAt(stops: List<Color>, turn: Double): Color {
        val position = ((turn % 1.0) + 1.0) % 1.0 * stops.size
        val index = position.toInt() % stops.size
        val f = (position - position.toInt()).toFloat()
        val a = stops[index]
        val b = stops[(index + 1) % stops.size]
        return Color(a.red + (b.red - a.red) * f, a.green + (b.green - a.green) * f, a.blue + (b.blue - a.blue) * f)
    }
}

/**
 * The orb, the character on Home (200dp) and in the voice screen's header (48dp). No mark
 * inside it. [level] is the playback or microphone level, 0 to 1. [frozenT] pins the
 * engine clock (raw engine time, as the library's parity harness uses) and [mono] drops
 * the tint, so a frame can be compared with the library's own render.
 */
@Composable
fun LibraryOrb(
    mode: OrbMode,
    modifier: Modifier = Modifier,
    diameter: Dp = 200.dp,
    level: Float = 0f,
    frozenT: Double? = null,
    mono: Boolean = false
) {
    val palette = LocalHeylana.current
    val dark = palette.mode == GlassMode.DARK
    val pinnedT = frozenT ?: OrbDebug.frozenT
    val plain = mono || OrbDebug.mono
    val currentLevel by rememberUpdatedState(level)

    // Each state keeps its own clock, advanced by real frame time at its preset speed, so
    // a loud voice can speed it up without a jump.
    val clocks = remember { DoubleArray(OrbMode.entries.size) }
    var tick by remember { mutableDoubleStateOf(0.0) }
    var shown by remember { mutableStateOf(mode) }
    var leaving by remember { mutableStateOf<OrbMode?>(null) }
    val fade = remember { Animatable(1f) }

    LaunchedEffect(mode) {
        if (mode == shown) return@LaunchedEffect
        leaving = shown
        shown = mode
        fade.snapTo(0f)
        fade.animateTo(1f, tween(HeylanaTokens.ORB_DISSOLVE_MS.toInt(), easing = LinearEasing))
        leaving = null
    }
    LaunchedEffect(pinnedT) {
        if (pinnedT != null) return@LaunchedEffect
        var last = 0L
        while (true) {
            withFrameNanos { now ->
                if (last != 0L) {
                    val dt = (now - last) / 1e9
                    for (m in OrbMode.entries) {
                        val preset = OrbEngine.resolve(AppOrb.stateFor(m), AppOrb.GEOMETRY_SIZE)
                        clocks[m.ordinal] += dt * preset.speed * AppOrb.speedFor(m) * (1 + AppOrb.LEVEL_SPEED * currentLevel)
                    }
                    tick += dt
                }
                last = now
            }
        }
    }

    Canvas(modifier.size(diameter)) {
        val seconds = tick
        val swell = 1f + AppOrb.LEVEL_SWELL * level
        val t = { m: OrbMode -> pinnedT ?: clocks[m.ordinal] }
        leaving?.let { drawOrb(OrbEngine.frame(AppOrb.stateFor(it), AppOrb.GEOMETRY_SIZE, t(it)), 1f - fade.value, swell, dark, plain, palette.aurora, seconds) }
        drawOrb(OrbEngine.frame(AppOrb.stateFor(shown), AppOrb.GEOMETRY_SIZE, t(shown)), fade.value, swell, dark, plain, palette.aurora, seconds)
    }
}

private fun DrawScope.drawOrb(
    frame: List<Dot>,
    alpha: Float,
    swell: Float,
    dark: Boolean,
    mono: Boolean,
    aurora: List<Color>,
    seconds: Double
) {
    if (alpha <= 0.01f) return
    val zoom = size.minDimension / AppOrb.GEOMETRY_SIZE * swell
    val half = AppOrb.GEOMETRY_SIZE / 2.0
    val cx = size.width / 2f
    val cy = size.height / 2f
    val turn = seconds / AppOrb.AURORA_TURN_S
    for (d in frame) {
        val dx = (d.x - half).toFloat()
        val dy = (d.y - half).toFloat()
        val a = (d.a * alpha).toFloat().coerceIn(0f, 1f)
        val colour = if (mono) {
            val g = AppOrb.grey(d.white, dark)
            Color(g, g, g)
        } else {
            val tint = AppOrb.auroraAt(aurora, atan2(dy, dx) / (2 * PI) + turn)
            Color(
                AppOrb.tinted((tint.red * 255).toInt(), d.white, dark),
                AppOrb.tinted((tint.green * 255).toInt(), d.white, dark),
                AppOrb.tinted((tint.blue * 255).toInt(), d.white, dark)
            )
        }
        drawCircle(colour.copy(alpha = a), radius = d.r.toFloat() * zoom, center = Offset(cx + dx * zoom, cy + dy * zoom))
    }
}
