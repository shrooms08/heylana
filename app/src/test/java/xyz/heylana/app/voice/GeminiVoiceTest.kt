package xyz.heylana.app.voice

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import xyz.heylana.app.settings.HeylanaSettings
import java.io.File

class GeminiVoiceTest {

    @Test
    fun `a refused or failed voice is named, never handed to another voice`() {
        assertEquals("429 daily_cap", VoiceFailure.reasonFor(429, "daily_cap"))
        assertEquals("quota", VoiceFailure.reasonFor(429, "quota"))
        assertEquals("quota", VoiceFailure.reasonFor(429, null))
        assertEquals("timeout", VoiceFailure.reasonFor(504, "upstream"))
        assertEquals("error", VoiceFailure.reasonFor(502, "upstream"))
        assertEquals("error", VoiceFailure.reasonFor(503, "voice_not_configured"))
    }

    @Test
    fun `Gemini's audio arrives in odd-sized deltas and still plays as whole samples, byte for byte`() {
        // What the worker passes on: one delta's decoded bytes after another, any length.
        val pcm = ByteArray(24_000 * 2 / 5) { (it % 251).toByte() } // 200ms of 24kHz mono 16-bit
        val deltas = listOf(3, 1, 4_801, 1, 2, 2_000, 1).let { sizes ->
            var at = 0
            sizes.map { n -> pcm.copyOfRange(at, at + n).also { at += n } } + listOf(pcm.copyOfRange(at, pcm.size))
        }
        val frames = PcmFrames(2)
        val out = ArrayList<Byte>()
        for (delta in deltas) {
            val taken = frames.take(delta, delta.size)
            assertEquals(0, taken.size % 2)
            out.addAll(taken.toList())
        }
        assertArrayEquals(pcm, out.toByteArray())
    }

    @Test
    fun `the picker has the two Gemini voices and no phone voice`() {
        assertEquals(listOf("skylar", "archie"), HeylanaSettings.VOICES)
        assertEquals("Sulafat", HeylanaSettings.VOICE_NAMES["skylar"])
        assertEquals("Achird", HeylanaSettings.VOICE_NAMES["archie"])
    }

    @Test
    fun `there is no phone voice left to fall back to`() {
        val root = listOf(File("src/main/java/xyz/heylana/app"), File("app/src/main/java/xyz/heylana/app")).first { it.isDirectory }
        val sources = root.walkTopDown().filter { it.extension == "kt" }.toList()
        assertTrue(sources.isNotEmpty())
        for (file in sources) {
            val text = file.readText()
            assertFalse("${file.name} still uses TextToSpeech", text.contains("android.speech.tts"))
            assertFalse("${file.name} still forces a phone voice", text.contains("forcePhoneVoice"))
        }
        val voice = File(root, "voice/HeylanaVoice.kt").readText()
        assertTrue(voice.contains("voice_failed reason="))
    }
}
