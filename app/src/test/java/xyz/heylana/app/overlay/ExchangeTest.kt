package xyz.heylana.app.overlay

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rule that broke hold-to-talk: **nothing settles Heylana back to idle while
 * an exchange is under way.**
 *
 * The sequence in the first test is the real one, off the phone. Opening the
 * microphone stops the speaker; text-to-speech then reports "no longer
 * speaking"; that report is what books the settle a second later. The settle
 * closes the box, and closing the box abandons the microphone — so the buddy
 * used to cancel the user mid-sentence, one second into every hold.
 */
class ExchangeTest {

    @Test
    fun `stopping the speaker to listen does not book the buddy's own undoing`() {
        val exchange = Exchange()
        exchange.listening()
        // The speaker's "no longer speaking" arrives here, and asks to settle.
        assertFalse(exchange.maySettle)
    }

    @Test
    fun `letting go is not the end of the exchange, the words are still coming`() {
        val exchange = Exchange()
        exchange.listening()
        exchange.released()
        assertFalse(exchange.maySettle)
    }

    @Test
    fun `nor is sending the question`() {
        val exchange = Exchange()
        exchange.listening()
        exchange.released()
        exchange.asking()
        assertFalse(exchange.maySettle)
    }

    @Test
    fun `the answer landing ends it`() {
        val exchange = Exchange()
        exchange.listening()
        exchange.released()
        exchange.asking()
        exchange.over()
        assertTrue(exchange.maySettle)
        assertFalse(exchange.inProgress)
    }

    @Test
    fun `a typed question holds it open too`() {
        val exchange = Exchange()
        exchange.asking()
        assertFalse(exchange.maySettle)
        exchange.over()
        assertTrue(exchange.maySettle)
    }

    @Test
    fun `giving up mid-hold ends it`() {
        val exchange = Exchange()
        exchange.listening()
        exchange.over()
        assertTrue(exchange.maySettle)
    }

    @Test
    fun `hearing nothing ends it`() {
        val exchange = Exchange()
        exchange.listening()
        exchange.released()
        exchange.over()
        assertTrue(exchange.maySettle)
    }

    @Test
    fun `an idle buddy may always settle`() {
        assertTrue(Exchange().maySettle)
    }

    @Test
    fun `letting go without listening first changes nothing`() {
        val exchange = Exchange()
        exchange.released()
        assertTrue(exchange.maySettle)
    }
}
