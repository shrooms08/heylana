package xyz.heylana.app.voice

/**
 * Whatever is listening while the user holds the buddy down.
 *
 * There are two: Deepgram over a websocket, which hears names like Kamino and
 * SKR properly, and the phone's own recogniser, which is always there. The
 * service does not care which it got — it holds one of these either way.
 */
interface Ears {

    /** False when this pair could not work on this phone at all. */
    fun available(): Boolean

    /** True between the microphone opening and the last word arriving. */
    val isListening: Boolean

    /**
     * Opens the microphone. [keyterms] are names the listener should lean
     * toward; ears that cannot use them ignore them.
     */
    fun start(keyterms: List<String>)

    /**
     * The user let go. True if words are still on their way, false if there is
     * nothing left to wait for.
     */
    fun release(): Boolean

    /** The hold turned into a drag: stop and throw away whatever was heard. */
    fun cancel()

    fun shutdown()
}

/**
 * What the ears report back. One set of callbacks, whichever pair is listening,
 * so the service is written once.
 */
data class EarCallbacks(
    /** The words so far, as they are heard. */
    val onPartial: (String) -> Unit,

    /** The final transcript, once. */
    val onFinal: (String) -> Unit,

    /** Something went wrong that the user should be told about. */
    val onProblem: (String) -> Unit,

    /** They held the buddy and said nothing. Not a problem, just nothing. */
    val onNothingHeard: () -> Unit,

    /** How loud it is hearing them, 0 to 1, for the ring around the disc. */
    val onLevel: (Float) -> Unit = {}
)
