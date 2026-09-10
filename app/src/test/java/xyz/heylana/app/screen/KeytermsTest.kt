package xyz.heylana.app.screen

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** What the ears are told to expect, and what is deliberately never sent. */
class KeytermsTest {

    private fun onScreen(vararg labels: Pair<String, Int>) =
        labels.map { Keyterms.Labelled(it.first, it.second) }

    @Test
    fun `what the ears are told carries the fixed words and nothing off the screen`() {
        val terms = Keyterms.forEars(onScreen("Receive" to 900, "12.44" to 800))
        assertEquals(Keyterms.ALWAYS, terms)
        assertFalse(terms.contains("Receive"))
    }

    @Test
    fun `Kamino is one of the fixed words, since that is the one that gets misheard`() {
        assertTrue(Keyterms.ALWAYS.contains("Kamino"))
        assertTrue(Keyterms.forEars(emptyList()).contains("Kamino"))
    }

    @Test
    fun `the screen's labels can be put back with one argument`() {
        val terms = Keyterms.forEars(onScreen("Receive" to 900), includeScreen = true)
        assertTrue(terms.contains("Receive"))
    }

    @Test
    fun `the words this phone is about are always sent`() {
        val terms = Keyterms.from(emptyList())
        assertTrue(terms.containsAll(Keyterms.ALWAYS))
    }

    @Test
    fun `what is on screen is sent too`() {
        val terms = Keyterms.from(onScreen("Earn" to 900, "Portfolio" to 400))
        assertTrue(terms.contains("Earn"))
        assertTrue(terms.contains("Portfolio"))
    }

    @Test
    fun `the biggest labels win when there are too many`() {
        val many = (1..40).map { "Button$it" to it * 10 }
        val terms = Keyterms.from(onScreen(*many.toTypedArray()))
        assertEquals(Keyterms.ALWAYS.size + Keyterms.MAX_FROM_SCREEN, terms.size)
        assertTrue(terms.contains("Button40"))
        assertFalse(terms.contains("Button1"))
    }

    @Test
    fun `a label the fixed list already has is not sent twice`() {
        val terms = Keyterms.from(onScreen("Swap" to 900))
        assertEquals(1, terms.count { it.equals("swap", ignoreCase = true) })
    }

    @Test
    fun `the same label twice on screen is sent once`() {
        val terms = Keyterms.from(onScreen("Earn" to 900, "Earn" to 400))
        assertEquals(1, terms.count { it == "Earn" })
    }

    @Test
    fun `a balance is not a hint and is never sent`() {
        val terms = Keyterms.from(onScreen("12.4408" to 900, "$1,204.55" to 800))
        assertFalse(terms.any { it.contains("12.44") })
        assertFalse(terms.any { it.contains("1,204") })
    }

    @Test
    fun `a whole sentence is not a name`() {
        val long = "Tap here to review the transaction before you approve it"
        val terms = Keyterms.from(onScreen(long to 900))
        assertFalse(terms.contains(long))
    }

    @Test
    fun `labels are trimmed and empty ones dropped`() {
        val terms = Keyterms.from(onScreen("  Receive  " to 900, "   " to 800))
        assertTrue(terms.contains("Receive"))
        assertEquals(Keyterms.ALWAYS.size + 1, terms.size)
    }
}
