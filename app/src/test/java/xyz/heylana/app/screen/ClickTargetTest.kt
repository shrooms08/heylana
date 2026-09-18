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
}
