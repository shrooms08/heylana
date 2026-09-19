package xyz.heylana.app.brain

import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/**
 * Every failure, in one of five plain lines. No upstream text — a provider's error
 * message, an exception, a status page — ever reaches the screen or the voice; what
 * happened is kept for Logcat (debug builds) and Sentry.
 */
object PlainError {

    /** The model could not be reached, or is over its limit, overloaded or too slow. */
    const val BRAIN = "I can't reach my brain right now. Try again in a moment."

    /** The voice is over its limit (the day's cap, or the provider's quota). */
    const val VOICE_LIMIT = "Voice is over its limit; text only for now."

    /** The ears failed: a recogniser error, a refused or broken socket. */
    const val EARS = "I didn't catch that."

    /** The phone has no network. */
    const val OFFLINE = "No connection."

    /** Heylana's own server failed. */
    const val OUR_SIDE = "Something went wrong on my side."

    /** The worker's reason for a model that failed upstream: its status rides along, never its words. */
    const val BRAIN_UNAVAILABLE = "brain_unavailable"

    enum class Kind { BRAIN, VOICE_LIMIT, EARS, OFFLINE, OUR_SIDE }

    fun line(kind: Kind): String = when (kind) {
        Kind.BRAIN -> BRAIN
        Kind.VOICE_LIMIT -> VOICE_LIMIT
        Kind.EARS -> EARS
        Kind.OFFLINE -> OFFLINE
        Kind.OUR_SIDE -> OUR_SIDE
    }

    /**
     * A refused /chat. Heylana's own caps and the user's key keep their plain lines
     * ([QuotaMessage]); a model that failed upstream, or any 429 or 529, is the brain; any
     * other 5xx, and any 4xx the app caused, is Heylana's side.
     */
    fun forChat(code: Int, reason: String): String {
        QuotaMessage.forReason(reason)?.let { return it }
        return line(chatKind(code, reason))
    }

    fun chatKind(code: Int, reason: String): Kind = when {
        reason == BRAIN_UNAVAILABLE -> Kind.BRAIN
        code == 429 || code == 529 -> Kind.BRAIN
        else -> Kind.OUR_SIDE
    }

    /** A request that never got an answer: no network is "No connection."; a timeout is the brain. */
    fun forIo(e: IOException): Kind = when (e) {
        is SocketTimeoutException -> Kind.BRAIN
        is UnknownHostException, is ConnectException, is NoRouteToHostException -> Kind.OFFLINE
        else -> if (e.cause is SocketTimeoutException) Kind.BRAIN else Kind.OFFLINE
    }

    /**
     * What an ear's failure shows: the microphone permission line stays (the user can fix
     * it), no network is [OFFLINE], and anything else, whatever the ear said, is [EARS].
     */
    fun forEars(message: String): String = when (message) {
        xyz.heylana.app.voice.Listener.NO_PERMISSION, OFFLINE -> message
        else -> EARS
    }

    /** What went wrong, for the log: the kind, the status and the worker's reason. Never a body. */
    fun logLine(kind: Kind, code: Int?, reason: String?): String =
        "error: kind=${kind.name.lowercase()} status=${code ?: "none"} reason=${reason?.takeIf { REASON.matches(it) } ?: "none"}"

    private val REASON = Regex("[A-Za-z_]{1,40}")
}
