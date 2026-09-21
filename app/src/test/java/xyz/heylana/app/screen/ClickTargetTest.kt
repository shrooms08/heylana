package xyz.heylana.app.screen

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ClickTargetTest {

    @Test
    fun `a label's tap goes to the smallest clickable box around it`() {
        // The Wallet as read on the Seeker: the "Swap" label inside its card, inside the whole row.
        val label = intArrayOf(169, 1418, 258, 1469)
        val boxes = listOf(
            intArrayOf(40, 1230, 1160, 1480),  // the row of three, clickable
            intArrayOf(40, 1232, 380, 1480),   // the Swap card, clickable
            intArrayOf(169, 1418, 258, 1469),  // the label itself, not clickable
            intArrayOf(420, 1232, 760, 1480)   // Receive, clickable, not around the label
        )
        assertEquals(1, ClickTarget.containing(label, boxes, listOf(true, true, false, true)))
    }

    @Test
    fun `nothing clickable around it means the label stays the target`() {
        assertNull(ClickTarget.containing(intArrayOf(10, 10, 20, 20), listOf(intArrayOf(30, 30, 40, 40)), listOf(true)))
    }

    @Test
    fun `the whole page is never the button - the Seeker's Wallet`() {
        val label = intArrayOf(169, 1418, 258, 1469)
        val boxes = listOf(
            intArrayOf(0, 0, 1200, 2670),      // the page, marked clickable
            intArrayOf(169, 1418, 258, 1469)   // the label
        )
        assertNull(ClickTarget.containing(label, boxes, listOf(true, false)))
    }

    // Jupiter's Trade screen on the Seeker, 2026-09-21, pixels: the whole page, the Swap tab,
    // the green Swap bar (a tappable box with no words) and the tappable word "Swap" inside it.
    private val jupiterBoxes = listOf(
        intArrayOf(0, 0, 1200, 2670),
        intArrayOf(44, 248, 166, 370),
        intArrayOf(52, 1672, 1148, 1792),
        intArrayOf(547, 1709, 654, 1759)
    )
    private val jupiterClickable = listOf(false, true, true, true)
    private val jupiterNamed = listOf(false, true, false, true)

    @Test
    fun `the green Swap bar is ringed whole, not its word`() {
        assertEquals(2, ClickTarget.target(3, jupiterBoxes, jupiterClickable, jupiterNamed))
        // The bar itself stays the bar; the tab, with no box around it, stays the tab.
        assertEquals(2, ClickTarget.target(2, jupiterBoxes, jupiterClickable, jupiterNamed))
        assertEquals(1, ClickTarget.target(1, jupiterBoxes, jupiterClickable, jupiterNamed))
    }

    @Test
    fun `a tappable label inside a named row stays the label`() {
        // The Wallet's Start inside its Earn row, where the row carries its own words.
        val boxes = listOf(intArrayOf(40, 1700, 1160, 1800), intArrayOf(990, 1713, 1159, 1795))
        assertEquals(1, ClickTarget.target(1, boxes, listOf(true, true), listOf(true, true)))
    }

    @Test
    fun `with no page element in the read, the bar is still a button - Jupiter as read`() {
        // What the Seeker's read held on Sept 21: the word not tappable, one unnamed tappable
        // 1098x122 box around it, the tab, the keypad, the bottom bar; no page-sized element.
        val boxes = listOf(
            intArrayOf(44, 248, 166, 370),       // Swap tab
            intArrayOf(51, 1672, 1149, 1794),    // the green bar
            intArrayOf(547, 1709, 654, 1759),    // "Swap", not tappable
            intArrayOf(52, 1840, 305, 1960),     // MAX
            intArrayOf(40, 2425, 1160, 2568)     // bottom bar
        )
        val clickable = listOf(true, true, false, true, true)
        val named = listOf(true, false, true, true, true)
        assertEquals(1, ClickTarget.target(2, boxes, clickable, named))
    }
}
