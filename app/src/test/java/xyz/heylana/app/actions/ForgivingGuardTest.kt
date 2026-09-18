package xyz.heylana.app.actions

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import xyz.heylana.app.brain.Routing

class ForgivingGuardTest {

    private fun allowed(action: QuickAction, said: String): QuickAction {
        val verdict = QuickGuard.check(action, said)
        assertTrue("$action refused for \"$said\": $verdict", verdict is QuickGuard.Verdict.Allowed)
        return (verdict as QuickGuard.Verdict.Allowed).action
    }

    @Test
    fun `spoken numbers become the digits they stand for`() {
        assertEquals("0800123", SpokenNumbers.digits("oh eight hundred one two three"))
        assertEquals("08001234567", SpokenNumbers.digits("zero eight zero zero one two three four five six seven"))
        assertEquals("447", SpokenNumbers.digits("double four seven"))
        assertEquals("21", SpokenNumbers.digits("twenty one"))
        assertEquals("0800123", SpokenNumbers.digits("0800 one two three"))
        assertEquals("", SpokenNumbers.digits("text ada I'm on my way"))
        // "o" is only a zero between other digits.
        assertEquals("505", SpokenNumbers.digits("five o five"))
        assertEquals("", SpokenNumbers.digits("go to o'hare"))
    }

    @Test
    fun `a number said aloud counts as said, for a text and a call`() {
        allowed(QuickAction.Message("0800123", null, "I'm on my way"), "message oh eight hundred one two three I'm on my way")
        allowed(QuickAction.Dial("0800123", null), "call oh eight hundred one two three")
    }

    @Test
    fun `message works as well as text, and routes as an action`() {
        val said = "message 0800 123 4567 I'm on my way"
        assertTrue(QuickActions.isQuickAction(said))
        assertTrue(QuickActions.isMessage(said))
        assertEquals(Routing.Why.QUICK_ACTION, Routing.forQuestion(null, said).why)
        allowed(QuickAction.Message("0800 123 4567", null, "I'm on my way"), said)
    }

    @Test
    fun `a reminder takes its time in any order`() {
        for (said in listOf("remind me at 6 to call my dad", "remind me to call my dad at 6", "set a reminder at 6 to call my dad")) {
            assertTrue(said, QuickActions.isQuickAction(said))
            val reminder = allowed(QuickAction.Reminder("call my dad", 6, 0), said) as QuickAction.Reminder
            assertTrue(reminder.bareHour)
        }
    }

    @Test
    fun `a missing part is asked for, once, in one question`() {
        fun ask(vararg pairs: Pair<String, Any?>) = QuickAction.clarify(mapOf("type" to "intent", *pairs))
        assertEquals("Who should I text?", ask("intent" to "message", "text" to "I'm on my way"))
        assertEquals("What should the message say?", ask("intent" to "message", "number" to "0800123"))
        assertEquals("What time should I remind you?", ask("intent" to "reminder", "text" to "call my dad"))
        assertEquals("What should I remind you about?", ask("intent" to "reminder", "hour" to 18))
        assertEquals("What time should the alarm be?", ask("intent" to "alarm"))
        assertEquals("Which app should I open?", ask("intent" to "open_app", "app" to null))
        assertNull(ask("intent" to "message", "number" to "0800123", "text" to "hi"))
        assertNull(QuickAction.clarify(mapOf("type" to "send", "intent" to "message")))
    }

    @Test
    fun `the words in the alarm-label field, a number as a name, an international number - all still a message`() {
        fun of(vararg pairs: Pair<String, Any?>) = QuickAction.of(mapOf("type" to "intent", "intent" to "message", *pairs))
        val said = "message 0800 123 4567 I'm on my way"
        // The words in "message" rather than "text".
        val inLabel = of("number" to "0800 123 4567", "message" to "I'm on my way")
        assertEquals(QuickAction.Message("0800 123 4567", null, "I'm on my way"), inLabel)
        allowed(inLabel!!, said)
        assertNull(QuickAction.clarify(mapOf("type" to "intent", "intent" to "message", "number" to "0800", "message" to "hi")))
        // The number put in "name".
        assertEquals(QuickAction.Message("0800 123 4567", null, "hi"), of("name" to "0800 123 4567", "text" to "hi"))
        // The model's international form of what was said: allowed, with the user's own digits.
        val international = allowed(QuickAction.Message("+44 800 123 4567", null, "I'm on my way"), said) as QuickAction.Message
        assertEquals("08001234567", international.number)
        val call = allowed(QuickAction.Dial("+448001234567", null), "call 0800 123 4567") as QuickAction.Dial
        assertEquals("08001234567", call.number)
        // A different number is still refused.
        assertTrue(QuickGuard.check(QuickAction.Message("+44 800 999 9999", null, "hi"), "message 0800 123 4567 hi") is QuickGuard.Verdict.Refused)
    }

    @Test
    fun `a part the model filled with a stand-in is missing - the Seeker's "Set a timer"`() {
        fun ask(vararg pairs: Pair<String, Any?>) = QuickAction.clarify(mapOf("type" to "intent", *pairs))
        // The worker's reply for "Set a timer", as seen on the Seeker.
        assertEquals("How long should the timer be?", ask("intent" to "timer", "seconds" to "<UNKNOWN>"))
        assertNull(QuickAction.of(mapOf("type" to "intent", "intent" to "timer", "seconds" to "<UNKNOWN>")))
        assertEquals("How long should the timer be?", ask("intent" to "timer"))
        assertEquals("What time should the alarm be?", ask("intent" to "alarm", "hour" to "unknown"))
        assertEquals("Which app should I open?", ask("intent" to "open_app", "app" to "<UNKNOWN>"))
        assertNull(ask("intent" to "timer", "seconds" to 120))
        assertNull(ask("intent" to "timer", "seconds" to "120"))
    }
}
