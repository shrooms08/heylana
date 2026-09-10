package xyz.heylana.app.brain

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import xyz.heylana.app.BuildConfig
import xyz.heylana.app.settings.HeylanaSettings
import java.io.IOException
import java.util.concurrent.TimeUnit

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
 * Talks to the Anthropic Messages API.
 *
 * The API key is read from [HeylanaSettings] at call time and never stored here,
 * never logged, and never written anywhere else.
 */
class AnthropicClient(private val settings: HeylanaSettings) {

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .callTimeout(75, TimeUnit.SECONDS)
        .build()

    /**
     * An ordinary question, optionally with the recent conversation for context.
     * Runs on the quick model — this also covers the first turn of a task, since
     * nothing knows it is a task until the reply comes back.
     */
    suspend fun ask(question: String, screenText: String, history: String? = null): BrainReply =
        send(HeylanaPrompt.userMessage(screenText, question, history), settings.quickModel)

    /** The next step of a task already under way. Runs on the stronger model. */
    suspend fun nextStep(
        goal: String,
        historyText: String,
        screenText: String,
        stepNumber: Int,
        needPointerHint: Boolean
    ): BrainReply = send(
        HeylanaPrompt.stepMessage(goal, historyText, screenText, stepNumber, needPointerHint),
        settings.taskModel
    )

    private suspend fun send(userMessage: String, model: String): BrainReply = withContext(Dispatchers.IO) {
        val key = settings.apiKey
        if (key.isNullOrBlank()) {
            return@withContext BrainReply.Failed("No API key yet. Add your key in Heylana → Settings.")
        }

        val payload = JSONObject()
            .put("model", model)
            .put("max_tokens", MAX_TOKENS)
            .put("system", HeylanaPrompt.SYSTEM)
            .put(
                "messages",
                JSONArray().put(
                    JSONObject().put("role", "user").put("content", userMessage)
                )
            )

        val request = Request.Builder()
            .url(ENDPOINT)
            .addHeader("x-api-key", key)
            .addHeader("anthropic-version", ANTHROPIC_VERSION)
            .addHeader("content-type", "application/json")
            .post(payload.toString().toRequestBody(JSON))
            .build()

        try {
            http.newCall(request).execute().use { response ->
                val body = response.body.string()
                if (!response.isSuccessful) {
                    return@withContext BrainReply.Failed(httpError(response.code, body))
                }
                logUsage(model, body)
                extractReply(body)
            }
        } catch (e: IOException) {
            BrainReply.Failed("Couldn't reach the API: ${e.message ?: "no connection"}")
        } catch (_: Exception) {
            BrainReply.Failed("Something went wrong reading the reply.")
        }
    }

    /**
     * Debug builds only: prints how many input tokens a request cost, so the
     * operator can watch the budget in Logcat. Counts and the model name only —
     * never a word of the screen or the conversation.
     */
    private fun logUsage(model: String, body: String) {
        if (!BuildConfig.DEBUG) return
        val usage = runCatching { JSONObject(body).optJSONObject("usage") }.getOrNull() ?: return
        Log.d(
            USAGE_TAG,
            "model=$model input_tokens=${usage.optInt("input_tokens", -1)} " +
                "output_tokens=${usage.optInt("output_tokens", -1)}"
        )
    }

    /** "401: invalid x-api-key" — short and readable, never a stack trace. */
    private fun httpError(code: Int, body: String): String {
        val detail = runCatching {
            JSONObject(body).optJSONObject("error")?.optString("message").orEmpty()
        }.getOrDefault("")
        return if (detail.isBlank()) "API error $code." else "API error $code: $detail"
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
        private const val ENDPOINT = "https://api.anthropic.com/v1/messages"
        private const val ANTHROPIC_VERSION = "2023-06-01"
        private const val MAX_TOKENS = 300

        /** Logcat tag for the token counter. */
        const val USAGE_TAG = "HeylanaTokens"
        private val JSON = "application/json".toMediaType()
    }
}
