package xyz.heylana.app.overlay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** No exchange lives longer than twenty seconds, whatever gets stuck. */
class ExchangeTimeoutTest {

    private var clock = 1_000_000L
    private fun exchange() = Exchange { clock }

    @Test
    fun `the limit is twenty seconds`() {
        assertEquals(20_000L, Exchange.LIMIT_MS)
    }

    @Test
    fun `an exchange is not overdue a moment before the limit and is at it`() {
        val exchange = exchange()
        exchange.listening()
        clock += Exchange.LIMIT_MS - 1
        assertFalse(exchange.overdue())
        clock += 1
        assertTrue(exchange.overdue())
    }

    @Test
    fun `the clock runs from the start of the exchange, not from its last step`() {
        val exchange = exchange()
        exchange.listening()
        clock += 12_000
        exchange.released()
        clock += 5_000
        exchange.asking()
        clock += 3_000
        assertTrue("hold, words and answer share the twenty seconds", exchange.overdue())
    }

    @Test
    fun `nothing under way is never overdue`() {
        val exchange = exchange()
        clock += 60_000
        assertFalse(exchange.overdue())
    }

    @Test
    fun `timing out ends it and says so exactly once`() {
        val exchange = exchange()
        val seen = mutableListOf<Exchange.Phase>()
        exchange.onChange = { seen.add(it) }
        exchange.listening()
        exchange.released()
        assertTrue(exchange.timedOut())
        assertEquals(Exchange.Phase.NONE, exchange.phase)
        assertFalse("a second timeout has nothing to end", exchange.timedOut())
        assertEquals(
            listOf(Exchange.Phase.LISTENING, Exchange.Phase.WAITING_FOR_WORDS, Exchange.Phase.NONE),
            seen
        )
    }

    @Test
    fun `a new exchange starts its own clock`() {
        val exchange = exchange()
        exchange.asking()
        clock += 19_000
        exchange.over()
        clock += 5_000
        exchange.asking()
        clock += 1_000
        assertFalse(exchange.overdue())
        assertEquals(19_000L, exchange.remaining())
    }

    @Test
    fun `repeating a phase does not report a change`() {
        val exchange = exchange()
        var changes = 0
        exchange.onChange = { changes++ }
        exchange.asking()
        exchange.asking()
        exchange.over()
        exchange.over()
        assertEquals(2, changes)
    }
}
