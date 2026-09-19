package xyz.heylana.app.brain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** "Use my own key" goes through the worker: the app never talks to Anthropic itself. */
class OwnKeyTest {

    private val main = listOf(File("src/main"), File("app/src/main")).first { it.isDirectory }

    @Test
    fun `nothing in the app calls Anthropic or names a model`() {
        val found = main.walkTopDown().filter { it.isFile && (it.extension == "kt" || it.extension == "xml") }
            .filter { file -> file.readText().let { it.contains("anthropic.com") || it.contains("x-api-key") || Regex("claude-[a-z]+-\\d").containsMatchIn(it) } }
            .map { it.name }.toList()
        assertTrue("found in $found", found.isEmpty())
    }

    @Test
    fun `the key rides in one header, and a refused key is said plainly`() {
        assertEquals("X-Heylana-Key", ProxyClient.OWN_KEY_HEADER)
        assertEquals(QuotaMessage.OWN_KEY_REFUSED, QuotaMessage.forReason("own_key_refused"))
        assertEquals(QuotaMessage.OWN_KEY_MALFORMED, QuotaMessage.forReason("bad_key"))
    }
}
