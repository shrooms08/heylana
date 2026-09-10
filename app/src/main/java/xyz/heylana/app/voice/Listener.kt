package xyz.heylana.app.voice

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer

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
    private val onProblem: (String) -> Unit
) {

    private var recognizer: SpeechRecognizer? = null
    private var listening = false

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
        val speech = recognizer ?: SpeechRecognizer.createSpeechRecognizer(context).also {
            recognizer = it
        }
        speech.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) = Unit
            override fun onBeginningOfSpeech() = Unit
            override fun onRmsChanged(rmsdB: Float) = Unit
            override fun onBufferReceived(buffer: ByteArray?) = Unit
            override fun onEndOfSpeech() = Unit
            override fun onEvent(eventType: Int, params: Bundle?) = Unit

            override fun onPartialResults(partialResults: Bundle?) {
                if (abandoned) return
                firstResult(partialResults)?.let(onPartial)
            }

            override fun onResults(results: Bundle?) {
                listening = false
                if (abandoned) return
                val text = firstResult(results)
                if (text.isNullOrBlank()) onProblem(NOTHING_HEARD) else onFinal(text)
            }

            override fun onError(error: Int) {
                listening = false
                if (abandoned) return
                onProblem(message(error))
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
        speech.startListening(intent)
    }

    /** The user let go: ask for the final transcript. */
    fun stop() {
        if (!listening) return
        recognizer?.stopListening()
    }

    /** The hold turned into a drag: stop and discard whatever was heard. */
    fun cancel() {
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
        SpeechRecognizer.ERROR_NO_MATCH,
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> NOTHING_HEARD

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
        private const val NOTHING_HEARD = "I didn't catch that. Hold me and try again."
    }
}
