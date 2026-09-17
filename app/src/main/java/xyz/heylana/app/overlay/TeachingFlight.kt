package xyz.heylana.app.overlay

import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sin

/*
 * The teaching flight, after Clicky by Farza (MIT): its buddy flies to the element it
 * is talking about along a quadratic bezier arc, swelling at the apex, dwells while it
 * explains, then flies back (leanring-buddy/OverlayWindow.swift, animateBezierFlightArc).
 * Ported here at teacher pace: the numbers are Heylana's, the shape of the motion is its.
 */
object TeachingFlight {

    /** How long one hop takes at a teacher's pace, and the room either side of it. */
    const val PACE_MS = 600L
    const val MIN_MS = 380L
    const val MAX_MS = 900L

    /** Distance a PACE_MS hop covers, in dp: everything else scales from it. */
    const val PACE_DP = 420f

    /** The arc's height: a fifth of the distance, never more than this. */
    const val ARC_RATIO = 0.2f
    const val ARC_MAX_DP = 80f

    /** How much bigger the disc gets at the top of the arc. */
    const val SWELL = 0.22f

    /** Longer for a longer hop, but always teacher pace: 380ms to 900ms. */
    fun durationMs(distanceDp: Float): Long =
        (PACE_MS * (distanceDp / PACE_DP)).toLong().coerceIn(MIN_MS, MAX_MS)

    fun arcHeightDp(distanceDp: Float): Float = min(distanceDp * ARC_RATIO, ARC_MAX_DP)

    /** Clicky's easing: 3t² − 2t³, so it leaves and lands gently. */
    fun smoothstep(progress: Float): Float {
        val p = progress.coerceIn(0f, 1f)
        return p * p * (3f - 2f * p)
    }

    /**
     * Where the disc is at [progress] (0 to 1) on the arc from ([x0], [y0]) to
     * ([x1], [y1]), into [out] as x, y. [arcHeight] bows it upward — up the screen,
     * so the disc arcs over what is between the two points.
     */
    fun at(progress: Float, x0: Float, y0: Float, x1: Float, y1: Float, arcHeight: Float, out: FloatArray) {
        val t = smoothstep(progress)
        val one = 1f - t
        val controlX = (x0 + x1) / 2f
        val controlY = (y0 + y1) / 2f - arcHeight
        out[0] = one * one * x0 + 2f * one * t * controlX + t * t * x1
        out[1] = one * one * y0 + 2f * one * t * controlY + t * t * y1
    }

    /** The disc's size at [progress]: a swell that peaks halfway and lands where it started. */
    fun scaleAt(progress: Float): Float = 1f + sin(progress.coerceIn(0f, 1f) * Math.PI.toFloat()) * SWELL

    fun distance(x0: Float, y0: Float, x1: Float, y1: Float): Float = hypot(x1 - x0, y1 - y0)

    /**
     * Where the disc stands while it talks about the element at [left], [top], [right],
     * [bottom]: beside it if there is room — the side with more of it — else under it, else
     * over it, and always on screen. [out] gets the disc's left and top, in the same space.
     */
    fun standBeside(
        left: Int,
        top: Int,
        right: Int,
        bottom: Int,
        discSize: Int,
        gap: Int,
        screenWidth: Int,
        screenHeight: Int,
        out: IntArray
    ) {
        val middleY = (top + bottom) / 2 - discSize / 2
        val besideRight = right + gap
        val besideLeft = left - gap - discSize
        val x = when {
            screenWidth - right >= gap + discSize && screenWidth - right >= left -> besideRight
            left >= gap + discSize -> besideLeft
            else -> Int.MIN_VALUE
        }
        if (x != Int.MIN_VALUE) {
            out[0] = x.coerceIn(0, (screenWidth - discSize).coerceAtLeast(0))
            out[1] = middleY.coerceIn(0, (screenHeight - discSize).coerceAtLeast(0))
            return
        }
        val centreX = ((left + right) / 2 - discSize / 2).coerceIn(0, (screenWidth - discSize).coerceAtLeast(0))
        val below = bottom + gap
        val above = top - gap - discSize
        out[0] = centreX
        out[1] = when {
            below + discSize <= screenHeight -> below
            above >= 0 -> above
            else -> middleY.coerceIn(0, (screenHeight - discSize).coerceAtLeast(0))
        }
    }
}
