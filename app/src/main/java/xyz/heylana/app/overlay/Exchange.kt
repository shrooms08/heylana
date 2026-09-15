package xyz.heylana.app.overlay

/**
 * Whether Heylana is in the middle of something the user started — and, from
 * that alone, what the disc should look like.
 *
 * It keeps three rules:
 *
 *  - **Nothing settles the buddy back to idle while an exchange is under way.**
 *    Settling closes the box, and closing the box abandons the microphone.
 *  - **The disc's look follows the exchange and nothing else.** Every change of
 *    phase is reported through [onChange], and the look is derived from it in
 *    [DiscLook]. Before this, each path that ended an exchange had to remember
 *    to put the disc back itself, and "nothing heard" forgot: the thinking ring
 *    turned for five minutes.
 *  - **No exchange lives longer than [LIMIT_MS].** Whatever is stuck — a socket
 *    that never closes, a recogniser that never answers — the exchange is ended
 *    and the disc rests.
 *
 * An exchange runs from the moment the microphone opens (or a typed question is
 * sent) until the answer lands, the user gives up, nothing was heard, or it runs
 * out of time.
 */
class Exchange(private val now: () -> Long = { System.currentTimeMillis() }) {

    enum class Phase {
        /** Nothing is going on: idle is allowed. */
        NONE,

        /** The microphone is open and the user is talking. */
        LISTENING,

        /** They let go; the ears still owe us the words. */
        WAITING_FOR_WORDS,

        /** A question is with the API. */
        ASKING
    }

    var phase: Phase = Phase.NONE
        private set

    /** When the exchange under way began, on the same clock as [now]. */
    var startedAt: Long = 0L
        private set

    /** Told about every change of phase, and only about changes. */
    var onChange: ((Phase) -> Unit)? = null

    /** The microphone is open. */
    fun listening() = move(Phase.LISTENING)

    /** They let go. The words are still on their way. */
    fun released() {
        if (phase == Phase.LISTENING) move(Phase.WAITING_FOR_WORDS)
    }

    /** A question has gone to the API, typed or spoken. */
    fun asking() = move(Phase.ASKING)

    /** The answer landed, or there is nothing left to wait for. */
    fun over() = move(Phase.NONE)

    val inProgress: Boolean get() = phase != Phase.NONE

    /** True only when nothing is waiting on the microphone or the API. */
    val maySettle: Boolean get() = phase == Phase.NONE

    /** True once the exchange under way has run for [LIMIT_MS] or more. */
    fun overdue(at: Long = now()): Boolean = inProgress && at - startedAt >= LIMIT_MS

    /** How long is left before [overdue], never below zero. */
    fun remaining(at: Long = now()): Long =
        if (!inProgress) LIMIT_MS else (LIMIT_MS - (at - startedAt)).coerceAtLeast(0)

    /**
     * Ends the exchange because it ran out of time. True if there was one to
     * end — the caller then melts the capsule and says so.
     */
    fun timedOut(): Boolean {
        if (!inProgress) return false
        move(Phase.NONE)
        return true
    }

    private fun move(next: Phase) {
        if (phase == next) return
        // The clock starts when something starts, and is not reset by the
        // exchange moving from listening to asking.
        if (phase == Phase.NONE) startedAt = now()
        phase = next
        onChange?.invoke(next)
    }

    companion object {
        /** The longest any one exchange may live, hold and answer together. */
        const val LIMIT_MS = 20_000L
    }
}

/**
 * What the disc looks like, derived from the exchange in this one place.
 *
 * Nothing else decides it. An exchange that is over is idle — unless the buddy
 * is pointing at something, which only happens once an answer has landed.
 */
enum class DiscLook {
    IDLE, LISTENING, THINKING, POINTING;

    companion object {
        fun of(phase: Exchange.Phase, pointing: Boolean): DiscLook = when (phase) {
            Exchange.Phase.LISTENING -> LISTENING
            Exchange.Phase.WAITING_FOR_WORDS, Exchange.Phase.ASKING -> THINKING
            Exchange.Phase.NONE -> if (pointing) POINTING else IDLE
        }
    }
}
