package xyz.heylana.app.home

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeChipsTest {

    @Test
    fun `the chips, in the order Heylana's own job puts them`() {
        assertEquals(
            listOf(
                "What am I signing?", "Check my balance", "Explain this screen",
                "Learn Solana", "What's new on Solana", "Set a timer", "Play a song",
            ),
            HomeChips.ALL.map { it.label }
        )
    }

    @Test
    fun `tapping a chip sends its words as a message, exactly as if typed`() {
        val sent = mutableListOf<String>()
        var started = 0
        for (chip in HomeChips.ALL.filter { it.action is ChipAction.Send }) {
            HomeChips.tap(chip, { sent += it }, { started++ })
        }
        assertEquals(listOf("Check my balance", "What's new on Solana right now?", "Set a timer", "Play a song"), sent)
        assertEquals(0, started)
    }

    @Test
    fun `the two chips about another app's screen start the buddy and send nothing`() {
        // The app never reads a screen itself, so both of these can only start the buddy.
        for (label in listOf("What am I signing?", "Explain this screen")) {
            val sent = mutableListOf<String>()
            var started = 0
            HomeChips.tap(HomeChips.ALL.single { it.label == label }, { sent += it }, { started++ })
            assertTrue(label, sent.isEmpty())
            assertEquals(label, 1, started)
        }
    }

    @Test
    fun `learn solana opens the topics and sends nothing`() {
        val sent = mutableListOf<String>()
        var learn = 0
        HomeChips.tap(HomeChips.ALL.single { it.label == "Learn Solana" }, { sent += it }, {}, { learn++ })
        assertTrue(sent.isEmpty())
        assertEquals(1, learn)
    }

    @Test
    fun `the signing chip leads, because the signing moment is what Heylana is for`() {
        assertEquals("What am I signing?", HomeChips.ALL.first().label)
    }
}
