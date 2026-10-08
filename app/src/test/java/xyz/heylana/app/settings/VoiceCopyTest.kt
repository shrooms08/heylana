package xyz.heylana.app.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceCopyTest {

    @Test
    fun `the privacy line names the provider that speaks`() {
        val deepgram = VoiceCopy.privacyLine(VoiceCopy.DEEPGRAM)
        assertTrue(deepgram.contains("The spoken answer text goes to Deepgram to become speech."))
        assertFalse(deepgram.contains("to Google (Gemini) to become speech"))
        val gemini = VoiceCopy.privacyLine(VoiceCopy.GEMINI)
        assertTrue(gemini.contains("The spoken answer text goes to Google (Gemini) to become speech."))
        assertTrue(VoiceCopy.privacyLine(VoiceCopy.CARTESIA).contains("goes to Cartesia"))
        // Everything else in the line stays as it was.
        for (line in listOf(deepgram, gemini)) {
            assertTrue(line.startsWith("Your voice goes to Deepgram to be transcribed while you hold the buddy."))
            assertTrue(line.contains(VoiceCopy.LIVE_SENTENCE))
            assertTrue(line.endsWith("The screen never goes to any of them."))
        }
    }

    @Test
    fun `AssemblyAI is named exactly when it is listening`() {
        val both = VoiceCopy.privacyLine(VoiceCopy.DEEPGRAM, assemblyai = true)
        assertTrue(both.startsWith("Your voice goes to Deepgram and AssemblyAI to be transcribed while you hold the buddy."))
        assertFalse(VoiceCopy.privacyLine(VoiceCopy.DEEPGRAM, assemblyai = false).contains("AssemblyAI"))
        assertTrue(xyz.heylana.app.home.PrivacyCopy.voiceDetail(true).startsWith("To Deepgram and AssemblyAI"))
        assertFalse(xyz.heylana.app.home.PrivacyCopy.voiceDetail(false).contains("AssemblyAI"))
    }

    @Test
    fun `the picker names follow the provider when the worker has not said`() {
        assertEquals("Callista", VoiceCopy.defaultName(VoiceCopy.DEEPGRAM, HeylanaSettings.VOICE_SKYLAR))
        assertEquals("Aries", VoiceCopy.defaultName(VoiceCopy.DEEPGRAM, HeylanaSettings.VOICE_ARCHIE))
        assertEquals("Sulafat", VoiceCopy.defaultName(VoiceCopy.GEMINI, HeylanaSettings.VOICE_SKYLAR))
        assertEquals("Achird", VoiceCopy.defaultName(VoiceCopy.GEMINI, HeylanaSettings.VOICE_ARCHIE))
        // The worker is set to Deepgram, so that is the line until /me says otherwise.
        assertEquals(VoiceCopy.DEEPGRAM, VoiceCopy.DEFAULT_PROVIDER)
    }

    @Test
    fun `the app and the worker agree on the names`() {
        val worker = java.io.File(listOf("../worker/src/voice.ts", "worker/src/voice.ts").first { java.io.File(it).exists() }).readText()
        assertTrue(worker.contains("deepgram: { skylar: 'Callista', archie: 'Aries' }"))
        assertTrue(worker.contains("gemini: { skylar: 'Sulafat', archie: 'Achird' }"))
    }
}
