package xyz.heylana.app.voice

import kotlin.math.sqrt

/** How loud a stretch of 16-bit little-endian mono speech is, as 0 to 1. */
object PlaybackLevel {

    /**
     * Speech peaks around a quarter of full scale in RMS; four times the RMS,
     * clamped, gives a level that uses the whole range without pinning at 1.
     */
    private const val GAIN = 4f

    fun of(pcm: ByteArray, length: Int): Float {
        val samples = length / 2
        if (samples == 0) return 0f
        var sum = 0.0
        for (i in 0 until samples) {
            val lo = pcm[2 * i].toInt() and 0xFF
            val hi = pcm[2 * i + 1].toInt()
            val value = ((hi shl 8) or lo).toShort() / 32768.0
            sum += value * value
        }
        return (sqrt(sum / samples).toFloat() * GAIN).coerceIn(0f, 1f)
    }
}
