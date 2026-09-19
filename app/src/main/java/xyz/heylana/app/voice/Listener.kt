package xyz.heylana.app.voice

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import xyz.heylana.app.HeylanaLog

/**
 * The phone's own ears: always there, free, and hard of hearing when it comes to
 * names like Kamino or SKR.
 *
 * Heylana would rather use [DeepgramEars], but these are what it falls back to
 * when the proxy cannot be reached, when the socket is too slow to open, or when
 * the debug switch says to.
 *
 * Partial results stream out as they are heard; the final transcript arrives
 * once, after [release].
 */
class Listener(
    private val context: Context,
    private val callbacks: EarCallbacks
) : Ears {

    private val onPartial: (String) -> Unit get() = callbacks.onPartial
    private val onFinal: (String) -> Unit get() = callbacks.onFinal
    private val onProblem: (String) -> Unit get() = callbacks.onProblem
    private val onNothingHeard: () -> Unit get() = callbacks.onNothingHeard


    private var recognizer: SpeechRecognizer? = null
    private var listening = false

    /** Holds an early "heard nothing" back until the user actually lets go. */
    private val nothingHeard = NothingHeardGate()

    /** Set when the user drags away mid-hold: results are then thrown away. */
    private var abandoned = false

    override val isListening: Boolean get() = listening

    /** The recogniser's own confidence in its best result, when it gives one (many don't: then null). */
    @Volatile
    private var lastConfidence: Float? = null

    override val confidence: Float? get() = lastConfidence

    override fun available(): Boolean = SpeechRecognizer.isRecognitionAvailable(context)

    /** Must be called on the main thread. [keyterms] bias it where the platform allows. */
    override fun start(keyterms: List<String>) {
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

            override fun onRmsChanged(rmsdB: Float) {
                // Roughly -2dB quiet to 10dB loud, in this recogniser's units.
                callbacks.onLevel(((rmsdB + 2f) / 12f).coerceIn(0f, 1f))
            }
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
                lastConfidence = results?.getFloatArray(SpeechRecognizer.CONFIDENCE_SCORES)
                    ?.firstOrNull()?.takeIf { it in 0f..1f }
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
            // The same hints Deepgram gets, where the platform will take them.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && keyterms.isNotEmpty()) {
                putExtra(
                    RecognizerIntent.EXTRA_BIASING_STRINGS,
                    ArrayList(keyterms.take(MAX_BIASING_STRINGS))
                )
            }
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
    override fun release(): Boolean {
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
    override fun cancel() {
        HeylanaLog.state("recogniser: cancel asked, listening=$listening")
        nothingHeard.started()
        if (!listening) return
        abandoned = true
        listening = false
        recognizer?.cancel()
    }

    override fun shutdown() {
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
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> NO_PERMISSION
        SpeechRecognizer.ERROR_NETWORK,
        SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> xyz.heylana.app.brain.PlainError.OFFLINE
        else -> xyz.heylana.app.brain.PlainError.EARS
    }

    companion object {
        const val UNAVAILABLE = xyz.heylana.app.brain.PlainError.EARS

        /** The one ear failure the user can fix, so it keeps its own words. */
        const val NO_PERMISSION = "I need microphone permission. Open Heylana and allow the microphone."

        /** More than this and the platform starts ignoring them anyway. */
        private const val MAX_BIASING_STRINGS = 20
    }
}
