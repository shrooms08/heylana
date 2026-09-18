package xyz.heylana.app.home

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import xyz.heylana.app.HeylanaLog
import xyz.heylana.app.actions.QuickAction
import xyz.heylana.app.actions.QuickActionRunner
import xyz.heylana.app.actions.QuickActions
import xyz.heylana.app.actions.QuickGuard
import xyz.heylana.app.actions.QuickLog
import xyz.heylana.app.actions.QuickText
import xyz.heylana.app.brain.BrainReply
import xyz.heylana.app.brain.Conversation
import xyz.heylana.app.brain.ProxyClient
import xyz.heylana.app.brain.Routing
import xyz.heylana.app.brain.SolanaCore
import xyz.heylana.app.settings.HeylanaSettings
import xyz.heylana.app.voice.HeylanaVoice

/** One exchange in the app: what was asked, and what came back. */
data class Exchange(val question: String, val answer: String)

/**
 * The in-app conversation. **It never reads a screen**: a question goes as chat (`why=chat`
 * — Solana lookups go along when it has Solana words, so "check my balance" can be answered),
 * and an action ("set a timer") goes the quick-action way, through the same guard and
 * runner as the buddy's, including its one clarifying question. Answers are spoken in
 * Heylana's voice unless the speaker is off, and shown in the strip under the orb; the last
 * three exchanges are kept, in memory only.
 */
class AppChat(
    private val context: Context,
    private val settings: HeylanaSettings,
    private val scope: CoroutineScope
) {
    private val brain = ProxyClient(settings)
    private val memory = Conversation()
    private val runner = QuickActionRunner(context)

    val exchanges = mutableStateListOf<Exchange>()
    var thinking by mutableStateOf(false)
        private set
    var asked by mutableStateOf<String?>(null)
        private set
    var speaking by mutableStateOf(false)
        private set
    var level by mutableFloatStateOf(0f)
        private set

    private var inFlight: Job? = null
    private var pendingClarify: String? = null

    private val voice: HeylanaVoice by lazy {
        HeylanaVoice(
            context = context,
            settings = settings,
            scope = scope,
            onSpeaking = { now -> scope.launch(Dispatchers.Main) { speaking = now } },
            onLevel = { l -> level = l },
            onFailed = { _, reason -> HeylanaLog.state("app: voice_failed reason=$reason, shown as text") }
        )
    }

    /** A typed question, a chip, or what the ears heard. */
    fun send(raw: String) {
        val typed = raw.trim()
        if (typed.isEmpty() || inFlight?.isActive == true) return
        voice.stop()
        // The answer to a clarifying question goes with the question it answers.
        val question = pendingClarify?.takeUnless { QuickActions.isQuickAction(typed) }?.let { "$it $typed" } ?: typed
        pendingClarify = null
        asked = typed
        thinking = true
        HeylanaLog.state("app: ask chars=${question.length} screen=not_read")
        inFlight = scope.launch {
            val answer = if (QuickActions.isQuickAction(question)) action(question) else chat(question)
            thinking = false
            asked = null
            if (answer != null) {
                exchanges.add(Exchange(typed, answer))
                while (exchanges.size > MAX_EXCHANGES) exchanges.removeAt(0)
                if (!settings.voiceMuted) voice.speak(answer)
            }
        }
    }

    private suspend fun chat(question: String): String {
        // Chat, always: the app has no screen to read. Solana words bring the lookups.
        val route = if (SolanaCore.mentionsSolana(question)) {
            Routing.Route(ProxyClient.MODE_QUICK, Routing.Why.CHAT, SolanaCore.Load.WORDS)
        } else Routing.CHAT
        val history = memory.asPromptText(null)
        return when (val reply = brain.ask(question, "", history, null, route)) {
            is BrainReply.Say -> reply.text.also { memory.record(question, it, null) }
            is BrainReply.Failed -> reply.message
        }
    }

    private suspend fun action(question: String): String? {
        val route = Routing.forQuestion(null, question)
        return when (val reply = brain.ask(question, "", null, null, route)) {
            is BrainReply.Say -> {
                val quick = reply.quick
                val clarify = reply.clarify
                when {
                    quick != null -> run(quick, question)
                    clarify != null -> {
                        pendingClarify = question
                        HeylanaLog.state("app: clarify asked")
                        clarify
                    }
                    else -> QuickText.NO_ACTION
                }
            }
            is BrainReply.Failed -> reply.message
        }
    }

    private fun run(action: QuickAction, question: String): String = when (val verdict = QuickGuard.check(action, question)) {
        is QuickGuard.Verdict.Refused -> {
            HeylanaLog.state("action: guard verdict=refused intent=${action.intent} reason=${verdict.reason}")
            verdict.line
        }
        is QuickGuard.Verdict.Allowed -> {
            HeylanaLog.state("action: guard verdict=allowed ${QuickLog.describe(verdict.action)}")
            runner.run(verdict.action).line
        }
    }

    /** A line of Heylana's own (not an answer from the model): shown in the strip, and said. */
    fun note(line: String) {
        exchanges.add(Exchange("", line))
        while (exchanges.size > MAX_EXCHANGES) exchanges.removeAt(0)
        if (!settings.voiceMuted) voice.speak(line)
    }

    /** A voice picked in Settings says a short line in it, speaker on or off. */
    fun sample(line: String) {
        voice.stop()
        voice.speak(line)
    }

    /** The speaker was switched off: stop mid-sentence. */
    fun silence() = voice.stop()

    fun shutdown() {
        inFlight?.cancel()
        voice.shutdown()
    }

    companion object {
        /** The strip's chevron reaches back this far. */
        const val MAX_EXCHANGES = 3
    }
}
