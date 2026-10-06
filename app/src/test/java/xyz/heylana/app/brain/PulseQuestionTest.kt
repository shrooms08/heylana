package xyz.heylana.app.brain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import xyz.heylana.app.memory.MemoryWords

/**
 * Which questions are answered from the pulse cache — what is happening now — and which are
 * not. The one that started it: "what hackathon is going on on Solana right now?"
 */
class PulseQuestionTest {

    @Test
    fun `a question about now goes to the pulse`() {
        for (question in listOf(
            "what hackathon is going on on Solana right now?",
            "What's new on Solana right now?",
            "what's new on Solana this week?",
            "any hackathons coming up?",
            "are there any bounties I could do?",
            "when is the Colosseum deadline?",
            "what's the latest Agave release?",
            "anything happening on Solana lately?",
            "show me Solana news",
            "is the hackathon still open?",
        )) {
            assertTrue(question, PulseQuestion.isTimeSensitive(question))
        }
    }

    @Test
    fun `a question about how Solana works never does`() {
        for (question in listOf(
            "what is a PDA?",
            "how do I derive a PDA in Anchor?",
            "how long is a Solana epoch?",
            "explain priority fees",
            "what's my USDC balance?",
            "how much SOL do I have",
            "send 0.01 USDC to my treasury",
            "what does this button do?",
            "teach me PDAs",
            "how do I enter a hackathon?",
        )) {
            assertFalse(question, PulseQuestion.isTimeSensitive(question))
        }
    }

    @Test
    fun `a question that names one kind asks for that kind`() {
        assertEquals("hackathon", PulseQuestion.categoryOf("any hackathons right now?"))
        assertEquals("hackathon", PulseQuestion.categoryOf("when is the bounty deadline?"))
        assertEquals("release", PulseQuestion.categoryOf("what's the latest Agave release?"))
        assertNull(PulseQuestion.categoryOf("what's new on Solana?"))
    }

    @Test
    fun `nothing from the pulse is ever kept in memory`() {
        // Memory is for facts about the user; the pulse is a cache that is refetched every six
        // hours, so a hackathon's name must never end up in it.
        for (question in listOf(
            "what hackathon is going on on Solana right now?",
            "What's new on Solana right now?",
            "any bounties this week?",
            "when is the Colosseum deadline?",
        )) {
            assertNull(question, MemoryWords.aboutUser(question))
            assertNull(question, MemoryWords.explicit(question))
            assertNull(question, MemoryWords.inferred(question))
        }
    }

    @Test
    fun `the Home chip asks the pulse question`() {
        val chip = xyz.heylana.app.home.HomeChips.ALL.first { it.label == "What's new on Solana" }
        val action = chip.action as xyz.heylana.app.home.ChipAction.Send
        assertTrue(PulseQuestion.isTimeSensitive(action.message))
    }
}
