package xyz.heylana.app.voice

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The screech: 16-bit audio written half a sample out of step, so every sample
 * after it is the tail of one and the head of the next.
 *
 * These feed the awkward lengths a socket really delivers and check that what
 * comes out is the same bytes, in the same order, always a whole number of
 * samples.
 */
class PcmFramesTest {

    private val stereo = 4
    private val mono = 2

    /** 0,1,2,3… so a shift of even one byte is obvious in the output. */
    private fun ramp(size: Int, from: Int = 0) =
        ByteArray(size) { ((from + it) % 251).toByte() }

    private fun feed(frames: PcmFrames, chunks: List<ByteArray>): ByteArray {
        val out = ArrayList<Byte>()
        for (chunk in chunks) {
            val taken = frames.take(chunk, chunk.size)
            assertEquals("every write must be whole samples", 0, taken.size % mono)
            out.addAll(taken.toList())
        }
        return out.toByteArray()
    }

    @Test
    fun `an odd chunk does not shift the samples that follow it`() {
        val frames = PcmFrames(mono)
        val first = ramp(5)
        val second = ramp(5, from = 5)

        val written = feed(frames, listOf(first, second))

        // Nine bytes of the ten are playable; the tenth waits for its partner.
        assertArrayEquals(ramp(10).copyOf(10 - 10 % mono).copyOf(written.size), written)
        assertArrayEquals(ramp(10).copyOf(written.size), written)
    }

    @Test
    fun `the whole stream comes out in order however it is chopped up`() {
        val source = ramp(4_097)
        val awkward = listOf(1, 3, 7, 13, 101, 511, 1_023, 2_439)
        val frames = PcmFrames(mono)

        val chunks = ArrayList<ByteArray>()
        var at = 0
        var i = 0
        while (at < source.size) {
            val size = minOf(awkward[i % awkward.size], source.size - at)
            chunks.add(source.copyOfRange(at, at + size))
            at += size
            i++
        }

        val written = feed(frames, chunks)
        assertArrayEquals(source.copyOf(written.size), written)
        assertTrue("at most one byte may be left waiting", frames.pending <= 1)
        assertEquals(source.size - source.size % mono, written.size)
    }

    @Test
    fun `a chunk too small to hold one sample is kept, not dropped`() {
        val frames = PcmFrames(mono)
        assertEquals(0, frames.take(byteArrayOf(7), 1).size)
        assertEquals(1, frames.pending)

        val next = frames.take(byteArrayOf(9, 11), 2)
        assertArrayEquals(byteArrayOf(7, 9), next)
        assertEquals(1, frames.pending)
    }

    @Test
    fun `stereo needs four bytes at a time`() {
        val frames = PcmFrames(stereo)
        assertEquals(0, frames.take(ramp(3), 3).size)
        val written = frames.take(ramp(3, from = 3), 3)
        assertEquals(4, written.size)
        assertArrayEquals(ramp(4), written)
    }

    @Test
    fun `a chunk that is already aligned passes straight through`() {
        val frames = PcmFrames(mono)
        val chunk = ramp(1_024)
        assertArrayEquals(chunk, frames.take(chunk, chunk.size))
        assertEquals(0, frames.pending)
    }

    @Test
    fun `only the length given is read, not the whole buffer`() {
        val frames = PcmFrames(mono)
        val buffer = ramp(1_024)
        assertEquals(100, frames.take(buffer, 100).size)
    }

    @Test
    fun `the next answer does not inherit half a sample from the last`() {
        val frames = PcmFrames(mono)
        frames.take(byteArrayOf(7), 1)
        frames.reset()
        assertEquals(0, frames.pending)
        assertArrayEquals(byteArrayOf(9, 11), frames.take(byteArrayOf(9, 11), 2))
    }
}
