package xyz.heylana.app.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The audio of the lines Heylana says the same way every time. The rules that matter: the
 * voice is part of the key, a stream that was cut off is never kept, and the folder cannot
 * grow past the handful of sentences there are.
 */
class SpokenCacheTest {

    @get:Rule
    val folder = TemporaryFolder()

    private fun cache() = SpokenCache(folder.root)

    private fun write(cache: SpokenCache, voice: String, text: String, bytes: Int) {
        cache.writingTo(voice, text).writeBytes(ByteArray(bytes))
        cache.keep(voice, text, bytes.toLong())
    }

    @Test
    fun `a line is kept once it has played through, and comes back`() {
        val cache = cache()
        val line = "No real Solana app asks for your recovery phrase."
        assertNull(cache.ready("skylar", line))

        write(cache, "skylar", line, 60_000)
        val kept = cache.ready("skylar", line)
        assertTrue(kept != null && kept.length() == 60_000L)
        // The part file is gone: what is left is the finished thing.
        assertFalse(cache.writingTo("skylar", line).exists())
    }

    @Test
    fun `another voice is another recording`() {
        val cache = cache()
        val line = "Careful: this is an approval, not a transfer."
        write(cache, "skylar", line, 40_000)
        assertNull("the other voice has not said it", cache.ready("archie", line))
        assertNotEquals(cache.fileFor("skylar", line), cache.fileFor("archie", line))
        // And the same voice and words always land on the same file.
        assertEquals(cache.fileFor("skylar", line), cache.fileFor("skylar", line))
    }

    @Test
    fun `a stream that was cut off is thrown away, not kept`() {
        val cache = cache()
        val line = "Careful: this closes an account."
        cache.writingTo("skylar", line).writeBytes(ByteArray(120))
        cache.keep("skylar", line, 120)
        assertNull("half a word is worse than none", cache.ready("skylar", line))
        assertFalse(cache.writingTo("skylar", line).exists())
    }

    @Test
    fun `the folder never grows past the lines there are`() {
        val cache = cache()
        for (i in 1..20) write(cache, "skylar", "line number $i.", 10_000)
        val files = folder.root.listFiles { file -> file.name.endsWith(".pcm") }!!
        assertTrue("${files.size} files", files.size <= 12)
        // The newest is still there; the oldest went.
        assertTrue(cache.ready("skylar", "line number 20.") != null)
        assertNull(cache.ready("skylar", "line number 1."))
    }

    @Test
    fun `clearing it leaves nothing behind`() {
        val cache = cache()
        write(cache, "skylar", "Careful: this closes an account.", 20_000)
        cache.clear()
        assertNull(cache.ready("skylar", "Careful: this closes an account."))
        assertEquals(0, folder.root.listFiles()!!.size)
    }

    @Test
    fun `the name it files audio under says nothing about the words`() {
        val cache = cache()
        val line = "No real Solana app asks for your recovery phrase."
        val name = cache.fileFor("skylar", line).name
        assertTrue(name, name.matches(Regex("[0-9a-f]{20}\\.pcm")))
        assertFalse(name.contains("recovery"))
    }

    @Test
    fun `the same words in a different voice are a different file`() {
        val cache = cache()
        write(cache, "skylar/Hera", "Careful.", 40_000)
        // The slot did not change, the voice behind it did: the old clip must not answer.
        assertNull(cache.ready("skylar/Sienna", "Careful."))
        assertNotNull(cache.ready("skylar/Hera", "Careful."))
        // And a swap back finds the old one again, since the key is the pair.
        write(cache, "skylar/Sienna", "Careful.", 40_000)
        assertNotNull(cache.ready("skylar/Sienna", "Careful."))
        assertNotNull(cache.ready("skylar/Hera", "Careful."))
    }
}
