package xyz.heylana.app.brain

import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import xyz.heylana.app.BuildConfig
import xyz.heylana.app.net.Proxy
import xyz.heylana.app.settings.HeylanaSettings
import java.io.IOException

/** A multi-step task the model says it is walking the user through. */
data class TaskState(val goal: String, val done: Boolean)

/** What the buddy says back, or why it could not. */
sealed interface BrainReply {
    /**
     * [pointAt] is the id of the one screen element the answer is about, or null.
     * It is taken on trust here and checked against the live snapshot by the caller.
     * [task] is non-null only when the model treated the request as a task.
     */
    data class Say(val text: String, val pointAt: Int?, val task: TaskState?) : BrainReply

    data class Failed(val message: String) : BrainReply
}

/**
 * Asks the question.
 *
 * Everything goes through Heylana's proxy, which is where the keys are: the app
 * says what kind of work it is — a quick question or the step of a task — and
 * the proxy chooses the model. The app cannot name one, and does not hold a key
 * to send with it.
 *
 * The one exception is the hidden "use my own key" setting, which talks to
 * Anthropic directly with a key the user typed in themselves. That key is read
 * from [HeylanaSettings] at call time and never logged or written anywhere else.
 */
class ProxyClient(private val settings: HeylanaSettings) {

    private val proxy = Proxy(settings)

    /** Opens the connection to whichever host the next request will go to. */
    suspend fun warmUp() {
        if (settings.useOwnKey) return
        proxy.warmUp()
    }

    /**
     * An ordinary question, optionally with the recent conversation for context.
     * Quick work — the proxy sends it to the cheap model. This also covers the
     * first turn of a task, since nothing knows it is a task until the reply
     * comes back.
     */
    suspend fun ask(
        question: String,
        screenText: String,
        history: String? = null,
        greeting: String? = null
    ): BrainReply =
        send(HeylanaPrompt.userMessage(screenText, question, history, greeting), MODE_QUICK)

    /** The next step of a task already under way. The proxy uses the stronger model. */
    suspend fun nextStep(
        goal: String,
        historyText: String,
        screenText: String,
        stepNumber: Int,
        needPointerHint: Boolean
    ): BrainReply = send(
        HeylanaPrompt.stepMessage(goal, historyText, screenText, stepNumber, needPointerHint),
        MODE_TASK
    )

    private suspend fun send(userMessage: String, mode: String): BrainReply = withContext(Dispatchers.IO) {
        val ownKey = settings.useOwnKey
        val request = if (ownKey) ownKeyRequest(userMessage, mode) else proxyRequest(userMessage, mode)
        if (request == null) {
            return@withContext BrainReply.Failed(if (ownKey) NO_KEY else NO_PROXY)
        }

        val warmed = proxy.warm
        val started = SystemClock.elapsedRealtime()
        try {
            Proxy.http.newCall(request).execute().use { response ->
                // execute() returns as the response headers land, which is the
                // first byte back — the part a warm connection actually changes.
                logFirstByte(SystemClock.elapsedRealtime() - started, warmed)
                proxy.spendWarmth()
                val body = response.body.string()
                if (!response.isSuccessful) {
                    return@withContext BrainReply.Failed(httpError(response.code, body))
                }
                logUsage(mode, body)
                extractReply(body)
            }
        } catch (e: IOException) {
            BrainReply.Failed("Couldn't reach Heylana: ${e.message ?: "no connection"}")
        } catch (_: Exception) {
            BrainReply.Failed("Something went wrong reading the reply.")
        }
    }

    /** What the app sends: the kind of work, not the model. */
    private fun proxyRequest(userMessage: String, mode: String): Request? {
        if (!proxy.isConfigured) return null
        return proxy.post("chat", payload(userMessage).put("mode", mode).toString())
    }

    /** The hidden way round: straight to Anthropic with the user's own key. */
    private fun ownKeyRequest(userMessage: String, mode: String): Request? {
        val key = settings.apiKey ?: return null
        val model = if (mode == MODE_TASK) {
            HeylanaSettings.DEFAULT_TASK_MODEL
        } else {
            HeylanaSettings.DEFAULT_QUICK_MODEL
        }
        return Request.Builder()
            .url(ANTHROPIC_ENDPOINT)
            .addHeader("x-api-key", key)
            .addHeader("anthropic-version", ANTHROPIC_VERSION)
            .addHeader("content-type", "application/json")
            .post(payload(userMessage).put("model", model).toString().toRequestBody(JSON))
            .build()
    }

    private fun payload(userMessage: String): JSONObject = JSONObject()
        .put("max_tokens", MAX_TOKENS)
        .put("system", HeylanaPrompt.SYSTEM)
        .put(
            "messages",
            JSONArray().put(JSONObject().put("role", "user").put("content", userMessage))
        )

    /**
     * Debug builds only: how long the request waited for its first byte back, and
     * whether the connection had been opened in advance. Timings only.
     */
    private fun logFirstByte(millis: Long, warmed: Boolean) {
        if (!BuildConfig.DEBUG) return
        Log.d(Proxy.USAGE_TAG, "first_byte_ms=$millis warmed=$warmed")
    }

    /**
     * Debug builds only: prints how many input tokens a request cost, so the
     * operator can watch the budget in Logcat. Counts and the model name only —
     * never a word of the screen or the conversation.
     */
    private fun logUsage(mode: String, body: String) {
        if (!BuildConfig.DEBUG) return
        val usage = runCatching { JSONObject(body).optJSONObject("usage") }.getOrNull() ?: return
        Log.d(
            Proxy.USAGE_TAG,
            "mode=$mode input_tokens=${usage.optInt("input_tokens", -1)} " +
                "output_tokens=${usage.optInt("output_tokens", -1)}"
        )
    }

    /** Short and readable, never a stack trace, and never a key. */
    private fun httpError(code: Int, body: String): String {
        val reason = runCatching { JSONObject(body).optString("reason") }.getOrDefault("")
        QuotaMessage.forReason(reason)?.let { return it }
        val detail = runCatching {
            JSONObject(body).optJSONObject("error")?.optString("message").orEmpty()
        }.getOrDefault("")
        return if (detail.isBlank()) "Something went wrong ($code)." else "Error $code: $detail"
    }

    /** First text block → strip fences → parse the JSON → fall back to raw text. */
    private fun extractReply(body: String): BrainReply {
        val content = JSONObject(body).optJSONArray("content")
            ?: return BrainReply.Say(body.trim(), null, null)

        var raw: String? = null
        for (i in 0 until content.length()) {
            val block = content.optJSONObject(i) ?: continue
            if (block.optString("type") == "text") {
                raw = block.optString("text")
                break
            }
        }
        val text = raw?.trim().orEmpty()
        if (text.isEmpty()) {
            return BrainReply.Say("Heylana had nothing to say about this screen.", null, null)
        }

        val unfenced = stripFences(text)
        val json = runCatching { JSONObject(unfenced) }.getOrNull()
            ?: return BrainReply.Say(unfenced, null, null)

        val say = json.optString("say").trim()
        return if (say.isEmpty()) {
            BrainReply.Say(unfenced, null, null)
        } else {
            BrainReply.Say(say, readPointAt(json), readTask(json))
        }
    }

    /**
     * Missing, null, not an object, or without a usable goal all mean "not a task",
     * so a malformed reply degrades to an ordinary one-shot answer.
     */
    private fun readTask(json: JSONObject): TaskState? {
        if (!json.has("task") || json.isNull("task")) return null
        val task = json.optJSONObject("task") ?: return null
        val goal = task.optString("goal").trim()
        if (goal.isEmpty()) return null
        return TaskState(goal = goal, done = task.optBoolean("done", false))
    }

    /**
     * Missing, null, non-numeric or negative all mean "do not point at anything".
     * Whether the id actually exists on screen is the caller's check.
     */
    private fun readPointAt(json: JSONObject): Int? {
        if (!json.has("point_at") || json.isNull("point_at")) return null
        val id = json.optInt("point_at", -1)
        return if (id >= 0) id else null
    }

    private fun stripFences(text: String): String {
        if (!text.startsWith("```")) return text
        return text
            .removePrefix("```json")
            .removePrefix("```")
            .removeSuffix("```")
            .trim()
    }

    companion object {
        private const val ANTHROPIC_ENDPOINT = "https://api.anthropic.com/v1/messages"
        private const val ANTHROPIC_VERSION = "2023-06-01"
        private const val MAX_TOKENS = 300

        /** What the app is allowed to say about the work. The proxy picks the model. */
        const val MODE_QUICK = "quick"
        const val MODE_TASK = "task"

        private const val NO_PROXY =
            "Heylana is not set up yet. Whoever built this app needs to add the proxy address."
        private const val NO_KEY =
            "No API key yet. Add your key in Heylana \u2192 Settings, or switch off Use my own key."

        private val JSON = "application/json".toMediaType()
    }
}
