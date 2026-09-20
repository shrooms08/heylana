package xyz.heylana.app.screen

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import xyz.heylana.app.HeylanaLog
import xyz.heylana.app.net.Proxy
import xyz.heylana.app.settings.HeylanaSettings
import java.io.File

/**
 * The phishing blocklist, kept on the phone.
 *
 * The worker hands over the **whole list** once a day and the phone checks against it
 * itself. That is the point: no address bar, no domain and no page anyone visited is ever
 * sent anywhere to be checked — not to Heylana, not to anyone. The only thing that goes
 * out is "which version do I have", and the answer is a date.
 *
 * It is kept in the app's own private file, read once into memory when the buddy starts.
 * A list that cannot be fetched is not an error: the look-alike check and the recovery
 * phrase check work without it, and they are the two that catch the newest scams anyway.
 */
class Blocklist(private val context: Context, private val settings: HeylanaSettings) {

    private val proxy = Proxy(settings)

    /** The domains, or empty until there are any. Read from many threads, replaced whole. */
    @Volatile
    var domains: Set<String> = emptySet()
        private set

    private val file: File get() = File(context.filesDir, FILE_NAME)

    /** What is already on the phone, straight away, so the first screen is covered. */
    fun loadFromDisk() {
        val stored = runCatching { file.takeIf { it.exists() }?.readText() }.getOrNull() ?: return
        domains = stored.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toSet()
        HeylanaLog.state("lookout: list from disk domains=${domains.size} version=${settings.blocklistVersion}")
    }

    /**
     * Asks the worker for a newer list, at most once a day. Cheap when there is nothing
     * new: the phone says which version it holds and gets a few bytes back.
     */
    suspend fun refresh(now: Long = System.currentTimeMillis()) = withContext(Dispatchers.IO) {
        if (!proxy.isConfigured) return@withContext
        val have = settings.blocklistVersion
        if (have.isNotEmpty() && now - settings.blocklistCheckedAt < DAY_MS) return@withContext
        settings.blocklistCheckedAt = now
        val path = if (have.isEmpty()) "lookout" else "lookout?have=$have"
        try {
            Proxy.http.newCall(proxy.get(path)).execute().use { response ->
                if (!response.isSuccessful) {
                    HeylanaLog.state("lookout: list refused ${response.code}")
                    return@withContext
                }
                val body = JSONObject(response.body.string())
                if (body.optBoolean("unchanged")) {
                    HeylanaLog.state("lookout: list unchanged version=$have")
                    return@withContext
                }
                val list = body.optJSONArray("domains") ?: return@withContext
                val fetched = buildSet { for (i in 0 until list.length()) add(list.getString(i)) }
                if (fetched.isEmpty()) return@withContext
                domains = fetched
                settings.blocklistVersion = body.optString("version")
                runCatching { file.writeText(fetched.joinToString("\n")) }
                // Counts only: which domains are on a public list is not worth logging, and
                // which ones this phone sees never is.
                HeylanaLog.state("lookout: list updated domains=${fetched.size} version=${settings.blocklistVersion}")
            }
        } catch (e: Exception) {
            HeylanaLog.state("lookout: list unreachable ${e.javaClass.simpleName}")
        }
    }

    private companion object {
        const val FILE_NAME = "blocklist.txt"
        const val DAY_MS = 24 * 60 * 60 * 1000L
    }
}
