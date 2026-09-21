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

    // ------------------------------------------------------------ how Solana works

    @Test
    fun `how Solana works gets the knowledge base's answer, code-shaped or not`() {
        for (question in listOf(
            "What is Agave?",
            "How long is a Solana epoch?",
            "How long is a Solana slot and how many does one leader get in a row?",
            "Does Solana have a mempool?",
            "What is a snapshot used for on Solana?",
            "How do staking rewards actually reach a delegator?",
            "Why do validators need SOL just to keep running?",
            "What is a local fee market on Solana?",
            "Is rent still collected from accounts on Solana?",
            "How much SOL does a token account need to be rent exempt?",
            "What does signAndSendTransactions do in Mobile Wallet Adapter?",
            "What causes 'Signature verification failed' when I send a transaction?",
            "What does 'This transaction has already been processed' mean?",
            "What does AccountOwnedByWrongProgram (3007) tell me?",
            "When should I avoid init_if_needed in Anchor?",
        )) {
            assertTrue(question, DevQuestion.isMechanics(question))
        }
    }

    @Test
    fun `the user's own money, a decision, a send or the screen are someone else's question`() {
        for (question in listOf(
            "How much SOL do I have?",
            "Is my SOL safe?",
            "How much is SOL worth?",
            "What is the SOL price today?",
            "Should I stake my SOL?",
            "Send 0.05 USDC to Ada",
            "Send 2 SOL to ada.skr",
            "Swap it all to USDC",
            "What am I signing?",
            "What does this button do?",
            "What is on the screen here?",
            "Is it safe to approve this?",
            "Tell me a joke",
        )) {
            assertFalse(question, DevQuestion.isMechanics(question))
        }
    }

    @Test
    fun `code leads only when the question asks for it`() {
        assertTrue(DevQuestion.wantsCode("How do I add a priority fee to a transaction?"))
        assertTrue(DevQuestion.wantsCode("How do I call another program from inside an Anchor instruction?"))
        assertTrue(DevQuestion.wantsCode("Show me the code to derive a PDA"))
        // Asking what a thing is, or why an error happens, wants words.
        assertFalse(DevQuestion.wantsCode("How long is a Solana epoch?"))
        assertFalse(DevQuestion.wantsCode("What do the seeds and bump constraints check in Anchor?"))
        assertFalse(DevQuestion.wantsCode("Why can two transactions that write the same account not run at once?"))
        // "Error Code" is the error's name.
        assertFalse(DevQuestion.wantsCode("My Anchor program failed with Error Code: ConstraintSeeds, Error Number: 2006. What does it mean?"))
    }

    @Test
    fun `the words line asks for the lookup, the trap and the source, and no code`() {
        val line = HeylanaPrompt.MECHANICS_LINE
        assertTrue(line, line.contains("knowledge base"))
        assertTrue(line, line.contains("trap"))
        assertTrue(line, line.contains("cite"))
        assertFalse("no code unless asked", line.contains("\"code\""))
    }

    @Test
    fun `a slot or an epoch is dated, since both moved this year`() {
        assertTrue(DevQuestion.movesWithVersion("How long is a Solana slot?"))
        assertTrue(DevQuestion.movesWithVersion("How long is a Solana epoch?"))
        assertTrue(DevQuestion.movesWithVersion("What is the maximum size of a Solana transaction?"))
        assertTrue(DevQuestion.movesWithVersion("Is rent still collected from accounts on Solana?"))
    }

    @Test
    fun `a reply with no code is unchanged`() {
        assertNull(null as String?)
        assertEquals("Just words.", Sources.shown(null, "Just words."))
    }
}
