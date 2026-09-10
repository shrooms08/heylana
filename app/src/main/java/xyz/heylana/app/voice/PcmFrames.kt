package xyz.heylana.app.voice

/**
 * Whole samples only.
 *
 * Audio arrives from the network in chunks of whatever length the socket felt
 * like, and 16-bit audio is two bytes per sample. Hand AudioTrack an odd number
 * of bytes and every sample after it is built from the second half of one and
 * the first half of the next — which does not sound like a glitch, it sounds
 * like a screech, and it never recovers.
 *
 * So the odd tail byte waits here for the rest of its sample to arrive.
 */
class PcmFrames(private val bytesPerFrame: Int) {

    private var carry = ByteArray(0)

    /** How many bytes are waiting for the rest of their sample. */
    val pending: Int get() = carry.size

    /**
     * The bytes that may be written now: whatever was left over last time,
     * followed by as much of [chunk] as completes a sample. Always a multiple
     * of [bytesPerFrame], and never a byte out of order.
     */
    fun take(chunk: ByteArray, length: Int): ByteArray {
        val available = carry.size + length
        val whole = available - available % bytesPerFrame
        if (whole == 0) {
            carry = carry + chunk.copyOf(length)
            return EMPTY
        }

        val out = ByteArray(whole)
        val fromCarry = minOf(carry.size, whole)
        carry.copyInto(out, 0, 0, fromCarry)
        chunk.copyInto(out, fromCarry, 0, whole - fromCarry)

        carry = if (available > whole) {
            chunk.copyOfRange(whole - fromCarry, length)
        } else {
            EMPTY
        }
        return out
    }

    /** Starting again: anything half-finished belongs to the last answer. */
    fun reset() {
        carry = EMPTY
    }

    private companion object {
        val EMPTY = ByteArray(0)
    }
}
