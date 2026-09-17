package xyz.heylana.app.brain

/**
 * Teaching mode, and the recap after a task.
 *
 * "Teach me how to swap" or "show me how" starts a task that explains itself: each
 * step's say opens with one short reason, then the instruction. "Why?" asked on a
 * step gives that step's reason and turns the rest of the task into teaching too.
 * Once a task is over, "what did I just do" is answered from its goal and steps —
 * and, for an on-chain task, from the wallet's recent activity.
 */
object Teaching {

    private val OPTIONS = setOf(RegexOption.IGNORE_CASE)

    private val TEACH = Regex(
        "\\b(teach me|show me how|explain (it |this )?as (you|we) go|explain (each|every) step|walk me through and explain|" +
            "help me (understand|learn)|i want to learn)\\b",
        OPTIONS
    )

    /** A short "why" about the step in hand: "why", "why that?", "but why do I tap it", "what's that for". */
    private val WHY = Regex(
        "^\\s*(but |and |ok,? |okay,? |wait,? |hmm,? )?(why|how come|what for|what'?s (that|this|it) for|what is (that|this|it) for)\\b",
        OPTIONS
    )

    private val RECAP = Regex(
        "\\bwhat (did|have) (i|we) (just )?(do|done|did)\\b|\\bwhat just happened\\b|\\brecap\\b|" +
            "\\bwhat did (that|it) (just )?do\\b|\\bsummari[sz]e what (i|we) did\\b",
        OPTIONS
    )

    /** Longest "why" still taken as a question about the step rather than a new question. */
    const val WHY_MAX_WORDS = 10

    /** How long after a task ends "what did I just do" still means it. */
    const val RECAP_WINDOW_MS = 10 * 60 * 1_000L

    fun wantsTeaching(question: String): Boolean = TEACH.containsMatchIn(question)

    fun isWhy(question: String): Boolean =
        WHY.containsMatchIn(question) && AnswerLength.words(question) <= WHY_MAX_WORDS

    fun isRecap(question: String): Boolean = RECAP.containsMatchIn(question)

    private val ON_CHAIN_WORDS = Regex(
        "\\b(swap|swapped|stake|staked|unstake|deposit|withdraw|send|sent|transfer|buy|sell|bridge|lend|borrow|" +
            "mint|claim|sign|approve|pay|paid|trade)\\b",
        OPTIONS
    )

    /** A task that may have put something on chain: a Solana app along the way, or money words in the goal. */
    fun onChain(goal: String, packages: Collection<String?>): Boolean =
        packages.any { SolanaApps.of(it) != null } || SolanaCore.mentionsSolana(goal) || ON_CHAIN_WORDS.containsMatchIn(goal)
}

/**
 * A task that has just ended, kept in memory only (never a file, never a log) for
 * [Teaching.RECAP_WINDOW_MS] so "what did I just do" has something to answer from.
 */
data class FinishedTask(
    val goal: String,
    val historyText: String,
    val onChain: Boolean,
    val endedAt: Long
) {
    fun fresh(now: Long): Boolean = now - endedAt < Teaching.RECAP_WINDOW_MS

    companion object {
        fun of(session: GuidanceSession, now: Long = System.currentTimeMillis()) = FinishedTask(
            goal = session.goal,
            historyText = session.historyText(),
            onChain = Teaching.onChain(session.goal, session.packages),
            endedAt = now
        )
    }
}
