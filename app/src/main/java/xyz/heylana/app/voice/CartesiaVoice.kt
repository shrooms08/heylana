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
import xyz.heylana.app.BuildConfig
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
    private val context: android.content.Context,
    private val settings: HeylanaSettings,
    private val scope: CoroutineScope,
    private val phone: Speaker,
    private val onSpeaking: (Boolean) -> Unit
) {

    private val proxy = Proxy(settings)

    private var stream: Job? = null
    private var track: AudioTrack? = null

    /** Holds back the odd tail byte so no sample is ever written half-finished. */
    private val frames = PcmFrames(BYTES_PER_FRAME)

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
        val minimum = AudioTrack.getMinBufferSize(rate, CHANNEL, ENCODING)
        // Enough room for a pause in the network, and enough in it before the
        // first sound so a pause does not leave a hole in the middle of a word.
        val bufferBytes = maxOf(minimum, bytesFor(rate, BUFFER_MS))
        val preRollBytes = bytesFor(rate, PRE_ROLL_MS)

        HeylanaLog.state(
            "voice: pcm rate=$rate channels=1 bits=16 " +
                "buffer_ms=${millisFor(rate, bufferBytes)} pre_roll_ms=$PRE_ROLL_MS"
        )

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
            .setBufferSizeInBytes(bufferBytes)
            .build()

        track = player
        frames.reset()
        val capture = openCapture(rate)

        var bytesWritten = 0L
        var framesWritten = 0L
        var speaking = false

        try {
            playing.response.body.byteStream().use { input ->
                val chunk = ByteArray(CHUNK_BYTES)
                while (!cancelled) {
                    val read = input.read(chunk)
                    if (read <= 0) break
                    capture?.write(chunk, 0, read)

                    // Whole samples only: a socket does not care where a sample
                    // ends, and half of one shifts everything after it.
                    val aligned = frames.take(chunk, read)
                    if (aligned.isEmpty()) continue

                    val written = player.write(aligned, 0, aligned.size, AudioTrack.WRITE_BLOCKING)
                    if (written > 0) {
                        bytesWritten += written
                        framesWritten += written / BYTES_PER_FRAME
                    }

                    if (!speaking && bytesWritten >= preRollBytes) {
                        // Three hundred milliseconds in hand before the first
                        // sound, so the speaker never runs dry mid-sentence.
                        player.play()
                        speaking = true
                        onSpeaking(true)
                    }
                }
            }

            // A short answer may never reach the pre-roll; play what there is.
            if (!speaking && bytesWritten > 0 && !cancelled) {
                player.play()
                speaking = true
                onSpeaking(true)
            }

            // Written is not the same as heard: wait for the speaker to catch up.
            while (!cancelled && speaking && player.playbackHeadPosition < framesWritten) {
                Thread.sleep(PLAYED_OUT_POLL_MS)
            }
        } catch (_: Exception) {
            // A stream that dies mid-sentence is not worth a message on screen;
            // the answer is already on the pane.
        } finally {
            capture?.let { file ->
                runCatching { file.close() }
                HeylanaLog.state("voice: saved ${bytesWritten}B to ${captureFile()?.absolutePath}")
            }
            playing.close()
            runCatching { player.stop() }
            // How often the speaker ran dry: anything above zero is audible.
            HeylanaLog.state(
                "voice: played ${millisFor(rate, bytesWritten.toInt())}ms " +
                    "underruns=${runCatching { player.underrunCount }.getOrDefault(-1)} " +
                    "leftover_bytes=${frames.pending}"
            )
            player.release()
            if (track === player) track = null
            if (speaking) onSpeaking(false)
        }
    }

    /** Bytes of 16-bit mono audio worth [millis] at [rate]. */
    private fun bytesFor(rate: Int, millis: Int): Int = rate * BYTES_PER_FRAME * millis / 1000

    private fun millisFor(rate: Int, bytes: Int): Int =
        if (rate == 0) 0 else (bytes.toLong() * 1000 / (rate * BYTES_PER_FRAME)).toInt()

    /** Debug only: where the last answer's audio is kept, if the switch is on. */
    private fun captureFile(): java.io.File? =
        context.getExternalFilesDir(null)?.let { java.io.File(it, CAPTURE_NAME) }

    private fun openCapture(rate: Int): java.io.OutputStream? {
        if (!BuildConfig.DEBUG || !settings.saveTtsStream) return null
        val file = captureFile() ?: return null
        return runCatching {
            HeylanaLog.state("voice: saving raw pcm s16le mono ${rate}Hz to ${file.name}")
            java.io.BufferedOutputStream(java.io.FileOutputStream(file))
        }.getOrNull()
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
        const val DEFAULT_SAMPLE_RATE = 24_000
        const val CHUNK_BYTES = 4_096
        const val PLAYED_OUT_POLL_MS = 40L

        /** Room for a pause in the network without a hole in the sentence. */
        const val BUFFER_MS = 400

        /** How much is in hand before the first sound comes out. */
        const val PRE_ROLL_MS = 300

        const val CAPTURE_NAME = "tts_capture.pcm"
    }
}
