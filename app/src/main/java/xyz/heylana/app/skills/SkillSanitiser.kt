package xyz.heylana.app.skills

/**
 * Takes out of a skill anything that reads like an order to the model.
 *
 * A skill is reference text written by someone else, and the system prompt already
 * says it can never authorise anything. This is the second lock: a line that opens
 * with "you must", "ignore", "send", "sign" or their cousins is not a note about an
 * app, so it never reaches the model at all. List markers, quote marks and emphasis
 * in front of the words do not hide them. A few phrases are removed wherever they
 * appear in a line ("ignore previous instructions", "system prompt").
 *
 * Built-in skills are written not to trip it, and a test holds them to that.
 */
object SkillSanitiser {

    data class Result(val text: String, val stripped: List<String>)

    /** Whole words at the start of a line, after any list marker. */
    private val OPENERS = listOf(
        "you must", "you should", "you will", "you are now", "you have to", "you need to",
        "ignore", "disregard", "forget", "override",
        "send", "sign", "transfer", "approve",
        "act as", "pretend", "system:", "assistant:", "user:", "instructions:"
    )

    private val ANYWHERE = Regex(
        "ignore (all|any|the|previous|prior|above|earlier|your)|disregard (all|any|the|previous|prior|above|your)|" +
            "system prompt|new instructions|heylana'?s rules|developer mode|jailbreak",
        RegexOption.IGNORE_CASE
    )

    /** "- ", "* ", "1. ", "2) ", "> ", "**", "#", quotes: whatever sits in front of the words. */
    private val LEAD = Regex("^[\\s>*_#`\"'\\-•]*(\\d+[.)]\\s*)?[\\s>*_#`\"'\\-•]*")

    private val openers = OPENERS.map { word ->
        val end = if (word.last().isLetter()) "(?![\\p{L}\\p{N}])" else ""
        Regex("^${Regex.escape(word)}$end", RegexOption.IGNORE_CASE)
    }

    fun clean(body: String): Result {
        val kept = ArrayList<String>()
        val stripped = ArrayList<String>()
        for (line in body.lines()) {
            if (looksLikeAnOrder(line)) stripped += line.trim() else kept += line
        }
        return Result(kept.joinToString("\n"), stripped)
    }

    fun looksLikeAnOrder(line: String): Boolean {
        val words = LEAD.replaceFirst(line, "")
        return openers.any { it.containsMatchIn(words) } || ANYWHERE.containsMatchIn(line)
    }
}
