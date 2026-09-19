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
import xyz.heylana.app.actions.ConfirmGate
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
    private val wallet = xyz.heylana.app.wallet.WalletApi(settings)
    private val memoryDesk = xyz.heylana.app.memory.memoryDeskFor(settings, wallet, source = "app")
    val lessons = xyz.heylana.app.lessons.LessonLibrary(context)
    private var lesson: xyz.heylana.app.lessons.Lesson? = null

    /** "PDAs, 2 of 5" while a lesson runs, for the strip; null otherwise. */
    var lessonProgress by mutableStateOf<String?>(null)
        private set

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

    /** A lesson from the topic list, or from "teach me <topic>": any earlier lesson gives way. */
    fun startLesson(note: xyz.heylana.app.lessons.LessonNote) {
        if (inFlight?.isActive == true) return
        val fresh = xyz.heylana.app.lessons.lessonFor(note, brain, settings, wallet)
        lesson = fresh
        HeylanaLog.state("lesson: started topic=${note.id} chunks=${fresh.size} in=app")
        voice.stop()
        asked = note.title
        thinking = true
        inFlight = scope.launch { lessonAnswer(note.title) { fresh.start() } }
    }

    private suspend fun lessonAnswer(shownAs: String, block: suspend () -> xyz.heylana.app.lessons.LessonLine) {
        val current = lesson
        val line = block()
        if (line.ended && lesson === current) lesson = null
        lessonProgress = lesson?.let { "${it.note.short}, ${it.step} of ${it.size}" }
        thinking = false
        asked = null
        exchanges.add(Exchange(shownAs, line.text))
        while (exchanges.size > MAX_EXCHANGES) exchanges.removeAt(0)
        if (!settings.voiceMuted) voice.speak(line.text)
    }

    /** A typed question, a chip, or what the ears heard. */
    fun send(raw: String) {
        val typed = raw.trim()
        if (typed.isEmpty() || inFlight?.isActive == true) return
        // A lesson hears everything said while it runs, but "remember that…", which is memory's.
        val running = lesson
        if (running != null && xyz.heylana.app.memory.MemoryWords.explicit(typed) == null) {
            voice.stop()
            asked = typed
            thinking = true
            HeylanaLog.state("app: ask chars=${typed.length} screen=not_read why=lesson")
            inFlight = scope.launch { lessonAnswer(typed) { running.hear(typed) } }
            return
        }
        xyz.heylana.app.lessons.LessonWords.topic(typed, lessons.notes)?.let { note ->
            startLesson(note)
            return
        }
        voice.stop()
        // The answer to a clarifying question goes with the question it answers.
        val question = pendingClarify?.takeUnless { QuickActions.isQuickAction(typed) }?.let { "$it $typed" } ?: typed
        pendingClarify = null
        asked = typed
        thinking = true
        HeylanaLog.state("app: ask chars=${question.length} screen=not_read")
        inFlight = scope.launch {
            val answer = when {
                xyz.heylana.app.lessons.LessonWords.wantsTopicList(question) -> xyz.heylana.app.lessons.LessonText.PICK
                // "Remember that…", a preference, or the yes that keeps it: no question to the model.
                memoryDesk.claims(question) -> memoryDesk.handle(question)
                QuickActions.isQuickAction(question) -> action(question)
                else -> chat(question)
            }
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
        // "Are you from Solana Mobile" is about Heylana, not Solana: no lookups for it.
        val route = if (SolanaCore.mentionsSolana(question) && !xyz.heylana.app.brain.ChatQuestions.isIdentity(question)) {
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
                    quick != null -> run(quick, question, reply.quickId)
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

    private suspend fun run(action: QuickAction, question: String, actionId: String?): String =
        when (val verdict = QuickGuard.check(action, question)) {
            is QuickGuard.Verdict.Refused -> {
                HeylanaLog.state("action: guard verdict=refused intent=${action.intent} reason=${verdict.reason}")
                verdict.line
            }
            is QuickGuard.Verdict.Allowed -> {
                HeylanaLog.state("action: guard verdict=allowed ${QuickLog.describe(verdict.action)}")
                // A message or a reminder (R3) fires only once the worker confirms it proposed it.
                when (val decision = ConfirmGate.check(
                    verdict.action.intent, actionId, settings.useOwnKey,
                    confirm = { kind, subject -> wallet.confirmation(kind, subject, guardPassed = true) }
                )) {
                    is ConfirmGate.Decision.Fire -> runner.run(verdict.action).line
                    is ConfirmGate.Decision.Hold -> decision.line
                }
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
