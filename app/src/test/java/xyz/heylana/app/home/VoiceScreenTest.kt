package xyz.heylana.app.home

import org.junit.Assert.assertEquals
import org.junit.Test

class VoiceScreenTest {

    @Test
    fun `a short press that started listening keeps listening`() {
        assertEquals(MicPress.OnRelease.KEEP_LISTENING, MicPress.onRelease(120, startedByThisPress = true))
    }

    @Test
    fun `a hold that started listening sends on release`() {
        assertEquals(MicPress.OnRelease.FINISH, MicPress.onRelease(MicPress.HOLD_MS, startedByThisPress = true))
        assertEquals(MicPress.OnRelease.FINISH, MicPress.onRelease(2_400, startedByThisPress = true))
    }

    @Test
    fun `any press while already listening sends`() {
        assertEquals(MicPress.OnRelease.FINISH, MicPress.onRelease(80, startedByThisPress = false))
    }

    @Test
    fun `the timer reads minutes and seconds`() {
        assertEquals("00:00", MicPress.clock(0))
        assertEquals("00:12", MicPress.clock(12_900))
        assertEquals("01:05", MicPress.clock(65_000))
        assertEquals("00:00", MicPress.clock(-5))
    }

    @Test
    fun `the label follows the ears first, then the answer`() {
        val p = VoiceSession.Phase.entries
        assertEquals(VoiceLabel.LISTENING, VoiceLabel.of(p[1], thinking = false, speaking = true))
        assertEquals(VoiceLabel.THINKING, VoiceLabel.of(VoiceSession.Phase.WAITING, thinking = false, speaking = false))
        assertEquals(VoiceLabel.THINKING, VoiceLabel.of(VoiceSession.Phase.READY, thinking = true, speaking = false))
        assertEquals(VoiceLabel.SPEAKING, VoiceLabel.of(VoiceSession.Phase.READY, thinking = false, speaking = true))
        assertEquals(VoiceLabel.PAUSED, VoiceLabel.of(VoiceSession.Phase.PAUSED, thinking = false, speaking = false))
        assertEquals(VoiceLabel.READY, VoiceLabel.of(VoiceSession.Phase.READY, thinking = false, speaking = false))
    }
}
