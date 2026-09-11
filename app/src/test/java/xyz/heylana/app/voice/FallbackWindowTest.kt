package xyz.heylana.app.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Whichever is ready first runs, and the choice is not revisited. */
class FallbackWindowTest {

    @Test
    fun `the ears get two and a half seconds, measured from the first touch`() {
        // Borrowing a key is a round trip through the proxy to Deepgram (around
        // 1.0 to 1.5s from Lagos) and the socket is another 1.1s cold. A window
        // under two seconds cannot be reached, which is why it used to fail.
        assertEquals(2_500L, FallbackWindow.EARS_MS)
        assertEquals(1_500L, FallbackWindow.VOICE_MS)
    }

    @Test
    fun `the window is one number, and what is left comes from it`() {
        val window = FallbackWindow(FallbackWindow.EARS_MS)
        assertEquals(FallbackWindow.EARS_MS, window.limitMs)
        assertEquals(2_100L, window.remaining(400))
        assertEquals(0L, window.remaining(FallbackWindow.EARS_MS))
        assertEquals(0L, window.remaining(FallbackWindow.EARS_MS + 5_000))
    }

    @Test
    fun `the ears are still ready at two and a half seconds and too late after`() {
        assertTrue(FallbackWindow(FallbackWindow.EARS_MS).preferredReady(2_500))
        assertFalse(FallbackWindow(FallbackWindow.EARS_MS).preferredReady(2_501))
    }

    @Test
    fun `ready in time and the good one runs`() {
        val window = FallbackWindow(FallbackWindow.EARS_MS)
        assertTrue(window.preferredReady(1_800))
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
        assertFalse(window.preferredReady(2_600))
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
