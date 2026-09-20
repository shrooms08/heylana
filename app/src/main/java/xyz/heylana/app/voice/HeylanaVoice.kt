package xyz.heylana.app.voice

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.SystemClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import xyz.heylana.app.BuildConfig
import xyz.heylana.app.HeylanaLog
import xyz.heylana.app.net.Proxy
import xyz.heylana.app.settings.HeylanaSettings

/**
 * Heylana's voice.
 *
 * The proxy asks the voice provider (Gemini TTS by default) for the words as raw
 * 16-bit audio and streams them straight back, so this can start playing before the
 * sentence has finished being made — the difference between a buddy that answers and
 * one that pauses first.
 *
 * There is no other voice. If the proxy refuses (the day's cap, the provider's quota),
 * fails, or no audio arrives within [VoiceFailure.FIRST_AUDIO_MS], Heylana stays silent:
 * [onFailed] hands back the words and why, so the answer is shown as text instead.
 *
 * [onSpeaking] is driven by the playback itself — true when the first audio
 * actually reaches the speaker, false when the last of it has been played out —
 * so the rings around the disc match what is being heard.
 */
class HeylanaVoice(
    private val context: android.content.Context,
    private val settings: HeylanaSettings,
    private val scope: CoroutineScope,
    private val onSpeaking: (Boolean) -> Unit,
    /**
     * How loud what is being heard right now is, 0 to 1, from the audio itself and
     * timed to the speaker rather than the network. Drives the speaking orb. Off
     * the main thread.
     */
    private val onLevel: (Float) -> Unit = {},
    /** The words that could not be spoken, and [VoiceFailure]'s reason. On the main thread. */
    private val onFailed: (text: String, reason: String) -> Unit = { _, _ -> }
) {

    private val proxy = Proxy(settings)

    private var track: AudioTrack? = null

    /** Holds back the odd tail byte so no sample is ever written half-finished. */
    private val frames = PcmFrames(BYTES_PER_FRAME)


    /** The first read waits this long; so does every read after it. */
    private val http = Proxy.http.newBuilder()
        .readTimeout(VoiceFailure.FIRST_AUDIO_MS, java.util.concurrent.TimeUnit.MILLISECONDS)
        .build()

    /** True when there is a voice to ask at all. */
    val available: Boolean
        get() = proxy.isConfigured

    /** A line waiting its turn, and the voice it is to be said in. */
    private class Line(
        val text: String,
        val voice: String,
        val clock: AnswerClock? = null,
        /** Audio already on its way from the worker: nothing to ask for, just play it. */
        val ready: Playing? = null,
        /** A line Heylana says the same way every time: its audio is worth keeping. */
        val fixed: Boolean = false
    ) {
        var gen: Int = 0
    }

    /** The audio of the fixed lines — the warnings — kept so they never wait on the voice. */
    private val kept = SpokenCache(java.io.File(context.filesDir, "spoken").apply { mkdirs() })

    private val lines = VoiceQueue<Line>()

    /** The generation now: a stop moves it on. */
    private val generation: Int get() = lines.generation

    /** Only one line is ever being fetched and played: one writer on one AudioTrack. */
    private val oneAtATime = Mutex()

    private fun pending(): Int = lines.pending()

    /**
     * Queues [text] to be read out after whatever is being said now — never over it, and
     * never cutting it off: only [stop] does that, on something the user did. True if the
     * voice is being asked, which is what tells the caller that either "finished speaking"
     * or [onFailed] will follow.
     */
    fun speak(said: String, clock: AnswerClock? = null): Boolean {
        // A web address is never read aloud: whatever line comes here, its links are chips.
        val text = xyz.heylana.app.brain.Sources.spoken(said)
        if (text.isBlank()) return false
        if (!proxy.isConfigured) return false
        val length = lines.add(Line(text, settings.voice, clock = clock))
        HeylanaLog.state("voice: queued length=$length")
        scope.launch(Dispatchers.IO) { drain() }
        return true
    }

    /**
     * A line Heylana says the same way every time — a warning — read out at once.
     *
     * The first time it is fetched like any other and kept; after that it plays from the
     * phone with no network at all, which is the difference between a warning that lands
     * before a thumb reaches Approve and one that does not.
     */
    fun speakFixed(said: String, clock: AnswerClock? = null): Boolean {
        val text = xyz.heylana.app.brain.Sources.spoken(said)
        if (text.isBlank()) return false
        val voice = settings.voice
        val ready = kept.ready(voice, text)
        if (ready != null) {
            val length = lines.add(
                Line(text, voice, clock = clock, ready = Playing(ready.inputStream(), DEFAULT_SAMPLE_RATE) {}, fixed = true)
            )
            HeylanaLog.state("voice: fixed line from the phone, no network queued=$length")
            scope.launch(Dispatchers.IO) { drain() }
            return true
        }
        if (!proxy.isConfigured) return false
        val length = lines.add(Line(text, voice, clock = clock, fixed = true))
        HeylanaLog.state("voice: fixed line asked for the first time queued=$length")
        scope.launch(Dispatchers.IO) { drain() }
        return true
    }

    /**
     * Fetches a fixed line's audio and keeps it, without playing a sound.
     *
     * Called when a wallet or a browser comes up: by the time a confirm sheet or a scam
     * page is in front, the words for it are already on the phone. Nothing is played, the
     * queue is untouched, and a line already kept costs nothing at all.
     */
    suspend fun prefetch(said: String): Boolean = withContext(Dispatchers.IO) {
        val text = xyz.heylana.app.brain.Sources.spoken(said)
        if (text.isBlank() || !proxy.isConfigured) return@withContext false
        val voice = settings.voice
        if (kept.ready(voice, text) != null) return@withContext false
        when (val opened = open(text, voice)) {
            is Opened.Failed -> {
                HeylanaLog.state("voice: could not fetch a fixed line ahead (${opened.reason})")
                false
            }
            is Opened.Ok -> {
                var written = 0L
                runCatching {
                    java.io.BufferedOutputStream(kept.writingTo(voice, text).outputStream()).use { out ->
                        opened.playing.stream.use { input ->
                            val chunk = ByteArray(CHUNK_BYTES)
                            while (true) {
                                val read = input.read(chunk)
                                if (read <= 0) break
                                out.write(chunk, 0, read)
                                written += read
                            }
                        }
                    }
                }
                opened.playing.close()
                val stored = kept.keep(voice, text, written)
                HeylanaLog.state(
                    if (stored) "voice: fixed line fetched ahead ${written / 1024}KB"
                    else "voice: a fixed line came back too short to keep"
                )
                stored
            }
        }
    }

    /**
     * Audio the worker is already making, played as it lands.
     *
     * The one-trip answer speaks the first sentence while the rest is still being written,
     * so there is nothing to ask for here: the stream is open and this only plays it, in
     * the same queue and on the same speaker as everything else.
     */
    fun play(stream: java.io.InputStream, rate: Int, clock: AnswerClock? = null): Boolean {
        val length = lines.add(Line("", settings.voice, clock = clock, ready = Playing(stream, rate) { stream.close() }))
        HeylanaLog.state("voice: streamed from the answer rate=$rate queued=$length")
        scope.launch(Dispatchers.IO) { drain() }
        return true
    }

    /** Plays the queue in order, one line at a time; a second drain waits, then finds it empty. */
    private suspend fun drain() {
        oneAtATime.withLock {
            while (true) {
                val (gen, line) = lines.next() ?: break
                line.gen = gen
                speakOne(line)
            }
        }
    }

    private fun speakOne(line: Line) {
        val started = SystemClock.uptimeMillis()
        line.clock?.voiceAsked(started)
        val opened = line.ready?.let { Opened.Ok(it) } ?: open(line.text, line.voice)
        if (line.gen != generation) {
            (opened as? Opened.Ok)?.playing?.close()
            return
        }
        when (opened) {
            is Opened.Failed -> fail(line, opened.reason)
            is Opened.Ok -> {
                HeylanaLog.state("voice=proxy voice=${line.voice} headers_ms=${SystemClock.uptimeMillis() - started} queued=${pending()}")
                // A fetched fixed line is written down as it plays, so the next one is instant.
                val saving = if (line.fixed && line.ready == null) {
                    runCatching { java.io.BufferedOutputStream(kept.writingTo(line.voice, line.text).outputStream()) }.getOrNull()
                } else {
                    null
                }
                val outcome = play(opened.playing, line.gen, line.clock, saving)
                if (saving != null) {
                    runCatching { saving.close() }
                    val stored = kept.keep(line.voice, line.text, savedBytes)
                    HeylanaLog.state(
                        if (stored) "voice: kept a fixed line's audio ${savedBytes / 1024}KB"
                        else "voice: that fixed line was cut off, not kept"
                    )
                }
                if (outcome != null && line.gen == generation) fail(line, outcome)
            }
        }
    }

    private fun fail(line: Line, reason: String) {
        // Silent: the words go back to be shown, nothing else speaks them.
        HeylanaLog.state("voice_failed reason=$reason")
        if (line.text.isBlank()) return
        scope.launch(Dispatchers.Main) { if (line.gen == generation) onFailed(line.text, reason) }
    }

    private sealed interface Opened {
        data class Ok(val playing: Playing) : Opened
        data class Failed(val reason: String) : Opened
    }

    /** The proxy's reply, held open, or why there is none. */
    private fun open(text: String, voice: String): Opened {
        val payload = JSONObject().put("text", text).put("voice", voice).toString()
        return try {
            val response = http.newCall(proxy.post("tts", payload)).execute()
            if (!response.isSuccessful) {
                val body = runCatching { response.body.string() }.getOrDefault("")
                response.close()
                val refusal = runCatching { JSONObject(body).optString("reason") }.getOrDefault("")
                HeylanaLog.state("voice: proxy refused ${response.code} reason=${refusal.ifEmpty { "none" }}")
                return Opened.Failed(VoiceFailure.reasonFor(response.code, refusal))
            }
            val rate = response.header("x-sample-rate")?.toIntOrNull() ?: DEFAULT_SAMPLE_RATE
            Opened.Ok(Playing(response.body.byteStream(), rate) { response.close() })
        } catch (e: java.io.InterruptedIOException) {
            Opened.Failed(VoiceFailure.TIMEOUT)
        } catch (e: Exception) {
            Opened.Failed(VoiceFailure.ERROR)
        }
    }

    /**
     * Writes the audio out as it arrives, and reports when it is really over. Null when
     * it played (or was stopped); a [VoiceFailure] reason when nothing could be heard.
     */
    /** How much the last play wrote to [SpokenCache], so a cut-off line is not kept. */
    private var savedBytes = 0L

    private fun play(playing: Playing, gen: Int, clock: AnswerClock? = null, saving: java.io.OutputStream? = null): String? {
        savedBytes = 0L
        // This line is over the moment a stop moves the generation on.
        fun stopped() = gen != generation
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

        var failure: String? = null
        var bytesWritten = 0L
        var framesWritten = 0L
        var speaking = false
        // Each written stretch's loudness, keyed by the frame it starts at, handed
        // out as the speaker's play head reaches it.
        val levels = ArrayDeque<Pair<Long, Float>>()
        fun reportHeard() {
            val head = runCatching { player.playbackHeadPosition.toLong() }.getOrDefault(0L)
            var latest: Float? = null
            while (levels.isNotEmpty() && levels.first().first <= head) latest = levels.removeFirst().second
            latest?.let(onLevel)
        }

        try {
            playing.stream.use { input ->
                val chunk = ByteArray(CHUNK_BYTES)
                while (!stopped()) {
                    val read = input.read(chunk)
                    if (read <= 0) break
                    clock?.firstAudio(SystemClock.uptimeMillis())
                    capture?.write(chunk, 0, read)
                    if (saving != null) {
                        runCatching { saving.write(chunk, 0, read) }
                        savedBytes += read
                    }

                    // Whole samples only: a socket does not care where a sample
                    // ends, and half of one shifts everything after it.
                    val aligned = frames.take(chunk, read)
                    if (aligned.isEmpty()) continue

                    val written = player.write(aligned, 0, aligned.size, AudioTrack.WRITE_BLOCKING)
                    if (written > 0) {
                        levels.addLast(framesWritten to PlaybackLevel.of(aligned, written))
                        bytesWritten += written
                        framesWritten += written / BYTES_PER_FRAME
                    }
                    if (speaking) reportHeard()

                    if (!speaking && bytesWritten >= preRollBytes) {
                        // A little audio in hand before the first sound, so the speaker
                        // never runs dry mid-sentence.
                        player.play()
                        speaking = true
                        clock?.spoke(SystemClock.uptimeMillis())
                        onSpeaking(true)
                    }
                }
            }

            // A short answer may never reach the pre-roll; play what there is.
            if (!speaking && bytesWritten > 0 && !stopped()) {
                player.play()
                speaking = true
                clock?.spoke(SystemClock.uptimeMillis())
                onSpeaking(true)
            }

            // Written is not the same as heard: wait for the speaker to catch up.
            while (!stopped() && speaking && player.playbackHeadPosition < framesWritten) {
                reportHeard()
                Thread.sleep(PLAYED_OUT_POLL_MS)
            }
        } catch (e: Exception) {
            // Nothing heard at all: the words are shown instead. A stream that dies
            // mid-sentence has already been heard in part and is left at that.
            if (!stopped() && bytesWritten == 0L) {
                failure = if (e is java.io.InterruptedIOException) VoiceFailure.TIMEOUT else VoiceFailure.ERROR
            }
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
            if (speaking) {
                onLevel(0f)
                // The next line in the queue follows straight on: "finished" only when none does.
                if (stopped() || pending() == 0) onSpeaking(false)
            }
        }
        // An answer that came back with no audio in it is a failure too.
        if (failure == null && !stopped() && bytesWritten == 0L) failure = VoiceFailure.ERROR
        return failure
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

    /**
     * Stops speaking and drops everything queued — for something the user did (a new
     * question, Next, the box closing, mute). The line in the air ends at once; the writer
     * sees the new generation and lets go of its AudioTrack itself.
     */
    fun stop() {
        val dropped = lines.stop()
        if (dropped > 0) HeylanaLog.state("voice: stopped, dropped queued=$dropped")
        track?.let { player ->
            runCatching { player.pause() }
            runCatching { player.flush() }
        }
    }

    fun shutdown() {
        stop()
    }

    /** Audio on its way, and the rate it is in: the worker's reply, or a stream already open. */
    private class Playing(
        val stream: java.io.InputStream,
        val sampleRate: Int,
        private val onClose: () -> Unit = {}
    ) {
        fun close() = runCatching { onClose() }
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
