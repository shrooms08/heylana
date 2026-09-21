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

    /**
     * Being walked through doing something: "teach me how to swap", "show me how to
     * stake", "help me send", "walk me through…". Heylana stays with the user through
     * the taps. "Help me understand" is an explanation, not a walk-through.
     */
    private val SESSION = Regex(
        "\\b(teach me (how )?to|show me how to|walk me through|take me through|" +
            "help me (to )?(?!understand\\b|learn\\b|with\\b|out\\b)[\\p{L}]+)",
        OPTIONS
    )

    /** "Stop", "cancel", "that's enough", "never mind": end a running task. Short, on its own. */
    private val STOP = Regex(
        "^\\s*(ok(ay)?,?\\s+|please\\s+)?(stop|cancel|quit|end( it| this)?|exit|that'?s enough|enough|never ?mind|forget it|i'?m done)" +
            "(\\s+(it|now|please|the task|teaching|here))*[\\s.!]*$",
        OPTIONS
    )

    fun wantsTeaching(question: String): Boolean = TEACH.containsMatchIn(question) || wantsSession(question)

    fun wantsSession(question: String): Boolean = SESSION.containsMatchIn(question)

    /**
     * Whether a running task's step, given as pieces, is an explanation that walks around the
     * screen — or the step itself. Several pieces walk; one pointed sentence *is* the step to do,
     * and it waits for the tap. Found on the Seeker: "how do I earn on my USDC" came back as a
     * task whose one sentence pointed at Start, and the phone played it once as a flight, flew
     * home and ended the task before anyone could tap.
     */
    fun stepWalksTheScreen(pieces: Int, teaches: Boolean): Boolean = teaches && pieces > 1

    fun isStop(question: String): Boolean = STOP.containsMatchIn(question)

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
