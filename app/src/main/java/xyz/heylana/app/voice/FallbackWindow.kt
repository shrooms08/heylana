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

    /**
     * The window ran out, or the preferred one failed outright. True if the
     * fallback should start now — false if the preferred one already won.
     */
    fun useFallback(): Boolean {
        if (choice == Choice.WAITING) choice = Choice.FALLBACK
        return choice == Choice.FALLBACK
    }

    companion object {
        /** Someone is holding the buddy down: this is all the patience there is. */
        const val EARS_MS = 800L

        /** An answer is ready to be read out; silence past this is worse than a plain voice. */
        const val VOICE_MS = 1_500L
    }
}
