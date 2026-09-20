package xyz.heylana.app.eval

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
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
        )
        out.writeText(json(request) + "\n")

        // What the eval leans on, held here so a prompt change cannot quietly break it.
        val system = request["system"] as String
        assertTrue("the Solana rules are loaded", system.contains("Solana"))
        assertTrue("the answer is still JSON", system.contains("say"))
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
