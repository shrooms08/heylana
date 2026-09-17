package xyz.heylana.app.brain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReplyParserTest {

    private fun reply(text: String) = ReplyParser.parse(text) as ReplyParser.Result.Reply

    private fun unreadable(text: String) =
        assertTrue("\"$text\" should be unreadable", ReplyParser.parse(text) is ReplyParser.Result.Unreadable)

    @Test
    fun `a clean reply speaks only its say`() {
        val r = reply("""{"say":"Lagos is lovely today.","point_at":null,"task":null}""")
        assertEquals("Lagos is lovely today.", r.say)
        assertEquals(null, r.fields["point_at"])
    }

    @Test
    fun `JSON with trailing prose - the prose is dropped`() {
        val r = reply(
            """{"say":"Abuja is the capital of Nigeria.","point_at":null,"task":null}
            |
            |Reply with ONLY this JSON, no fences, no prose. I hope that helps!""".trimMargin()
        )
        assertEquals("Abuja is the capital of Nigeria.", r.say)
        assertFalse(r.objectText.contains("hope"))
    }

    @Test
    fun `prose with embedded JSON, fenced or not - only the say`() {
        val r = reply(
            "Sure! Here is my answer:\n```json\n{\"say\": \"Why did the wallet blush? It saw the seed phrase.\", \"point_at\": null, \"task\": null}\n```\nLet me know if you want another."
        )
        assertEquals("Why did the wallet blush? It saw the seed phrase.", r.say)
        val braces = reply("I think {this} is odd. {\"say\":\"Tap {Swap} at the bottom.\",\"point_at\":4,\"task\":null}")
        assertEquals("Tap {Swap} at the bottom.", braces.say)
        assertEquals(4L, braces.fields["point_at"])
    }

    @Test
    fun `duplicated sentences are said once`() {
        assertEquals(
            "Pretty good, thanks. How about yours?",
            reply("""{"say":"Pretty good, thanks. How about yours? Pretty good, thanks. How about yours?","point_at":null,"task":null}""").say
        )
        assertEquals("Hi! Hi there.", ReplyParser.dedupe("Hi! Hi there."))
        assertEquals("Tap Swap. Then tap Review.", ReplyParser.dedupe("Tap Swap. tap swap! Then tap Review."))
        assertEquals("One sentence only", ReplyParser.dedupe("One sentence only"))
    }

    @Test
    fun `a malformed reply is unreadable, never spoken raw`() {
        unreadable("Pretty good, thanks for asking!")
        unreadable("""{"say":"Abuja is the capital.","point_at":null""")
        unreadable("""{"answer":"Abuja"}""")
        unreadable("")
        // The contract or the prompt echoed inside say.
        unreadable("""{"say":"{\"say\":\"...\",\"point_at\":<id or null>} Abuja.","point_at":null,"task":null}""")
        unreadable("""{"say":"Reply with ONLY this JSON. Abuja is the capital.","point_at":null,"task":null}""")
        unreadable("""{"say":"point_at: the id in brackets. Hello!","point_at":null,"task":null}""")
    }

    @Test
    fun `a send or quick action reply keeps its empty say and its action`() {
        val r = reply("""{"say":"","point_at":null,"task":null,"action":{"type":"intent","intent":"timer","seconds":300}}""")
        assertEquals("", r.say)
        assertEquals(300L, (r.fields["action"] as Map<*, *>)["seconds"])
    }

    @Test
    fun `strings with escapes and unicode read correctly`() {
        assertEquals("She said \"hi\" – café\nok", reply("""{"say":"She said \"hi\" – café\nok"}""").say)
    }

    @Test
    fun `every route, chat included, has the 60-word cap unless signing or starting a task`() {
        assertEquals(60, AnswerLength.capFor(explainsSigning = Routing.CHAT.explainsSigning))
        assertEquals(60, AnswerLength.capFor(explainsSigning = false, startsTask = false))
        assertEquals(40, AnswerLength.capFor(explainsSigning = true))
        assertEquals(25, AnswerLength.capFor(explainsSigning = false, startsTask = true))
    }
}
