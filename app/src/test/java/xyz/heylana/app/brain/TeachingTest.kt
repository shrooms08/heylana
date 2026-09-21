package xyz.heylana.app.brain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TeachingTest {

    @Test
    fun `teach me and show me how start a teaching task`() {
        for (q in listOf("teach me how to swap", "Show me how to stake SOL", "can you teach me to send USDC", "explain each step")) {
            assertTrue(q, Teaching.wantsTeaching(q))
        }
        for (q in listOf("how do I swap", "swap 1 SOL to USDC", "show me the swap button")) {
            assertFalse(q, Teaching.wantsTeaching(q))
        }
    }

    @Test
    fun `a short why on a step is about the step`() {
        for (q in listOf("why", "Why?", "but why", "why that one", "why do I tap review", "what's that for", "how come")) {
            assertTrue(q, Teaching.isWhy(q))
        }
        for (q in listOf("what is a swap", "tell me why the sky is blue and also how rainbows form in detail please", "ok next")) {
            assertFalse(q, Teaching.isWhy(q))
        }
    }

    @Test
    fun `what did I just do is a recap`() {
        for (q in listOf("what did I just do", "What have I done", "what just happened?", "recap please", "what did that do")) {
            assertTrue(q, Teaching.isRecap(q))
        }
        assertFalse(Teaching.isRecap("what do I do next"))
    }

    @Test
    fun `a task is on chain when a Solana app was used or the goal moves money`() {
        assertTrue(Teaching.onChain("swap 1 SOL to USDC", emptyList()))
        assertTrue(Teaching.onChain("change the theme", listOf("com.solanamobile.wallet")))
        assertFalse(Teaching.onChain("set a wallpaper", listOf("com.android.settings")))
    }

    @Test
    fun `a finished task keeps its goal and steps for ten minutes`() {
        val session = GuidanceSession("swap 1 SOL to USDC")
        session.record("Open Trade.", "t1", "Trade", "ag.jup.jupiter.android")
        val task = FinishedTask.of(session, now = 1_000L)
        assertEquals("swap 1 SOL to USDC", task.goal)
        assertTrue(task.historyText.contains("1. Open Trade."))
        assertTrue(task.onChain)
        assertTrue(task.fresh(1_000L + Teaching.RECAP_WINDOW_MS - 1))
        assertFalse(task.fresh(1_000L + Teaching.RECAP_WINDOW_MS))
        assertEquals(setOf("ag.jup.jupiter.android"), session.packages)
    }

    @Test
    fun `teaching goes with the first question and every step, and steps stay under 25 words`() {
        assertEquals(25, AnswerLength.STEP_WORDS)
        assertTrue(HeylanaPrompt.SYSTEM.contains("Steps under 25 words."))
        assertTrue(HeylanaPrompt.SYSTEM.contains("Asked to teach, show how, or why: each step's say starts with one short reason."))
        assertTrue(HeylanaPrompt.userMessage("App: x", "teach me how to swap", teaching = true).contains(HeylanaPrompt.TEACH_LINE))
        assertFalse(HeylanaPrompt.userMessage("App: x", "how do I swap").contains(HeylanaPrompt.TEACH_LINE))
        assertTrue(HeylanaPrompt.stepMessage("g", "h", "s", 2, needPointerHint = false, teaching = true).endsWith(HeylanaPrompt.TEACH_LINE))
        assertFalse(HeylanaPrompt.stepMessage("g", "h", "s", 2, needPointerHint = false).contains(HeylanaPrompt.TEACH_LINE))
        assertTrue(HeylanaPrompt.TEACH_LINE.contains("under 25 words"))
    }

    @Test
    fun `why asks for the step's reason with no screen and no pointer`() {
        val message = HeylanaPrompt.whyMessage("swap 1 SOL to USDC", "Steps so far:\n1. Open Trade. [Trade]", "why")
        assertTrue(message.contains("Goal: swap 1 SOL to USDC"))
        assertTrue(message.contains("1. Open Trade."))
        assertTrue(message.contains("the reason for that step in one short sentence"))
        assertTrue(message.contains("point_at null"))
        assertFalse(message.contains("Screen now:"))
    }

    @Test
    fun `a recap uses the steps, and recent activity only for an on-chain task`() {
        val onChain = HeylanaPrompt.recapMessage("swap 1 SOL", "Steps so far:\n1. Open Trade.", onChain = true, question = "what did I just do")
        assertTrue(onChain.contains("recent_activity"))
        assertTrue(onChain.contains("never invent"))
        assertFalse(HeylanaPrompt.recapMessage("set wallpaper", "1. Open Settings.", onChain = false, question = "q").contains("recent_activity"))

        val money = Routing.recapRoute(FinishedTask("swap 1 SOL", "h", onChain = true, endedAt = 0))
        assertEquals(ProxyClient.MODE_TASK, money.mode)
        assertTrue(money.toolsWanted)
        assertTrue(money.skipsScreen)
        val plain = Routing.recapRoute(FinishedTask("set wallpaper", "h", onChain = false, endedAt = 0))
        assertEquals(ProxyClient.MODE_QUICK, plain.mode)
        assertNull(plain.solana)
        assertTrue(Routing.TEACH_WHY.skipsScreen)
    }

    @Test
    fun `one pointed sentence in a task is the step to do, several pieces are a walk around the screen`() {
        // "how do I earn on my USDC": one sentence pointing at Start. It must wait for the tap.
        assertEquals(false, Teaching.stepWalksTheScreen(pieces = 1, teaches = true))
        // An explanation in pieces walks the screen and needs no tap.
        assertEquals(true, Teaching.stepWalksTheScreen(pieces = 3, teaches = true))
        // Plain words never walk.
        assertEquals(false, Teaching.stepWalksTheScreen(pieces = 0, teaches = false))
    }
}
