package xyz.heylana.app.brain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class VarietyTest {

    @Test
    fun `jokes, facts, riddles and stories get variety - other chat does not`() {
        for (q in listOf("tell me a joke", "another joke", "give me a fun fact", "tell me a riddle", "make me laugh", "tell me a story")) {
            assertTrue(q, Variety.wantsVariety(q))
        }
        for (q in listOf("how's your day going", "what is the capital of Nigeria", "who are you")) {
            assertFalse(q, Variety.wantsVariety(q))
        }
    }

    @Test
    fun `the seed changes from ask to ask`() {
        val random = Random(42)
        val seeds = (1..20).map { Variety.seedWord(random) }.toSet()
        assertTrue("only ${seeds.size} different seeds in 20", seeds.size >= 10)
        assertTrue(Variety.SEEDS.size >= 50)
        assertEquals(Variety.SEEDS.size, Variety.SEEDS.toSet().size)
    }

    @Test
    fun `the chat message carries the variety line only when a seed is given`() {
        val with = HeylanaPrompt.chatMessage("tell me a joke", seed = "penguin")
        assertTrue(with.contains("Make it fresh"))
        assertTrue(with.contains("Build it around this word: penguin."))
        assertTrue(with.endsWith("User asks: tell me a joke"))
        assertFalse(HeylanaPrompt.chatMessage("how are you").contains("Make it fresh"))
    }
}
