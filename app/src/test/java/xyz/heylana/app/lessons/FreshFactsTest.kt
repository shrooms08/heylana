package xyz.heylana.app.lessons

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Facts that move, held to what is true.
 *
 * Every note in `skills/` ships inside the APK and is handed to the model as the truth about
 * the app or the topic in front of the user, so a number that has moved on is worse than no
 * note at all: the model repeats it with Heylana's confidence. The dev eval caught four of
 * them at once — a slot said to be 400ms, an epoch said to be two days, a transaction capped
 * at 1,232 bytes whatever its version, and Firedancer said to be still coming.
 *
 * The slot time is the one that keeps coming back, because ten years of the internet says
 * 400ms, so it has a rule of its own: no shipped note may put "400ms" anywhere near the word
 * slot. The other three are checked by asking for the correction, not the mistake, so a note
 * rewritten from memory fails here rather than on the phone.
 */
class FreshFactsTest {

    private val repo = listOf(File(".."), File(".")).first { File(it, "skills").isDirectory }

    private val notes: List<File>
        get() = File(repo, "skills").walkTopDown().filter { it.isFile && it.extension == "md" }.toList()

    /** How near counts as next to: a sentence's worth either side. */
    private val near = 160

    private val fourHundred = Regex("400\\s?(ms|milliseconds)", RegexOption.IGNORE_CASE)

    @Test
    fun `no shipped note says a slot is 400ms`() {
        assertTrue("there are notes to check", notes.isNotEmpty())
        for (note in notes) {
            val text = note.readText()
            for (hit in fourHundred.findAll(text)) {
                val from = maxOf(0, hit.range.first - near)
                val to = minOf(text.length, hit.range.last + near)
                val around = text.substring(from, to)
                assertTrue(
                    "${note.name}: \"400ms\" next to a slot — a slot has been 300ms since " +
                        "August 2026 (SIMD-0525). Near it: ${around.replace("\n", " ")}",
                    !around.contains("slot", ignoreCase = true),
                )
            }
        }
    }

    @Test
    fun `the four corrections are in the notes that carry them`() {
        val slots = note("validators-slots.md")
        assertTrue("a slot is 300ms", slots.contains("300 milliseconds") || slots.contains("300ms"))
        assertTrue("an epoch is about 36 hours", slots.contains("36 hours"))
        assertTrue("it says which month it is true for", slots.contains("September 2026"))

        val transactions = note("transactions.md")
        assertTrue("v1 raises the cap to 4,096 bytes", transactions.contains("4,096 bytes"))
        assertTrue("and 1,232 is still the v0 cap", transactions.contains("1,232 bytes"))

        val clients = note("agave-firedancer.md")
        assertTrue("full Firedancer is on mainnet", clients.contains("launched on mainnet in December 2025"))
    }

    /** Every note says when it was last checked against a source. */
    @Test
    fun `every lesson note is dated`() {
        for (note in File(repo, "skills/lessons").listFiles().orEmpty().filter { it.extension == "md" }) {
            assertTrue("${note.name} has no checked: line", note.readText().contains("\nchecked:"))
        }
    }

    private fun note(name: String): String =
        notes.first { it.name == name }.readText()
}
