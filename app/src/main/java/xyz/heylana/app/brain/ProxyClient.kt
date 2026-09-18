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
import xyz.heylana.app.actions.QuickAction
import xyz.heylana.app.HeylanaLog
import xyz.heylana.app.net.Proxy
import xyz.heylana.app.settings.HeylanaSettings
import xyz.heylana.app.skills.Skill
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
    data class Say(
        val text: String,
        val pointAt: Int?,
        val task: TaskState?,
        /** A send the model proposed. Checked by SendGuard before anything happens. */
        val action: SendAction? = null,
        /** An alarm, timer, app, page, place or number. Checked by QuickGuard first. */
        val quick: QuickAction? = null,
        /**
         * How it is spoken: one piece, or up to four with an element each, so the disc can
         * walk the screen while it explains. Always says the same words as [text].
         */
        val segments: List<SaySegment> = emptyList(),
        /** The model named a quick action but left a part out: the one question to ask for it. */
        val clarify: String? = null
    ) : BrainReply {
        /** True when the answer walks the screen: more than one piece, or one that points. */
        val teaches: Boolean get() = segments.size > 1 || segments.any { it.pointAt != null }
    }

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
        greeting: String? = null,
        route: Routing.Route = Routing.PLAIN,
        knownAddresses: List<String> = emptyList(),
        skill: Skill? = null,
        teaching: Boolean = false,
        walkThrough: Boolean = false
    ): BrainReply {
        val tools = route.toolsWanted && !settings.useOwnKey
        val quickAction = route.why == Routing.Why.QUICK_ACTION
        // A send or a quick action is one forced tool call that writes nothing: app notes would only cost.
        val carried = skill.takeUnless { route.why == Routing.Why.SEND_QUESTION || quickAction || route.skipsScreen }
        // Through the worker the action tool is forced; only the own-key path has to ask in words.
        val quickRules = quickAction && settings.useOwnKey
        HeylanaLog.state(
            "brain: mode=${route.mode} why=${route.why.log} " +
                (route.solana?.let { "solana-core loaded reason=${it.log}" } ?: "solana-core not loaded") +
                " tools=${if (tools) "sent" else "not sent"} ${skillLog(carried)} " +
                "quick-action=${if (!quickAction) "no" else if (quickRules) "rules" else "forced"}"
        )
        val extra = JSONObject()
        val message = if (route.explainsSigning) {
            val found = SigningScan.of(screenText)
            // Counts only: what is on a signing screen is never written to the log.
            HeylanaLog.state(
                "sign-check: addresses=${found.addresses.size} shortened=${found.shortAddresses.size} " +
                    "amounts=${found.amounts.size} typed=${knownAddresses.size}"
            )
            // The worker matches shortened addresses before the model sees the question.
            if (tools && found.shortAddresses.isNotEmpty()) {
                extra.put(
                    "signing",
                    JSONObject().put("short", JSONArray(found.shortAddresses)).put("typed", JSONArray(knownAddresses))
                )
            }
            // Only the lookup a sign explanation uses, and none when there is no full
            // address to look up: every tool definition is read again each round.
            if (tools) {
                extra.put("tool_names", JSONArray(if (found.addresses.isNotEmpty()) listOf(EXPLAIN_ADDRESS) else emptyList()))
            }
            HeylanaPrompt.signingMessage(screenText, question, found)
        } else if (route.skipsScreen) {
            HeylanaPrompt.chatMessage(question, history, if (route.allowsGreeting) greeting else null)
        } else {
            HeylanaPrompt.userMessage(screenText, question, history, if (route.allowsGreeting) greeting else null, teaching, walkThrough)
        }
        if (walkThrough) HeylanaLog.state("teach: walk-through asked, a task if it takes taps")
        else if (teaching) HeylanaLog.state("teach: first step asked with reasons")
        // A send is never left to prose: the worker asks the model for the send only.
        if (tools && route.why == Routing.Why.SEND_QUESTION) extra.put("intent", "send")
        // Nor is an alarm, a timer, an app, a page, a place or a number.
        if (quickAction && !settings.useOwnKey) extra.put("intent", "quick_action")
        val reply = send(
            message,
            route.mode,
            solana = route.solana != null,
            tools = tools,
            extra = extra,
            skill = carried,
            signing = route.explainsSigning,
            quickRules = quickRules
        )
        // A reply that starts a task is a spoken step: under 25 words.
        val startsTask = reply is BrainReply.Say && reply.task?.done == false
        return limitLength(reply, AnswerLength.capFor(route.explainsSigning, startsTask))
    }

    /** "Why?" on the step in hand: its reason and the step again. Quick model, no screen. */
    suspend fun explainStep(goal: String, historyText: String, question: String): BrainReply {
        HeylanaLog.state("brain: mode=$MODE_QUICK why=${Routing.Why.TEACH_WHY.log} screen=not_read")
        val reply = send(HeylanaPrompt.whyMessage(goal, historyText, question), MODE_QUICK)
        return limitLength(reply, AnswerLength.STEP_WORDS)
    }

    /**
     * "What did I just do" right after a task. An on-chain one goes with the Solana rules
     * and recent_activity alone, so the recap says what actually landed.
     */
    suspend fun recap(task: FinishedTask, question: String): BrainReply {
        val route = Routing.recapRoute(task)
        val tools = route.toolsWanted && !settings.useOwnKey
        HeylanaLog.state(
            "brain: mode=${route.mode} why=${route.why.log} on_chain=${task.onChain} " +
                "tools=${if (tools) "sent names=$RECENT_ACTIVITY" else "not sent"} screen=not_read"
        )
        val extra = JSONObject()
        if (tools) extra.put("tool_names", JSONArray(listOf(RECENT_ACTIVITY)))
        val reply = send(
            HeylanaPrompt.recapMessage(task.goal, task.historyText, task.onChain, question),
            route.mode,
            solana = route.solana != null,
            tools = tools,
            extra = extra
        )
        return limitLength(reply, AnswerLength.GENERAL_WORDS)
    }

    /** The next step of a task already under way. The proxy uses the stronger model. */
    suspend fun nextStep(
        goal: String,
        historyText: String,
        screenText: String,
        stepNumber: Int,
        needPointerHint: Boolean,
        skill: Skill? = null,
        teaching: Boolean = false
    ): BrainReply {
        HeylanaLog.state("brain: mode=$MODE_TASK why=task_step teaching=$teaching ${skillLog(skill)}")
        val reply = send(
            HeylanaPrompt.stepMessage(goal, historyText, screenText, stepNumber, needPointerHint, teaching),
            MODE_TASK,
            skill = skill
        )
        // A step is spoken; a finished task's confirmation keeps the general line.
        val done = reply is BrainReply.Say && reply.task?.done == true
        return limitLength(reply, if (done) AnswerLength.GENERAL_WORDS else AnswerLength.STEP_WORDS)
    }

    /**
     * An answer over its word cap is sent back once to be said in fewer words, and the
     * shorter one is used if it really is shorter. Counts only in the log. A send
     * carries no words of its own, so it is never shortened.
     */
    private suspend fun limitLength(reply: BrainReply, cap: Int): BrainReply {
        if (reply !is BrainReply.Say || reply.action != null) return reply
        val words = AnswerLength.words(reply.text)
        if (words <= cap) return reply
        // The shorter wording is plain text by design; it still may not echo the prompt.
        val shortened = shorten(reply.text, cap)?.let { ReplyParser.dedupe(it.trim()) }
            ?.takeUnless { ReplyParser.looksLikeInstructions(it) || it.trimStart().startsWith("{") }
        val chosen = AnswerLength.better(reply.text, shortened)
        HeylanaLog.state(
            "answer: over cap words=$words cap=$cap asked_shorter=${if (shortened == null) "failed" else "ok"} " +
                "now=${AnswerLength.words(chosen)}"
        )
        val shortened_text = AddressText.shorten(chosen)
        // Shortening rewrites the whole answer, so it is one piece again; where it pointed, it still does.
        return reply.copy(
            text = shortened_text,
            segments = listOf(SaySegment(shortened_text, reply.segments.firstOrNull()?.pointAt ?: reply.pointAt))
        )
    }

    /** The shorter wording, or null if it could not be had. Never counted as a talk. */
    private suspend fun shorten(text: String, cap: Int): String? = withContext(Dispatchers.IO) {
        val clipped = text.take(SHORTEN_MAX_CHARS)
        val messages = JSONArray().put(JSONObject().put("role", "user").put("content", clipped))
        val request = if (settings.useOwnKey) {
            val key = settings.apiKey ?: return@withContext null
            val body = JSONObject()
                .put("model", HeylanaSettings.DEFAULT_QUICK_MODEL)
                .put("max_tokens", SHORTEN_MAX_TOKENS)
                .put("system", HeylanaPrompt.shortenSystem(cap))
                .put("messages", messages)
            Request.Builder()
                .url(ANTHROPIC_ENDPOINT)
                .addHeader("x-api-key", key)
                .addHeader("anthropic-version", ANTHROPIC_VERSION)
                .addHeader("content-type", "application/json")
                .post(body.toString().toRequestBody(JSON))
                .build()
        } else {
            if (!proxy.isConfigured) return@withContext null
            // The worker writes the rest of this request itself.
            val body = JSONObject().put("mode", MODE_QUICK).put("shorten", true).put("max_words", cap).put("messages", messages)
            proxy.post("chat", body.toString())
        }
        try {
            Proxy.http.newCall(request).execute().use { response ->
                val body = response.body.string()
                if (!response.isSuccessful) return@withContext null
                logUsage("shorten", body)
                firstText(body)
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun firstText(body: String): String? {
        val content = runCatching { JSONObject(body).optJSONArray("content") }.getOrNull() ?: return null
        for (i in 0 until content.length()) {
            val block = content.optJSONObject(i) ?: continue
            if (block.optString("type") == "text") return block.optString("text")
        }
        return null
    }

    /** "skill=jupiter tokens=312", or "skill=none". The id and a count, never the text. */
    private fun skillLog(skill: Skill?): String =
        skill?.let { "skill=${it.id} tokens=${it.tokens}" } ?: "skill=none"

    private suspend fun send(
        userMessage: String,
        mode: String,
        solana: Boolean = false,
        tools: Boolean = false,
        extra: JSONObject? = null,
        skill: Skill? = null,
        signing: Boolean = false,
        quickRules: Boolean = false
    ): BrainReply = withContext(Dispatchers.IO) {
        // A send or a quick action comes back with an empty say and the action (or none):
        // an empty say is expected there, not a reply to ask for again.
        val expectsAction = extra?.optString("intent").orEmpty().isNotEmpty()
        when (val first = attempt(userMessage, mode, solana, tools, extra, skill, signing, quickRules, expectsAction)) {
            is Attempt.Done -> first.reply
            is Attempt.Unreadable -> {
                HeylanaLog.state("reply: unreadable reason=${first.reason} retry=once")
                logRawReply(first.raw)
                val retry = attempt(
                    userMessage + "\n\n" + ReplyParser.JSON_ONLY,
                    mode, solana, tools, extra, skill, signing, quickRules, expectsAction
                )
                when (retry) {
                    is Attempt.Done -> retry.reply.also { HeylanaLog.state("reply: retry readable") }
                    is Attempt.Unreadable -> {
                        HeylanaLog.state("reply: unreadable after retry reason=${retry.reason}")
                        logRawReply(retry.raw)
                        BrainReply.Say(ReplyParser.NOT_CAUGHT, null, null)
                    }
                }
            }
        }
    }

    /** One request and its reply: read, unreadable (with the raw text, for the debug log only), or failed. */
    private sealed interface Attempt {
        data class Done(val reply: BrainReply) : Attempt
        data class Unreadable(val reason: String, val raw: String) : Attempt
    }

    private fun attempt(
        userMessage: String,
        mode: String,
        solana: Boolean,
        tools: Boolean,
        extra: JSONObject?,
        skill: Skill?,
        signing: Boolean,
        quickRules: Boolean,
        expectsAction: Boolean
    ): Attempt {
        val ownKey = settings.useOwnKey
        val system = HeylanaPrompt.system(solana, skill, signing, quickRules)
        val request = if (ownKey) {
            ownKeyRequest(userMessage, mode, system)
        } else {
            proxyRequest(userMessage, mode, system, tools, extra)
        }
        if (request == null) {
            return Attempt.Done(BrainReply.Failed(if (ownKey) NO_KEY else NO_PROXY))
        }

        val warmed = proxy.warm
        val started = SystemClock.elapsedRealtime()
        return try {
            Proxy.http.newCall(request).execute().use { response ->
                // execute() returns as the response headers land, which is the
                // first byte back — the part a warm connection actually changes.
                logFirstByte(SystemClock.elapsedRealtime() - started, warmed)
                proxy.spendWarmth()
                val body = response.body.string()
                if (!response.isSuccessful) {
                    return Attempt.Done(BrainReply.Failed(httpError(response.code, body)))
                }
                logUsage(mode, body)
                extractReply(body, expectsAction)
            }
        } catch (e: IOException) {
            Attempt.Done(BrainReply.Failed("Couldn't reach Heylana: ${e.message ?: "no connection"}"))
        } catch (_: Exception) {
            Attempt.Done(BrainReply.Failed("Something went wrong reading the reply."))
        }
    }

    /**
     * Debug builds only: the raw reply that could not be read, so the operator can see
     * what the model did. Addresses shortened; never in a release build, never spoken.
     */
    private fun logRawReply(raw: String) {
        if (!BuildConfig.DEBUG) return
        HeylanaLog.state("reply: raw chars=${raw.length} text=" + AddressText.shorten(raw).replace('\n', ' ').take(RAW_LOG_CHARS))
    }

    /** What the app sends: the kind of work, not the model. */
    private fun proxyRequest(
        userMessage: String,
        mode: String,
        system: String,
        tools: Boolean,
        extra: JSONObject?
    ): Request? {
        if (!proxy.isConfigured) return null
        // The worker owns the tool definitions; the app only says whether to send them.
        val body = payload(userMessage, system).put("mode", mode)
        if (tools) body.put("tools", true)
        extra?.keys()?.forEach { key -> body.put(key, extra.get(key)) }
        return proxy.post("chat", body.toString())
    }

    /** The hidden way round: straight to Anthropic with the user's own key. */
    private fun ownKeyRequest(userMessage: String, mode: String, system: String): Request? {
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
            .post(payload(userMessage, system).put("model", model).toString().toRequestBody(JSON))
            .build()
    }

    private fun payload(userMessage: String, system: String): JSONObject = JSONObject()
        .put("max_tokens", MAX_TOKENS)
        .put("system", system)
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
        // Summed across every tool round by the worker, so this is the whole question.
        HeylanaLog.state(
            "usage: mode=$mode input_tokens=${usage.optInt("input_tokens", -1)} " +
                "output_tokens=${usage.optInt("output_tokens", -1)} " +
                "tool_ms=${usage.optInt("tool_ms", 0)} tools=${usage.optString("tools").ifEmpty { "none" }}"
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

    /**
     * Every text block, then [ReplyParser]: only the reply object's say is ever spoken
     * or shown. Anything else — no object, an object with no say, a say echoing the
     * prompt, or an empty say where words were due — is unreadable, never raw text.
     */
    private fun extractReply(body: String, expectsAction: Boolean): Attempt {
        val content = runCatching { JSONObject(body).optJSONArray("content") }.getOrNull()
            ?: return Attempt.Unreadable("no_content", body)
        val text = buildString {
            for (i in 0 until content.length()) {
                val block = content.optJSONObject(i) ?: continue
                if (block.optString("type") == "text") append(block.optString("text")).append('\n')
            }
        }.trim()
        if (text.isEmpty()) return Attempt.Unreadable("empty", body)

        val parsed = ReplyParser.parse(text, expectsAction)
        if (parsed is ReplyParser.Result.Unreadable) return Attempt.Unreadable(parsed.reason, text)
        parsed as ReplyParser.Result.Reply
        val json = runCatching { JSONObject(parsed.objectText) }.getOrNull()
            ?: return Attempt.Unreadable("json", text)

        val action = readAction(json)
        lastClarify = null
        val quick = readQuick(json)
        val clarify = lastClarify
        val task = readTask(json)
        val extraChars = text.length - parsed.objectText.length
        if (extraChars > 0) HeylanaLog.state("reply: text outside the json chars=$extraChars dropped")
        if (parsed.say.length < json.optString("say").trim().length) HeylanaLog.state("reply: repeated sentence dropped")
        if (parsed.segments.size > 1) {
            HeylanaLog.state("reply: segments=${parsed.segments.size} points=${parsed.segments.count { it.pointAt != null }}")
        }
        // Every word the model says is shortened here, once, before it is shown or spoken.
        val segments = parsed.segments.map { it.copy(text = AddressText.shorten(it.text)) }
        // One piece keeps point_at as it always did; segments carry their own.
        val pointAt = if (parsed.segments.size <= 1) parsed.segments.firstOrNull()?.pointAt ?: readPointAt(json) else null
        return Attempt.Done(BrainReply.Say(AddressText.shorten(parsed.say), pointAt, task, action, quick, segments, clarify))
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
    private fun readQuick(json: JSONObject): QuickAction? {
        val action = json.optJSONObject("action") ?: return null
        if (action.optString("type") != QuickAction.TYPE) return null
        val fields = HashMap<String, Any?>()
        action.keys().forEach { key -> fields[key] = if (action.isNull(key)) null else action.opt(key) }
        // The kind of action and its times only: what else was asked for stays out of the log.
        HeylanaLog.state(
            "action: raw type=intent intent=${action.optString("intent")} hour=${action.opt("hour")} " +
                "minutes=${action.opt("minutes")} seconds=${action.opt("seconds")}"
        )
        return QuickAction.of(fields).also {
            if (it == null) {
                lastClarify = QuickAction.clarify(fields)
                HeylanaLog.state("action: raw action incomplete clarify=${lastClarify != null}")
            }
        }
    }

    /** Set by [readQuick] for the reply being read: the question for a part the action is missing. */
    private var lastClarify: String? = null

    private fun readAction(json: JSONObject): SendAction? {
        val action = json.optJSONObject("action") ?: return null
        if (action.optString("type") != "send") return null
        val amount = if (action.has("amount") && !action.isNull("amount")) action.opt("amount")?.toString() else null
        val to = action.optString("to")
        val kind = when {
            AddressText.isKey(to.trim()) -> "address"
            Regex("\\.(skr|sol)$", RegexOption.IGNORE_CASE).containsMatchIn(to.trim()) -> "name"
            else -> "other"
        }
        // The raw action as it came back, with no more than four characters of the recipient.
        HeylanaLog.state(
            "send: raw action type=${action.optString("type")} to=${to.take(4)} kind=$kind " +
                "amount=${amount ?: "null"} token=${action.optString("token")}"
        )
        return SendAction.of(action.optString("type"), to, amount, action.optString("token")).also {
            if (it == null) HeylanaLog.state("send: raw action rejected as malformed")
        }
    }

    private fun readPointAt(json: JSONObject): Int? {
        if (!json.has("point_at") || json.isNull("point_at")) return null
        val id = json.optInt("point_at", -1)
        return if (id >= 0) id else null
    }

    companion object {
        private const val ANTHROPIC_ENDPOINT = "https://api.anthropic.com/v1/messages"
        private const val ANTHROPIC_VERSION = "2023-06-01"
        private const val MAX_TOKENS = 300
        private const val SHORTEN_MAX_TOKENS = 150
        /** The worker refuses a longer text to shorten; see worker/src/shorten.ts. */
        private const val SHORTEN_MAX_CHARS = 1_200
        private const val EXPLAIN_ADDRESS = "explain_address"
        private const val RECENT_ACTIVITY = "recent_activity"
        /** The most of an unreadable raw reply the debug log carries. */
        private const val RAW_LOG_CHARS = 600

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
