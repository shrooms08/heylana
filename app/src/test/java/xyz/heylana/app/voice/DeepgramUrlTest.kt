package xyz.heylana.app.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import xyz.heylana.app.screen.Keyterms

/**
 * The listening socket's whole configuration is its URL, so this is the only
 * place to check that what Heylana thinks it asked for is what actually goes.
 */
class DeepgramUrlTest {

    private fun url(terms: List<String> = emptyList()) = deepgramUrl(terms, 16_000)

    @Test
    fun `nova-3 with the settings a held-down buddy needs`() {
        val url = url()
        assertTrue(url.startsWith("wss://api.deepgram.com/v1/listen?"))
        assertTrue("nova-3 hears names", url.contains("model=nova-3"))
        assertTrue("the capsule fills in live", url.contains("interim_results=true"))
        assertTrue("the finger decides when it is over", url.contains("endpointing=false"))
        assertTrue("names come back written properly", url.contains("smart_format=true"))
    }

    @Test
    fun `the audio format is the one the microphone is opened in`() {
        val url = url()
        assertTrue(url.contains("encoding=linear16"))
        assertTrue(url.contains("sample_rate=16000"))
        assertTrue(url.contains("channels=1"))
    }

    @Test
    fun `each keyterm is its own parameter, which is what nova-3 expects`() {
        val url = url(listOf("Kamino", "Seed Vault", "SKR"))
        assertEquals(3, Regex("[?&]keyterm=").findAll(url).count())
        assertTrue(url.contains("&keyterm=Kamino"))
        assertTrue("spaces are encoded", url.contains("&keyterm=Seed+Vault"))
        assertTrue(url.contains("&keyterm=SKR"))
    }

    @Test
    fun `a comma-joined list would be one long term, so it is never built that way`() {
        val url = url(listOf("Kamino", "Jupiter"))
        assertTrue(!url.contains("Kamino,Jupiter"))
        assertTrue(!url.contains("Kamino%2CJupiter"))
    }

    @Test
    fun `every fixed word goes out, Kamino among them`() {
        val terms = Keyterms.forEars(emptyList())
        val url = url(terms)
        assertEquals(terms.size, Regex("[?&]keyterm=").findAll(url).count())
        assertTrue(url.contains("&keyterm=Kamino"))
        assertTrue(url.contains("&keyterm=Seed+Vault"))
    }

    @Test
    fun `no keyterms is still a valid socket`() {
        assertTrue(!url().contains("keyterm="))
    }
}
