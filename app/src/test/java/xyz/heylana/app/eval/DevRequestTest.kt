package xyz.heylana.app.eval

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import xyz.heylana.app.brain.DevQuestion
import xyz.heylana.app.brain.HeylanaPrompt
import java.io.File

/**
 * Writes what the phone sends for a developer's Solana question, so the dev eval asks the
 * worker exactly what Heylana asks it.
 *
 * A developer question typed on Home is chat with the Solana knowledge and the lookups
 * loaded — the words carry Solana in them, so `AppChat` sends `solana` and `tools` — and
 * that is the one request shape the whole of `scripts/eval/dev_set.jsonl` is scored
 * against. The question itself is put in by the eval, so the prompt is written here once
 * with a sentinel in its place and split around it.
 */
class DevRequestTest {

    private val repo = listOf(File(".."), File(".")).first { File(it, "skills").isDirectory }
    private val out = File(repo, "scripts/eval/dev_request.json")

    /** Fixed, so the same question asks the same thing every run. */
    private val now = "Now: Saturday 20 September 2026, 11:00 (UTC+01:00). UTC: 10:00."

    private val sentinel = "<<<QUESTION>>>"

    @Test
    fun `the developer request is what the phone would send`() {
        val message = HeylanaPrompt.chatMessage(sentinel, now = now)
        assertTrue("the question goes in whole", message.contains(sentinel))
        val prefix = message.substringBefore(sentinel)
        val suffix = message.substringAfter(sentinel)
        assertEquals(message, prefix + sentinel + suffix)

        val request = mapOf(
            "mode" to "quick",
            "max_tokens" to 300,
            "system" to HeylanaPrompt.system(solana = true),
            "user_prefix" to prefix,
            "user_suffix" to suffix,
            // Solana words load the knowledge and the lookups, and the knowledge base is
            // one of them: that is where a source chip comes from.
            "tools" to true,
            // What `ProxyClient.ask` puts after the question when the question is a
            // developer's one. The eval was sending the question without it, so it was
            // measuring an answer the phone never asks for.
            "dev_line" to HeylanaPrompt.DEV_LINE,
            "dev_version_line" to HeylanaPrompt.DEV_VERSION_LINE,
        )
        out.writeText(json(request) + "\n")

        // What the eval leans on, held here so a prompt change cannot quietly break it.
        val system = request["system"] as String
        assertTrue("the Solana rules are loaded", system.contains("Solana"))
        assertTrue("the answer is still JSON", system.contains("say"))
    }

    /**
     * Which of the set's questions the phone would give the developer's shape to.
     *
     * `ProxyClient.ask` adds [HeylanaPrompt.DEV_LINE] — the code, the trap, the source —
     * only to a question [DevQuestion.isDev] recognises, and the dated line only when the
     * answer moves with a release. The eval was sending every question without either, so
     * for the questions the phone treats as a developer's it was scoring an answer Heylana
     * never asks for. The verdict is worked out here, by the phone's own classifier, and
     * written beside the question so the eval asks exactly what the phone would.
     */
    @Test
    fun `the set carries the phone's verdict on each question`() {
        val set = File(repo, "scripts/eval/dev_set.jsonl").readLines().filter { it.isNotBlank() }
        assertEquals(60, set.size)
        val verdicts = set.associate { line ->
            val question = field(line, "q")
            field(line, "id") to mapOf(
                "dev" to DevQuestion.isDev(question),
                "dated" to DevQuestion.movesWithVersion(question),
            )
        }
        assertEquals(60, verdicts.size)
        File(repo, "scripts/eval/dev_verdicts.json").writeText(json(verdicts) + "\n")
    }

    /** One string field out of a line of the set; org.json is a stub here. */
    private fun field(line: String, name: String): String {
        val at = line.indexOf("\"$name\": \"") + name.length + 5
        val out = StringBuilder()
        var i = at
        while (i < line.length && line[i] != '"') {
            if (line[i] == '\\') { i++; out.append(line[i]) } else out.append(line[i])
            i++
        }
        return out.toString()
    }

    /** org.json is a stub in unit tests, so the writer is here, as the other eval's is. */
    private fun json(value: Any?): String = when (value) {
        null -> "null"
        is String -> "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"")
            .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t") + "\""
        is Boolean, is Number -> value.toString()
        is Map<*, *> -> value.entries.joinToString(",", "{", "}") { json(it.key.toString()) + ":" + json(it.value) }
        is List<*> -> value.joinToString(",", "[", "]") { json(it) }
        else -> json(value.toString())
    }
}
