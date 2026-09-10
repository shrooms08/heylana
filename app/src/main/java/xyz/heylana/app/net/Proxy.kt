package xyz.heylana.app.net

import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import xyz.heylana.app.BuildConfig
import xyz.heylana.app.HeylanaLog
import xyz.heylana.app.settings.HeylanaSettings
import java.net.URI
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/**
 * Where Heylana's keys live — which is to say, not here.
 *
 * The phone knows one address and its own device id. Everything that needs a
 * key — the answers, the voice, the ears — goes through the proxy, which adds
 * the key on the other side. See `worker/README.md`.
 *
 * The address comes from `local.properties` at build time, and can be pointed
 * somewhere else in the advanced settings for testing against a stub.
 */
class Proxy(private val settings: HeylanaSettings) {

    /** The proxy's address, without a trailing slash. Empty means "not set up". */
    val url: String
        get() = settings.proxyUrlOverride.ifEmpty { BuildConfig.PROXY_URL }.trimEnd('/')

    val isConfigured: Boolean
        get() = url.startsWith("http://") || url.startsWith("https://")

    /** A POST to one of the proxy's routes, carrying this install's id. */
    fun post(path: String, json: String): Request = Request.Builder()
        .url("$url/${path.trimStart('/')}")
        .addHeader(DEVICE_HEADER, settings.deviceId)
        .addHeader("content-type", "application/json")
        .post(json.toRequestBody(JSON))
        .build()

    /**
     * Opens the connection before there is anything to send, so the request
     * that follows does not wait for DNS, for the radio to come out of idle, or
     * for a full TLS handshake.
     *
     * **No request is made.** No body, no headers, no device id: a socket and a
     * handshake to a hostname, then it closes.
     */
    suspend fun warmUp() = withContext(Dispatchers.IO) {
        if (!settings.warmUpConnection) return@withContext
        if (!isConfigured) return@withContext
        if (warm) return@withContext

        val host = runCatching { URI(url).host }.getOrNull() ?: return@withContext
        val started = SystemClock.elapsedRealtime()
        val opened = runCatching {
            val factory = SSLSocketFactory.getDefault() as SSLSocketFactory
            (factory.createSocket(host, HTTPS_PORT) as SSLSocket).use { socket ->
                socket.soTimeout = WARM_TIMEOUT_MS
                socket.startHandshake()
            }
        }.isSuccess
        if (opened) warmedAt = SystemClock.elapsedRealtime()
        if (BuildConfig.DEBUG) {
            Log.d(USAGE_TAG, "warmup opened=$opened in ${SystemClock.elapsedRealtime() - started}ms")
        }
    }

    /** True while a connection opened in advance is still worth counting on. */
    val warm: Boolean
        get() = warmedAt != 0L && SystemClock.elapsedRealtime() - warmedAt < WARM_TTL_MS

    /** A request has now used it, whatever it was. */
    fun spendWarmth() {
        warmedAt = 0L
    }

    /**
     * Borrowed ears: a Deepgram key that stops working two minutes from now.
     * Null when the proxy is unreachable or has run the day's allowance out.
     *
     * The key is kept until it is nearly out of time, so holding the buddy twice
     * in a minute does not mint twice — the first hold pays for the round trip
     * and the second one starts listening immediately.
     */
    suspend fun sttToken(): String? = withContext(Dispatchers.IO) {
        if (!isConfigured) return@withContext null

        borrowed?.let { key ->
            if (SystemClock.elapsedRealtime() < borrowedUntil) {
                HeylanaLog.state("proxy: stt-token from cache")
                return@withContext key
            }
        }

        val minted = runCatching {
            http.newCall(post("stt-token", "{}")).execute().use { response ->
                if (!response.isSuccessful) {
                    HeylanaLog.state("proxy: stt-token refused ${response.code}")
                    return@use null
                }
                org.json.JSONObject(response.body.string())
                    .optString("key")
                    .takeIf { it.isNotBlank() }
            }
        }.getOrNull()

        if (minted != null) {
            borrowed = minted
            borrowedUntil = SystemClock.elapsedRealtime() + STT_KEY_KEEP_MS
        }
        minted
    }

    /** The last borrowed key is no good any more — the socket said so. */
    fun forgetSttToken() {
        borrowed = null
        borrowedUntil = 0L
    }

    companion object {
        const val DEVICE_HEADER = "X-Heylana-Device"

        /** Logcat tag for the token counter, kept from before the proxy. */
        const val USAGE_TAG = "HeylanaTokens"

        private const val HTTPS_PORT = 443
        private const val WARM_TTL_MS = 60_000L
        private const val WARM_TIMEOUT_MS = 5_000

        private val JSON = "application/json".toMediaType()

        /**
         * The worker mints these for two minutes; keeping them for ninety
         * seconds leaves room for the hold that is already under way.
         */
        private const val STT_KEY_KEEP_MS = 90_000L

        @Volatile
        private var warmedAt = 0L

        @Volatile
        private var borrowed: String? = null

        @Volatile
        private var borrowedUntil = 0L

        /**
         * One client for questions, voice and ears alike: they share a host, so
         * they share a connection, and warming it once warms it for all three.
         */
        val http: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .callTimeout(75, TimeUnit.SECONDS)
                .build()
        }
    }
}
