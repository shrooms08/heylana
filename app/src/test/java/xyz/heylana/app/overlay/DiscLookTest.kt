package xyz.heylana.app.overlay

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The disc's look comes from the exchange and nowhere else, so no path that ends
 * an exchange can forget to put it back. The trace that prompted this: "nothing
 * heard", capsule melted, and the thinking ring kept turning for five minutes.
 */
class DiscLookTest {

    private fun lookAfter(steps: (Exchange) -> Unit): DiscLook {
        val exchange = Exchange { 0L }
        var look = DiscLook.of(exchange.phase, pointing = false)
        exchange.onChange = { phase -> look = DiscLook.of(phase, pointing = false) }
        steps(exchange)
        return look
    }

    @Test
    fun `each phase has one look`() {
        assertEquals(DiscLook.IDLE, DiscLook.of(Exchange.Phase.NONE, pointing = false))
        assertEquals(DiscLook.LISTENING, DiscLook.of(Exchange.Phase.LISTENING, pointing = false))
        assertEquals(DiscLook.THINKING, DiscLook.of(Exchange.Phase.WAITING_FOR_WORDS, pointing = false))
        assertEquals(DiscLook.THINKING, DiscLook.of(Exchange.Phase.ASKING, pointing = false))
    }

    @Test
    fun `pointing only shows once nothing is under way`() {
        assertEquals(DiscLook.POINTING, DiscLook.of(Exchange.Phase.NONE, pointing = true))
        assertEquals(DiscLook.THINKING, DiscLook.of(Exchange.Phase.ASKING, pointing = true))
        assertEquals(DiscLook.LISTENING, DiscLook.of(Exchange.Phase.LISTENING, pointing = true))
    }

    @Test
    fun `nothing heard after letting go returns the disc to idle`() {
        // The exact sequence from the Seeker: hold, let go, nobody heard a word.
        assertEquals(DiscLook.IDLE, lookAfter { it.listening(); it.released(); it.over() })
    }

    @Test
    fun `an answer returns the disc to idle`() {
        assertEquals(DiscLook.IDLE, lookAfter { it.listening(); it.released(); it.asking(); it.over() })
    }

    @Test
    fun `an error or a refused question returns the disc to idle`() {
        assertEquals(DiscLook.IDLE, lookAfter { it.asking(); it.over() })
    }

    @Test
    fun `a cancelled hold returns the disc to idle`() {
        assertEquals(DiscLook.IDLE, lookAfter { it.listening(); it.over() })
    }

    @Test
    fun `a timeout returns the disc to idle from any phase`() {
        assertEquals(DiscLook.IDLE, lookAfter { it.listening(); it.timedOut() })
        assertEquals(DiscLook.IDLE, lookAfter { it.listening(); it.released(); it.timedOut() })
        assertEquals(DiscLook.IDLE, lookAfter { it.asking(); it.timedOut() })
    }

    @Test
    fun `while the words are on their way the disc is thinking`() {
        assertEquals(DiscLook.THINKING, lookAfter { it.listening(); it.released() })
    }
}
