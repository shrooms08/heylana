package xyz.heylana.app.brain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class TruncatedReplyTest {

    @Test
    fun `a reply cut off after its speech keeps the speech and the target`() {
        // say first, as the answer shape now puts it: the limit fell in "task".
        val cut = """{"say":"Tap the green Swap button at the bottom to review.","point_at":29,"task":{"goal":"Swap 0.0009 SO"""
        val kept = ReplyParser.salvage(cut)!!
        assertEquals("Tap the green Swap button at the bottom to review.", kept.say)
        assertEquals(29, kept.pointAt)
    }

    @Test
    fun `a reply cut off mid-speech keeps only whole sentences`() {
        val cut = """{"say":"Swapping needs the Trade screen. Tap Trade at the bott"""
        assertEquals("Swapping needs the Trade screen.", ReplyParser.salvage(cut)!!.say)
        // Pieces: the first piece's text counts as speech too.
        val pieces = """{"say":[{"text":"Tap Trade to start.","point_at":12},{"text":"Then Sw"""
        val kept = ReplyParser.salvage(pieces)!!
        assertEquals("Tap Trade to start.", kept.say)
        assertEquals(12, kept.pointAt)
    }

    @Test
    fun `a reply cut off with no speech in it salvages nothing, so the retry asks for one sentence`() {
        // What the Seeker got on Sept 21: 300 tokens with no say key at all.
        assertNull(ReplyParser.salvage("""{"task":{"goal":"Swap 0.0009 SOL to USDC","done":false},"point_at":13,"reason":"The user"""))
        // Speech begun but no whole sentence yet.
        assertNull(ReplyParser.salvage("""{"say":"Now tap the green Sw"""))
        assertTrue(ReplyParser.ONE_SENTENCE.contains("one short sentence"))
        assertEquals("Give me a second, let me look again.", ReplyParser.LOOK_AGAIN)
    }

    @Test
    fun `the ears' line is only ever the ears' line`() {
        assertEquals("I didn't catch that.", PlainError.EARS)
        assertNotEquals(PlainError.EARS, ReplyParser.UNREADABLE)
        assertNotEquals(PlainError.EARS, ReplyParser.LOOK_AGAIN)
        // Nowhere else in the app says it: a reply the model got wrong is not the ears failing.
        val main = File("src/main/java")
        val sayers = main.walkTopDown()
            .filter { it.isFile && it.extension == "kt" && it.readText().contains("didn't catch that") }
            .map { it.name }
            .toList()
        assertEquals(listOf("PlainError.kt"), sayers)
    }
}
