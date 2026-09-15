package xyz.heylana.app.screen

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A plain question is never refused for want of a screen. When screen reading is
 * off the question still goes, and what stands in for the listing tells the model
 * why it is empty — so "what is the capital of Nigeria" gets Abuja, and a question
 * about the screen gets the one line that fixes it.
 */
class ScreenReadingOffTest {

    @Test
    fun `with screen reading off the model is told so, and told to answer anyway`() {
        val text = ScreenSnapshot.empty(readingOff = true).toPromptText()
        assertTrue(text.contains("screen reading is switched off"))
        assertTrue(text.contains("general knowledge"))
        assertTrue(text.contains("switch on Heylana's screen reading"))
    }

    @Test
    fun `an empty screen with reading on is just an empty screen`() {
        val text = ScreenSnapshot.empty().toPromptText()
        assertTrue(text.contains("(nothing readable on screen)"))
        assertFalse(text.contains("switched off"))
    }

    @Test
    fun `either way there is still a listing to send, so nothing is refused`() {
        assertTrue(ScreenSnapshot.empty(readingOff = true).isEmpty)
        assertTrue(ScreenSnapshot.empty(readingOff = true).toPromptText().isNotBlank())
    }

    @Test
    fun `the line never claims a balance or names anything on screen`() {
        val text = ScreenSnapshot.READING_OFF_LINE
        assertFalse(text.any { it.isDigit() })
    }
}
