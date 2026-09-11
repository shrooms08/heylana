package xyz.heylana.app.voice

/**
 * How long the good ears — or the good voice — get before the phone's own take
 * over.
 *
 * Heylana would rather use Deepgram and Cartesia, but neither is worth waiting
 * on: a user holding the buddy down expects it to be listening *now*, and an
 * answer that arrives silently is worse than one read out by the phone. So each
 * gets a short window, and whichever is ready first is the one that runs.
 *
 * Once the choice is made it stays made. A socket that finally opens after the
 * phone has already started listening does not get to take over mid-sentence.
 */
class FallbackWindow(val limitMs: Long) {

    enum class Choice { WAITING, PREFERRED, FALLBACK }

    var choice: Choice = Choice.WAITING
        private set

    /**
     * The preferred one is ready, [elapsed] milliseconds after it was asked for.
     * True if it is the one that will be used.
     */
    fun preferredReady(elapsed: Long): Boolean {
        if (choice == Choice.WAITING) {
            choice = if (elapsed <= limitMs) Choice.PREFERRED else Choice.FALLBACK
        }
        return choice == Choice.PREFERRED
    }

    /** How much of the window is left, given how long ago it started. */
    fun remaining(elapsed: Long): Long = (limitMs - elapsed).coerceAtLeast(0)

    /**
     * The window ran out, or the preferred one failed outright. True if the
     * fallback should start now — false if the preferred one already won.
     */
    fun useFallback(): Boolean {
        if (choice == Choice.WAITING) choice = Choice.FALLBACK
        return choice == Choice.FALLBACK
    }

    companion object {
        /**
         * All the patience there is for the good ears, measured from the moment
         * the buddy is touched — not from the long press, because borrowing a
         * key and opening a socket starts on the way down.
         *
         * Set from measurements in Lagos: borrowing a key is a round trip
         * through the proxy to Deepgram and back, around 1.0 to 1.5 seconds, and
         * opening the socket to Deepgram is another 1.1 seconds cold. Anything
         * under two seconds is a window the good ears cannot reach.
         *
         * **This is the only place the number lives.** Every timer and every
         * check measures against this one constant.
         */
        const val EARS_MS = 2_500L

        /** An answer is ready to be read out; silence past this is worse than a plain voice. */
        const val VOICE_MS = 1_500L
    }
}
