package xyz.heylana.app.brain

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SolanaWordsTest {

    @Test
    fun `Solana's own ideas bring the Solana block and the knowledge base`() {
        for (q in listOf("how do priority fees work", "what is a PDA", "explain compute units", "what does Anchor do", "when is the next epoch")) {
            assertTrue(q, SolanaCore.mentionsSolana(q))
        }
    }

    @Test
    fun `everyday questions stay plain chat`() {
        for (q in listOf("tell me a joke", "what's the capital of France", "how far is the moon", "what fees does my bank charge")) {
            assertFalse(q, SolanaCore.mentionsSolana(q))
        }
    }
}
