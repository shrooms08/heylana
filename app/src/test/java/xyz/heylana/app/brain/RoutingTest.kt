package xyz.heylana.app.brain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RoutingTest {

    private val chrome = "com.android.chrome"
    private val treasury = "7c2y8xXRFYVamzNJ11hX3sicHexPHNuDwpiJ6sEnSxSv"

    @Test
    fun `a general question in Chrome loads nothing and stays quick`() {
        val route = Routing.forQuestion(chrome, "what is the capital of Nigeria")
        assertEquals(ProxyClient.MODE_QUICK, route.mode)
        assertEquals(Routing.Why.PLAIN, route.why)
        assertNull(route.solana)
        assertFalse(route.toolsWanted)
    }

    @Test
    fun `the quick path's prompt is exactly what it was`() {
        assertEquals(HeylanaPrompt.SYSTEM, HeylanaPrompt.system(solana = false))
        assertTrue(HeylanaPrompt.system(solana = true).contains(SolanaCore.KNOWLEDGE))
    }

    @Test
    fun `Solana words load the knowledge, and a mere lookalike does not`() {
        assertEquals(SolanaCore.Load.WORDS, Routing.forQuestion(chrome, "what is SOL worth right now").solana)
        assertEquals(SolanaCore.Load.WORDS, Routing.forQuestion(chrome, "who is $treasury").solana)
        assertEquals(SolanaCore.Load.WORDS, Routing.forQuestion(chrome, "is bob.skr real").solana)
        assertNull(Routing.forQuestion(chrome, "help me solve this").solana)
        assertNull(Routing.forQuestion(chrome, "what's a consolation prize").solana)
    }

    @Test
    fun `a known Solana app loads the knowledge whatever the question`() {
        val route = Routing.forQuestion("com.solanamobile.dappstore", "what is on this screen")
        assertEquals(SolanaCore.Load.APP, route.solana)
        assertEquals(ProxyClient.MODE_QUICK, route.mode)
    }

    @Test
    fun `wallet, swap and signing screens go to the task model`() {
        assertEquals(Routing.Why.WALLET_SCREEN, Routing.forQuestion("com.solanamobile.wallet", "what is my balance").why)
        assertEquals(Routing.Why.SWAP_SCREEN, Routing.forQuestion("ag.jup.jupiter.android", "what is this").why)
        val signing = Routing.forQuestion("com.solanamobile.seedvaultimpl", "hello")
        assertEquals(Routing.Why.SIGNING_SCREEN, signing.why)
        assertEquals(ProxyClient.MODE_TASK, signing.mode)
    }

    @Test
    fun `send and explain questions go to the task model with tools, even in Chrome`() {
        val send = Routing.forQuestion(chrome, "send 0.05 to my friend")
        assertEquals(Routing.Why.SEND_QUESTION, send.why)
        assertEquals(ProxyClient.MODE_TASK, send.mode)
        assertTrue(send.toolsWanted)

        val signing = Routing.forQuestion(chrome, "What am I signing?")
        assertEquals(Routing.Why.EXPLAIN_QUESTION, signing.why)
        assertEquals(ProxyClient.MODE_TASK, signing.mode)

        // No Solana word in it, and it still brings the tools.
        val approve = Routing.forQuestion(chrome, "what will this approve")
        assertEquals(Routing.Why.EXPLAIN_QUESTION, approve.why)
        assertEquals(SolanaCore.Load.ROUTE, approve.solana)
    }

    @Test
    fun `asking what a button does stays quick and loads nothing`() {
        val route = Routing.forQuestion(chrome, "what does this button do")
        assertEquals(Routing.Why.PLAIN, route.why)
        assertEquals(ProxyClient.MODE_QUICK, route.mode)
        assertNull(route.solana)
    }

    @Test
    fun `the knowledge block stays near its budget`() {
        // About four characters to a token: 350 tokens is roughly 1,400 characters.
        assertTrue("knowledge is ${SolanaCore.KNOWLEDGE.length} chars", SolanaCore.KNOWLEDGE.length <= 1700)
    }
}
