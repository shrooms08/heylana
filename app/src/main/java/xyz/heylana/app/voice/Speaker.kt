package xyz.heylana.app.voice

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale

/**
 * Reads Heylana's answers out loud.
 *
 * If text-to-speech will not start on this device the whole class quietly becomes
 * a no-op — [available] stays false and the caller falls back to text only.
 */
class Speaker(context: Context, private val onSpeakingChanged: (Boolean) -> Unit) {

    /** False until the engine reports it is ready; false forever if it failed. */
    @Volatile
    var available: Boolean = false
        private set

    /** True once the engine has finished starting up, either way. */
    @Volatile
    var settled: Boolean = false
        private set

    private val main = Handler(Looper.getMainLooper())
    private var engine: TextToSpeech? = null

    init {
        engine = TextToSpeech(context.applicationContext) { status ->
            val ok = status == TextToSpeech.SUCCESS
            if (ok) {
                val result = engine?.setLanguage(Locale.getDefault())
                val usable = result != TextToSpeech.LANG_MISSING_DATA &&
                    result != TextToSpeech.LANG_NOT_SUPPORTED
                if (!usable) engine?.setLanguage(Locale.US)
                available = true
            }
            settled = true
        }.apply {
            setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) = post(true)
                override fun onDone(utteranceId: String?) = post(false)
                override fun onStop(utteranceId: String?, interrupted: Boolean) = post(false)

                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) = post(false)
            })
        }
    }

    fun speak(text: String) {
        if (!available || text.isBlank()) return
        engine?.speak(text, TextToSpeech.QUEUE_FLUSH, null, UTTERANCE_ID)
    }

    fun stop() {
        engine?.stop()
        post(false)
    }

    fun shutdown() {
        engine?.stop()
        engine?.shutdown()
        engine = null
        available = false
    }

    private fun post(speaking: Boolean) {
        main.post { onSpeakingChanged(speaking) }
    }

    private companion object {
        const val UTTERANCE_ID = "heylana"
    }
}
