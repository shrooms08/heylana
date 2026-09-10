package xyz.heylana.app.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Whichever is ready first runs, and the choice is not revisited. */
class FallbackWindowTest {

    @Test
    fun `the ears are given eight hundred milliseconds and the voice one and a half seconds`() {
        assertEquals(800L, FallbackWindow.EARS_MS)
        assertEquals(1_500L, FallbackWindow.VOICE_MS)
    }

    @Test
    fun `ready in time and the good one runs`() {
        val window = FallbackWindow(FallbackWindow.EARS_MS)
        assertTrue(window.preferredReady(420))
        assertEquals(FallbackWindow.Choice.PREFERRED, window.choice)
    }

    @Test
    fun `ready exactly on the limit still counts`() {
        val window = FallbackWindow(FallbackWindow.EARS_MS)
        assertTrue(window.preferredReady(FallbackWindow.EARS_MS))
    }

    @Test
    fun `too slow and the phone takes over`() {
        val window = FallbackWindow(FallbackWindow.EARS_MS)
        assertTrue(window.useFallback())
        assertEquals(FallbackWindow.Choice.FALLBACK, window.choice)
    }

    @Test
    fun `a socket that opens late does not take over mid-sentence`() {
        val window = FallbackWindow(FallbackWindow.EARS_MS)
        window.useFallback()
        assertFalse(window.preferredReady(1_200))
        assertEquals(FallbackWindow.Choice.FALLBACK, window.choice)
    }

    @Test
    fun `the timer firing after the good one won changes nothing`() {
        val window = FallbackWindow(FallbackWindow.EARS_MS)
        window.preferredReady(200)
        assertFalse(window.useFallback())
        assertEquals(FallbackWindow.Choice.PREFERRED, window.choice)
    }

    @Test
    fun `ready past the limit is treated as too late even without the timer`() {
        val window = FallbackWindow(FallbackWindow.EARS_MS)
        assertFalse(window.preferredReady(FallbackWindow.EARS_MS + 1))
    }

    @Test
    fun `the decision is made once and held`() {
        val window = FallbackWindow(FallbackWindow.VOICE_MS)
        assertTrue(window.preferredReady(100))
        assertTrue(window.preferredReady(9_000))
        assertEquals(FallbackWindow.Choice.PREFERRED, window.choice)
    }
}
