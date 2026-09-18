package xyz.heylana.app.overlay

/**
 * A touch on the disc while Heylana speaks stops her. The touch-down stops the voice at
 * once; if it turns out to be a tap, that tap was the stop and does not also open or
 * close the box. A hold still listens (listening stops the voice anyway) and a drag
 * still drags.
 */
object SpeechTouch {
    fun stops(speaking: Boolean): Boolean = speaking

    fun tapToggles(stoppedSpeech: Boolean): Boolean = !stoppedSpeech
}
