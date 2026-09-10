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

    val stepNumber: Int get() = recorded.size

    /** True once the last allowed step has been given. */
    val atCap: Boolean get() = recorded.size >= MAX_STEPS

    /** Records a step and reports whether it repeats the previous one. */
    fun record(say: String, elementKey: String?, elementLabel: String?, packageName: String?): Boolean {
        val repeated = elementKey != null && elementKey == pointedKey
        recorded.add(GuidanceStep(say, elementLabel))
        pointedKey = elementKey
        pointedPackage = packageName
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
 * The last few ordinary question-and-answer turns, so follow-ups like "and then?"
 * have something to refer back to. Capped, in memory only, cleared with the panel.
 */
class Conversation(private val limit: Int = 2) {

    private val turns = ArrayDeque<Pair<String, String>>()

    fun record(question: String, answer: String) {
        turns.addLast(question to answer)
        while (turns.size > limit) turns.removeFirst()
    }

    fun clear() = turns.clear()

    /** Null when there is nothing worth sending. */
    fun asPromptText(): String? {
        if (turns.isEmpty()) return null
        return buildString {
            append("Earlier:\n")
            for ((question, answer) in turns) {
                append("User: ").append(question).append('\n')
                append("You: ").append(answer).append('\n')
            }
        }.trimEnd()
    }
}
