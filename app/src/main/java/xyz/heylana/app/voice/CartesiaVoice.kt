package xyz.heylana.app.voice

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.SystemClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import xyz.heylana.app.HeylanaLog
import xyz.heylana.app.net.Proxy
import xyz.heylana.app.settings.HeylanaSettings

/**
 * Heylana's voice.
 *
 * The proxy asks Cartesia for the words as raw 16-bit audio and streams them
 * straight back, so this can start playing before the sentence has finished
 * being made — which is the difference between a buddy that answers and one
 * that pauses first.
 *
 * Nothing is worth waiting on, though. If the first byte has not arrived within
 * [FallbackWindow.VOICE_MS], the phone's own voice reads the answer instead and
 * the user hears an answer rather than silence. Which one spoke is logged as
 * voice=cartesia or voice=android.
 *
 * [onSpeaking] is driven by the playback itself — true when the first audio
 * actually reaches the speaker, false when the last of it has been played out —
 * so the rings around the disc match what is being heard.
 */
class CartesiaVoice(
    private val settings: HeylanaSettings,
    private val scope: CoroutineScope,
    private val phone: Speaker,
    private val onSpeaking: (Boolean) -> Unit
) {

    private val proxy = Proxy(settings)

    private var stream: Job? = null
    private var track: AudioTrack? = null

    @Volatile
    private var cancelled = false

    /** True when something can speak at all, whichever ends up doing it. */
    val available: Boolean
        get() = proxy.isConfigured || phone.available

    /** True once the phone's engine has finished starting up, either way. */
    val settled: Boolean get() = phone.settled

    /**
     * Reads [text] out. True if something is going to speak, which is what tells
     * the caller a "finished speaking" will follow.
     */
    fun speak(text: String): Boolean {
        if (text.isBlank()) return false
        stop()
        cancelled = false

        val chosen = settings.voice
        if (settings.forcePhoneVoice || chosen == HeylanaSettings.VOICE_PHONE || !proxy.isConfigured) {
            return speakOnPhone(text)
        }

        val window = FallbackWindow(FallbackWindow.VOICE_MS)
        stream = scope.launch {
            val started = SystemClock.uptimeMillis()
            val opened = withContext(Dispatchers.IO) { open(text, chosen) }
            val elapsed = SystemClock.uptimeMillis() - started

            if (cancelled) {
                opened?.close()
                return@launch
            }
            if (opened == null || !window.preferredReady(elapsed)) {
                opened?.close()
                if (!cancelled) speakOnPhone(text)
                return@launch
            }

            HeylanaLog.state("voice=cartesia")
            withContext(Dispatchers.IO) { play(opened) }
        }

        // Whatever happens, something will speak: either the stream or the phone.
        return available
    }

    /** The proxy's reply, held open, or null if it did not arrive. */
    private fun open(text: String, voice: String): Playing? {
        val payload = JSONObject().put("text", text).put("voice", voice).toString()
        return runCatching {
            val response = Proxy.http.newCall(proxy.post("tts", payload)).execute()
            if (!response.isSuccessful) {
                HeylanaLog.state("voice: proxy refused ${response.code}")
                response.close()
                return null
            }
            val rate = response.header("x-sample-rate")?.toIntOrNull() ?: DEFAULT_SAMPLE_RATE
            Playing(response, rate)
        }.getOrNull()
    }

    /** Writes the audio out as it arrives, and reports when it is really over. */
    private fun play(playing: Playing) {
        val rate = playing.sampleRate
        val minimum = AudioTrack.getMinBufferSize(rate, CHANNEL, ENCODING).coerceAtLeast(MIN_BUFFER)
        val player = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANT)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(ENCODING)
                    .setSampleRate(rate)
                    .setChannelMask(CHANNEL)
                    .build()
            )
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setBufferSizeInBytes(minimum * 2)
            .build()

        track = player
        var framesWritten = 0L
        var speaking = false

        try {
            playing.response.body.byteStream().use { input ->
                val chunk = ByteArray(CHUNK_BYTES)
                while (!cancelled) {
                    val read = input.read(chunk)
                    if (read <= 0) break
                    if (!speaking) {
                        // The first audio is on its way to the speaker: this is
                        // the moment the buddy starts talking, not before.
                        player.play()
                        speaking = true
                        onSpeaking(true)
                    }
                    val written = player.write(chunk, 0, read, AudioTrack.WRITE_BLOCKING)
                    if (written > 0) framesWritten += written / BYTES_PER_FRAME
                }
            }

            // Written is not the same as heard: wait for the speaker to catch up.
            while (!cancelled && speaking && player.playbackHeadPosition < framesWritten) {
                Thread.sleep(PLAYED_OUT_POLL_MS)
            }
        } catch (_: Exception) {
            // A stream that dies mid-sentence is not worth a message on screen;
            // the answer is already on the pane.
        } finally {
            playing.close()
            runCatching { player.stop() }
            player.release()
            if (track === player) track = null
            if (speaking) onSpeaking(false)
        }
    }

    private fun speakOnPhone(text: String): Boolean {
        HeylanaLog.state("voice=android")
        return phone.speak(text)
    }

    fun stop() {
        cancelled = true
        stream?.cancel()
        stream = null
        track?.let { player ->
            runCatching { player.pause() }
            runCatching { player.flush() }
        }
        phone.stop()
    }

    fun shutdown() {
        stop()
        phone.shutdown()
    }

    /** The open reply and the rate its audio is in. */
    private class Playing(val response: okhttp3.Response, val sampleRate: Int) {
        fun close() = runCatching { response.close() }
    }

    private companion object {
        const val CHANNEL = AudioFormat.CHANNEL_OUT_MONO
        const val ENCODING = AudioFormat.ENCODING_PCM_16BIT
        const val BYTES_PER_FRAME = 2
        const val DEFAULT_SAMPLE_RATE = 22_050
        const val MIN_BUFFER = 8_192
        const val CHUNK_BYTES = 4_096
        const val PLAYED_OUT_POLL_MS = 40L
    }
}
