package xyz.heylana.app.brain

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TeachingSessionTest {

    @Test
    fun `teach me, show me how and help me start a walk-through`() {
        for (q in listOf(
            "teach me how to swap", "teach me to stake SOL", "show me how to send USDC", "help me swap",
            "help me set up a new wallet", "walk me through buying SKR", "can you help me connect my wallet"
        )) {
            assertTrue(q, Teaching.wantsSession(q))
            assertTrue(q, Teaching.wantsTeaching(q))
        }
        for (q in listOf("help me understand staking", "help me learn about fees", "how do I swap", "what does swap do", "help with this")) {
            assertFalse(q, Teaching.wantsSession(q))
        }
    }

    @Test
    fun `stop ends a running task, and only when it is said on its own`() {
        for (q in listOf("stop", "Stop.", "okay stop", "cancel", "that's enough", "never mind", "stop it now", "I'm done")) {
            assertTrue(q, Teaching.isStop(q))
        }
        for (q in listOf("stop loss orders", "how do I stop the timer", "cancel my subscription on spotify")) {
            assertFalse(q, Teaching.isStop(q))
        }
    }

    @Test
    fun `a walk-through asks for a task with the first step only, and no walk-through does not`() {
        val asked = HeylanaPrompt.userMessage("App: wallet", "teach me how to swap", teaching = true, walkThrough = true)
        assertTrue(asked.contains(HeylanaPrompt.WALK_THROUGH_LINE))
        assertFalse(asked.contains(HeylanaPrompt.TEACH_LINE))
        assertTrue(HeylanaPrompt.WALK_THROUGH_LINE.contains("reply with a task"))
        assertTrue(HeylanaPrompt.WALK_THROUGH_LINE.contains("first step only, starting with one short reason"))
        assertFalse(HeylanaPrompt.userMessage("App: wallet", "how do I swap").contains(HeylanaPrompt.WALK_THROUGH_LINE))
    }
}
