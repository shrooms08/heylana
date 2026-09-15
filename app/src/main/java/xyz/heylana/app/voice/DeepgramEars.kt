package xyz.heylana.app.voice

import android.Manifest
import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.SystemClock
import androidx.annotation.RequiresPermission
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString.Companion.toByteString
import org.json.JSONObject
import xyz.heylana.app.BuildConfig
import xyz.heylana.app.HeylanaLog
import xyz.heylana.app.net.Borrowed
import xyz.heylana.app.net.Proxy
import java.net.URLEncoder
import kotlin.math.abs
import kotlin.math.min

/**
 * Ears that hear names.
 *
 * The phone's own recogniser turns "Kamino" into "come in oh" and "SKR" into
 * "seeker", which is no use on a wallet screen. Deepgram takes a list of terms
 * to lean toward, so this sends the words this phone is about along with the
 * biggest tappable labels on the screen the user is looking at.
 *
 * The key is borrowed: the proxy mints one that stops working two minutes later
 * ([Proxy.sttToken]), so the real Deepgram key never reaches the phone. Audio
 * goes straight from here to Deepgram over the socket and is not kept, written
 * down, or logged — only whether it worked.
 *
 * [onReady] fires the moment the socket is open and the microphone is running,
 * which is what decides whether these ears or the phone's own get used at all.
 */
class DeepgramEars(
    private val proxy: Proxy,
    private val scope: CoroutineScope,
    heard: EarCallbacks,
    ready: () -> Unit,
    unavailable: (String) -> Unit
) : Ears {

    /**
     * Every report goes back on the main thread, whoever produced it.
     *
     * The socket reads on OkHttp's thread and the microphone on an IO thread,
     * and the callbacks end up in views. Calling them straight from there
     * started an animation off the main thread, which Android answers by killing
     * the whole app — and a crash with the accessibility service bound in the
     * same process leaves Android refusing to bind it again, so the buddy then
     * says it cannot read the screen with the setting still switched on.
     */
    private val main = android.os.Handler(android.os.Looper.getMainLooper())

    private fun onMain(block: () -> Unit) {
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) block() else main.post(block)
    }

    private val callbacks = EarCallbacks(
        onPartial = { text -> onMain { heard.onPartial(text) } },
        onFinal = { text -> onMain { heard.onFinal(text) } },
        onProblem = { message -> onMain { heard.onProblem(message) } },
        onNothingHeard = { onMain { heard.onNothingHeard() } },
        onLevel = { level -> onMain { heard.onLevel(level) } }
    )
    private val onReady: () -> Unit = { onMain(ready) }
    private val onUnavailable: (String) -> Unit = { reason -> onMain { unavailable(reason) } }

    /** How far along getting ready to listen has got. */
    enum class Stage { IDLE, TOKEN, SOCKET, READY, FAILED }

    @Volatile
    var stage: Stage = Stage.IDLE
        private set

    /** Set once the user is actually holding: record as soon as we can. */
    @Volatile
    private var wanted = false

    private var socket: WebSocket? = null
    private var recorder: AudioRecord? = null
    private var pump: Job? = null

    @Volatile
    private var listening = false

    @Volatile
    private var abandoned = false

    @Volatile
    private var released = false

    /** Everything Deepgram has settled on so far, in order. */
    private val heard = StringBuilder()

    /**
     * Audio recorded before the socket was up.
     *
     * The microphone starts the moment the user holds the buddy, whether or not
     * the socket has finished opening — waiting would throw away the first words
     * of every question. What is captured in the meantime waits here and is
     * flushed the moment the socket answers.
     */
    private val early = ArrayDeque<ByteArray>()

    @Volatile
    private var earlyBytes = 0

    /** How long the socket took to open, once it has. */
    @Volatile
    var socketMillis = 0L
        private set

    /** How long borrowing the key took. */
    @Volatile
    var tokenMillis = 0L
        private set

    /** Why the last attempt failed, in a word. Null while it is still going. */
    @Volatile
    var failure: String? = null
        private set

    private var socketAskedAt = 0L

    private val nothingHeard = NothingHeardGate()

    override val isListening: Boolean get() = listening

    /** Nothing to check up front: the proxy answers that when it is asked. */
    override fun available(): Boolean = proxy.isConfigured

    /**
     * Gets everything ready without opening the microphone: borrows a key and
     * opens the socket.
     *
     * This runs the moment the buddy is touched, long before anyone knows
     * whether that touch will become a hold, so that a hold can start listening
     * the instant it begins rather than waiting on a round trip. If the touch
     * turns out to be a tap or a drag, [discard] closes it again quietly.
     */
    fun prepare(keyterms: List<String>) {
        if (stage != Stage.IDLE && stage != Stage.FAILED) return
        abandoned = false
        released = false
        wanted = false
        heard.setLength(0)
        nothingHeard.started()
        stage = Stage.TOKEN

        scope.launch {
            when (val borrowed = proxy.sttToken()) {
                is Borrowed.Key -> {
                    tokenMillis = borrowed.millis
                    HeylanaLog.state(
                        "deepgram: token_ms=${borrowed.millis} cached=${borrowed.cached}"
                    )
                    if (abandoned) return@launch
                    stage = Stage.SOCKET
                    open(borrowed.value, keyterms)
                }

                is Borrowed.Refused -> {
                    tokenMillis = borrowed.millis
                    stage = Stage.FAILED
                    // A key that is not allowed to mint keys will never be, so
                    // this one is worth saying out loud rather than as a number.
                    failure = if (borrowed.scopeProblem) {
                        REASON_TOKEN_SCOPE
                    } else {
                        "token_refused_${borrowed.code}"
                    }
                    if (!abandoned) onUnavailable(failure!!)
                }

                is Borrowed.Unreachable -> {
                    tokenMillis = borrowed.millis
                    stage = Stage.FAILED
                    failure = if (borrowed.cause == "not_set_up") {
                        REASON_NOT_SET_UP
                    } else {
                        "token_unreachable_${borrowed.cause}"
                    }
                    if (!abandoned) onUnavailable(failure!!)
                }
            }
        }
    }

    /**
     * The user is holding: start recording. Safe to call before the socket is
     * open — the microphone starts the moment it is.
     */
    @SuppressLint("MissingPermission")
    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    fun beginSpeaking() {
        wanted = true
        if (stage == Stage.READY) startRecordingNow()
    }

    /** Prepare and record as soon as possible: the plain Ears way in. */
    @SuppressLint("MissingPermission")
    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    override fun start(keyterms: List<String>) {
        prepare(keyterms)
        beginSpeaking()
    }

    private fun open(key: String, keyterms: List<String>) {
        HeylanaLog.state("deepgram: opening socket, keyterms=${keyterms.size}")
        socketAskedAt = SystemClock.elapsedRealtime()
        val request = Request.Builder()
            .url(deepgramUrl(keyterms, SAMPLE_RATE, listenBase()))
            .addHeader("Authorization", "Token $key")
            .build()

        socket = Proxy.http.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                if (abandoned) {
                    webSocket.cancel()
                    return
                }
                stage = Stage.READY
                socketMillis = SystemClock.elapsedRealtime() - socketAskedAt
                HeylanaLog.state("deepgram: socket_ms=$socketMillis")
                // Anything recorded while it was opening goes first, in order.
                flushEarly(webSocket)
                onReady()
                // The hold may have started while the socket was still opening.
                if (wanted) startRecordingNow()
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                if (abandoned) return
                handle(text)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                val refused = response?.code
                // Say what actually happened. "code=0" meant there was no HTTP
                // answer at all, and hid whether that was DNS, TLS or a reset.
                val body = runCatching { response?.peekBody(BODY_PEEK_BYTES)?.string() }
                    .getOrNull()
                    ?.replace(Regex("\\s+"), " ")
                    ?.take(BODY_PEEK_BYTES.toInt())
                HeylanaLog.state(
                    "deepgram: socket failed ${t::class.simpleName}: ${t.message?.take(160)} " +
                        "http=${refused ?: "none"} stage=$stage body=${body ?: "none"}"
                )
                stopRecording()
                if (abandoned) return
                val wasReady = stage == Stage.READY
                stage = Stage.FAILED
                listening = false
                // A key the socket would not take is not worth keeping.
                if (refused == 401 || refused == 403) proxy.forgetSttToken()
                failure = if (refused != null) "socket_error_$refused" else REASON_SOCKET_ERROR
                // Before the user let go this is still recoverable: the phone's
                // own ears can take over without them noticing.
                if (!released) onUnavailable(failure ?: REASON_SOCKET_ERROR) else if (wasReady) finish()
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                stopRecording()
                if (abandoned) return
                stage = Stage.IDLE
                listening = false
                finish()
            }
        })
    }

    /** Deepgram sends a message per guess; only the settled ones are kept. */
    private fun handle(message: String) {
        val json = runCatching { JSONObject(message) }.getOrNull() ?: return
        val alternative = json.optJSONObject("channel")
            ?.optJSONArray("alternatives")
            ?.optJSONObject(0)
            ?: return

        val text = alternative.optString("transcript").trim()
        if (text.isEmpty()) return

        if (json.optBoolean("is_final")) {
            if (heard.isNotEmpty()) heard.append(' ')
            heard.append(text)
            callbacks.onPartial(heard.toString())
        } else {
            val sofar = if (heard.isEmpty()) text else "$heard $text"
            callbacks.onPartial(sofar)
        }
    }

    /**
     * Opens the microphone, socket or no socket.
     *
     * Until it is up, the audio goes into [early] rather than being thrown away,
     * because the first half second of a question is usually the half that says
     * what it is about.
     */
    @SuppressLint("MissingPermission")
    private fun startRecordingNow() {
        if (listening) return
        listening = true
        val minimum = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL, ENCODING)
        if (minimum <= 0) {
            listening = false
            onUnavailable(REASON_NO_MICROPHONE)
            return
        }
        val size = maxOf(minimum, FRAME_BYTES * 4)
        val record = runCatching {
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                SAMPLE_RATE, CHANNEL, ENCODING, size
            )
        }.getOrNull()

        if (record == null || record.state != AudioRecord.STATE_INITIALIZED) {
            record?.release()
            listening = false
            HeylanaLog.state("deepgram: microphone would not open")
            onUnavailable(REASON_NO_MICROPHONE)
            return
        }

        recorder = record
        record.startRecording()
        pump = scope.launch(Dispatchers.IO) {
            val frame = ByteArray(FRAME_BYTES)
            var checked = false
            while (!abandoned && recorder != null) {
                val read = record.read(frame, 0, frame.size)
                if (read <= 0) break
                if (!checked) {
                    checked = true
                    // Android may hand the microphone to the phone's own
                    // recogniser and give this capture silence; say if it did.
                    val silenced = runCatching {
                        record.activeRecordingConfiguration?.isClientSilenced
                    }.getOrNull()
                    HeylanaLog.state("deepgram: microphone open silenced=${silenced ?: "unknown"}")
                }
                val piece = frame.copyOf(read)
                val live = socket.takeIf { stage == Stage.READY }
                if (live != null) {
                    live.send(piece.toByteString())
                } else {
                    keepEarly(piece)
                }
                callbacks.onLevel(levelOf(frame, read))
            }
        }
    }

    /** Holds the audio recorded before the socket answered, oldest first. */
    private fun keepEarly(piece: ByteArray) {
        synchronized(early) {
            early.addLast(piece)
            earlyBytes += piece.size
            // A few seconds is all that can be worth keeping; past that the
            // socket is not coming and the phone's ears have taken over anyway.
            while (earlyBytes > MAX_EARLY_BYTES && early.isNotEmpty()) {
                earlyBytes -= early.removeFirst().size
            }
        }
    }

    private fun flushEarly(webSocket: WebSocket) {
        val pieces = synchronized(early) {
            val all = early.toList()
            early.clear()
            earlyBytes = 0
            all
        }
        if (pieces.isEmpty()) return
        val bytes = pieces.sumOf { it.size }
        HeylanaLog.state("deepgram: flushed ${bytes / BYTES_PER_MS}ms recorded while connecting")
        for (piece in pieces) webSocket.send(piece.toByteString())
    }

    private fun stopRecording() {
        pump?.cancel()
        pump = null
        recorder?.let { record ->
            runCatching { record.stop() }
            record.release()
        }
        recorder = null
    }

    /** Rough loudness of one frame, for the ring that breathes around the disc. */
    private fun levelOf(frame: ByteArray, read: Int): Float {
        var peak = 0
        var i = 0
        while (i + 1 < read) {
            val sample = (frame[i].toInt() and 0xFF) or (frame[i + 1].toInt() shl 8)
            peak = maxOf(peak, abs(sample.toShort().toInt()))
            i += SAMPLE_STRIDE
        }
        return min(1f, peak / LOUD_ENOUGH)
    }

    override fun release(): Boolean {
        released = true
        val heldBack = nothingHeard.releasedNow()
        if (!listening) {
            if (heldBack && !abandoned) callbacks.onNothingHeard()
            return false
        }
        // Tell Deepgram there is no more audio; the last words arrive after it.
        stopRecording()
        socket?.send(CLOSE_STREAM)
        scope.launch {
            withContext(Dispatchers.IO) { Thread.sleep(FLUSH_MS) }
            if (listening) {
                listening = false
                finish()
            }
        }
        return true
    }

    /** One place where the words are handed over, however the socket ended. */
    private fun finish() {
        val words = heard.toString().trim()
        socket?.close(NORMAL_CLOSE, null)
        socket = null
        if (abandoned) return
        if (words.isEmpty()) {
            if (nothingHeard.nothingHeard()) callbacks.onNothingHeard()
        } else {
            callbacks.onFinal(words)
        }
    }

    /** The touch was a tap or a drag after all: close it again, quietly. */
    fun discard() {
        if (stage == Stage.IDLE) return
        HeylanaLog.state("deepgram: not a hold, closing")
        cancel()
    }

    override fun cancel() {
        abandoned = true
        wanted = false
        listening = false
        stage = Stage.IDLE
        synchronized(early) {
            early.clear()
            earlyBytes = 0
        }
        stopRecording()
        socket?.close(NORMAL_CLOSE, null)
        socket?.cancel()
        socket = null
    }

    override fun shutdown() = cancel()

    private companion object {
        const val SAMPLE_RATE = 16_000
        const val CHANNEL = AudioFormat.CHANNEL_IN_MONO
        const val ENCODING = AudioFormat.ENCODING_PCM_16BIT

        /** 20ms of audio: small enough that the words appear as they are said. */
        const val FRAME_BYTES = SAMPLE_RATE / 50 * 2

        /** Every eighth sample is plenty to know how loud someone is. */
        const val SAMPLE_STRIDE = 16

        /** Where a normal speaking voice sits, as a 16-bit peak. */
        const val LOUD_ENOUGH = 12_000f

        /** How long to wait for the last words after the audio stops. */
        const val FLUSH_MS = 900L

        const val NORMAL_CLOSE = 1000

        val CLOSE_STREAM: String = JSONObject().put("type", "CloseStream").toString()

        /** 16-bit audio at 16kHz: two bytes a sample, sixteen samples a millisecond. */
        const val BYTES_PER_MS = SAMPLE_RATE * 2 / 1000

        /** Four seconds of audio is more than any hold needs to survive. */
        const val MAX_EARLY_BYTES = BYTES_PER_MS * 4_000

        /** How much of a refused socket's answer is worth writing down. */
        const val BODY_PEEK_BYTES = 200L

        /** Where the listening socket goes: Deepgram, or a debug build's stand-in. */
        fun listenBase(): String =
            if (BuildConfig.DEBUG && BuildConfig.LISTEN_URL.isNotEmpty()) {
                BuildConfig.LISTEN_URL
            } else {
                DEEPGRAM_LISTEN
            }

        const val DEEPGRAM_LISTEN = "wss://api.deepgram.com/v1/listen"

        /** Why the phone's own ears had to take over. */
        const val REASON_TOKEN_SCOPE = "token_scope_keys_write"
        const val REASON_NOT_SET_UP = "not_set_up"
        const val REASON_SOCKET_ERROR = "socket_error"
        const val REASON_NO_MICROPHONE = "no_microphone"
    }
}

/**
 * Deepgram's URL carries the whole configuration, keyterms included: nova-3 with
 * interim results on so the capsule fills in live, endpointing off because the
 * finger decides when it is over, and smart formatting so names and numbers come
 * back written properly.
 *
 * Keyterms are one repeated `keyterm=` parameter each, which is what nova-3
 * expects — a comma-joined list is read as one long term and boosts nothing.
 */
internal fun deepgramUrl(
    keyterms: List<String>,
    sampleRate: Int,
    base: String = "wss://api.deepgram.com/v1/listen"
): String = buildString {
    append(base)
    append("?model=nova-3")
    append("&interim_results=true")
    append("&endpointing=false")
    append("&smart_format=true")
    append("&encoding=linear16")
    append("&sample_rate=").append(sampleRate)
    append("&channels=1")
    for (term in keyterms) {
        append("&keyterm=").append(URLEncoder.encode(term, "UTF-8"))
    }
}
