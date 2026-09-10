package xyz.heylana.app.brain

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import xyz.heylana.app.settings.HeylanaSettings
import java.io.IOException
import java.util.concurrent.TimeUnit

/** What the buddy says back, or why it could not. */
sealed interface BrainReply {
    data class Say(val text: String) : BrainReply
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

    suspend fun ask(question: String, screenText: String): BrainReply = withContext(Dispatchers.IO) {
        val key = settings.apiKey
        if (key.isNullOrBlank()) {
            return@withContext BrainReply.Failed("No API key yet. Add your key in Heylana → Settings.")
        }

        val payload = JSONObject()
            .put("model", settings.model)
            .put("max_tokens", MAX_TOKENS)
            .put("system", HeylanaPrompt.SYSTEM)
            .put(
                "messages",
                JSONArray().put(
                    JSONObject()
                        .put("role", "user")
                        .put("content", HeylanaPrompt.userMessage(screenText, question))
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
                BrainReply.Say(extractSay(body))
            }
        } catch (e: IOException) {
            BrainReply.Failed("Couldn't reach the API: ${e.message ?: "no connection"}")
        } catch (_: Exception) {
            BrainReply.Failed("Something went wrong reading the reply.")
        }
    }

    /** "401: invalid x-api-key" — short and readable, never a stack trace. */
    private fun httpError(code: Int, body: String): String {
        val detail = runCatching {
            JSONObject(body).optJSONObject("error")?.optString("message").orEmpty()
        }.getOrDefault("")
        return if (detail.isBlank()) "API error $code." else "API error $code: $detail"
    }

    /** First text block → strip fences → parse {"say": ...} → fall back to raw text. */
    private fun extractSay(body: String): String {
        val content = JSONObject(body).optJSONArray("content") ?: return body.trim()
        var raw: String? = null
        for (i in 0 until content.length()) {
            val block = content.optJSONObject(i) ?: continue
            if (block.optString("type") == "text") {
                raw = block.optString("text")
                break
            }
        }
        val text = raw?.trim().orEmpty()
        if (text.isEmpty()) return "Heylana had nothing to say about this screen."

        val unfenced = stripFences(text)
        val parsed = runCatching { JSONObject(unfenced).optString("say") }.getOrNull()
        return if (!parsed.isNullOrBlank()) parsed else unfenced
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
        private const val MAX_TOKENS = 600
        private val JSON = "application/json".toMediaType()
    }
}
