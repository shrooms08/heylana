package xyz.heylana.app.voice

/**
 * When "I heard nothing" is allowed to mean anything.
 *
 * The recogniser can give up while the user is still holding the buddy down — a
 * couple of seconds of quiet before they start speaking is enough. Reported
 * there and then, that would melt the capsule and put the buddy back to rest
 * with the user's finger still on it, mid-sentence.
 *
 * So a no-match only counts once they have let go. One that arrives early is
 * remembered and reported at the release instead, which is the moment the user
 * is actually asking for an answer.
 */
class NothingHeardGate {

    private var released = false
    private var pending = false

    /** The microphone has just opened. */
    fun started() {
        released = false
        pending = false
    }

    /**
     * The recogniser heard nothing. True if that may be acted on now; false if
     * it is being held until the user lets go.
     */
    fun nothingHeard(): Boolean {
        if (released) return true
        pending = true
        return false
    }

    /**
     * The user let go. True if a no-match was waiting on this moment and should
     * be acted on now.
     */
    fun releasedNow(): Boolean {
        released = true
        val waiting = pending
        pending = false
        return waiting
    }
}
