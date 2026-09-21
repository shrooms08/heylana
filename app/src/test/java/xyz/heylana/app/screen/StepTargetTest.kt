package xyz.heylana.app.screen

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import xyz.heylana.app.screen.StepTarget.Candidate

class StepTargetTest {

    // Jupiter's Trade screen on the Seeker, Swap and Market selected, labels as read off it
    // on 2026-09-21 (balances made up).
    private val jupiterSwap = listOf(
        Candidate(1, "Swap", editable = false, clickable = true),
        Candidate(2, "Perps", editable = false, clickable = true),
        Candidate(3, "Predictions", editable = false, clickable = true),
        Candidate(4, "Market", editable = false, clickable = true),
        Candidate(5, "Limit", editable = false, clickable = true),
        Candidate(6, "Recurring", editable = false, clickable = true),
        Candidate(7, "Sell", editable = false, clickable = false),
        Candidate(8, "SOL", editable = false, clickable = true),
        Candidate(9, "0.009 SOL", editable = false, clickable = false),
        Candidate(10, "0", editable = false, clickable = false),
        Candidate(11, "Buy", editable = false, clickable = false),
        Candidate(12, "USDC", editable = false, clickable = true),
        Candidate(13, "Enter Amount", editable = false, clickable = true),
        Candidate(14, "MAX", editable = false, clickable = true),
        Candidate(15, "75%", editable = false, clickable = true),
        Candidate(16, "50%", editable = false, clickable = true),
        Candidate(17, "CLEAR", editable = false, clickable = true),
        Candidate(18, "Home", editable = false, clickable = true),
        Candidate(19, "Markets", editable = false, clickable = true),
        Candidate(20, "Trade", editable = false, clickable = true),
        Candidate(21, "Account", editable = false, clickable = true)
    )

    @Test
    fun `Jupiter step 2 - the pointer leaves Market for the amount`() {
        val market = 4
        for (say in listOf(
            "Market is selected, so type the amount of SOL you want to swap.",
            "Good, Market is already on. Now enter the amount you want to sell.",
            "With Market selected, type how much SOL to swap on the keypad.",
            "Market is chosen. Next, type the amount."
        )) {
            assertEquals(say, 13, StepTarget.choose(say, market, jupiterSwap))
        }
    }

    @Test
    fun `an amount field that takes typing is a fine target too`() {
        val withField = jupiterSwap.filter { it.id != 13 } + Candidate(30, null, editable = true, clickable = true)
        assertEquals(30, StepTarget.choose("Market is selected, so type the amount.", 4, withField))
    }

    @Test
    fun `the model's element stays when the instruction names it`() {
        assertEquals(20, StepTarget.choose("Tap Trade at the bottom to open swaps.", 20, jupiterSwap))
        assertEquals(4, StepTarget.choose("Swap is open. Tap Market to swap at today's price.", 4, jupiterSwap))
        assertEquals(13, StepTarget.choose("Tap the green Enter Amount button to review.", 13, jupiterSwap))
    }

    @Test
    fun `the first instruction wins, not a later one`() {
        // Trade first, then Swap: the user acts on Trade next.
        assertEquals(20, StepTarget.choose("Tap Trade, then tap Swap.", 1, jupiterSwap))
    }

    @Test
    fun `quoted labels are matched whole`() {
        assertEquals(6, StepTarget.choose("Tap “Recurring” to buy a bit every day.", 5, jupiterSwap))
    }

    @Test
    fun `words that name nothing on screen leave the model's choice`() {
        assertEquals(8, StepTarget.choose("Pick what to pay with.", 8, jupiterSwap))
        assertEquals(8, StepTarget.choose("This is where your SOL sits.", 8, jupiterSwap))
        assertNull(StepTarget.choose("Wait for the quote to load.", null, jupiterSwap))
    }

    @Test
    fun `two equal matches that both take a tap leave the model's choice`() {
        val twins = listOf(
            Candidate(1, "Install", editable = false, clickable = true),
            Candidate(2, "Install", editable = false, clickable = true),
            Candidate(3, "Games", editable = false, clickable = true)
        )
        assertEquals(2, StepTarget.choose("Tap Install beside the app.", 2, twins))
        assertEquals(3, StepTarget.choose("Tap Install beside the app.", 3, twins))
    }
}
