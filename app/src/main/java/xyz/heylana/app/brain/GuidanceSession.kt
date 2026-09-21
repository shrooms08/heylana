package xyz.heylana.app.brain

/**
 * One step Heylana has already given, so the model knows where the user got to.
 *
 * Only [summary] is ever sent back — one short line, not the full sentence, since
 * the whole history is resent on every advance.
 */
data class GuidanceStep(val say: String, val elementLabel: String?) {

    val summary: String
        get() {
            val trimmed = if (say.length > SUMMARY_CHARS) say.take(SUMMARY_CHARS).trimEnd() + "…" else say
            return if (elementLabel != null) "$trimmed [$elementLabel]" else trimmed
        }

    private companion object {
        const val SUMMARY_CHARS = 70
    }
}

/**
 * A task being walked through one step at a time.
 *
 * The session is what makes guidance stateful: it carries the goal and every step
 * already given, so each advance asks "given this goal and what we have done, and
 * a fresh look at the screen, what is the next single step?".
 *
 * Nothing here is persisted. A session lives only as long as the task does.
 */
class GuidanceSession(val goal: String) {

    val startedAt: Long = System.currentTimeMillis()

    /** A step came back cut off with no speech and the screen was read again once already. */
    var lookedAgain: Boolean = false

    private val recorded = mutableListOf<GuidanceStep>()
    val steps: List<GuidanceStep> get() = recorded

    /** Which element the current step points at, if any, and where it lives. */
    var pointedKey: String? = null
        private set
    var pointedPackage: String? = null
        private set

    /**
     * Set when two steps in a row point at the same element: the user probably
     * tapped and nothing happened, so auto-advance stops until they act.
     */
    var stuck: Boolean = false
        private set

    /** True when the last step came back without an element to point at. */
    var lastStepHadNoPointer: Boolean = false
        private set

    /**
     * Teach as it goes: each step opens with one short reason. Set by "teach me" or
     * "show me how" at the start, or by a "why" asked on any step.
     */
    var teaching: Boolean = false

    /** Every app a step was given in, for the recap's on-chain check. */
    private val seenPackages = LinkedHashSet<String>()
    val packages: Set<String> get() = seenPackages

    val stepNumber: Int get() = recorded.size

    /** True once the last allowed step has been given. */
    val atCap: Boolean get() = recorded.size >= MAX_STEPS

    /** Records a step and reports whether it repeats the previous one. */
    fun record(say: String, elementKey: String?, elementLabel: String?, packageName: String?): Boolean {
        val repeated = elementKey != null && elementKey == pointedKey
        recorded.add(GuidanceStep(say, elementLabel))
        pointedKey = elementKey
        pointedPackage = packageName
        packageName?.let { seenPackages += it }
        stuck = repeated
        lastStepHadNoPointer = elementKey == null
        return repeated
    }

    /** The user did something, so stop treating the session as stuck. */
    fun unstick() {
        stuck = false
    }

    /** The prior steps as one short line each. */
    fun historyText(): String = if (recorded.isEmpty()) {
        "No steps yet."
    } else {
        buildString {
            append("Steps so far:\n")
            recorded.forEachIndexed { index, step ->
                append(index + 1).append(". ").append(step.summary).append('\n')
            }
        }.trimEnd()
    }

    companion object {
        const val MAX_STEPS = 8
    }
}

/**
 * Heylana's short-term memory: the last few ordinary exchanges, so a follow-up
 * like "and the other one" has something to refer back to.
 *
 * It lives in memory and nowhere else — never a file, never a log, never a
 * preference. It holds at most [limit] exchanges, each one forgotten [windowMs]
 * after it happened, and the whole lot is dropped the moment the user moves to a
 * different app or the buddy stops. What goes over the wire is capped at
 * [MAX_CHARS] as well, oldest first, so remembering can never quietly grow the
 * cost of a request.
 */
class Conversation(
    private val limit: Int = MAX_TURNS,
    private val windowMs: Long = WINDOW_MS,
    private val now: () -> Long = { System.currentTimeMillis() }
) {

    private data class Turn(
        val question: String,
        val answer: String,
        val packageName: String?,
        val at: Long
    )

    private val turns = ArrayDeque<Turn>()

    /** How many exchanges are being remembered right now. */
    val size: Int get() = turns.size

    fun record(question: String, answer: String, packageName: String?) {
        forgetStaleOrForeign(packageName)
        turns.addLast(
            Turn(
                question = question.trim().take(QUESTION_CHARS),
                answer = answer.trim().take(ANSWER_CHARS),
                packageName = packageName,
                at = now()
            )
        )
        while (turns.size > limit) turns.removeFirst()
    }

    fun clear() = turns.clear()

    /**
     * The remembered exchanges as the model sees them, or null when there is
     * nothing worth sending. Asking from a different app forgets the lot first:
     * "the other one" never means something from another screen.
     */
    fun asPromptText(packageName: String? = null): String? {
        forgetStaleOrForeign(packageName)
        if (turns.isEmpty()) return null

        val lines = turns.map { "User: ${it.question}\nYou: ${it.answer}" }
        val kept = ArrayDeque<String>()
        var budget = MAX_CHARS
        // Oldest is the first to go if it will not all fit.
        for (line in lines.asReversed()) {
            if (budget - line.length < 0) break
            budget -= line.length
            kept.addFirst(line)
        }
        if (kept.isEmpty()) return null

        return buildString {
            append("Earlier:\n")
            kept.forEach { append(it).append('\n') }
        }.trimEnd()
    }

    /** Drops anything too old, and everything if the app has changed. */
    private fun forgetStaleOrForeign(packageName: String?) {
        val cutoff = now() - windowMs
        while (turns.isNotEmpty() && turns.first().at <= cutoff) turns.removeFirst()
        if (packageName == null) return
        if (turns.isNotEmpty() && turns.last().packageName != packageName) turns.clear()
    }

    companion object {
        /** How many exchanges back Heylana can refer to. */
        const val MAX_TURNS = 3

        /** How long an exchange stays worth remembering. */
        const val WINDOW_MS = 10 * 60 * 1_000L

        /** The most memory that may be spent on any one request. */
        const val MAX_CHARS = 600

        private const val QUESTION_CHARS = 90
        private const val ANSWER_CHARS = 150
    }
}
