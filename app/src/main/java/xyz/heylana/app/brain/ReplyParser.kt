package xyz.heylana.app.brain

/**
 * The one way a model reply becomes words: find the JSON object in it, and take only
 * its `say`. Every path — chat, plain, a quick action, a send, a task step, a why, a
 * recap — goes through here, so raw model output (prose around the JSON, the JSON
 * itself, echoed prompt wording) can never reach the voice or the strip.
 *
 * Plain Kotlin with its own small JSON reader, so it is tested on the JVM exactly as
 * it runs on the phone.
 */
object ReplyParser {

    sealed interface Result {
        /** [objectText] is the reply object alone; [say] is its say, cleaned and de-duplicated. */
        data class Reply(val objectText: String, val fields: Map<String, Any?>, val say: String) : Result
        /** No reply object, or one whose say is not something to speak. */
        data class Unreadable(val reason: String) : Result
    }

    /** What is spoken when a reply could not be read, even after asking once more. */
    const val NOT_CAUGHT = "I didn't catch that, say it again."

    /** Added to the question for the one retry after an unreadable reply. */
    const val JSON_ONLY = "Reply with only the JSON object, nothing before or after it."

    fun parse(text: String): Result {
        val candidates = objects(text)
        if (candidates.isEmpty()) return Result.Unreadable("no_json")
        // The first object that is a reply: it has a say, or an action.
        val (objectText, fields) = candidates.firstOrNull { (_, map) -> map["say"] is String || map["action"] is Map<*, *> }
            ?: return Result.Unreadable("no_reply_object")
        val rawSay = (fields["say"] as? String).orEmpty()
        if (looksLikeInstructions(rawSay)) return Result.Unreadable("say_holds_instructions")
        return Result.Reply(objectText, fields, dedupe(rawSay.trim()))
    }

    /**
     * Words that only ever come from the prompt or the reply contract. A say holding
     * them is the model echoing its instructions, not answering.
     */
    private val INSTRUCTIONS = Regex(
        "\"say\"\\s*:|\"point_at\"|point_at:|\"task\"\\s*:|Reply with ONLY|no fences, no prose|" +
            "task\\.goal|User asks:|Screen now:|App notes for|<<<|>>>",
        RegexOption.IGNORE_CASE
    )

    fun looksLikeInstructions(say: String): Boolean = INSTRUCTIONS.containsMatchIn(say)

    /** A sentence said twice is said once: the first time it appears is kept. */
    fun dedupe(say: String): String {
        if (say.isEmpty()) return say
        val sentences = SENTENCE_END.split(say).map { it.trim() }.filter { it.isNotEmpty() }
        if (sentences.size < 2) return say
        val seen = HashSet<String>()
        val kept = sentences.filter { seen.add(normal(it)) }
        return if (kept.size == sentences.size) say else kept.joinToString(" ")
    }

    private val SENTENCE_END = Regex("(?<=[.!?…])\\s+")

    private fun normal(sentence: String) = sentence.lowercase().replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()

    /** Every top-level JSON object in the text, in order, with its parsed fields. */
    fun objects(text: String): List<Pair<String, Map<String, Any?>>> {
        val found = ArrayList<Pair<String, Map<String, Any?>>>()
        var i = 0
        while (i < text.length) {
            if (text[i] != '{') { i++; continue }
            val end = closingBrace(text, i)
            if (end < 0) { i++; continue }
            val slice = text.substring(i, end + 1)
            val parsed = runCatching { MiniJson(slice).readWhole() }.getOrNull()
            if (parsed is Map<*, *>) {
                @Suppress("UNCHECKED_CAST")
                found += slice to (parsed as Map<String, Any?>)
                i = end + 1
            } else {
                i++
            }
        }
        return found
    }

    /** Where the object opened at [start] closes, minding strings; -1 if it never does. */
    private fun closingBrace(text: String, start: Int): Int {
        var depth = 0
        var inString = false
        var escaped = false
        for (i in start until text.length) {
            val c = text[i]
            if (inString) {
                when {
                    escaped -> escaped = false
                    c == '\\' -> escaped = true
                    c == '"' -> inString = false
                }
                continue
            }
            when (c) {
                '"' -> inString = true
                '{' -> depth++
                '}' -> { depth--; if (depth == 0) return i }
            }
        }
        return -1
    }
}

/** A strict little JSON reader: objects, arrays, strings, numbers, true, false, null. */
internal class MiniJson(private val s: String) {
    private var p = 0

    fun readWhole(): Any? {
        val value = read()
        skip()
        require(p == s.length) { "trailing" }
        return value
    }

    private fun skip() { while (p < s.length && s[p].isWhitespace()) p++ }

    private fun read(): Any? {
        skip()
        require(p < s.length) { "end" }
        return when (val c = s[p]) {
            '{' -> obj()
            '[' -> arr()
            '"' -> str()
            't' -> word("true", true)
            'f' -> word("false", false)
            'n' -> word("null", null)
            else -> if (c == '-' || c.isDigit()) num() else throw IllegalArgumentException("unexpected $c")
        }
    }

    private fun word(w: String, value: Any?): Any? {
        require(s.startsWith(w, p)) { "bad literal" }
        p += w.length
        return value
    }

    private fun obj(): Map<String, Any?> {
        val out = LinkedHashMap<String, Any?>()
        p++
        skip()
        if (s.getOrNull(p) == '}') { p++; return out }
        while (true) {
            skip()
            require(s.getOrNull(p) == '"') { "key" }
            val key = str()
            skip()
            require(s.getOrNull(p) == ':') { "colon" }
            p++
            out[key] = read()
            skip()
            when (s.getOrNull(p)) {
                ',' -> p++
                '}' -> { p++; return out }
                else -> throw IllegalArgumentException("object")
            }
        }
    }

    private fun arr(): List<Any?> {
        val out = ArrayList<Any?>()
        p++
        skip()
        if (s.getOrNull(p) == ']') { p++; return out }
        while (true) {
            out += read()
            skip()
            when (s.getOrNull(p)) {
                ',' -> p++
                ']' -> { p++; return out }
                else -> throw IllegalArgumentException("array")
            }
        }
    }

    private fun str(): String {
        p++
        val out = StringBuilder()
        while (true) {
            require(p < s.length) { "string" }
            val c = s[p++]
            when (c) {
                '"' -> return out.toString()
                '\\' -> {
                    val e = s[p++]
                    when (e) {
                        '"', '\\', '/' -> out.append(e)
                        'b' -> out.append('\b')
                        'f' -> out.append('\u000C')
                        'n' -> out.append('\n')
                        'r' -> out.append('\r')
                        't' -> out.append('\t')
                        'u' -> { out.append(s.substring(p, p + 4).toInt(16).toChar()); p += 4 }
                        else -> throw IllegalArgumentException("escape")
                    }
                }
                else -> out.append(c)
            }
        }
    }

    private fun num(): Number {
        val start = p
        if (s[p] == '-') p++
        while (p < s.length && (s[p].isDigit() || s[p] in ".eE+-")) p++
        val text = s.substring(start, p)
        return text.toLongOrNull() ?: text.toDouble()
    }
}
