package xyz.heylana.app.brain

/** One step Heylana has already given, so the model knows where the user got to. */
data class GuidanceStep(val say: String, val elementLabel: String?)

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
        return repeated
    }

    /** The user did something, so stop treating the session as stuck. */
    fun unstick() {
        stuck = false
    }

    /** The prior steps, written out for the model. */
    fun historyText(): String = if (recorded.isEmpty()) {
        "No steps given yet. This is the first step."
    } else {
        buildString {
            append("Steps already given to the user, in order:\n")
            recorded.forEachIndexed { index, step ->
                append(index + 1).append(". ").append(step.say)
                step.elementLabel?.let { append(" (pointed at \"").append(it).append("\")") }
                append('\n')
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
class Conversation(private val limit: Int = 4) {

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
            append("Recent conversation, oldest first:\n")
            for ((question, answer) in turns) {
                append("User: ").append(question).append('\n')
                append("You: ").append(answer).append('\n')
            }
        }.trimEnd()
    }
}
