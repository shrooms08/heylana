package xyz.heylana.app.skills

import xyz.heylana.app.Features
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.URI
import java.util.concurrent.TimeUnit

/**
 * The public list of skills to install: a JSON file anyone can read, at the address
 * in `BuildConfig.SKILLS_INDEX_URL`.
 *
 * ```
 * {"skills": [{"id": "chrome", "name": "Chrome", "summary": "…", "version": "1", "url": "skills/chrome.md"}]}
 * ```
 *
 * A `url` may be relative to the index itself, so the whole folder can move to
 * another repository without editing a line. Only https is fetched (plus loopback
 * in debug builds, for a local test server). Nothing about the user goes with the
 * request: no device id, no session, no header of Heylana's.
 */
object SkillIndex {

    data class Entry(val id: String, val name: String, val summary: String, val version: String, val url: String)

    private const val MAX_ENTRIES = 50
    private val ID = Regex("^[a-z0-9][a-z0-9-]{0,39}$")

    /** One entry as it came out of the JSON, before any checking. */
    data class Raw(val id: String, val name: String, val summary: String, val version: String, val url: String)

    fun parse(json: String, indexUrl: String, allowLoopback: Boolean = false): List<Entry> {
        val list = runCatching {
            val trimmed = json.trim()
            if (trimmed.startsWith("[")) JSONArray(trimmed) else JSONObject(trimmed).getJSONArray("skills")
        }.getOrNull() ?: return emptyList()
        val raw = (0 until list.length()).mapNotNull { i ->
            list.optJSONObject(i)?.let {
                Raw(it.optString("id"), it.optString("name"), it.optString("summary"), it.optString("version"), it.optString("url"))
            }
        }
        return entries(raw, indexUrl, allowLoopback)
    }

    /** Checks each entry and resolves its url against the index's own address. */
    fun entries(raw: List<Raw>, indexUrl: String, allowLoopback: Boolean = false): List<Entry> {
        val base = runCatching { URI(indexUrl) }.getOrNull() ?: return emptyList()
        return raw.mapNotNull { item ->
            val name = item.name.trim()
            val given = item.url.trim()
            if (!ID.matches(item.id) || name.isEmpty() || given.isEmpty()) return@mapNotNull null
            val url = runCatching { base.resolve(given).toString() }.getOrNull() ?: return@mapNotNull null
            if (!fetchable(url, allowLoopback)) return@mapNotNull null
            Entry(item.id, name.take(MAX_TEXT), item.summary.trim().take(MAX_TEXT), item.version.take(MAX_TEXT), url)
        }.distinctBy { it.id }.take(MAX_ENTRIES)
    }

    fun fetchable(url: String, allowLoopback: Boolean): Boolean {
        val uri = runCatching { URI(url) }.getOrNull() ?: return false
        return when (uri.scheme) {
            "https" -> !uri.host.isNullOrEmpty()
            "http" -> allowLoopback && (uri.host == "127.0.0.1" || uri.host == "localhost")
            else -> false
        }
    }

    sealed interface Fetched {
        data class Ok(val text: String) : Fetched
        data class Failed(val why: String) : Fetched
    }

    private val http: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .callTimeout(20, TimeUnit.SECONDS)
            .followRedirects(false)
            .build()
    }

    /** A plain GET, capped at [SkillFile.MAX_FILE_BYTES] for a skill or [MAX_INDEX_BYTES] for the index. */
    suspend fun fetch(url: String, maxBytes: Int, allowLoopback: Boolean): Fetched = withContext(Dispatchers.IO) {
        // The Skill market is on the roadmap: nothing is downloaded while it is off.
        if (!Features.SKILL_MARKET) return@withContext Fetched.Failed("market_off")
        if (!fetchable(url, allowLoopback)) return@withContext Fetched.Failed("not_https")
        try {
            http.newCall(Request.Builder().url(url).get().build()).execute().use { response ->
                if (!response.isSuccessful) return@withContext Fetched.Failed("http_${response.code}")
                val bytes = response.body.source().use { source ->
                    source.request(maxBytes.toLong() + 1)
                    source.buffer.readByteArray(minOf(source.buffer.size, maxBytes.toLong() + 1))
                }
                if (bytes.size > maxBytes) Fetched.Failed("too_big") else Fetched.Ok(String(bytes, Charsets.UTF_8))
            }
        } catch (e: IOException) {
            Fetched.Failed(e::class.simpleName ?: "IOException")
        }
    }

    const val MAX_INDEX_BYTES = 64_000
    private const val MAX_TEXT = 160
}
