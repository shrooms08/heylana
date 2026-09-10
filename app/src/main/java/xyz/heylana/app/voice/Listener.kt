package xyz.heylana.app.voice

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import xyz.heylana.app.HeylanaLog

/**
 * Listens while the user holds the buddy.
 *
 * Partial results stream out through [onPartial] so the chat field fills in live;
 * the final transcript arrives once through [onFinal] after [stop].
 */
class Listener(
    private val context: Context,
    private val onPartial: (String) -> Unit,
    private val onFinal: (String) -> Unit,
    private val onProblem: (String) -> Unit,
    /**
     * The user held the buddy and said nothing. That is not a problem worth a
     * message on screen — the caller just puts everything back to rest.
     */
    private val onNothingHeard: () -> Unit
) {

    private var recognizer: SpeechRecognizer? = null
    private var listening = false

    /** Holds an early "heard nothing" back until the user actually lets go. */
    private val nothingHeard = NothingHeardGate()

    /** Set when the user drags away mid-hold: results are then thrown away. */
    private var abandoned = false

    val isListening: Boolean get() = listening

    fun available(): Boolean = SpeechRecognizer.isRecognitionAvailable(context)

    /** Must be called on the main thread. */
    fun start() {
        if (listening) return
        if (!available()) {
            onProblem(UNAVAILABLE)
            return
        }

        abandoned = false
        nothingHeard.started()
        val speech = recognizer ?: SpeechRecognizer.createSpeechRecognizer(context).also {
            recognizer = it
        }
        speech.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) =
                HeylanaLog.state("recogniser: ready")

            override fun onBeginningOfSpeech() =
                HeylanaLog.state("recogniser: speech began")

            override fun onRmsChanged(rmsdB: Float) = Unit
            override fun onBufferReceived(buffer: ByteArray?) = Unit

            override fun onEndOfSpeech() =
                HeylanaLog.state("recogniser: speech ended")

            override fun onEvent(eventType: Int, params: Bundle?) = Unit

            override fun onPartialResults(partialResults: Bundle?) {
                if (abandoned) return
                // How much was heard, never what.
                HeylanaLog.state("recogniser: partial")
                firstResult(partialResults)?.let(onPartial)
            }

            override fun onResults(results: Bundle?) {
                listening = false
                if (abandoned) return
                val text = firstResult(results)
                HeylanaLog.state("recogniser: results empty=${text.isNullOrBlank()}")
                if (!text.isNullOrBlank()) {
                    onFinal(text)
                } else if (nothingHeard.nothingHeard()) {
                    onNothingHeard()
                }
            }

            override fun onError(error: Int) {
                listening = false
                HeylanaLog.state("recogniser: error code=$error abandoned=$abandoned")
                if (abandoned) return
                when (error) {
                    // Not a problem, and not necessarily now: the recogniser
                    // gives up on its own if the user is quiet for a moment
                    // before they start talking, and their finger is still down.
                    SpeechRecognizer.ERROR_NO_MATCH,
                    SpeechRecognizer.ERROR_SPEECH_TIMEOUT ->
                        if (nothingHeard.nothingHeard()) onNothingHeard()

                    else -> onProblem(message(error))
                }
            }
        })

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(
                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
            )
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
        }

        listening = true
        HeylanaLog.state("recogniser: started")
        speech.startListening(intent)
    }

    /**
     * The user let go: ask for the final transcript.
     *
     * Returns true if words are still on their way. False means there is nothing
     * left to wait for — either the recogniser had already given up while they
     * were holding, in which case [onNothingHeard] fires now, or it was never
     * running.
     */
    fun release(): Boolean {
        HeylanaLog.state("recogniser: released, listening=$listening")
        val heardNothing = nothingHeard.releasedNow()
        if (listening) {
            recognizer?.stopListening()
            return true
        }
        if (heardNothing && !abandoned) onNothingHeard()
        return false
    }

    /** The hold turned into a drag: stop and discard whatever was heard. */
    fun cancel() {
        HeylanaLog.state("recogniser: cancel asked, listening=$listening")
        nothingHeard.started()
        if (!listening) return
        abandoned = true
        listening = false
        recognizer?.cancel()
    }

    fun shutdown() {
        abandoned = true
        listening = false
        recognizer?.destroy()
        recognizer = null
    }

    private fun firstResult(bundle: Bundle?): String? =
        bundle?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            ?.firstOrNull()
            ?.trim()
            ?.takeIf { it.isNotEmpty() }

    private fun message(error: Int): String = when (error) {
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS ->
            "I need microphone permission. Open Heylana and allow the microphone."

        SpeechRecognizer.ERROR_NETWORK,
        SpeechRecognizer.ERROR_NETWORK_TIMEOUT ->
            "Voice input needs a connection right now. Type instead."

        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Still finishing the last one, hold again."
        else -> UNAVAILABLE
    }

    companion object {
        const val UNAVAILABLE = "Voice input not available on this device, type instead."
    }
}
