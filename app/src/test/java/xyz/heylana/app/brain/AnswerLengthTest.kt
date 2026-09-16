package xyz.heylana.app.brain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AnswerLengthTest {

    @Test
    fun `signing answers get 40 words, everything else 60`() {
        assertEquals(40, AnswerLength.capFor(explainsSigning = true))
        assertEquals(60, AnswerLength.capFor(explainsSigning = false))
    }

    @Test
    fun `words are counted the way they are said, not by punctuation`() {
        assertEquals(9, AnswerLength.words("The screen shows 0.05 USDC to 7c2y…SxSv — check it."))
        assertEquals(0, AnswerLength.words("  "))
        assertFalse(AnswerLength.tooLong("word ".repeat(60), 60))
        assertTrue(AnswerLength.tooLong("word ".repeat(61), 60))
    }

    @Test
    fun `the shorter wording wins only when it is shorter and says something`() {
        val long = "Tap Install next to the app and then wait for it to finish downloading before you open it."
        assertEquals("Tap Install next to the app.", AnswerLength.better(long, "\"Tap Install next to the app.\""))
        assertEquals(long, AnswerLength.better(long, null))
        assertEquals(long, AnswerLength.better(long, "   "))
        assertEquals(long, AnswerLength.better(long, "$long And more."))
    }
}
