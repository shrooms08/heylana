package xyz.heylana.app.voice

/**
 * The microphone's last few hundred milliseconds before a hold, kept in memory only.
 *
 * Recording starts at touch-down, but nothing is sent until the touch becomes a hold;
 * until then the audio goes round this ring, oldest dropped first, so at the long press
 * the [RING_MS] before it can be put at the front of the stream. A tap or a drag clears
 * it without anything having left the phone.
 */
class PreRoll(private val capacity: Int) {

    private val ring = ByteArray(capacity)
    private var start = 0
    private var size = 0

    @Synchronized
    fun write(piece: ByteArray) {
        for (b in piece) {
            val end = (start + size) % capacity
            ring[end] = b
            if (size < capacity) size++ else start = (start + 1) % capacity
        }
    }

    /** Everything kept, oldest first, and the ring emptied. Whole samples only. */
    @Synchronized
    fun drain(): ByteArray {
        val whole = size - size % BYTES_PER_SAMPLE
        val skip = size - whole
        val out = ByteArray(whole)
        for (i in 0 until whole) out[i] = ring[(start + skip + i) % capacity]
        clear()
        return out
    }

    @Synchronized
    fun clear() {
        start = 0
        size = 0
    }

    companion object {
        /** How much before the hold is kept. */
        const val RING_MS = 600

        const val BYTES_PER_SAMPLE = 2

        fun bytesFor(sampleRate: Int): Int = sampleRate * BYTES_PER_SAMPLE * RING_MS / 1000

        /**
         * How much of the time between the microphone starting and the hold did not make it
         * into the stream: none while the hold comes within [RING_MS] of the microphone.
         */
        fun clippedMs(holdAfterMicMs: Long): Long = (holdAfterMicMs - RING_MS).coerceAtLeast(0)
    }
}
