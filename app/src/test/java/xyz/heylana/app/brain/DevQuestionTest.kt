package xyz.heylana.app.brain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Who is asking, and what they get. A developer gets the code first, the trap after it and
 * a page to check; everyone else gets the sentence they came for.
 */
class DevQuestionTest {

    @Test
    fun `someone building gets the developer's shape`() {
        for (question in listOf(
            "How do I derive a PDA in Anchor?",
            "How can I sign for a PDA in a CPI?",
            "Why does my program fail with error code 2006?",
            "What does the init constraint do?",
            "How do I add a priority fee?",
            "My transaction fails with custom program error: 0x1770",
            "How do I call spl-token from the CLI?",
            "What does authorize return in Mobile Wallet Adapter?",
        )) {
            assertTrue(question, DevQuestion.isDev(question))
        }
    }

    @Test
    fun `someone using Solana does not`() {
        for (question in listOf(
            "Is my SOL safe?",
            "What is Solana?",
            "How much is SOL worth?",
            "Should I stake my SOL?",
            "What is a lamport?",
            "Send 0.05 USDC to Ada",
            "Tell me a joke",
            "What am I signing?",
            // An amount has four digits in it too; only an error is an error.
            "Send 2000 USDC to Ada",
            "What happened in 2024?",
        )) {
            assertFalse(question, DevQuestion.isDev(question))
        }
    }

    @Test
    fun `an error is a developer's question even when it names no tool`() {
        assertTrue(DevQuestion.isDev("Why does my program fail with error code 2006?"))
        assertTrue(DevQuestion.isDev("custom program error: 0x1770"))
        assertTrue(DevQuestion.isDev("Error Code: ConstraintSeeds. Error Number: 2006"))
    }

    @Test
    fun `a developer's word alone is not enough, nor a developer's grammar alone`() {
        // The subject without the work: a user asking what a thing is.
        assertFalse(DevQuestion.isDev("What is a lookup table"))
        // The work without the subject: not a Solana question at all.
        assertFalse(DevQuestion.isDev("How do I change my ringtone?"))
        // Both, so it is one.
        assertTrue(DevQuestion.isDev("How do I make a lookup table?"))
    }

    @Test
    fun `a fact that moves is dated, one that does not is left alone`() {
        assertTrue(DevQuestion.movesWithVersion("What is the current Anchor version?"))
        assertTrue(DevQuestion.movesWithVersion("How long is a slot time now?"))
        assertTrue(DevQuestion.movesWithVersion("What is the max transaction size?"))
        assertTrue(DevQuestion.movesWithVersion("Which Firedancer is on mainnet?"))
        // The shape of an account, or what a word means, does not move with a release.
        assertFalse(DevQuestion.movesWithVersion("What is a program derived address?"))
        assertFalse(DevQuestion.movesWithVersion("What does has_one check?"))
    }

    @Test
    fun `the shape asks for the code, the trap and the source, in that order`() {
        val line = HeylanaPrompt.DEV_LINE
        val code = line.indexOf("\"code\"")
        val trap = line.indexOf("trap")
        val cite = line.indexOf("cite")
        assertTrue("code comes first", code in 1 until trap)
        assertTrue("the source comes last", trap < cite)
        // And the search, because the eval found it answering from memory.
        assertTrue(line, line.contains("Search the Solana knowledge base first"))
        assertTrue(line, line.contains("never read aloud"))
        assertTrue(HeylanaPrompt.DEV_VERSION_LINE.contains("as of September 2026"))
    }

    @Test
    fun `the code is shown above the words, and the words stand on their own`() {
        val words = "findProgramAddressSync was renamed; the async one is deprecated."
        val code = "// @solana/web3.js v1, September 2026\nconst [pda] = PublicKey.findProgramAddressSync(seeds, programId)"
        val shown = Sources.shown(code, words)
        assertTrue(shown.startsWith("// @solana/web3.js v1"))
        assertTrue(shown.endsWith(words))
        // Nothing to show is the answer as it always was.
        assertEquals(words, Sources.shown(null, words))
        assertEquals(words, Sources.shown("   ", words))
    }

    @Test
    fun `a reply with no code is unchanged`() {
        assertNull(null as String?)
        assertEquals("Just words.", Sources.shown(null, "Just words."))
    }
}
