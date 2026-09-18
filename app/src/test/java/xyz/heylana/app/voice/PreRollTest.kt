package xyz.heylana.app.voice

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class PreRollTest {

    @Test
    fun `600ms of 16kHz 16-bit audio is kept before the hold`() {
        assertEquals(19_200, PreRoll.bytesFor(16_000))
    }

    @Test
    fun `the ring keeps the newest audio, oldest first, whole samples`() {
        val ring = PreRoll(8)
        ring.write(byteArrayOf(1, 2, 3, 4, 5, 6))
        ring.write(byteArrayOf(7, 8, 9, 10))
        assertArrayEquals(byteArrayOf(3, 4, 5, 6, 7, 8, 9, 10), ring.drain())
        assertArrayEquals(ByteArray(0), ring.drain())
        ring.write(byteArrayOf(1, 2, 3))
        // An odd byte is dropped from the front so every sample stays whole.
        assertArrayEquals(byteArrayOf(2, 3), ring.drain())
    }

    @Test
    fun `nothing is clipped while the hold comes within the ring of the microphone starting`() {
        assertEquals(0L, PreRoll.clippedMs(450L))
        assertEquals(0L, PreRoll.clippedMs(600L))
        assertEquals(150L, PreRoll.clippedMs(750L))
    }
}
