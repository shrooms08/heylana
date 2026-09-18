package xyz.heylana.app.lessons

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import xyz.heylana.app.brain.AnswerLength
import xyz.heylana.app.brain.BrainReply
import xyz.heylana.app.brain.LessonReply
import java.io.File
import java.time.LocalDate

/** The curriculum as the build packs it, and a lesson run against a made-up model. */
class LessonTest {

    private val dir = listOf(File("../skills/lessons"), File("skills/lessons")).first { it.isDirectory }

    private val notes: List<LessonNote> = LessonLibrary.sorted(
        dir.listFiles { f -> f.name.endsWith(".md") }!!.map { file ->
            val parsed = LessonNote.parse(file.readText())
            assertTrue("${file.name}: $parsed", parsed is LessonNote.Companion.Parsed.Ok)
            (parsed as LessonNote.Companion.Parsed.Ok).note.also { assertEquals(file.name.removeSuffix(".md"), it.id) }
        }
    )

    private fun note(id: String) = notes.single { it.id == id }

    // ------------------------------------------------------------ the notes

    @Test
    fun `twenty-one notes, ten to build and eleven on infrastructure, each under 450 tokens`() {
        assertEquals(LessonLibrary.ORDER, notes.map { it.id })
        assertEquals(10, notes.count { it.track == LessonNote.BUILD })
        assertEquals(11, notes.count { it.track == LessonNote.INFRASTRUCTURE })
        notes.forEach { note ->
            assertTrue("${note.id} tokens=${note.tokens}", note.tokens < LessonNote.MAX_TOKENS)
            assertTrue(note.id, note.chunks in 4..6)
            assertTrue(note.id, note.aliases.isNotEmpty())
            // What the Memory screen will show: the worker's own rule for a lesson line.
            assertTrue(note.id, Regex("^knows [A-Za-z0-9 .,&/+()-]{2,80}, \\d{4}-\\d{2}-\\d{2}$").matches("knows ${note.short}, 2026-09-18"))
        }
    }

    @Test
    fun `a note with too long a body, or chunks out of range, is refused`() {
        val head = "---\nid: x1\ntitle: X\ntrack: build\naliases: x\nrecap: r\n"
        assertEquals(LessonNote.Companion.Parsed.Bad("too_long"), LessonNote.parse("${head}chunks: 4\n---\n" + "word ".repeat(400)))
        assertEquals(LessonNote.Companion.Parsed.Bad("bad_chunks"), LessonNote.parse("${head}chunks: 7\n---\nbody"))
        assertEquals(LessonNote.Companion.Parsed.Bad("bad_track"), LessonNote.parse(head.replace("build", "art") + "chunks: 4\n---\nbody"))
    }

    // ------------------------------------------------------------ chunking

    @Test
    fun `every note cuts into its number of chunks, in order, every line in exactly one`() {
        notes.forEach { note ->
            val slices = note.slices()
            assertEquals(note.id, note.chunks, slices.size)
            assertTrue(note.id, slices.all { it.isNotBlank() })
            val lines = note.body.lines().map { it.trimEnd() }.filter { it.isNotBlank() }
            assertEquals(note.id, lines, slices.flatMap { it.lines() })
        }
    }

    @Test
    fun `a chunk is said in under 40 words, whole sentences where they fit`() {
        val long = "Accounts hold data. " + "Every account has an owner program that alone may change it and ".repeat(4) + "that is the rule."
        val capped = LessonText.cap(long, Lesson.CHUNK_WORDS - 1)
        assertTrue(AnswerLength.words(capped) < 40)
        assertEquals("Accounts hold data.", capped)
        val oneSentence = "word ".repeat(60).trim() + "."
        assertEquals(39, AnswerLength.words(LessonText.cap(oneSentence, 39)))
        assertEquals("What owns an account?", LessonText.question("What owns an account? And why?"))
        assertEquals("Who can sign for a PDA?", LessonText.question("Who can sign for a PDA"))
        assertNull(LessonText.question("null"))
    }

    // ------------------------------------------------------------ routing

    @Test
    fun `teach me a topic finds it, a walk-through never does`() {
        assertEquals("pdas", LessonWords.topic("Teach me PDAs", notes)?.id)
        assertEquals("pdas", LessonWords.topic("teach me about program derived addresses", notes)?.id)
        assertEquals("poh-tower-bft", LessonWords.topic("give me a lesson on proof of history", notes)?.id)
        assertEquals("token-programs", LessonWords.topic("teach me token-2022", notes)?.id)
        assertEquals("staking", LessonWords.topic("I want to learn about stake pools", notes)?.id)
        assertEquals("agave-firedancer", LessonWords.topic("learn Firedancer", notes)?.id)
        assertEquals("rpc", LessonWords.topic("teach me RPC.", notes)?.id)
        assertNull(LessonWords.topic("teach me how to swap", notes))
        assertNull(LessonWords.topic("teach me to stake", notes))
        assertNull(LessonWords.topic("what is a PDA", notes))
        assertNull(LessonWords.topic("teach me something funny", notes))
        assertTrue(LessonWords.wantsTopicList("Learn Solana"))
        assertFalse(LessonWords.wantsTopicList("teach me PDAs"))
    }

    @Test
    fun `during a lesson the commands are commands and everything else is an answer`() {
        assertEquals(LessonWords.Command.SKIP, LessonWords.command("skip"))
        assertEquals(LessonWords.Command.SKIP, LessonWords.command("next one please"))
        assertEquals(LessonWords.Command.SLOWER, LessonWords.command("Slower."))
        assertEquals(LessonWords.Command.SLOWER, LessonWords.command("can you slow down"))
        assertEquals(LessonWords.Command.EXAMPLE, LessonWords.command("give me an example"))
        assertEquals(LessonWords.Command.EXAMPLE, LessonWords.command("example"))
        assertEquals(LessonWords.Command.DEEPER, LessonWords.command("why?"))
        assertEquals(LessonWords.Command.STOP, LessonWords.command("stop"))
        assertEquals(LessonWords.Command.STOP, LessonWords.command("OK stop the lesson"))
        for (answer in listOf("the program that owns it", "no", "yes", "a program derived address", "why not the wallet", "it stops the replay", "seeds and a bump")) {
            assertNull(answer, LessonWords.command(answer))
        }
    }

    @Test
    fun `explain this over Solana docs in a browser, and nowhere else`() {
        assertTrue(LessonWords.isExplainThis("explain this"))
        assertTrue(LessonWords.isExplainThis("What does this code do?"))
        assertTrue(LessonWords.isExplainThis("explain this paragraph to me"))
        assertFalse(LessonWords.isExplainThis("explain staking"))
        assertTrue(LessonWords.isSolanaDocs("com.android.chrome", "[3] solana.com/docs/core/pda"))
        assertTrue(LessonWords.isSolanaDocs("com.android.chrome", "beta.solpg.io"))
        assertFalse(LessonWords.isSolanaDocs("com.android.chrome", "news.example.com"))
        assertFalse(LessonWords.isSolanaDocs("com.solanamobile.wallet", "solana.com/docs"))
    }

    // ------------------------------------------------------------ a lesson

    private class Model {
        val messages = mutableListOf<String>()
        val replies = ArrayDeque<BrainReply>()
        suspend fun ask(message: String, @Suppress("UNUSED_PARAMETER") step: Int): BrainReply {
            messages += message
            return replies.removeFirst()
        }
        fun says(say: String, check: String?, verdict: String? = null) {
            replies += BrainReply.Say(say, null, null, lesson = LessonReply(check, verdict))
        }
    }

    private fun lesson(model: Model, kept: MutableList<String>) = Lesson(
        note = note("pdas"),
        ask = model::ask,
        keep = { kept += it },
        today = { LocalDate.of(2026, 9, 18) }
    )

    @Test
    fun `wrong then right moves on one chunk, stop recaps and keeps knows PDAs`() = runBlocking {
        val model = Model()
        val kept = mutableListOf<String>()
        val pdas = lesson(model, kept)

        model.says("A PDA is an address a program makes from seeds.", "What is a PDA made from?")
        val first = pdas.start()
        assertEquals("Lesson on PDAs. A PDA is an address a program makes from seeds. What is a PDA made from?", first.text)
        assertEquals(1, pdas.step)
        assertTrue(model.messages[0].contains("chunk 1 of 5"))
        assertTrue("the note is the context", model.messages[0].contains(note("pdas").body))

        model.says("Not quite. Think of seeds as ingredients.", "What goes into a PDA?", verdict = "wrong")
        val wrong = pdas.hear("a private key")
        assertEquals(1, pdas.step)
        assertFalse(wrong.ended)
        assertTrue(model.messages[1].contains("\"a private key\""))
        assertTrue(model.messages[1].contains("You asked: \"What is a PDA made from?\""))

        model.says("Right. Next, no private key exists for a PDA.", "Who can sign for it?", verdict = "right")
        val right = pdas.hear("seeds and the program id")
        assertEquals(2, pdas.step)
        assertEquals("Right. Next, no private key exists for a PDA. Who can sign for it?", right.text)
        assertEquals(1, pdas.rightAnswers)

        val stop = pdas.hear("stop")
        assertTrue(stop.ended)
        assertTrue(stop.text.startsWith("Stopping there. Recap: A PDA is"))
        assertEquals(listOf("knows PDAs, 2026-09-18"), kept)
        assertEquals(3, model.messages.size)
    }

    @Test
    fun `stopping before a right answer keeps nothing, and a lesson that ends says so`() = runBlocking {
        val model = Model()
        val kept = mutableListOf<String>()
        val pdas = lesson(model, kept)
        model.says("A PDA is made from seeds.", "Made from what?")
        pdas.start()
        assertTrue(pdas.hear("that's enough").ended)
        assertTrue(kept.isEmpty())
        assertEquals(LessonText.OVER, pdas.hear("seeds").text)
    }

    @Test
    fun `skip, slower, example and why each ask for the same chunk their own way`() = runBlocking {
        val model = Model()
        val pdas = lesson(model, mutableListOf())
        model.says("Chunk one.", "Q1?")
        pdas.start()
        model.says("Slowly now.", "Q1 again?")
        pdas.hear("slower")
        model.says("For example.", "Q1 once more?")
        pdas.hear("example")
        model.says("Underneath it.", "Q1 deeper?")
        pdas.hear("why")
        assertEquals(1, pdas.step)
        model.says("Chunk two.", "Q2?")
        pdas.hear("skip")
        assertEquals(2, pdas.step)
        assertTrue(model.messages[1].contains("go slower"))
        assertTrue(model.messages[2].contains("example"))
        assertTrue(model.messages[3].contains("They asked why"))
        assertTrue(model.messages[4].contains("chunk 2 of 5"))
    }

    @Test
    fun `the last right answer ends with praise and the recap, a failed turn moves nothing`() = runBlocking {
        val model = Model()
        val kept = mutableListOf<String>()
        val pdas = lesson(model, kept)
        model.says("One.", "Q?")
        pdas.start()
        repeat(4) {
            model.says("Two.", "Q?")
            pdas.hear("skip")
        }
        assertEquals(5, pdas.step)
        model.replies += BrainReply.Failed("Couldn't reach Heylana: no connection")
        val failed = pdas.hear("seeds")
        assertTrue(failed.failed)
        assertEquals(5, pdas.step)
        model.says("Well done.", null, verdict = "right")
        val end = pdas.hear("seeds")
        assertTrue(end.ended)
        assertEquals("Well done. That's the lesson. Recap: ${note("pdas").recap}", end.text)
        assertEquals(listOf("knows PDAs, 2026-09-18"), kept)
    }

    @Test
    fun `a verdict the phone cannot read keeps the lesson where it is`() {
        assertEquals(Lesson.Verdict.PARTLY, Lesson.Verdict.of(null))
        assertEquals(Lesson.Verdict.PARTLY, Lesson.Verdict.of("maybe"))
        assertEquals(Lesson.Verdict.RIGHT, Lesson.Verdict.of(" Right "))
        assertEquals(Lesson.Verdict.WRONG, Lesson.Verdict.of("wrong"))
    }
}
