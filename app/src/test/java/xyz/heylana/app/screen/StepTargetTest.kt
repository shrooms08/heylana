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

    @Test
    fun `Jupiter step 1 as the Seeker said it - Trade, not Home's Swap`() {
        val home = listOf(
            Candidate(1, "Swap", editable = false, clickable = true),
            Candidate(2, "Earn", editable = false, clickable = true),
            Candidate(3, "Trade", editable = false, clickable = true),
            Candidate(4, "Home", editable = false, clickable = false)
        )
        assertEquals(3, StepTarget.choose("To swap, first tap Trade at the bottom, then Swap, then Market to open the swap screen.", 1, home))
    }

    @Test
    fun `a tab and its label named alike are one target - the model's Swap moves to Trade`() {
        // Jupiter's Home as the Seeker read it: the Trade tab and its label, both clickable.
        val home = listOf(
            Candidate(1, "Swap", editable = false, clickable = true, box = intArrayOf(940, 594, 1083, 737)),
            Candidate(2, "Trade", editable = false, clickable = true, box = intArrayOf(518, 2425, 747, 2568)),
            Candidate(3, "Trade", editable = false, clickable = true, box = intArrayOf(580, 2520, 690, 2560))
        )
        assertEquals(2, StepTarget.choose("To swap, first tap Trade at the bottom, then Swap, then Market.", 1, home))
    }

    @Test
    fun `a heading never takes the pointer from a chip that takes the tap`() {
        // As the Seeker said it: the Sell card's token chip pointed, "Sell" is its heading.
        assertEquals(8, StepTarget.choose("You are swapping USDC to SOL right now, so first tap the Sell chip to switch it to SOL instead.", 8, jupiterSwap))
    }

    @Test
    fun `whether a step says to type`() {
        assertEquals(true, StepTarget.isTyping("The sell token is already set to SOL, so now tap the amount field. Type in 0.0009 there."))
        assertEquals(true, StepTarget.isTyping("Now enter the amount you want to sell."))
        assertEquals(false, StepTarget.isTyping("Tap Trade at the bottom to get started."))
    }

    // Jupiter's Trade screen as the Seeker read it on 2026-09-21, bounds in pixels: the "Swap"
    // tab at the top and the green Swap button above the keypad, each with its label inside.
    private val swapTwins = listOf(
        Candidate(46, "Swap", editable = false, clickable = true, box = intArrayOf(44, 248, 166, 370)),
        Candidate(47, "Swap", editable = false, clickable = false, box = intArrayOf(52, 285, 158, 335)),
        Candidate(4, "Market", editable = false, clickable = true, box = intArrayOf(51, 400, 367, 484)),
        Candidate(13, "0.0009", editable = false, clickable = true, box = intArrayOf(407, 637, 1108, 760)),
        Candidate(60, "Swap", editable = false, clickable = true, box = intArrayOf(52, 1672, 1148, 1792)),
        Candidate(61, "Swap", editable = false, clickable = false, box = intArrayOf(548, 1710, 652, 1755))
    )

    @Test
    fun `Jupiter step 3 - the green Swap button, not the Swap tab`() {
        // As the Seeker said it, with the model pointing at the tab.
        assertEquals(60, StepTarget.choose("You typed 0.0009 in the Sell box. Tap the green Swap button to review the rate and fee.", 46, swapTwins))
        assertEquals(60, StepTarget.choose("Now tap Swap at the bottom.", 46, swapTwins))
        // No qualifier at all: the primary action, the big button.
        assertEquals(60, StepTarget.choose("Now tap Swap.", 46, swapTwins))
        // The model already on the button's label: it stays on the button.
        assertEquals(61, StepTarget.choose("Tap the green Swap button.", 61, swapTwins))
    }

    // As Jupiter really reports it: the green button is a tappable box with no words of its
    // own, and "Swap" is a separate text inside it that takes no tap.
    private val swapAsReported = listOf(
        Candidate(46, "Swap", editable = false, clickable = true, box = intArrayOf(44, 248, 166, 370)),
        Candidate(4, "Market", editable = false, clickable = true, box = intArrayOf(51, 400, 367, 484)),
        Candidate(60, null, editable = false, clickable = true, box = intArrayOf(52, 1672, 1148, 1792)),
        Candidate(61, "Swap", editable = false, clickable = false, box = intArrayOf(548, 1710, 652, 1755))
    )

    @Test
    fun `Jupiter step 3 as reported - the unnamed green box with Swap inside it`() {
        assertEquals(61, StepTarget.choose("Now tap the green Swap button to review the rate.", 46, swapAsReported))
        assertEquals(61, StepTarget.choose("Now tap Swap.", 46, swapAsReported))
        assertEquals(46, StepTarget.choose("Tap the Swap tab at the top.", 61, swapAsReported))
    }

    @Test
    fun `the words can ask for the tab too`() {
        assertEquals(46, StepTarget.choose("First tap the Swap tab at the top.", 60, swapTwins))
        assertEquals(46, StepTarget.choose("Tap the Swap tab.", 60, swapTwins))
    }
}
