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
     * [bottom], with its strip ([stripWidth] wide) beside it: next to the element on the side
     * with room for the disc and the strip, the strip on the far side — else under it, else
     * over it — always on screen, and never with the strip pushed back over the element.
     * [out] gets the disc's left and top, and (if it has a third slot) 1 when the strip goes
     * on the disc's left, 0 on its right.
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
        out: IntArray,
        stripWidth: Int = 0
    ) {
        val middleY = ((top + bottom) / 2 - discSize / 2).coerceIn(0, (screenHeight - discSize).coerceAtLeast(0))
        val roomRight = screenWidth - right
        val roomLeft = left
        val need = gap + discSize + stripWidth
        fun put(x: Int, y: Int, stripOnLeft: Boolean) {
            out[0] = x
            out[1] = y
            if (out.size > 2) out[2] = if (stripOnLeft) 1 else 0
        }
        // Beside it, on whichever side has room for the disc and its strip; the more room first.
        val sides = if (roomRight >= roomLeft) listOf(true, false) else listOf(false, true)
        for (onRight in sides) {
            if (onRight && roomRight >= need) return put(right + gap, middleY, stripOnLeft = false)
            if (!onRight && roomLeft >= need) return put(left - gap - discSize, middleY, stripOnLeft = true)
        }
        // Under it, else over it: the disc near the element's middle, the strip on the side with
        // more room, and the pair slid along so both are on screen.
        val below = bottom + gap
        val above = top - gap - discSize
        val y = when {
            below + discSize <= screenHeight -> below
            above >= 0 -> above
            else -> middleY
        }
        val centre = (left + right) / 2 - discSize / 2
        val stripOnLeft = centre + discSize / 2 > screenWidth / 2
        val minX = if (stripOnLeft) stripWidth else 0
        val maxX = screenWidth - discSize - (if (stripOnLeft) 0 else stripWidth)
        put(centre.coerceIn(minX.coerceAtMost(maxX.coerceAtLeast(0)), maxX.coerceAtLeast(0)), y, stripOnLeft)
    }

    /**
     * Where Heylana's whole window — the disc and its strip, [windowWidth] by
     * [windowHeight] — goes while it talks about the element at [left], [top], [right],
     * [bottom]: beside it (the side with more room first), else under it, else over it,
     * always on screen, and never over the element. Placing only the disc was not enough:
     * the window around it is touchable, and where the disc sits inside it depends on the
     * layout, so on the Seeker the disc landed on the Swap button and caught the very tap
     * the step asked for. [out] gets the window's left and top, and 1 when the strip is on
     * the disc's left (the disc at the window's right end, nearer the element), else 0.
     */
    fun placeWindow(
        left: Int,
        top: Int,
        right: Int,
        bottom: Int,
        windowWidth: Int,
        windowHeight: Int,
        gap: Int,
        screenWidth: Int,
        screenHeight: Int,
        out: IntArray
    ) {
        fun fits(x: Int, y: Int) = x >= 0 && y >= 0 && x + windowWidth <= screenWidth && y + windowHeight <= screenHeight
        fun clear(x: Int, y: Int) = x + windowWidth <= left || x >= right || y + windowHeight <= top || y >= bottom
        fun clampY(y: Int) = y.coerceIn(0, (screenHeight - windowHeight).coerceAtLeast(0))
        fun clampX(x: Int) = x.coerceIn(0, (screenWidth - windowWidth).coerceAtLeast(0))
        val middleY = clampY((top + bottom) / 2 - windowHeight / 2)
        val centreX = clampX((left + right) / 2 - windowWidth / 2)
        val rightFirst = screenWidth - right >= left
        // x, y, strip on the left
        val candidates = mutableListOf<Triple<Int, Int, Boolean>>()
        val besideRight = Triple(right + gap, middleY, false)
        val besideLeft = Triple(left - gap - windowWidth, middleY, true)
        if (rightFirst) candidates += listOf(besideRight, besideLeft) else candidates += listOf(besideLeft, besideRight)
        val stripOnLeft = (left + right) / 2 > screenWidth / 2
        candidates += Triple(centreX, bottom + gap, stripOnLeft)
        candidates += Triple(centreX, top - gap - windowHeight, stripOnLeft)
        val chosen = candidates.firstOrNull { (x, y, _) -> fits(x, y) && clear(x, y) }
            // Nowhere fully clear: under it, pushed on screen — still never on top if it can help it.
            ?: Triple(centreX, clampY(bottom + gap), stripOnLeft)
        out[0] = chosen.first
        out[1] = chosen.second
        if (out.size > 2) out[2] = if (chosen.third) 1 else 0
    }
}
