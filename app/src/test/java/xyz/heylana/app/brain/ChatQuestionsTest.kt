package xyz.heylana.app.brain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatQuestionsTest {

    @Test
    fun `small talk, jokes, opinions and general knowledge are chat`() {
        for (q in listOf(
            "how's your day going", "How are you?", "hey", "thanks!", "good morning Heylana",
            "tell me a joke", "give me a fun fact", "what do you think about pineapple on pizza",
            "what's your favourite movie", "who are you", "I'm bored",
            "who won the 2010 world cup", "what is the capital of Nigeria", "how far is the moon",
            "what does serendipity mean", "when was the Eiffel Tower built"
        )) {
            assertTrue("\"$q\" should be chat", ChatQuestions.isChat(q))
        }
    }

    @Test
    fun `anything about the screen, the phone, money or an action is not chat`() {
        for (q in listOf(
            "what does this button do", "what is the capital shown here", "how do I get to settings",
            "what do you think of this app", "tell me a joke about this page",
            "how many tokens do I have", "what is the SOL price", "send 1 SOL to bob.skr",
            "what am I signing", "set a timer for 5 minutes", "open the wallet", "call mum",
            "why", "what should I tap next", "where is the swap button", "how are you able to see my screen",
            "what's on screen", "summarise", ""
        )) {
            assertFalse("\"$q\" should not be chat", ChatQuestions.isChat(q))
        }
    }

    @Test
    fun `a chat route is quick, carries nothing Solana, skips the screen and may greet`() {
        val route = Routing.chatRoute("tell me a joke")!!
        assertEquals(ProxyClient.MODE_QUICK, route.mode)
        assertEquals("chat", route.why.log)
        assertNull(route.solana)
        assertFalse(route.toolsWanted)
        assertTrue(route.skipsScreen)
        assertTrue(route.allowsGreeting)
        assertNull(Routing.chatRoute("what does this button do"))
        assertFalse(Routing.PLAIN.skipsScreen)
    }
}
