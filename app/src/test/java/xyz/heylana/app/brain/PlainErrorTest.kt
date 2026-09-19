package xyz.heylana.app.brain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLHandshakeException

class PlainErrorTest {

    @Test
    fun `a model that failed upstream, or any 429 or 529, is the brain`() {
        assertEquals(PlainError.BRAIN, PlainError.forChat(502, PlainError.BRAIN_UNAVAILABLE))
        assertEquals(PlainError.BRAIN, PlainError.forChat(429, "rate_limited"))
        assertEquals(PlainError.BRAIN, PlainError.forChat(529, ""))
    }

    @Test
    fun `the worker's own failures are Heylana's side`() {
        assertEquals(PlainError.OUR_SIDE, PlainError.forChat(500, "internal"))
        assertEquals(PlainError.OUR_SIDE, PlainError.forChat(503, ""))
        assertEquals(PlainError.OUR_SIDE, PlainError.forChat(400, "bad_request"))
    }

    @Test
    fun `Heylana's own caps and the user's key keep their lines`() {
        assertEquals(QuotaMessage.TALKS_CAP, PlainError.forChat(429, "talks_cap"))
        assertEquals(QuotaMessage.DAILY_CAP, PlainError.forChat(429, "daily_cap"))
        assertEquals(QuotaMessage.OWN_KEY_REFUSED, PlainError.forChat(401, "own_key_refused"))
    }

    @Test
    fun `no network is No connection, a timeout is the brain`() {
        assertEquals(PlainError.Kind.OFFLINE, PlainError.forIo(UnknownHostException("heylana-proxy.heylana.workers.dev")))
        assertEquals(PlainError.Kind.OFFLINE, PlainError.forIo(ConnectException("failed to connect")))
        assertEquals(PlainError.Kind.OFFLINE, PlainError.forIo(SSLHandshakeException("x")))
        assertEquals(PlainError.Kind.BRAIN, PlainError.forIo(SocketTimeoutException("timeout")))
        assertEquals(PlainError.Kind.BRAIN, PlainError.forIo(IOException("canceled", SocketTimeoutException())))
    }

    @Test
    fun `an ear's failure is I didn't catch that, unless the user can fix it`() {
        assertEquals(PlainError.EARS, PlainError.forEars("Deepgram 401: {\"err_code\":\"INVALID_AUTH\"}"))
        assertEquals(PlainError.EARS, PlainError.forEars("Speech recognition error 7"))
        assertEquals(PlainError.OFFLINE, PlainError.forEars(PlainError.OFFLINE))
        assertEquals(xyz.heylana.app.voice.Listener.NO_PERMISSION, PlainError.forEars(xyz.heylana.app.voice.Listener.NO_PERMISSION))
    }

    @Test
    fun `the log line carries kind, status and a reason word, never a body`() {
        assertEquals("error: kind=brain status=502 reason=brain_unavailable", PlainError.logLine(PlainError.Kind.BRAIN, 502, "brain_unavailable"))
        val body = PlainError.logLine(PlainError.Kind.OUR_SIDE, 500, "{\"error\":{\"message\":\"sk-ant-123 leaked\"}}")
        assertFalse(body, body.contains("sk-ant"))
        assertEquals("error: kind=our_side status=500 reason=none", body)
    }

    @Test
    fun `the five lines, word for word`() {
        assertEquals("I can't reach my brain right now. Try again in a moment.", PlainError.BRAIN)
        assertEquals("Voice is over its limit; text only for now.", PlainError.VOICE_LIMIT)
        assertEquals("I didn't catch that.", PlainError.EARS)
        assertEquals("No connection.", PlainError.OFFLINE)
        assertEquals("Something went wrong on my side.", PlainError.OUR_SIDE)
    }
}
