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

    /** Which of the phone's voices was picked, for the debug trace. */
    @Volatile
    var chosenVoice: String? = null
        private set

    init {
        engine = TextToSpeech(context.applicationContext) { status ->
            val ok = status == TextToSpeech.SUCCESS
            if (ok) {
                val result = engine?.setLanguage(Locale.getDefault())
                val usable = result != TextToSpeech.LANG_MISSING_DATA &&
                    result != TextToSpeech.LANG_NOT_SUPPORTED
                if (!usable) engine?.setLanguage(Locale.US)
                tune()
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

    /**
     * Picks a voice rather than taking whatever the engine hands out, and sits
     * it a touch above flat. The default on most phones is the satnav voice.
     */
    private fun tune() {
        val engine = engine ?: return
        engine.setPitch(PhoneVoice.PITCH)
        engine.setSpeechRate(PhoneVoice.RATE)

        val offered = runCatching { engine.voices }.getOrNull().orEmpty()
        val best = PhoneVoice.best(
            offered.map { voice ->
                PhoneVoice.Option(
                    name = voice.name.orEmpty(),
                    locale = voice.locale?.toString().orEmpty(),
                    quality = voice.quality,
                    needsNetwork = voice.isNetworkConnectionRequired
                )
            }
        ) ?: return

        offered.firstOrNull { it.name == best.name }?.let { engine.voice = it }
        chosenVoice = best.name
    }

    /** True if something is going to speak, so a "finished" will follow. */
    fun speak(text: String): Boolean {
        if (!available || text.isBlank()) return false
        engine?.speak(text, TextToSpeech.QUEUE_FLUSH, null, UTTERANCE_ID)
        return true
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
