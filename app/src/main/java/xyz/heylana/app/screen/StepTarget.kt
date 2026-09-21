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
        // A label inside a button-sized tappable box is that button: Jupiter's green Swap is an
        // unnamed box with the word "Swap" inside it, and dropping the word lost the button.
        val acting = candidates.associate { c -> c.id to (c.clickable || c.editable || holder(c, candidates) != null) }
        val pointedActs = acting[pointed] ?: false
        val scored = candidates
            .filter { !pointedActs || it.id == pointed || acting[it.id] == true }
            .map { c -> holder(c, candidates)?.let { h -> c.withBox(h.box) } ?: c }
            .mapNotNull { c -> score(instruction, c).takeIf { it > 0 }?.let { c to it } }
        if (scored.isEmpty()) return pointed
        val best = scored.maxOf { it.second }
        val top = scored.filter { it.second == best }.map { it.first }
        // Elements named equally, grouped: one drawn inside the other is one thing (a tab and
        // its label, as Jupiter's bottom bar reports them).
        val groups = groups(top)
        if (groups.size == 1) {
            val group = groups.single()
            if (pointed != null && group.any { it.id == pointed }) return pointed
            // The model's element is not named: the group's outermost, else what takes a tap.
            val pick = group.firstOrNull { outer -> group.all { it === outer || contains(outer.box, it.box) } }
                ?: group.filter { it.clickable || it.editable }.singleOrNull()
            return pick?.id ?: pointed
        }
        // Two different things share the name — Jupiter's "Swap" tab at the top and its green
        // Swap button: the words decide; with nothing to go on, the primary action.
        val chosen = resolve(groups, instruction.qualifiers)
            ?: return pointed?.takeIf { id -> top.any { it.id == id } } ?: pointed
        if (pointed != null && chosen.any { it.id == pointed }) return pointed
        return (chosen.firstOrNull { outer -> chosen.all { it === outer || contains(outer.box, it.box) } } ?: chosen.first()).id
    }

    /** How the instruction tells same-named things apart. */
    internal enum class Qualifier { LOW, HIGH, TAB, PRIMARY }

    private val QUALIFIER_WORDS = mapOf(
        "bottom" to Qualifier.LOW, "lower" to Qualifier.LOW, "below" to Qualifier.LOW,
        "top" to Qualifier.HIGH, "upper" to Qualifier.HIGH, "header" to Qualifier.HIGH, "above" to Qualifier.HIGH,
        "tab" to Qualifier.TAB, "tabs" to Qualifier.TAB,
        "button" to Qualifier.PRIMARY, "green" to Qualifier.PRIMARY, "blue" to Qualifier.PRIMARY,
        "big" to Qualifier.PRIMARY, "large" to Qualifier.PRIMARY, "main" to Qualifier.PRIMARY
    )

    /** Groups of candidates, each group one thing on screen (boxes inside one another). */
    private fun groups(top: List<Candidate>): List<List<Candidate>> {
        val groups = mutableListOf<MutableList<Candidate>>()
        for (c in top) {
            val home = groups.firstOrNull { g -> g.any { contains(it.box, c.box) || contains(c.box, it.box) } }
            if (home != null) home += c else groups += mutableListOf(c)
        }
        return groups
    }

    /**
     * Which group the words mean: a position word first (bottom, top), then "tab" (the
     * smaller), then a button, a colour or nothing at all (the primary action: the larger,
     * then the lower). Null when the boxes are not known.
     */
    private fun resolve(groups: List<List<Candidate>>, qualifiers: Set<Qualifier>): List<Candidate>? {
        fun outer(g: List<Candidate>): IntArray? = g.mapNotNull { it.box }.maxByOrNull { area(it) }
        if (groups.any { outer(it) == null }) return null
        val boxed = groups.map { it to outer(it)!! }
        val pick = when {
            Qualifier.LOW in qualifiers -> boxed.maxByOrNull { it.second[3] }
            Qualifier.HIGH in qualifiers -> boxed.minByOrNull { it.second[1] }
            Qualifier.TAB in qualifiers -> boxed.minByOrNull { area(it.second) }
            else -> boxed.maxWithOrNull(compareBy<Pair<List<Candidate>, IntArray>>({ area(it.second) }, { it.second[3] }))
        }
        return pick?.first
    }

    private fun area(box: IntArray): Long = (box[2] - box[0]).toLong() * (box[3] - box[1])

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
    internal data class Instruction(
        val typing: Boolean,
        val named: Set<String>,
        val quoted: List<String>,
        val qualifiers: Set<Qualifier> = emptySet()
    )

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
            val qualifiers = after.takeWhile { it != "to" && it != "so" }.mapNotNull { QUALIFIER_WORDS[it] }.toSet()
            return Instruction(typing = verb in TYPE_VERBS, named = named, quoted = inQuotes, qualifiers = qualifiers)
        }
        return null
    }

    /** The button-sized tappable box a label sits in, if it is not tappable itself. */
    private fun holder(c: Candidate, all: List<Candidate>): Candidate? {
        val box = c.box ?: return null
        if (c.clickable || c.editable) return null
        return all.filter { it.clickable && it.id != c.id && contains(it.box, box) && area(it.box!!) <= area(box) * HOLDER_AREA }
            .minByOrNull { area(it.box!!) }
    }

    /** A holder more than this many times the label's area is a card or a page, not its button. */
    private const val HOLDER_AREA = 40L

    private fun Candidate.withBox(box: IntArray?): Candidate = Candidate(id, label, editable, clickable, box)

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
