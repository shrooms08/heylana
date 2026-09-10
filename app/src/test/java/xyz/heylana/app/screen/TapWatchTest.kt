package xyz.heylana.app.screen

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rule the pointer follows once it is on screen. An answer's box gets fifteen
 * seconds to notice the user acting and then gets out of the way either way; a
 * step's box never times out, and its watch keeps running for as long as the step
 * is up.
 */
class TapWatchTest {

    private val armed = 10_000L
    private val key = "Button||Swap|"

    /** A box that answered a question: on the clock. */
    private fun watch() = TapWatch(key, armed)

    /** A box that is a step of a task: not on the clock. */
    private fun stepWatch() = TapWatch(key, armed, persistent = true)

    @Test
    fun `a tap on the highlighted element is acknowledged at once`() {
        assertEquals(
            Verdict.ACKNOWLEDGE,
            watch().consider(armed + 50, ScreenSignal.Clicked(key))
        )
    }

    @Test
    fun `a tap on something else is ignored`() {
        assertEquals(
            Verdict.IGNORE,
            watch().consider(armed + 3_000, ScreenSignal.Clicked("Button||Send|"))
        )
    }

    @Test
    fun `an unreadable tap is ignored rather than guessed at`() {
        assertEquals(
            Verdict.IGNORE,
            watch().consider(armed + 3_000, ScreenSignal.Clicked(null))
        )
    }

    @Test
    fun `the screen settling as the box appears does not count`() {
        assertEquals(
            Verdict.IGNORE,
            watch().consider(armed + TapWatch.GRACE_MS - 1, ScreenSignal.Changed)
        )
    }

    @Test
    fun `the screen moving after the grace period is acknowledged`() {
        assertEquals(
            Verdict.ACKNOWLEDGE,
            watch().consider(armed + TapWatch.GRACE_MS, ScreenSignal.Changed)
        )
    }

    @Test
    fun `the watch runs for fifteen seconds`() {
        assertEquals(15_000L, TapWatch.WINDOW_MS)
        val watch = watch()
        assertFalse(watch.expired(armed + TapWatch.WINDOW_MS - 1))
        assertTrue(watch.expired(armed + TapWatch.WINDOW_MS))
    }

    @Test
    fun `nothing counts once the window has run out`() {
        val watch = watch()
        val late = armed + TapWatch.WINDOW_MS
        assertEquals(Verdict.EXPIRE, watch.consider(late, ScreenSignal.Clicked(key)))
        assertEquals(Verdict.EXPIRE, watch.consider(late, ScreenSignal.Changed))
    }

    @Test
    fun `a step's box never times out`() {
        val watch = stepWatch()
        assertFalse(watch.expired(armed + TapWatch.WINDOW_MS))
        assertFalse(watch.expired(armed + 10 * TapWatch.WINDOW_MS))
    }

    @Test
    fun `a step's box is still acknowledged long after an answer's would have gone`() {
        val late = armed + 10 * TapWatch.WINDOW_MS
        assertEquals(
            Verdict.ACKNOWLEDGE,
            stepWatch().consider(late, ScreenSignal.Clicked(key))
        )
        assertEquals(
            Verdict.ACKNOWLEDGE,
            stepWatch().consider(late, ScreenSignal.Changed)
        )
    }

    @Test
    fun `a step's box still ignores a tap on something else`() {
        assertEquals(
            Verdict.IGNORE,
            stepWatch().consider(armed + TapWatch.WINDOW_MS, ScreenSignal.Clicked("Button||Send|"))
        )
    }

    @Test
    fun `a step's box is still given time to settle`() {
        assertEquals(
            Verdict.IGNORE,
            stepWatch().consider(armed + TapWatch.GRACE_MS - 1, ScreenSignal.Changed)
        )
    }

    @Test
    fun `a watch with nothing to match still expires rather than acknowledging taps`() {
        val watch = TapWatch(null, armed)
        assertEquals(Verdict.IGNORE, watch.consider(armed + 100, ScreenSignal.Clicked(key)))
        assertEquals(
            Verdict.ACKNOWLEDGE,
            watch.consider(armed + TapWatch.GRACE_MS, ScreenSignal.Changed)
        )
    }
}
