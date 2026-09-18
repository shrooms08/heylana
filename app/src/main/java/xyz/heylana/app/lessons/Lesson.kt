package xyz.heylana.app.lessons

import xyz.heylana.app.brain.AnswerLength
import xyz.heylana.app.brain.BrainReply
import xyz.heylana.app.brain.HeylanaPrompt
import java.time.LocalDate

/** What a lesson turn says, and whether the lesson is over. */
data class LessonLine(val text: String, val ended: Boolean, val failed: Boolean = false)

/**
 * One lesson in progress: a topic note taught in its 4 to 6 chunks, each under
 * [CHUNK_WORDS] spoken words and followed by a check question. The user's answer decides
 * what comes next — right moves on, partly gets an example, wrong is explained again
 * another way — and "skip", "slower", "example", "why" and "stop" work at any point.
 *
 * The phone keeps the place: which chunk, the question waiting, how many answers were
 * right. The model is asked one thing a turn, on the quick model, with the note as its only
 * context. At the end the note's own recap is said (no call), and a lesson with at least one
 * right answer is kept in memory as "knows PDAs, 2026-09-18" when memory is on.
 */
class Lesson(
    val note: LessonNote,
    /** One quick-model turn: the message, and the chunk it is about (for the log). */
    private val ask: suspend (message: String, step: Int) -> BrainReply,
    /** Keeps "knows <topic>, <date>" as the user's progress; the caller knows if memory is on. */
    private val keep: suspend (content: String) -> Unit,
    private val today: () -> LocalDate = LocalDate::now,
    private val log: (String) -> Unit = {}
) {
    private val slices = note.slices()
    val size: Int get() = slices.size

    /** The chunk being taught, 1 to [size]. */
    var step = 1
        private set
    var ended = false
        private set
    /** The check question waiting for an answer, if one is. */
    var check: String? = null
        private set
    var rightAnswers = 0
        private set
    private var turns = 0

    suspend fun start(): LessonLine {
        log("lesson=${note.id} step=1 start chunks=$size")
        val first = turn(Kind.TEACH)
        return if (first.failed) first else first.copy(text = "${LessonText.started(note)} ${first.text}")
    }

    /** What the user said while the lesson runs: a command, or an answer to the check. */
    suspend fun hear(text: String): LessonLine {
        if (ended) return LessonLine(LessonText.OVER, ended = true)
        val command = LessonWords.command(text)
        log("lesson=${note.id} step=$step heard=${command?.name?.lowercase() ?: "answer"}")
        return when (command) {
            LessonWords.Command.STOP -> finish(stopped = true, lead = null)
            LessonWords.Command.SKIP -> if (step >= size) finish(stopped = false, lead = null) else {
                step++
                turn(Kind.TEACH).also { if (it.failed) step-- }
            }
            LessonWords.Command.SLOWER -> turn(Kind.SLOWER)
            LessonWords.Command.EXAMPLE -> turn(Kind.EXAMPLE)
            LessonWords.Command.DEEPER -> turn(Kind.DEEPER)
            null -> turn(Kind.ANSWER, answer = text.trim())
        }
    }

    enum class Kind { TEACH, ANSWER, SLOWER, EXAMPLE, DEEPER }

    private suspend fun turn(kind: Kind, answer: String? = null): LessonLine {
        if (turns >= MAX_TURNS) {
            log("lesson=${note.id} step=$step turns=$turns cap reached")
            return finish(stopped = true, lead = null)
        }
        turns++
        val last = step >= size
        val message = HeylanaPrompt.lessonMessage(
            note = note,
            step = step,
            size = size,
            slice = slices[step - 1],
            nextSlice = slices.getOrNull(step),
            instruction = instruction(kind, answer, last)
        )
        val reply = ask(message, step)
        if (reply !is BrainReply.Say) {
            turns--
            return LessonLine((reply as BrainReply.Failed).message, ended = false, failed = true)
        }
        val verdict = if (kind == Kind.ANSWER) Verdict.of(reply.lesson?.verdict) else null
        log("lesson=${note.id} step=$step kind=${kind.name.lowercase()}${verdict?.let { " verdict=${it.name.lowercase()}" } ?: ""}")
        val said = LessonText.cap(reply.text, CHUNK_WORDS - 1)
        val question = reply.lesson?.check?.let { LessonText.question(it) }
        if (verdict == Verdict.RIGHT) {
            rightAnswers++
            if (last) return finish(stopped = false, lead = said)
            step++
        }
        // Whatever the model left out, the same question still stands.
        check = question ?: check
        return LessonLine(listOfNotNull(said.takeIf { it.isNotBlank() }, check).joinToString(" "), ended = false)
    }

    private fun instruction(kind: Kind, answer: String?, last: Boolean): String = when (kind) {
        Kind.TEACH -> HeylanaPrompt.LESSON_TEACH
        Kind.SLOWER -> HeylanaPrompt.LESSON_SLOWER
        Kind.EXAMPLE -> HeylanaPrompt.LESSON_EXAMPLE
        Kind.DEEPER -> HeylanaPrompt.LESSON_DEEPER
        Kind.ANSWER -> HeylanaPrompt.lessonAnswer(check ?: "", answer.orEmpty(), last)
    }

    /** The end: the note's recap, and the progress kept if an answer was ever right. */
    private suspend fun finish(stopped: Boolean, lead: String?): LessonLine {
        ended = true
        check = null
        val kept = rightAnswers > 0
        if (kept) keep(progress())
        log("lesson=${note.id} step=$step end stopped=$stopped right=$rightAnswers kept=$kept")
        val close = if (stopped) LessonText.stopped(note) else LessonText.finished(note)
        return LessonLine(listOfNotNull(lead?.takeIf { it.isNotBlank() }, close).joinToString(" "), ended = true)
    }

    /** "knows PDAs, 2026-09-18": what the Memory screen shows for a lesson. */
    fun progress(): String = "knows ${note.short}, ${today()}"

    enum class Verdict {
        RIGHT, PARTLY, WRONG;

        companion object {
            /** Anything but a clear "right" keeps the lesson on the same chunk. */
            fun of(raw: String?): Verdict = when (raw?.trim()?.lowercase()) {
                "right", "correct", "yes" -> RIGHT
                "wrong", "incorrect", "no" -> WRONG
                else -> PARTLY
            }
        }
    }

    companion object {
        /** Each chunk is under this many spoken words: 39 at most. */
        const val CHUNK_WORDS = 40
        /** A check question is one short question. */
        const val CHECK_WORDS = 20
        /** A lesson is never more than this many model turns, whatever is said. */
        const val MAX_TURNS = 16
    }
}

/** Every word a lesson says itself. */
object LessonText {
    const val OVER = "That lesson is over. Say teach me and a topic to start another."
    const val PICK = "Which topic? Say teach me and a topic, like PDAs, transactions, staking or RPC."

    fun started(note: LessonNote) = "Lesson on ${note.short}."
    fun finished(note: LessonNote) = "That's the lesson. Recap: ${note.recap}"
    fun stopped(note: LessonNote) = "Stopping there. Recap: ${note.recap}"

    /** The whole sentences that fit in [maxWords]; the first sentence alone is cut at the word. */
    fun cap(text: String, maxWords: Int): String {
        val trimmed = text.trim()
        if (AnswerLength.words(trimmed) <= maxWords) return trimmed
        val sentences = Regex("(?<=[.!?])\\s+").split(trimmed)
        val kept = StringBuilder()
        for (sentence in sentences) {
            val next = if (kept.isEmpty()) sentence else "$kept $sentence"
            if (AnswerLength.words(next) > maxWords) break
            kept.clear().append(next)
        }
        if (kept.isNotEmpty()) return kept.toString()
        return trimmed.split(Regex("\\s+")).take(maxWords).joinToString(" ").trimEnd(',', ';', ':') + "."
    }

    /** One short question, ending in a question mark. */
    fun question(text: String): String? {
        val trimmed = text.trim().takeIf { it.isNotEmpty() && it.lowercase() != "null" } ?: return null
        val first = Regex("(?<=[?])\\s+").split(trimmed).first()
        val short = if (AnswerLength.words(first) <= Lesson.CHECK_WORDS) first
        else first.split(Regex("\\s+")).take(Lesson.CHECK_WORDS).joinToString(" ").trimEnd('?', '.', ',') + "?"
        return if (short.endsWith("?")) short else short.trimEnd('.', '!') + "?"
    }
}
