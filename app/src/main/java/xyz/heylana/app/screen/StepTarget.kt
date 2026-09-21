package xyz.heylana.app.screen

/**
 * Which element a step's pointer goes to: the one the spoken instruction tells the user to
 * act on next. The model picks an id, and mostly picks well, but on the Seeker's Jupiter swap
 * it said "Market is selected, so type the amount" and pointed at Market — the thing already
 * done, not the thing to do. So the first instruction in the step's words (a clause that opens
 * with tap, type, enter, choose…) is read for what it names, and if the model's element is not
 * named there and exactly one element is, the pointer goes to that one instead. Anything the
 * words do not settle is left as the model said.
 */
object StepTarget {

    /**
     * An element as this rule sees it: its id, its visible words, whether it takes typing or a
     * tap, and where it is (left, top, right, bottom), when known.
     */
    class Candidate(
        val id: Int,
        val label: String?,
        val editable: Boolean,
        val clickable: Boolean,
        val box: IntArray? = null
    )

    private val TAP_VERBS = listOf("tap", "press", "hit", "click", "choose", "select", "pick", "toggle", "slide", "swipe")
    private val TYPE_VERBS = listOf("type", "enter", "fill in", "put in", "key in")
    private val VERBS = TYPE_VERBS + TAP_VERBS

    /** Words that may come before the verb in an instruction: "now tap…", "so type…". */
    private val LEAD = setOf(
        "now", "next", "then", "first", "so", "and", "just", "please", "finally", "also",
        "after", "that", "go", "ahead", "you", "can", "to", "begin", "start", "lastly"
    )

    /** Where the thing named ends and the rest of the sentence begins: "the amount | you want to sell". */
    private val OBJECT_ENDS = setOf(
        "you", "that", "which", "to", "for", "from", "so", "into", "in", "on", "at", "with",
        "and", "then", "until", "when", "if", "under", "below", "above", "beside", "next", "near", "of"
    )

    private val FILLER = setOf(
        "the", "a", "an", "your", "my", "this", "that", "its", "their", "button", "field", "tab",
        "box", "icon", "option", "chip", "card", "link", "menu", "toggle", "switch", "green", "blue",
        "big", "small", "top", "bottom", "left", "right", "how", "much", "many", "want", "it"
    )

    private val QUOTED = Regex("[\"“”]([^\"“”]{1,40})[\"“”]")

    /**
     * The element to point at for a step that says [say], the model having picked [pointed],
     * among [candidates]. Returns [pointed] unless the instruction names another element and
     * not the pointed one.
     */
    fun choose(say: String, pointed: Int?, candidates: List<Candidate>): Int? {
        val instruction = firstInstruction(say) ?: return pointed
        // The model's element takes a tap: only something that also takes one may replace it.
        // "Tap the Sell chip" pointed at the token chip, and the heading "Sell" is not it.
        val pointedActs = candidates.firstOrNull { it.id == pointed }?.let { it.clickable || it.editable } ?: false
        val scored = candidates
            .filter { !pointedActs || it.id == pointed || it.clickable || it.editable }
            .mapNotNull { c -> score(instruction, c).takeIf { it > 0 }?.let { c to it } }
        if (scored.isEmpty()) return pointed
        if (pointed != null && scored.any { it.first.id == pointed }) return pointed
        val best = scored.maxOf { it.second }
        val top = scored.filter { it.second == best }
        // Two elements named equally: one drawn inside the other is one thing (a tab and its
        // label, as Jupiter's bottom bar reports them); else the one that takes a tap; else
        // leave the model's choice.
        val pick = top.singleOrNull()
            ?: top.firstOrNull { outer -> top.all { it === outer || contains(outer.first.box, it.first.box) } }
            ?: top.filter { it.first.clickable || it.first.editable }.singleOrNull()
        return pick?.first?.id ?: pointed
    }

    /**
     * Whether the step tells the user to type or enter something, anywhere in it: "tap the
     * amount field. Type in 0.0009 there." is a typing step though it opens with a tap.
     */
    fun isTyping(say: String): Boolean = clauses(say).any { words ->
        val rest = words.dropWhile { it in LEAD }.joinToString(" ")
        TYPE_VERBS.any { rest == it || rest.startsWith("$it ") }
    }

    private fun clauses(say: String): List<List<String>> =
        say.lowercase().replace(Regex("[“”‘’\"]"), " ").split(Regex("[.!?;,:]|\\bthen\\b"))
            .map { clause -> clause.split(Regex("[^a-z0-9%']+")).filter { it.isNotEmpty() } }

    /** For the trace: how many elements the first instruction names, or -1 with no instruction. */
    fun matches(say: String, candidates: List<Candidate>): Int {
        val instruction = firstInstruction(say) ?: return -1
        return candidates.count { score(instruction, it) > 0 }
    }

    /** The first clause of [say] that tells the user to do something: its verb and what it names. */
    internal data class Instruction(val typing: Boolean, val named: Set<String>, val quoted: List<String>)

    internal fun firstInstruction(say: String): Instruction? {
        val quoted = QUOTED.findAll(say).map { it.groupValues[1].trim().lowercase() }.toList()
        val clauses = say.lowercase()
            .replace(Regex("[“”‘’\"]"), " ")
            .split(Regex("[.!?;,:]|\\bthen\\b"))
        for (clause in clauses) {
            val words = clause.split(Regex("[^a-z0-9%']+")).filter { it.isNotEmpty() }
            var i = 0
            while (i < words.size && words[i] in LEAD) i++
            if (i >= words.size) continue
            val rest = words.drop(i).joinToString(" ")
            val verb = VERBS.firstOrNull { rest == it || rest.startsWith("$it ") } ?: continue
            val after = rest.removePrefix(verb).trim().split(' ').filter { it.isNotEmpty() }
            val obj = after.takeWhile { it !in OBJECT_ENDS }
            val inQuotes = quoted.filter { q -> clause.contains(q) }
            // "type how much SOL to sell": the thing named is the amount, whatever it is in.
            val howMuch = verb in TYPE_VERBS && after.take(2) == listOf("how", "much")
            val named = if (howMuch) setOf("amount") else obj.filter { it !in FILLER && it.any(Char::isLetter) }.toSet()
            if (named.isEmpty() && inQuotes.isEmpty()) continue
            return Instruction(typing = verb in TYPE_VERBS, named = named, quoted = inQuotes)
        }
        return null
    }

    private fun contains(outer: IntArray?, inner: IntArray?): Boolean =
        outer != null && inner != null &&
            outer[0] <= inner[0] && outer[1] <= inner[1] && outer[2] >= inner[2] && outer[3] >= inner[3]

    private fun score(instruction: Instruction, candidate: Candidate): Int {
        val label = candidate.label?.trim()?.lowercase().orEmpty()
        var score = 0
        if (label.isNotEmpty()) {
            if (instruction.quoted.any { it == label }) return 10
            val words = label.split(Regex("[^a-z0-9%']+"))
                .filter { it.isNotEmpty() && it.any(Char::isLetter) }
                // A button that says what to do ("Enter Amount") is named by its thing.
                .filter { w -> VERBS.none { it == w } && w !in FILLER }
            // Paragraphs are not buttons.
            if (words.isNotEmpty() && words.size <= 4 && instruction.named.containsAll(words)) {
                score = 3 + words.size
            }
        }
        if (instruction.typing && candidate.editable) score += 2
        return score
    }
}
