package xyz.heylana.app.brain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards on the system prompt.
 *
 * Runs on the JVM against the prompt string itself: nothing here touches the
 * network, a device, or an API key. The prompt is resent on every request, so
 * both its content and its size are worth pinning.
 */
class HeylanaPromptTest {

    @Test
    fun `system prompt stays inside its character budget`() {
        assertTrue(
            "system prompt is ${HeylanaPrompt.SYSTEM.length} chars, budget is $BUDGET",
            HeylanaPrompt.SYSTEM.length <= BUDGET
        )
    }

    @Test
    fun `system prompt forbids the self-promotion tail`() {
        val prompt = HeylanaPrompt.SYSTEM
        assertTrue("must tell it to stop after answering", prompt.contains("then stop"))
        assertTrue("must forbid describing its abilities", prompt.contains("Never describe your abilities"))
        assertTrue("must forbid offering further help", prompt.contains("offer further help"))
        for (phrase in listOf("let me know if", "I can also", "feel free to")) {
            assertTrue("must name the closing line \"$phrase\"", prompt.contains(phrase))
        }
    }

    @Test
    fun `system prompt names Solana only as context and to forbid mentioning it`() {
        val prompt = HeylanaPrompt.SYSTEM
        // Twice, and only twice: once to say which phone this is, once in the rule
        // that stops it bringing Solana up unprompted.
        assertEquals(2, Regex("Solana").findAll(prompt).count())
        assertTrue(
            prompt.contains("mention Solana, Seeker or Heylana unless the question is about")
        )
        // The old persona listed what it knew, which is what invited the tail.
        for (bait in listOf("wallets, dApps", "Seed Vault", "dApp Store", "friendly")) {
            assertFalse("prompt still advertises \"$bait\"", prompt.contains(bait))
        }
    }

    @Test
    fun `self-description is allowed only when asked for`() {
        assertTrue(
            HeylanaPrompt.SYSTEM.contains("Only if asked what you can do, describe it in two sentences")
        )
    }

    @Test
    fun `json contract is untouched`() {
        val prompt = HeylanaPrompt.SYSTEM
        assertTrue(
            prompt.contains(
                "{\"say\":\"...\",\"point_at\":<id or null>," +
                    "\"task\":{\"goal\":\"...\",\"done\":true|false}|null}"
            )
        )
        assertTrue(prompt.contains("Reply with ONLY this JSON, no fences, no prose:"))
        assertTrue(prompt.contains("point_at: the id in brackets"))
        assertTrue(prompt.contains("task: null for a question you can answer in one go"))
    }

    @Test
    fun `a question carries the screen and the question, and history when there is any`() {
        val plain = HeylanaPrompt.userMessage("App: Clock\n[0] Button \"start\"", "what is this")
        assertTrue(plain.contains("Screen now:"))
        assertTrue(plain.contains("[0] Button \"start\""))
        assertTrue(plain.contains("User asks: what is this"))

        val withHistory = HeylanaPrompt.userMessage("App: Clock", "and then?", "Earlier:\nUser: hi")
        assertTrue(withHistory.startsWith("Earlier:"))
    }

    @Test
    fun `a step carries the goal, the steps so far and the pointer hint`() {
        val step = HeylanaPrompt.stepMessage(
            goal = "set an alarm for 7am",
            historyText = "Steps so far:\n1. Tap the plus button",
            screenText = "App: Clock",
            stepNumber = 2,
            needPointerHint = true
        )
        assertTrue(step.contains("Goal: set an alarm for 7am"))
        assertTrue(step.contains("1. Tap the plus button"))
        assertTrue(step.contains("Step 2 of max ${GuidanceSession.MAX_STEPS}"))
        assertTrue(step.contains("pointed at nothing"))

        val noHint = HeylanaPrompt.stepMessage("g", "h", "s", 1, needPointerHint = false)
        assertFalse(noHint.contains("pointed at nothing"))
    }

    private companion object {
        /** Every request pays for this string, so it is capped deliberately. */
        const val BUDGET = 1400
    }
}
