package xyz.heylana.app.orbs

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import xyz.heylana.app.ui.HeylanaTokens
import kotlin.math.PI
import kotlin.math.atan2

/**
 * Draws an orb frame onto the disc's face: the library's dots, tinted from
 * Heylana's tokens, on a dark substrate (so near dots read bright, as the library
 * mirrors ink on dark themes).
 *
 * Geometry is always the library's 64px tuning, scaled to [diameter], so every
 * state keeps the density and dot sizes it was tuned at.
 */
class OrbPainter {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

    /**
     * [t] is engine time (already multiplied by the preset speed). [alpha] fades the
     * whole orb; [gather] 0 pulls every dot to the centre, 1 leaves it where the
     * frame put it — the dissolve between the mark and the orb. [hue] turns the
     * aurora of the thinking state.
     */
    fun draw(
        canvas: Canvas,
        state: OrbState,
        t: Double,
        cx: Float,
        cy: Float,
        diameter: Float,
        alpha: Float,
        gather: Float,
        hue: Float
    ) {
        if (alpha <= 0.01f) return
        val frame = OrbEngine.frame(state, GEOMETRY_SIZE, t)
        val scale = diameter / GEOMETRY_SIZE
        val centre = GEOMETRY_SIZE / 2.0
        val tint = tintFor(state)
        for (dot in frame) {
            val dx = (dot.x - centre).toFloat() * scale * gather
            val dy = (dot.y - centre).toFloat() * scale * gather
            val ink = (1.0 - OrbEngine.clamp01(dot.white)).toFloat()
            val colour = if (state == OrbState.BREATHING) {
                auroraAt(atan2(dy, dx) / (2f * PI.toFloat()) + hue)
            } else {
                tint
            }
            paint.color = Color.argb(
                (dot.a.toFloat() * alpha * 255f).toInt().coerceIn(0, 255),
                (Color.red(colour) * ink).toInt(),
                (Color.green(colour) * ink).toInt(),
                (Color.blue(colour) * ink).toInt()
            )
            canvas.drawCircle(cx + dx, cy + dy, dot.r.toFloat() * scale * (0.5f + 0.5f * gather), paint)
        }
    }

    companion object {
        /** The library's tuning size the geometry is always computed at. */
        const val GEOMETRY_SIZE = 64

        /** Listening and speaking in the glow, working in the accent lifted toward white. */
        fun tintFor(state: OrbState): Int = when (state) {
            OrbState.LISTENING -> HeylanaTokens.glow or 0xFF000000.toInt()
            OrbState.WORKING -> mix(HeylanaTokens.accent, Color.WHITE, WORKING_LIFT)
            OrbState.COMPOSING -> mix(HeylanaTokens.glow or 0xFF000000.toInt(), Color.WHITE, SPEAKING_LIFT)
            OrbState.BREATHING -> HeylanaTokens.auroraStops[0]
        }

        /** The aurora stops around the circle, blended, wrapping. [turn] is in turns. */
        fun auroraAt(turn: Float): Int {
            val stops = HeylanaTokens.auroraStops
            val position = ((turn % 1f) + 1f) % 1f * stops.size
            val index = position.toInt() % stops.size
            return mix(stops[index], stops[(index + 1) % stops.size], position - position.toInt())
        }

        fun mix(a: Int, b: Int, f: Float): Int = Color.rgb(
            (Color.red(a) + (Color.red(b) - Color.red(a)) * f).toInt(),
            (Color.green(a) + (Color.green(b) - Color.green(a)) * f).toInt(),
            (Color.blue(a) + (Color.blue(b) - Color.blue(a)) * f).toInt()
        )

        private const val WORKING_LIFT = 0.3f
        private const val SPEAKING_LIFT = 0.25f
    }
}
