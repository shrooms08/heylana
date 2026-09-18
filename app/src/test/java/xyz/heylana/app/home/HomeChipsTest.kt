package xyz.heylana.app.home

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeChipsTest {

    @Test
    fun `the export's chips, in its order`() {
        assertEquals(
            listOf("Ask about this screen", "Learn Solana", "Teach me to swap", "Check my balance", "Send USDC", "Set a timer", "Play a song"),
            HomeChips.ALL.map { it.label }
        )
    }

    @Test
    fun `tapping a chip sends its words as a message, exactly as if typed`() {
        val sent = mutableListOf<String>()
        var started = 0
        for (chip in HomeChips.ALL.drop(2)) HomeChips.tap(chip, { sent += it }, { started++ })
        assertEquals(listOf("Teach me to swap", "Check my balance", "Send USDC", "Set a timer", "Play a song"), sent)
        assertEquals(0, started)
    }

    @Test
    fun `ask about this screen starts the buddy and sends nothing - the app never reads a screen`() {
        val sent = mutableListOf<String>()
        var started = 0
        HomeChips.tap(HomeChips.FIRST_ROW.first(), { sent += it }, { started++ })
        assertTrue(sent.isEmpty())
        assertEquals(1, started)
    }

    @Test
    fun `learn solana opens the topics and sends nothing`() {
        val sent = mutableListOf<String>()
        var learn = 0
        HomeChips.tap(HomeChips.ALL.single { it.label == "Learn Solana" }, { sent += it }, {}, { learn++ })
        assertTrue(sent.isEmpty())
        assertEquals(1, learn)
    }
}
