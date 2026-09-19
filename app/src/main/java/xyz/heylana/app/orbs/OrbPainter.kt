package xyz.heylana.app.orbs

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import xyz.heylana.app.ui.HeylanaTokens
import kotlin.math.PI
import kotlin.math.atan2

/**
 * Draws an orb frame onto the disc's face: the library's dots in the ink, with a faint
 * accent cast on the outermost ring, on a dark substrate (so near dots read bright, as the library
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
        // How far out the outermost dot sits: the accent cast is measured against it.
        var outer = 0.0
        for (dot in frame) outer = maxOf(outer, kotlin.math.hypot(dot.x - centre, dot.y - centre))
        if (outer <= 0.0) outer = 1.0
        for (dot in frame) {
            val dx = (dot.x - centre).toFloat() * scale * gather
            val dy = (dot.y - centre).toFloat() * scale * gather
            val ink = (1.0 - OrbEngine.clamp01(dot.white)).toFloat()
            val colour = inkAt((kotlin.math.hypot(dot.x - centre, dot.y - centre) / outer).toFloat())
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

        /**
         * Every live state is the ink — white — with the faint accent cast on the outermost
         * ring only, as the app's orb: from [HeylanaTokens.ORB_CAST_FROM] of the way out,
         * easing in to [HeylanaTokens.ORB_CAST]. [outward] is the dot's distance from the
         * centre over the outermost dot's.
         */
        fun inkAt(outward: Float): Int {
            val from = HeylanaTokens.ORB_CAST_FROM
            val x = ((outward - from) / (1f - from)).coerceIn(0f, 1f)
            return mix(HeylanaTokens.orbInk, HeylanaTokens.accent, x * x * (3f - 2f * x) * HeylanaTokens.ORB_CAST)
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

    }
}
