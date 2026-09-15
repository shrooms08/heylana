package xyz.heylana.app.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Whichever is ready first runs, and the choice is not revisited. */
class FallbackWindowTest {

    /** Any window will do for the rule; the voice's is the one in use. */
    private val LIMIT = FallbackWindow.VOICE_MS

    @Test
    fun `the voice gets a second and a half`() {
        assertEquals(1_500L, FallbackWindow.VOICE_MS)
    }

    @Test
    fun `what is left of a window comes from its one number`() {
        val window = FallbackWindow(LIMIT)
        assertEquals(LIMIT - 400, window.remaining(400))
        assertEquals(0L, window.remaining(LIMIT + 5_000))
    }

    @Test
    fun `ready in time and the good one runs`() {
        val window = FallbackWindow(LIMIT)
        assertTrue(window.preferredReady(LIMIT - 100))
        assertEquals(FallbackWindow.Choice.PREFERRED, window.choice)
    }

    @Test
    fun `ready exactly on the limit still counts`() {
        val window = FallbackWindow(LIMIT)
        assertTrue(window.preferredReady(LIMIT))
    }

    @Test
    fun `too slow and the phone takes over`() {
        val window = FallbackWindow(LIMIT)
        assertTrue(window.useFallback())
        assertEquals(FallbackWindow.Choice.FALLBACK, window.choice)
    }

    @Test
    fun `a socket that opens late does not take over mid-sentence`() {
        val window = FallbackWindow(LIMIT)
        window.useFallback()
        assertFalse(window.preferredReady(LIMIT + 100))
        assertEquals(FallbackWindow.Choice.FALLBACK, window.choice)
    }

    @Test
    fun `the timer firing after the good one won changes nothing`() {
        val window = FallbackWindow(LIMIT)
        window.preferredReady(200)
        assertFalse(window.useFallback())
        assertEquals(FallbackWindow.Choice.PREFERRED, window.choice)
    }

    @Test
    fun `ready past the limit is treated as too late even without the timer`() {
        val window = FallbackWindow(LIMIT)
        assertFalse(window.preferredReady(LIMIT + 1))
    }

    @Test
    fun `the decision is made once and held`() {
        val window = FallbackWindow(FallbackWindow.VOICE_MS)
        assertTrue(window.preferredReady(100))
        assertTrue(window.preferredReady(9_000))
        assertEquals(FallbackWindow.Choice.PREFERRED, window.choice)
    }
}
