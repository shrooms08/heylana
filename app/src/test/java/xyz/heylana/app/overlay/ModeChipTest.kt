package xyz.heylana.app.overlay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import xyz.heylana.app.wallet.SendStage

class ModeChipTest {

    @Test
    fun `the chip says one of seven things`() {
        assertEquals(
            listOf("reading", "thinking", "preparing", "simulating", "approve in wallet", "sent", "working"),
            BuddyMode.entries.map { it.label }
        )
    }

    @Test
    fun `a confirmed send's stages are simulating, approve in wallet, then working until it lands`() {
        assertEquals(BuddyMode.SIMULATING, BuddyMode.of(SendStage.SIMULATING))
        assertEquals(BuddyMode.APPROVE_IN_WALLET, BuddyMode.of(SendStage.APPROVE_IN_WALLET))
        assertEquals(BuddyMode.WORKING, BuddyMode.of(SendStage.CHECKING))
    }

    @Test
    fun `a touch while she speaks stops her, and that tap does not also toggle the box`() {
        assertTrue(SpeechTouch.stops(speaking = true))
        assertFalse(SpeechTouch.tapToggles(stoppedSpeech = true))
        assertFalse(SpeechTouch.stops(speaking = false))
        assertTrue(SpeechTouch.tapToggles(stoppedSpeech = false))
    }
}
