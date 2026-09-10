package xyz.heylana.app.brain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Heylana's short-term memory: three exchanges, ten minutes, one app, and a hard
 * ceiling on what any of it may cost.
 */
class ConversationTest {

    private var clock = 1_000_000L
    private fun memory(limit: Int = Conversation.MAX_TURNS) =
        Conversation(limit = limit, windowMs = Conversation.WINDOW_MS, now = { clock })

    private val wallet = "com.solanamobile.seedvault"

    @Test
    fun `a follow-up can see the exchange before it`() {
        val memory = memory()
        memory.record("what does this do", "It opens your wallet.", wallet)
        val text = memory.asPromptText(wallet)
        assertNotNull(text)
        assertTrue(text!!.contains("what does this do"))
        assertTrue(text.contains("It opens your wallet."))
    }

    @Test
    fun `nothing remembered means nothing sent`() {
        assertNull(memory().asPromptText(wallet))
    }

    @Test
    fun `only the last three exchanges are kept`() {
        val memory = memory()
        repeat(5) { memory.record("question $it", "answer $it", wallet) }
        assertEquals(3, memory.size)
        val text = memory.asPromptText(wallet)!!
        assertTrue(text.contains("question 2"))
        assertTrue(text.contains("question 4"))
        assertTrue(!text.contains("question 1"))
    }

    @Test
    fun `an exchange is forgotten ten minutes after it happened`() {
        val memory = memory()
        memory.record("what is this", "A wallet.", wallet)
        assertEquals(10 * 60 * 1_000L, Conversation.WINDOW_MS)

        clock += Conversation.WINDOW_MS - 1
        assertNotNull(memory.asPromptText(wallet))

        clock += 1
        assertNull(memory.asPromptText(wallet))
        assertEquals(0, memory.size)
    }

    @Test
    fun `the newer half of a memory survives while the older half expires`() {
        val memory = memory()
        memory.record("first", "one", wallet)
        clock += Conversation.WINDOW_MS - 1_000
        memory.record("second", "two", wallet)

        clock += 2_000
        val text = memory.asPromptText(wallet)!!
        assertTrue(!text.contains("first"))
        assertTrue(text.contains("second"))
    }

    @Test
    fun `moving to another app forgets everything`() {
        val memory = memory()
        memory.record("what does this do", "It opens your wallet.", wallet)
        assertNull(memory.asPromptText("com.android.chrome"))
        assertEquals(0, memory.size)
    }

    @Test
    fun `recording in another app starts again rather than mixing the two`() {
        val memory = memory()
        memory.record("first", "one", wallet)
        memory.record("second", "two", "com.android.chrome")
        assertEquals(1, memory.size)
        assertTrue(!memory.asPromptText("com.android.chrome")!!.contains("first"))
    }

    @Test
    fun `stopping forgets everything`() {
        val memory = memory()
        memory.record("first", "one", wallet)
        memory.clear()
        assertEquals(0, memory.size)
        assertNull(memory.asPromptText(wallet))
    }

    @Test
    fun `what is sent stays inside the budget however long the answers were`() {
        val memory = memory()
        repeat(3) { memory.record("q".repeat(300), "a".repeat(600), wallet) }
        val text = memory.asPromptText(wallet)!!
        assertTrue(text.length <= Conversation.MAX_CHARS + "Earlier:\n".length + 3)
    }
}
