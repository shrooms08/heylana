package xyz.heylana.app.brain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SaySegmentTest {

    private fun reply(text: String) = ReplyParser.parse(text) as ReplyParser.Result.Reply

    @Test
    fun `a string say is one piece, pointing where point_at says`() {
        val r = reply("""{"say":"Tap Swap at the bottom.","point_at":4,"task":null}""")
        assertEquals(listOf(SaySegment("Tap Swap at the bottom.", 4)), r.segments)
        assertEquals("Tap Swap at the bottom.", r.say)
    }

    @Test
    fun `segments are read in order, with their own elements`() {
        val r = reply(
            """{"say":[{"text":"Swaps live on the Swap tab.","point_at":2},
               {"text":"Pick what you pay with here.","point_at":5},
               {"text":"Then check the rate.","point_at":null}],"point_at":null,"task":null}""".trimIndent()
        )
        assertEquals(3, r.segments.size)
        assertEquals(SaySegment("Swaps live on the Swap tab.", 2), r.segments[0])
        assertEquals(SaySegment("Then check the rate.", null), r.segments[2])
        // The whole answer is still one line for the cap, the strip and the memory.
        assertEquals("Swaps live on the Swap tab. Pick what you pay with here. Then check the rate.", r.say)
    }

    @Test
    fun `four at most, and anything malformed is dropped`() {
        val segments = SaySegment.of(
            listOf(
                mapOf("text" to "One.", "point_at" to 1),
                mapOf("text" to "  ", "point_at" to 2),
                mapOf("text" to "Two.", "point_at" to "3"),
                mapOf("point_at" to 4),
                "a bare string",
                mapOf("text" to "Three.", "point_at" to -1),
                mapOf("text" to "Four."),
                mapOf("text" to "Five.")
            )
        )
        assertEquals(SaySegment.MAX_SEGMENTS, segments.size)
        assertEquals(listOf("One.", "Two.", "Three.", "Four."), segments.map { it.text })
        assertEquals(listOf(1, 3, null, null), segments.map { it.pointAt })
    }

    @Test
    fun `an answer that walks the screen says so`() {
        val walking = BrainReply.Say("a b", null, null, segments = listOf(SaySegment("a", 1), SaySegment("b", null)))
        assertTrue(walking.teaches)
        assertTrue(BrainReply.Say("a", 1, null, segments = listOf(SaySegment("a", 1))).teaches)
        assertTrue(!BrainReply.Say("a", null, null, segments = listOf(SaySegment("a", null))).teaches)
    }

    @Test
    fun `the contract offers both shapes, and teaching asks for the pieces`() {
        assertTrue(HeylanaPrompt.SYSTEM.contains("say: the words."))
        assertTrue(HeylanaPrompt.SYSTEM.contains("use up to 4 pieces"))
        assertTrue(HeylanaPrompt.SYSTEM.contains("[{\"text\":\"one sentence\",\"point_at\":<id or null>}]"))
        assertTrue(HeylanaPrompt.TEACH_LINE.contains("Use say pieces"))
        assertTrue(HeylanaPrompt.SEGMENTS_LINE.contains("point_at on every sentence that names a button"))
    }

    @Test
    fun `a say with nothing usable in it is unreadable, not silence`() {
        assertTrue(ReplyParser.parse("""{"say":[],"point_at":null,"task":null}""") is ReplyParser.Result.Unreadable)
        assertTrue(ReplyParser.parse("""{"say":[{"text":"  "}],"point_at":null}""") is ReplyParser.Result.Unreadable)
    }
}
