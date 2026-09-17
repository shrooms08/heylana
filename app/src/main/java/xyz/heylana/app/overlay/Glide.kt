package xyz.heylana.app.overlay

/** The glide's small rules, away from the views so they can be tested. */
object Glide {

    /** How much of each new finger sample the drag speed takes; the rest is the speed so far. */
    const val SMOOTHING = 0.6f

    fun smooth(previous: Float, sample: Float): Float = previous + (sample - previous) * SMOOTHING

    /** A fling's speed carried into the glide, capped so a flick cannot throw the disc past its dock. */
    fun startVelocity(fling: Float, maxPerSecond: Float): Float = fling.coerceIn(-maxPerSecond, maxPerSecond)
}
