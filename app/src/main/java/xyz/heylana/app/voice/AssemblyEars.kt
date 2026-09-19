package xyz.heylana.app.voice

import android.os.SystemClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString.Companion.toByteString
import org.json.JSONArray
import org.json.JSONObject
import xyz.heylana.app.HeylanaLog
import xyz.heylana.app.net.Borrowed
import xyz.heylana.app.net.Proxy
import java.net.URLEncoder

/**
 * The third ear: AssemblyAI Universal-Streaming over a websocket.
 *
 * It has no microphone of its own. [DeepgramEars] records once and hands every piece to
 * [feed] as well, pre-roll first, so the two cloud ears hear exactly the same audio and
 * neither is silenced for the other. The socket is opened from the first touch, on a
 * single-use token the worker mints (`/stt-token-aai`); the real key never reaches the phone.
 *
 * On release the audio runs on for [DeepgramEars.TRAILING_MS] (the same trailing moment
 * Deepgram gets), then AssemblyAI is asked to end the turn (`ForceEndpoint`) and given
 * [FINAL_WAIT_MS] to send it, then the session is closed (`Terminate`). The words are every
 * turn it heard, in order; [confidence] is the mean of their word confidences. Nothing it
 * hears is logged — only counts, timings and why it had nothing.
 */
class AssemblyEars(
    private val proxy: Proxy,
    private val scope: CoroutineScope,
    heard: EarCallbacks,
    unavailable: (String) -> Unit
) : Ears {

    /** Every report goes back on the main thread, whoever produced it (see [DeepgramEars]). */
    private val main = android.os.Handler(android.os.Looper.getMainLooper())

    private fun onMain(block: () -> Unit) {
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) block() else main.post(block)
    }

    private val callbacks = EarCallbacks(
        onPartial = { text -> onMain { heard.onPartial(text) } },
        onFinal = { text -> onMain { heard.onFinal(text) } },
        onProblem = { message -> onMain { heard.onProblem(message) } },
        onNothingHeard = { onMain { heard.onNothingHeard() } }
    )
    private val onUnavailable: (String) -> Unit = { reason -> onMain { unavailable(reason) } }

    enum class Stage { IDLE, TOKEN, SOCKET, READY, FAILED }

    @Volatile
    var stage: Stage = Stage.IDLE
        private set

    /** Why it has nothing to give, in a word. Null while it is still going. */
    @Volatile
    var failure: String? = null
        private set

    @Volatile
    var tokenMillis = 0L
        private set

    @Volatile
    var socketMillis = 0L
        private set

    /** From the release to the final words, once they came. */
    @Volatile
    var finalMillis = -1L
        private set

    private var socket: WebSocket? = null
    private var socketAskedAt = 0L

    @Volatile
    private var abandoned = false

    @Volatile
    private var released = false

    @Volatile
    private var listening = false

    /** Set when a turn ends after the release asked for it. */
    @Volatile
    private var endpointed = false

    @Volatile
    private var endpointAskedAt = 0L

    /** Each turn as last heard, by its order: its words, whether it ended, its word confidences. */
    private data class Turn(val text: String, val ended: Boolean, val confidences: List<Float>)

    private val turns = sortedMapOf<Int, Turn>()

    /** Audio waiting to make up a piece of at least [CHUNK_BYTES]; AssemblyAI wants 50 to 1000ms a message. */
    private val pending = java.io.ByteArrayOutputStream()

    /** Pieces made before the socket was open, oldest first; flushed the moment it is. */
    private val early = ArrayDeque<ByteArray>()
    private var earlyBytes = 0

    override val isListening: Boolean get() = listening

    override fun available(): Boolean = proxy.isConfigured

    override val confidence: Float?
        get() = synchronized(turns) {
            val all = turns.values.flatMap { it.confidences }
            if (all.isEmpty()) null else all.sum() / all.size
        }

    /** The words heard so far, every turn in order. */
    private fun words(): String = synchronized(turns) { turns.values.joinToString(" ") { it.text }.trim() }

    /** From the first touch: a token and an open socket, before anyone knows it will be a hold. */
    fun prepare(keyterms: List<String>) {
        if (stage != Stage.IDLE && stage != Stage.FAILED) return
        abandoned = false
        released = false
        endpointed = false
        finalMillis = -1L
        failure = null
        synchronized(turns) { turns.clear() }
        synchronized(early) {
            early.clear()
            earlyBytes = 0
            pending.reset()
        }
        stage = Stage.TOKEN
        scope.launch {
            when (val borrowed = proxy.sttTokenAai()) {
                is Borrowed.Key -> {
                    tokenMillis = borrowed.millis
                    HeylanaLog.state("assemblyai: token_ms=${borrowed.millis}")
                    if (abandoned) return@launch
                    stage = Stage.SOCKET
                    open(borrowed.value, keyterms)
                }
                is Borrowed.Refused -> fail("token_refused_${borrowed.code}", borrowed.millis)
                is Borrowed.Unreachable -> fail(
                    if (borrowed.cause == "not_set_up") "not_set_up" else "token_unreachable_${borrowed.cause}",
                    borrowed.millis
                )
            }
        }
    }

    private fun fail(reason: String, millis: Long) {
        tokenMillis = millis
        stage = Stage.FAILED
        failure = reason
        if (!abandoned) onUnavailable(reason)
    }

    /** The plain [Ears] way in: prepare now; the audio arrives through [feed]. */
    override fun start(keyterms: List<String>) {
        prepare(keyterms)
        listening = true
    }

    /** The user is holding: from now on the audio fed in is sent. */
    fun beginSpeaking() {
        listening = true
    }

    private fun open(token: String, keyterms: List<String>) {
        HeylanaLog.state("assemblyai: opening socket, keyterms=${keyterms.size}")
        socketAskedAt = SystemClock.elapsedRealtime()
        val request = Request.Builder().url(assemblyUrl(keyterms, DeepgramEars.SAMPLE_RATE, token)).build()
        socket = Proxy.http.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                if (abandoned) {
                    webSocket.cancel()
                    return
                }
                socketMillis = SystemClock.elapsedRealtime() - socketAskedAt
                stage = Stage.READY
                HeylanaLog.state("assemblyai: socket_ms=$socketMillis")
                flushEarly(webSocket)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                if (!abandoned) handle(text)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                HeylanaLog.state(
                    "assemblyai: socket failed ${t::class.simpleName} http=${response?.code ?: "none"} stage=$stage"
                )
                socket = null
                if (abandoned) return
                val wasReady = stage == Stage.READY
                stage = Stage.FAILED
                failure = response?.code?.let { "socket_error_$it" } ?: "socket_error"
                if (!released) onUnavailable(failure!!) else if (wasReady) finish()
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                socket = null
                if (abandoned) return
                if (released && !endpointed) HeylanaLog.state("assemblyai: socket closed code=$code before the final")
                stage = Stage.IDLE
                if (listening) finish()
            }
        })
    }

    /** A Turn is the current guess at one turn; the latest for each turn order is kept. */
    private fun handle(message: String) {
        val json = runCatching { JSONObject(message) }.getOrNull() ?: return
        when (json.optString("type")) {
            "Turn" -> {
                val order = json.optInt("turn_order", 0)
                val ended = json.optBoolean("end_of_turn")
                val words = json.optJSONArray("words") ?: JSONArray()
                val confidences = (0 until words.length()).mapNotNull { i ->
                    words.optJSONObject(i)?.takeIf { it.has("confidence") }?.optDouble("confidence")?.toFloat()
                }
                val text = json.optString("transcript").trim()
                synchronized(turns) {
                    if (text.isEmpty()) turns.remove(order) else turns[order] = Turn(text, ended, confidences)
                }
                if (ended && released && endpointAskedAt > 0) endpointed = true
                val sofar = words()
                if (sofar.isNotEmpty()) callbacks.onPartial(sofar)
            }
            "Termination" -> HeylanaLog.state("assemblyai: terminated")
        }
    }

    /**
     * One piece of the shared microphone's audio, on the recording thread. Gathered into
     * pieces of [CHUNK_BYTES] (AssemblyAI takes 50 to 1000ms a message) and sent, or kept
     * until the socket is open.
     */
    fun feed(piece: ByteArray) {
        if (abandoned || !listening || stage == Stage.FAILED) return
        val chunk = synchronized(early) {
            pending.write(piece)
            if (pending.size() < CHUNK_BYTES) return
            pending.toByteArray().also { pending.reset() }
        }
        sendOrKeep(chunk)
    }

    private fun sendOrKeep(chunk: ByteArray) {
        val live = socket.takeIf { stage == Stage.READY }
        if (live != null) {
            live.send(chunk.toByteString())
            return
        }
        synchronized(early) {
            early.addLast(chunk)
            earlyBytes += chunk.size
            while (earlyBytes > DeepgramEars.MAX_EARLY_BYTES && early.isNotEmpty()) earlyBytes -= early.removeFirst().size
        }
    }

    private fun flushEarly(webSocket: WebSocket) {
        val chunks = synchronized(early) {
            val all = early.toList()
            early.clear()
            earlyBytes = 0
            all
        }
        if (chunks.isEmpty()) return
        HeylanaLog.state("assemblyai: flushed ${chunks.sumOf { it.size } / DeepgramEars.BYTES_PER_MS}ms recorded while connecting")
        for (chunk in chunks) webSocket.send(chunk.toByteString())
    }

    /**
     * The user let go. The trailing audio still arrives through [feed]; then the turn is
     * ended on purpose and the final waited for, as Deepgram's Finalize is.
     */
    override fun release(): Boolean {
        released = true
        if (!listening || abandoned) return false
        val releasedAt = SystemClock.elapsedRealtime()
        scope.launch(Dispatchers.IO) {
            Thread.sleep(DeepgramEars.TRAILING_MS)
            // The last few milliseconds, padded to AssemblyAI's shortest piece.
            val rest = synchronized(early) { pending.toByteArray().also { pending.reset() } }
            if (rest.isNotEmpty()) sendOrKeep(if (rest.size >= MIN_CHUNK_BYTES) rest else rest.copyOf(MIN_CHUNK_BYTES))
            // Still connecting (a cold token and socket take a second or two): what was said is
            // kept, so the socket is worth a short wait before the final is asked for.
            val waitFrom = SystemClock.elapsedRealtime()
            while ((stage == Stage.TOKEN || stage == Stage.SOCKET) && !abandoned &&
                SystemClock.elapsedRealtime() - waitFrom < OPEN_WAIT_MS
            ) Thread.sleep(POLL_MS)
            if (SystemClock.elapsedRealtime() - waitFrom > POLL_MS) {
                HeylanaLog.state("assemblyai: waited ${SystemClock.elapsedRealtime() - waitFrom}ms for the socket stage=$stage")
            }
            val live = socket
            if (live == null || stage != Stage.READY) {
                HeylanaLog.state("assemblyai: no final reason=${failure ?: "socket_not_open"} stage=$stage")
                listening = false
                finish()
                return@launch
            }
            endpointAskedAt = SystemClock.elapsedRealtime()
            live.send(FORCE_ENDPOINT)
            HeylanaLog.state("assemblyai: endpoint asked trailing_ms=${DeepgramEars.TRAILING_MS}")
            while (!endpointed && socket != null && !abandoned &&
                SystemClock.elapsedRealtime() - endpointAskedAt < FINAL_WAIT_MS
            ) Thread.sleep(POLL_MS)
            if (abandoned) return@launch
            val waited = SystemClock.elapsedRealtime() - endpointAskedAt
            val words = words()
            when {
                endpointed && words.isNotEmpty() -> {
                    finalMillis = SystemClock.elapsedRealtime() - releasedAt
                    HeylanaLog.state(
                        "assemblyai: final after_ms=$waited words=${words.split(Regex("\\s+")).size} " +
                            "confidence=${confidence?.let { "%.2f".format(it) } ?: "none"}"
                    )
                }
                words.isNotEmpty() -> {
                    finalMillis = SystemClock.elapsedRealtime() - releasedAt
                    HeylanaLog.state("assemblyai: no end of turn after_ms=$waited, words kept")
                }
                else -> HeylanaLog.state("assemblyai: no final reason=${if (endpointed) "no_speech" else "timeout"} after_ms=$waited")
            }
            socket?.send(TERMINATE)
            if (listening) {
                listening = false
                finish()
            }
        }
        return true
    }

    /** One place where the words are handed over. */
    private fun finish() {
        listening = false
        socket?.close(NORMAL_CLOSE, null)
        socket = null
        if (abandoned) return
        val words = words()
        if (words.isEmpty()) callbacks.onNothingHeard() else callbacks.onFinal(words)
    }

    /** The touch was a tap or a drag after all: close it, quietly. */
    fun discard() {
        if (stage == Stage.IDLE) return
        HeylanaLog.state("assemblyai: not a hold, closing")
        cancel()
    }

    override fun cancel() {
        abandoned = true
        listening = false
        stage = Stage.IDLE
        synchronized(early) {
            early.clear()
            earlyBytes = 0
            pending.reset()
        }
        socket?.close(NORMAL_CLOSE, null)
        socket?.cancel()
        socket = null
    }

    override fun shutdown() = cancel()

    companion object {
        /** 100ms of 16-bit mono at 16kHz a message. */
        const val CHUNK_BYTES = DeepgramEars.BYTES_PER_MS * 100

        /** AssemblyAI's shortest piece: 50ms. */
        const val MIN_CHUNK_BYTES = DeepgramEars.BYTES_PER_MS * 50

        /** How long a socket still opening at the release is waited for. */
        const val OPEN_WAIT_MS = 1_500L

        /** How long the end of the turn is waited for once asked. */
        const val FINAL_WAIT_MS = 1_500L

        private const val POLL_MS = 20L
        private const val NORMAL_CLOSE = 1000

        const val ASSEMBLY_LISTEN = "wss://streaming.assemblyai.com/v3/ws"

        val FORCE_ENDPOINT: String = JSONObject().put("type", "ForceEndpoint").toString()
        val TERMINATE: String = JSONObject().put("type", "Terminate").toString()
    }
}

/** The socket's address: 16-bit PCM at [sampleRate], turns formatted, the keyterms, the single-use token. */
internal fun assemblyUrl(keyterms: List<String>, sampleRate: Int, token: String): String = buildString {
    append(AssemblyEars.ASSEMBLY_LISTEN)
    append("?sample_rate=").append(sampleRate)
    append("&encoding=pcm_s16le")
    append("&format_turns=true")
    if (keyterms.isNotEmpty()) {
        // A JSON array of strings, written by hand so it is the same on the JVM and the phone.
        val array = keyterms.joinToString(",", "[", "]") { "\"" + it.replace("\\", "\\\\").replace("\"", "\\\"") + "\"" }
        append("&keyterms_prompt=").append(URLEncoder.encode(array, "UTF-8"))
    }
    append("&token=").append(URLEncoder.encode(token, "UTF-8"))
}
