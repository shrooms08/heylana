package xyz.heylana.app.wallet

import org.junit.Assert.assertEquals
import org.junit.Test

class BackoffTest {

    @Test
    fun `waits grow 2, 3, 5, 8, 13, 21 seconds and the last is cut to fit a minute`() {
        assertEquals(listOf(2_000L, 3_000L, 5_000L, 8_000L, 13_000L, 21_000L, 8_000L), Backoff.delays(60_000))
        assertEquals(60_000L, Backoff.delays(60_000).sum())
    }

    @Test
    fun `a short budget is never overrun`() {
        assertEquals(listOf(2_000L, 1_000L), Backoff.delays(3_000))
        assertEquals(emptyList<Long>(), Backoff.delays(0))
    }
}
