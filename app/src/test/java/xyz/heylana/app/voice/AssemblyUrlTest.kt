package xyz.heylana.app.voice

import org.junit.Assert.assertTrue
import org.junit.Test

class AssemblyUrlTest {

    @Test
    fun `the socket asks for 16-bit PCM at the phone's rate, formatted turns, the keyterms and the token`() {
        val url = assemblyUrl(listOf("Solana", "SKR"), 16_000, "tok/en+1")
        assertTrue(url, url.startsWith("wss://streaming.assemblyai.com/v3/ws?sample_rate=16000&encoding=pcm_s16le&format_turns=true"))
        assertTrue(url, url.contains("&keyterms_prompt=%5B%22Solana%22%2C%22SKR%22%5D"))
        assertTrue(url, url.endsWith("&token=tok%2Fen%2B1"))
    }

    @Test
    fun `a message is 100ms of audio, never under AssemblyAI's 50ms`() {
        assertTrue(AssemblyEars.CHUNK_BYTES == 3_200)
        assertTrue(AssemblyEars.MIN_CHUNK_BYTES == 1_600)
    }
}
