package xyz.heylana.app.overlay

/**
 * Whether Heylana is in the middle of something the user started.
 *
 * It exists to keep one rule: **nothing may put the buddy back to idle while an
 * exchange is under way.** Settling back to idle closes the box, and closing the
 * box abandons the microphone, so a settle that arrives at the wrong moment does
 * not just look wrong — it throws away what the user is saying.
 *
 * They arrive at the wrong moment easily. Opening the microphone stops the
 * speaker first, text-to-speech reports "no longer speaking" a moment later, and
 * that report is what books the settle. Without this, holding the buddy booked
 * its own undoing one second later.
 *
 * An exchange runs from the moment the microphone opens (or a typed question is
 * sent) until the answer lands, the user gives up, or nothing was heard.
 */
class Exchange {

    enum class Phase {
        /** Nothing is going on: idle is allowed. */
        NONE,

        /** The microphone is open and the user is talking. */
        LISTENING,

        /** They let go; the recogniser still owes us the words. */
        WAITING_FOR_WORDS,

        /** A question is with the API. */
        ASKING
    }

    var phase: Phase = Phase.NONE
        private set

    /** The microphone is open. */
    fun listening() {
        phase = Phase.LISTENING
    }

    /** They let go. The words are still on their way. */
    fun released() {
        if (phase == Phase.LISTENING) phase = Phase.WAITING_FOR_WORDS
    }

    /** A question has gone to the API, typed or spoken. */
    fun asking() {
        phase = Phase.ASKING
    }

    /** The answer landed, or there is nothing left to wait for. */
    fun over() {
        phase = Phase.NONE
    }

    val inProgress: Boolean get() = phase != Phase.NONE

    /** True only when nothing is waiting on the microphone or the API. */
    val maySettle: Boolean get() = phase == Phase.NONE
}
