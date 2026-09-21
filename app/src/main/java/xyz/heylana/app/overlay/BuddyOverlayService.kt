package xyz.heylana.app.overlay

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import xyz.heylana.app.BuildConfig
import xyz.heylana.app.HeylanaLog
import xyz.heylana.app.MainActivity
import xyz.heylana.app.R
import xyz.heylana.app.brain.ProxyClient
import xyz.heylana.app.brain.BrainReply
import xyz.heylana.app.brain.AnswerLength
import xyz.heylana.app.brain.Conversation
import xyz.heylana.app.brain.Greeting
import xyz.heylana.app.brain.Routing
import xyz.heylana.app.brain.SaySegment
import xyz.heylana.app.brain.AddressText
import xyz.heylana.app.brain.Source
import xyz.heylana.app.brain.Sources
import xyz.heylana.app.brain.TypedAddresses
import xyz.heylana.app.brain.SendAction
import xyz.heylana.app.brain.SendGuard
import xyz.heylana.app.wallet.Answer
import xyz.heylana.app.wallet.BuildText
import xyz.heylana.app.wallet.SendActivity
import xyz.heylana.app.wallet.SimulationResult
import xyz.heylana.app.wallet.SendQuote
import xyz.heylana.app.wallet.SendRelay
import xyz.heylana.app.wallet.SendStage
import xyz.heylana.app.wallet.ClusterBadge
import xyz.heylana.app.wallet.TxEnding
import xyz.heylana.app.wallet.TxEvent
import xyz.heylana.app.wallet.TxMachine
import xyz.heylana.app.wallet.TxState
import xyz.heylana.app.wallet.TxSummary
import xyz.heylana.app.wallet.TxText
import xyz.heylana.app.wallet.SendResult
import xyz.heylana.app.wallet.SendText
import xyz.heylana.app.wallet.WalletApi
import xyz.heylana.app.wallet.WalletProblem
import xyz.heylana.app.brain.FinishedTask
import xyz.heylana.app.brain.ErrorTable
import xyz.heylana.app.brain.GuidanceSession
import xyz.heylana.app.brain.HeylanaPrompt
import xyz.heylana.app.brain.Teaching
import xyz.heylana.app.net.Proxy
import xyz.heylana.app.screen.HeylanaAccessibilityService
import xyz.heylana.app.screen.Keyterms
import xyz.heylana.app.screen.ScreenNode
import xyz.heylana.app.screen.ScreenSignal
import xyz.heylana.app.screen.ScreenSnapshot
import xyz.heylana.app.screen.StepAdvance
import xyz.heylana.app.screen.StepTarget
import xyz.heylana.app.screen.TapWatch
import xyz.heylana.app.screen.Verdict
import xyz.heylana.app.settings.HeylanaSettings
import xyz.heylana.app.skills.SkillStore
import xyz.heylana.app.actions.ConfirmGate
import xyz.heylana.app.actions.QuickAction
import xyz.heylana.app.actions.QuickActions
import xyz.heylana.app.actions.QuickActionRunner
import xyz.heylana.app.actions.QuickGuard
import xyz.heylana.app.actions.QuickLog
import xyz.heylana.app.actions.QuickText
import xyz.heylana.app.voice.HeylanaVoice
import xyz.heylana.app.voice.VoiceFailure
import xyz.heylana.app.voice.AssemblyEars
import xyz.heylana.app.voice.DeepgramEars
import xyz.heylana.app.voice.EarsRace
import xyz.heylana.app.voice.EarCallbacks
import xyz.heylana.app.voice.Listener
import xyz.heylana.app.voice.MicPermissionActivity

/**
 * Foreground service that keeps the buddy on top of every other app and runs the
 * ask-about-this-screen round trip: read the screen, ask the model, speak the
 * answer, and point at the one element the answer is about.
 *
 * Declared as a `specialUse` foreground service: the app's whole purpose is an
 * always-available on-screen companion, which none of the predefined types cover.
 */
class BuddyOverlayService : Service() {

    private var overlayView: BuddyOverlayView? = null
    private var highlight: HighlightOverlayView? = null

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var inFlight: Job? = null

    private val settings: HeylanaSettings by lazy { HeylanaSettings.get(this) }
    private val brain: ProxyClient by lazy { ProxyClient(settings) }
    private val skills: SkillStore by lazy { SkillStore(this, settings) }
    private val quickRunner: QuickActionRunner by lazy { QuickActionRunner(this) }

    /** Heylana's voice, through the proxy. When it cannot speak, the words are shown and it stays silent. */
    private var mouth: HeylanaVoice? = null

    /** The phone's own ears: always there, and what everything falls back to. */
    private var phoneEars: Listener? = null

    /** The good ears, when the proxy can lend a key for them. They also own the microphone. */
    private var cloudEars: DeepgramEars? = null

    /** The third ear, AssemblyAI, fed from [cloudEars]' microphone. */
    private var assemblyEars: AssemblyEars? = null

    /** When the buddy was touched — everything about the ears is timed from here. */
    private var earsAskedAt = 0L
    private var earsKeyterms: List<String> = emptyList()
    /** So the "voice unavailable" footnote is only ever shown once. */
    private var noteShown = false

    /** The task being walked through right now, if any. */
    private var session: GuidanceSession? = null
    private val conversation = Conversation()

    /** Says their name on the first answer; a new buddy is a new one of these. */
    private val greeting = Greeting()

    /** A send on the confirmation strip, waiting for confirm or cancel. Nothing settles it away. */
    private var awaitingConfirm: SendQuote? = null

    /** A send over a quarter of the balance, waiting for the user to say it again. */
    private var overLimit: PendingSend? = null

    private data class PendingSend(val quote: SendQuote, val at: Long)

    private val walletApi by lazy { WalletApi(settings) }
    private val lessons by lazy { xyz.heylana.app.lessons.LessonLibrary(this) }
    /** The lesson in progress, if one is: it hears every word until it ends. */
    private var lesson: xyz.heylana.app.lessons.Lesson? = null
    /** When "explain this" last explained Solana docs, so "why" straight after goes deeper. */
    private var docsExplainedAt = 0L
    private val memoryDesk by lazy { xyz.heylana.app.memory.memoryDeskFor(settings, walletApi, source = "buddy") }

    /** Addresses typed since the buddy started, to recognise them shortened on a wallet screen. Memory only. */
    private val typedAddresses = TypedAddresses()

    private val main = Handler(Looper.getMainLooper())

    /** What the user has going on, and therefore what may not be torn down. */
    private val exchange = Exchange { SystemClock.uptimeMillis() }

    /** Deciding whose words to use, from the long press until one is chosen. */
    private var race: EarsRace? = null

    /**
     * How long this spoken question took to answer, stretch by stretch. Starts when the
     * user lets go and ends at the first sound; times only, never a word.
     */
    private var answerClock: xyz.heylana.app.voice.AnswerClock? = null

    /** When the user let go, for the log. */
    private var releasedAt = 0L

    /** Asks the race again once its windows may have closed. */
    private val raceTick = Runnable { judge(race?.tick(SystemClock.uptimeMillis())) }

    /** Nothing may be under way for longer than [Exchange.LIMIT_MS]. */
    private val exchangeTimeout = Runnable { onExchangeTimeout() }

    /**
     * Puts everything back to rest a beat after an exchange finishes. A typed
     * answer keeps its strip; a spoken one leaves nothing behind but the
     * highlight, which clears on its own rules.
     */
    private val settleToIdle = Runnable {
        val view = overlayView ?: return@Runnable
        // Whatever booked this, the user has since started something else.
        if (!exchange.maySettle) {
            HeylanaLog.state("settle: skipped, ${exchange.phase}")
            return@Runnable
        }
        if (awaitingConfirm != null) {
            HeylanaLog.state("settle: skipped, a send is waiting to be confirmed")
            return@Runnable
        }
        // A spoken warning ends like any other line, and the end of it must not take the
        // warning off the screen: the glance has its own twelve seconds to be read in.
        if (shownGlance) {
            HeylanaLog.state("settle: skipped, a warning is on screen")
            return@Runnable
        }
        HeylanaLog.state("settle: run")
        // The box changing shape uncovers part of the app: a fresh quiet time for the step.
        stepAdvance?.ownWindowsChanged(SystemClock.uptimeMillis())
        view.endVoiceExchange()
        // Never while the capsule is still on screen: closing the box abandons
        // the microphone, and that is not what the end of an answer means.
        if (view.wasSpoken && !view.isCapsuleShowing) view.closePanel()
        view.setTalking(false)
    }
    private val autoAdvanceCheck = Runnable { considerAutoAdvance() }


    /** Runs only while a box is up and Heylana is waiting for the user to act. */
    private var tapWatch: TapWatch? = null
    private val tapWatchExpired = Runnable { endTapWatch(acknowledged = false) }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        live = this
        if (BuildConfig.DEBUG) {
            registerDebugQuickAction()
            registerDebugPanel()
        }
        val onSpeaking: (Boolean) -> Unit = { speaking ->
            speakingNow = speaking
            main.post {
                HeylanaLog.state("speak: speaking=$speaking")
                if (speaking) reportSpeed()
                if (speaking) reportGlanceSpoken()
                overlayView?.setTalking(speaking)
                main.removeCallbacks(settleToIdle)
                // Mid-teaching, the end of a sentence is the cue for the next one, not to settle.
                val run = teaching
                if (!speaking && run != null) {
                    run.spoken()
                    return@post
                }
                // A teaching session's last line: home once it has been said.
                if (!speaking && flyHomeAfterSpeech) {
                    main.postDelayed(flyHomeNow, BETWEEN_SEGMENTS_MS)
                    return@post
                }
                if (!speaking && session != null) stepSpeaking(false)
                if (!speaking && exchange.maySettle) {
                    HeylanaLog.state("settle: scheduled${if (overlayView?.showingSources == true) " linger=sources" else ""}")
                    main.postDelayed(settleToIdle, settleDelay())
                }
            }
        }
        mouth = HeylanaVoice(
            context = this,
            settings = settings,
            scope = scope,
            onSpeaking = onSpeaking,
            onLevel = { level -> main.post { overlayView?.setPlaybackLevel(level) } },
            onFailed = { text, reason -> showUnspoken(text, reason) }
        )
        phoneEars = Listener(this, phoneCallbacks)

        // The disc follows the exchange, and the twenty-second limit starts with it.
        exchange.onChange = { phase ->
            HeylanaLog.state("exchange: $phase")
            main.removeCallbacks(exchangeTimeout)
            if (phase != Exchange.Phase.NONE) main.postDelayed(exchangeTimeout, exchange.remaining())
            overlayView?.showPhase(phase)
        }
    }

    /** What the phone's own recogniser reports, marked as coming from it. */
    private val phoneCallbacks: EarCallbacks by lazy { callbacksFor(EarsRace.Ear.ANDROID) }

    /** What Deepgram reports, marked as coming from it. */
    private val cloudCallbacks: EarCallbacks by lazy { callbacksFor(EarsRace.Ear.DEEPGRAM) }

    /** What AssemblyAI reports, marked as coming from it. */
    private val assemblyCallbacks: EarCallbacks by lazy { callbacksFor(EarsRace.Ear.ASSEMBLYAI) }

    /** True once Deepgram has put words in the capsule this hold. */
    private var deepgramSpoke = false

    /** True once AssemblyAI has put words in the capsule this hold. */
    private var assemblySpoke = false

    /**
     * Both ears report into the race rather than acting on their own, so the
     * two cannot each decide the exchange is over.
     */
    private fun callbacksFor(ear: EarsRace.Ear) = EarCallbacks(
        onPartial = { text ->
            // Deepgram's words, once it has any, are the ones shown; then AssemblyAI's; then the phone's.
            if (ear == EarsRace.Ear.DEEPGRAM) deepgramSpoke = true
            if (ear == EarsRace.Ear.ASSEMBLYAI) assemblySpoke = true
            val shown = when (ear) {
                EarsRace.Ear.DEEPGRAM -> true
                EarsRace.Ear.ASSEMBLYAI -> !deepgramSpoke
                EarsRace.Ear.ANDROID -> !deepgramSpoke && !assemblySpoke
            }
            if (shown) overlayView?.showHeard(text)
        },
        onFinal = { text ->
            HeylanaLog.state("ears: ${ear.name.lowercase()} final")
            val confidence = when (ear) {
                EarsRace.Ear.DEEPGRAM -> cloudEars?.confidence
                EarsRace.Ear.ASSEMBLYAI -> assemblyEars?.confidence
                EarsRace.Ear.ANDROID -> phoneEars?.confidence
            }
            // A cloud final may wait a moment for the other cloud ear's: look again then.
            if (ear != EarsRace.Ear.ANDROID) main.postDelayed(raceTick, EarsRace.COMPARE_MS)
            judge(race?.heard(ear, SystemClock.uptimeMillis(), text, confidence))
        },
        onProblem = { message ->
            HeylanaLog.state("ears: ${ear.name.lowercase()} problem")
            judge(race?.failed(ear, SystemClock.uptimeMillis(), message))
        },
        onNothingHeard = {
            HeylanaLog.state("ears: ${ear.name.lowercase()} heard nothing")
            judge(race?.nothing(ear, SystemClock.uptimeMillis()))
        },
        onLevel = { level -> overlayView?.showMicLevel(level) }
    )

    /** Acts on what the race decided, if it decided anything. */
    private fun judge(verdict: EarsRace.Verdict?) {
        when (verdict) {
            null, EarsRace.Verdict.Wait, EarsRace.Verdict.Settled -> Unit

            is EarsRace.Verdict.Use -> {
                val deepgram = cloudEars
                val assembly = assemblyEars
                // Which ear, how sure, how long after the release; every ear's result beside it. Never the words.
                HeylanaLog.state(
                    "ears=${verdict.ear.name.lowercase()} won reason=${verdict.reason} " +
                        "confidence=${verdict.confidence?.let { "%.2f".format(it) } ?: "none"} " +
                        "ms=${SystemClock.uptimeMillis() - releasedAt} " +
                        "all=[${race?.summary()}] " +
                        "token_ms=${deepgram?.tokenMillis ?: -1} socket_ms=${deepgram?.socketMillis ?: -1} " +
                        "aai_token_ms=${assembly?.tokenMillis ?: -1} aai_socket_ms=${assembly?.socketMillis ?: -1}"
                )
                captureEars(verdict)
                answerClock?.heard(SystemClock.uptimeMillis())
                endRace()
                ask(verdict.text)
            }

            EarsRace.Verdict.NothingHeard -> {
                HeylanaLog.state("ears: neither heard a word")
                endRace()
                // They held the buddy and said nothing. Nothing to answer and
                // nothing to apologise for: melt the capsule, and the disc rests
                // because the exchange is over.
                exchange.over()
                overlayView?.endVoiceExchange()
                overlayView?.setTalking(false)
            }

            is EarsRace.Verdict.Problem -> {
                // What the ear said stays in the log; the user hears the plain line.
                HeylanaLog.state("ears: problem reported, shown as plain")
                xyz.heylana.app.ops.CrashReports.problem("ears", "ears", null, null)
                endRace()
                exchange.over()
                overlayView?.endVoiceExchange()
                overlayView?.showNotice(xyz.heylana.app.brain.PlainError.forEars(verdict.message))
            }
        }
    }

    /** The race is over: both ears stop and the socket closes. */
    private fun endRace() {
        main.removeCallbacks(raceTick)
        race = null
        deepgramSpoke = false
        assemblySpoke = false
        phoneEars?.cancel()
        cloudEars?.cancel()
        cloudEars = null
        assemblyEars?.cancel()
        assemblyEars = null
    }

    /** An exchange has run for twenty seconds: end it, whatever is stuck. */
    private fun onExchangeTimeout() {
        if (!exchange.overdue()) {
            if (exchange.inProgress) main.postDelayed(exchangeTimeout, exchange.remaining())
            return
        }
        HeylanaLog.state("exchange: timed out in ${exchange.phase}")
        endRace()
        inFlight?.cancel()
        mouth?.stop()
        exchange.timedOut()
        overlayView?.endVoiceExchange()
        overlayView?.showNotice(TOOK_TOO_LONG)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }

        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "Heylana needs the overlay permission", Toast.LENGTH_LONG).show()
            stopSelf()
            return START_NOT_STICKY
        }

        startAsForeground()
        showOverlay()
        isRunning = true
        return START_STICKY
    }

    override fun onDestroy() {
        isRunning = false
        if (live === this) live = null
        HeylanaAccessibilityService.watchWindows(null)
        debugQuickAction?.let { runCatching { unregisterReceiver(it) } }
        debugPanel?.let { runCatching { unregisterReceiver(it) } }
        debugQuickAction = null
        // Stop means forget: the memory never outlives the buddy.
        conversation.clear()
        lesson = null
        endSession(clearBox = false)
        stopTapWatch()
        main.removeCallbacksAndMessages(null)
        scope.cancel()
        cloudEars?.shutdown()
        cloudEars = null
        assemblyEars?.shutdown()
        assemblyEars = null
        phoneEars?.shutdown()
        phoneEars = null
        mouth?.shutdown()
        mouth = null
        highlight?.removeFromWindow()
        highlight = null
        overlayView?.removeFromWindow()
        overlayView = null
        HeylanaAccessibilityService.readThrough = null
        super.onDestroy()
    }

    private fun showOverlay() {
        if (overlayView != null) return

        highlight = HighlightOverlayView(this).also { view ->
            view.onFadedOut = {
                // Nothing is being pointed at any more, so nothing is watched.
                stopTapWatch()
                overlayView?.stopLooking()
            }
            view.addToWindow()
        }

        overlayView = BuddyOverlayView(this).also { view ->
            HeylanaAccessibilityService.readThrough = { through -> view.letScreenReadThrough(through) }
            // The pointer's window doubles as the stage the disc flies across.
            view.flightStage = highlight
            view.onQuestion = { question -> ask(question) }
            // Touching the disc is the earliest warning that anything is
            // coming, so the connection is opened and the ears start getting
            // ready while the user is still deciding what the touch will be.
            // Dispatched straight to IO: naming `brain` builds the HTTP client
            // the first time, and no part of that belongs in a touch handler.
            view.onTouched = {
                scope.launch(Dispatchers.IO) { brain.warmUp() }
                prepareEars()
            }
            view.onNotAHold = { discardEars() }
            view.onHoldStart = { startListening() }
            view.onHoldEnd = { finishListening() }
            view.onHoldCancel = { abandonListening() }
            view.onMuteToggled = { muted ->
                settings.voiceMuted = muted
                if (muted) mouth?.stop()
            }
            view.onNext = { advance(userAsked = true) }
            view.onDone = { stopSessionOnRequest() }
            view.onConfirmSend = { confirmSend() }
            // Touching the disc while Heylana speaks stops her.
            view.isSpeaking = { speakingNow }
            view.onInterrupt = { interruptSpeech() }
            view.onCancelSend = { cancelSend() }
            view.onSourceTapped = { openSource(it) }
            view.onPanelClosed = {
                HeylanaLog.state("panel: closed")
                teaching?.cancel()
                if (awaitingConfirm != null) {
                    awaitingConfirm = null
                    HeylanaLog.state("send: cancelled, panel closed")
                }
                if (keepSpeechThroughClose) {
                    // The box closed itself so the phone's app is in front, and the line
                    // saying what was done is still being spoken: it plays to the end.
                    keepSpeechThroughClose = false
                    HeylanaLog.state("speak: kept playing through the close")
                } else {
                    mouth?.stop()
                }
                stopTapWatch()
                highlight?.hide()
                view.stopLooking()
                abandonListening()
                endSession(clearBox = true)
                // The memory deliberately survives this: closing the box is not
                // the user saying "forget that". Stopping, changing app or ten
                // minutes are the three things that are.
            }
            view.setMuted(settings.voiceMuted)
            view.voiceShowsText = settings.showTextForVoice
            view.addToWindow()
        }
        // The buddy is up: start watching for signing screens, if that is switched on.
        applyLookout()
    }

    // ------------------------------------------------------------------ ask

    /**
     * Reads the screen first — while the app the user is asking about is still the
     * topmost non-Heylana window — then asks the brain off the main thread.
     */
    /** A quick action waiting on one missing part, and when it asked. */
    private data class PendingClarify(val question: String, val at: Long)
    private var pendingClarify: PendingClarify? = null

    /**
     * The answer to a clarifying question goes with the question it answers: "text I'm on
     * my way" → "Who should I text?" → "Ada" becomes "text I'm on my way Ada". A question
     * that is an action or a chat of its own starts afresh.
     */
    private fun withClarified(question: String): String {
        val pending = pendingClarify ?: return question
        pendingClarify = null
        if (System.currentTimeMillis() - pending.at > CLARIFY_WINDOW_MS) return question
        if (QuickActions.isQuickAction(question) || Routing.chatRoute(question) != null) return question
        HeylanaLog.state("action: clarify answered")
        return "${pending.question} $question"
    }

    private fun ask(asked: String) {
        val question = withClarified(asked)
        HeylanaLog.state("ask: sending")
        answerClock?.asked(SystemClock.uptimeMillis())
        val view = overlayView ?: return
        if (inFlight?.isActive == true) return

        // A lesson hears everything said while it runs — answers and its own words (skip,
        // slower, example, why, stop) — except "remember that…", which is memory's.
        val running = lesson
        if (running != null && xyz.heylana.app.memory.MemoryWords.explicit(question) == null) {
            lessonTurn { running.hear(question) }
            return
        }
        // "Teach me PDAs": a lesson on a topic from the curriculum, over whatever app is in front.
        xyz.heylana.app.lessons.LessonWords.topic(question, lessons.notes)?.let { note ->
            startLesson(note)
            return
        }
        if (xyz.heylana.app.lessons.LessonWords.wantsTopicList(question)) {
            sayLine(xyz.heylana.app.lessons.LessonText.PICK)
            return
        }

        // "Remember that…", a preference, or the yes that keeps it: no question to the model.
        if (memoryDesk.claims(question)) {
            exchange.asking()
            inFlight = scope.launch {
                val line = memoryDesk.handle(question)
                exchange.over()
                if (line != null) sayLine(line)
            }
            return
        }
        exchange.asking()

        // "Why?" on a step is about the step, not a new question: the task, its box and
        // its pointer all stay, and the rest of the task teaches as it goes.
        val task = session
        if (task != null && Teaching.isWhy(question)) {
            explainStep(task, question)
            return
        }
        // "Stop" while a task runs ends it, the same as Done.
        if (task != null && Teaching.isStop(question)) {
            HeylanaLog.state("teach: stop asked")
            exchange.over()
            overlayView?.endVoiceExchange()
            stopSessionOnRequest()
            return
        }
        // "Stop" with nothing running means stop talking and put the box away: nothing to ask.
        if (task == null && Teaching.isStop(question)) {
            HeylanaLog.state("ask: stop with nothing running model=not_asked")
            teaching?.cancel()
            mouth?.stop()
            exchange.over()
            view.endVoiceExchange()
            view.closePanel()
            return
        }

        // A new question drops whatever the last one left behind, including any
        // task that was running, and any teaching flight in the air.
        teaching?.cancel()
        mouth?.stop()
        stopTapWatch()
        highlight?.hide()
        view.stopLooking()
        endSession(clearBox = false)
        if (awaitingConfirm != null) {
            awaitingConfirm = null
            view.hideSendConfirm()
            HeylanaLog.state("send: dropped, a new question came")
        }

        // A plain question is never refused for want of a screen. With screen
        // reading off the question still goes, with an empty listing that says
        // why, and the model answers from general knowledge — or, if the question
        // really was about the screen, tells them to switch screen reading on.
        view.showThinking()
        // The keyboard squeezes the app underneath, which hides whatever sits at the
        // bottom of it — often the very button the answer is about. Drop it and let
        // the app lay itself out again before reading the screen.
        view.hideKeyboard()

        // "Turn on the flashlight" says everything: done here, at once, with no model call.
        QuickActions.flashlightCommand(question)?.let { direct ->
            HeylanaLog.state("action: said outright intent=flashlight state=${if (direct.on) "on" else "off"} model=not_asked")
            exchange.over()
            view.endVoiceExchange()
            handleQuick(direct, question)
            return
        }

        // "Turn it off" straight after the flashlight went on can only mean the torch:
        // done here, with nothing asked of the model.
        val followUp = QuickActions.flashlightFollowUp(question)
        if (followUp != null && lastQuick?.let { it.intent == QuickAction.FLASHLIGHT && System.currentTimeMillis() - it.at < FOLLOW_UP_MS } == true) {
            HeylanaLog.state("action: follow-up intent=flashlight state=${if (followUp.on) "on" else "off"} model=not_asked")
            exchange.over()
            view.endVoiceExchange()
            handleQuick(followUp, question)
            return
        }

        // An error the built-in table knows, in the user's own words: answered here, no screen, no model.
        ErrorTable.find(question)?.let { known ->
            HeylanaLog.state("error: table hit name=${known.name} where=said model=not_asked")
            exchange.over()
            view.endVoiceExchange()
            conversation.record(question, ErrorTable.line(known), null)
            sayLine(ErrorTable.line(known), listOf(ErrorTable.source(known)))
            return
        }

        // "What did I just do" right after a task: answered from that task, with no screen read.
        val finished = lastTask?.takeIf { it.fresh(System.currentTimeMillis()) }
        if (finished != null && Teaching.isRecap(question)) {
            recapTask(finished, question)
            return
        }

        // Small talk, a joke, general knowledge: decided from the words alone, so the
        // screen is never read and no listing goes with the question.
        val chat = Routing.chatRoute(question)

        // A fact about the user ("I use Jupiter for swaps") is kept alongside the question.
        val saving = scope.async { memoryDesk.autoSave(question) }

        inFlight = scope.launch {
            val snapshot = if (chat != null) {
                HeylanaLog.state("ask: screen not read why=chat")
                ScreenSnapshot.empty(readingOff = false)
            } else readScreenForQuestion(view)

            val screenText = if (chat != null) "" else snapshot.toPromptText()
            if (chat == null) logScreenSize(snapshot, screenText)

            // An error: the user's words first, then — asked about an error — the screen. The table
            // answers what it knows; anything else that reads like an error goes to the knowledge base.
            val asksError = ErrorTable.asksAboutError(question)
            val onScreen = if (asksError) ErrorTable.find(screenText) else null
            if (onScreen != null) {
                HeylanaLog.state("error: table hit name=${onScreen.name} where=screen model=not_asked")
                exchange.over()
                view.endVoiceExchange()
                sayLine(ErrorTable.line(onScreen), listOf(ErrorTable.source(onScreen)))
                return@launch
            }
            val errorText = when {
                ErrorTable.looksLikeError(question) -> question
                asksError && ErrorTable.looksLikeError(screenText) -> screenText
                else -> null
            }
            if (errorText != null) {
                HeylanaLog.state("error: not in the table where=${if (errorText === question) "said" else "screen"}")
                mode(BuddyMode.THINKING)
                val reply = brain.explainError(question, errorText)
                mode(null)
                exchange.over()
                view.endVoiceExchange()
                when (reply) {
                    is BrainReply.Say -> {
                        conversation.record(question, reply.text, snapshot.packageName)
                        sayLine(reply.text, reply.sources)
                    }
                    is BrainReply.Failed -> view.showNotice(reply.message)
                }
                return@launch
            }

            // A chat question belongs to no app: it neither reads nor clears what came before.
            val memory = conversation.asPromptText(if (chat != null) null else snapshot.packageName)

            // The second turn of a send over a quarter of the balance: said again, it
            // goes to the strip without asking the model anything.
            val waiting = overLimit
            overLimit = null
            if (waiting != null && System.currentTimeMillis() - waiting.at < SendGuard.PENDING_MS &&
                SendGuard.confirmsPending(question, waiting.quote.amount)
            ) {
                exchange.over()
                view.endVoiceExchange()
                HeylanaLog.state("send: over a quarter, said again amount=${waiting.quote.amount}")
                showSendStrip(waiting.quote)
                return@launch
            }

            typedAddresses.record(question)
            val route = chat ?: Routing.forQuestion(snapshot.packageName, question, screenText, settings.walletSession?.pubkey)
            // No hello by name on a send or a signing explanation, and it is not used up by one.
            val greetingLine = if (route.allowsGreeting) greeting.lineFor(settings.callMe) else null
            HeylanaLog.state("greeting: ${if (greetingLine != null) "included" else "not included"} why=${route.why.log}")
            val skill = if (chat != null) null else skills.pick(snapshot.packageName, question)
            val teaching = Teaching.wantsTeaching(question)
            // A walk-through is only for a question the model answers in words: never a
            // send, a quick action or chat, which have their own shapes.
            val walkThrough = Teaching.wantsSession(question) &&
                route.why != Routing.Why.SEND_QUESTION && route.why != Routing.Why.QUICK_ACTION && !route.skipsScreen
            // Solana docs or Playground in the browser: "explain this" explains the passage in
            // view, and "why" straight after goes one level deeper on it.
            val docs = xyz.heylana.app.lessons.LessonWords.isSolanaDocs(snapshot.packageName, screenText)
            val now = System.currentTimeMillis()
            val lens = when {
                !docs -> null
                xyz.heylana.app.lessons.LessonWords.isExplainThis(question) -> HeylanaPrompt.DOCS_EXPLAIN_LINE.also { docsExplainedAt = now }
                now - docsExplainedAt < Teaching.RECAP_WINDOW_MS &&
                    xyz.heylana.app.lessons.LessonWords.command(question) == xyz.heylana.app.lessons.LessonWords.Command.DEEPER ->
                    HeylanaPrompt.DOCS_DEEPER_LINE
                else -> null
            }
            if (docs) HeylanaLog.state("docs: solana docs in front lens=${when (lens) { null -> "none"; HeylanaPrompt.DOCS_EXPLAIN_LINE -> "explain"; else -> "deeper" }}")
            // A page that carries on below the screen: answered from the part on screen, and said so.
            val longPage = chat == null && !walkThrough && snapshot.moreBelow &&
                route.why != Routing.Why.SEND_QUESTION && route.why != Routing.Why.QUICK_ACTION &&
                xyz.heylana.app.screen.PageExtent.aboutThePage(question, snapshot.packageName)
            val lenses = listOfNotNull(lens, if (longPage) HeylanaPrompt.MORE_BELOW_LINE else null)
            mode(BuddyMode.THINKING)
            val asked = brain.ask(
                question, screenText, memory, greetingLine, route, typedAddresses.all(), skill, teaching, walkThrough,
                lenses.joinToString("\n\n").ifEmpty { null },
                // A teaching walk-through is spoken step by step as the disc flies, never ahead of it.
                voice = if (teaching || walkThrough) null else spokenSink()
            )
            answerClock?.answered(SystemClock.uptimeMillis())
            // A one-trip answer is already being said by now, so this is what completes the line.
            main.post { reportSpeed() }
            val reply = if (longPage && asked is BrainReply.Say) onScreenOnly(asked, snapshot) else asked
            mode(null)
            // The answer is here: from now on settling back to idle is allowed.
            exchange.over()
            // Runs once the answer below is on the strip.
            scope.launch { if (saving.await()) flashRemembered() }
            if (reply is BrainReply.Say) {
                HeylanaLog.state(
                    "answer: task=${reply.task != null} done=${reply.task?.done} pointed=${reply.pointAt != null} " +
                        "segments=${reply.segments.size} words=${AnswerLength.words(reply.text)}"
                )
            }
            when (reply) {
                is BrainReply.Say -> {
                    if (route.allowsGreeting) greeting.answered()
                    // The capsule goes as the answer lands, whichever way it
                    // was asked for.
                    view.endVoiceExchange()
                    val action = reply.action
                    if (action != null) {
                        handleSend(action, question)
                        return@launch
                    }
                    val quick = reply.quick
                    if (quick != null) {
                        handleQuick(quick, question, actionId = reply.quickId)
                        return@launch
                    }
                    val clarify = reply.clarify
                    if (clarify != null && route.why == Routing.Why.QUICK_ACTION) {
                        // A part is missing: ask for it, and put the answer with the question.
                        pendingClarify = PendingClarify(question, System.currentTimeMillis())
                        HeylanaLog.state("action: clarify asked")
                        sayLine(clarify)
                        return@launch
                    }
                    if (route.why == Routing.Why.QUICK_ACTION) {
                        // Nor does a quick action: no action, no pretending one happened.
                        HeylanaLog.state("action: raw missing why=quick_action")
                        sayLine(QuickText.NO_ACTION)
                        return@launch
                    }
                    if (route.why == Routing.Why.SEND_QUESTION) {
                        // A send never falls back to the model's own words.
                        HeylanaLog.state("send: raw action missing")
                        sayLine(SendText.NO_ACTION)
                        return@launch
                    }
                    val task = reply.task
                    if (task != null && !task.done) {
                        startSession(task.goal, reply, snapshot, teaching)
                    } else {
                        conversation.record(question, reply.text, if (chat != null) null else snapshot.packageName)
                        showOneShot(reply, snapshot)
                    }
                }

                is BrainReply.Failed -> {
                    view.endVoiceExchange()
                    view.showNotice(reply.message)
                }
            }
        }
    }

    /** The ordinary read for a question: the app under the box, after the keyboard has gone. */
    private suspend fun readScreenForQuestion(view: BuddyOverlayView): ScreenSnapshot {
        mode(BuddyMode.READING)
        // The app under the box has to be readable for the moment of the read.
        view.letScreenReadThrough(true)
        return try {
            delay(KEYBOARD_SETTLE_MS)

            // A screen with nothing readable on it is not a reason to refuse.
            // Plenty of questions are not about the screen at all, and some apps
            // hand us an empty tree however hard we look — Chrome does unless
            // something is subscribed to its events, which Heylana deliberately
            // is not. The model is told the screen was unreadable and answers
            // from general knowledge. A task step is different: see advance().
            val reading = HeylanaAccessibilityService.isConnected
            HeylanaLog.state("ask: screen reading connected=$reading")
            HeylanaAccessibilityService.snapshotOrNull()
                ?: ScreenSnapshot.empty(readingOff = !reading)
        } finally {
            view.letScreenReadThrough(false)
        }
    }

    // ------------------------------------------------------------ quick actions

    private var debugQuickAction: android.content.BroadcastReceiver? = null

    /**
     * Debug builds only: runs an action as if the model had written it, so the guard
     * and the intent can be checked on a phone without asking the model anything.
     * The line is shown, never spoken: speaking it would be a /tts call.
     *
     *     adb shell am broadcast -a xyz.heylana.app.debug.QUICK_ACTION \
     *       --es said "open the wallet" --es action '{"type":"intent","intent":"open_app","app":"wallet"}'
     */
    /**
     * Debug builds only: dock the disc and open or close the box without a finger on
     * it (a touch opens the connection to the proxy):
     *
     *     adb shell am broadcast -a xyz.heylana.app.debug.PANEL --es dock right
     *     adb shell am broadcast -a xyz.heylana.app.debug.PANEL --ez toggle true
     */
    private fun registerDebugPanel() {
        val receiver = object : android.content.BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val view = overlayView ?: return
                intent.getStringExtra("dock")?.let { view.debugDock(left = it == "left") }
                // Which ears listen: auto, deepgram, assemblyai or android.
                if (intent.hasExtra("ears_capture")) {
                    settings.saveEarsCapture = intent.getBooleanExtra("ears_capture", false)
                    if (!settings.saveEarsCapture) java.io.File(filesDir, EARS_CAPTURE).delete()
                    HeylanaLog.state("ears: capture ${if (settings.saveEarsCapture) "on" else "off, file deleted"}")
                }
                intent.getStringExtra("ears")?.let { ear ->
                    settings.forceEar = ear
                    HeylanaLog.state("ears: debug switch now ${settings.forceEar}")
                }
                // The unasked glance, on made-up text, so the wake can be checked without
                // a wallet screen or anyone's browser:
                //   adb shell am broadcast -a xyz.heylana.app.debug.PANEL --es glance secret
                intent.getStringExtra("glance")?.let { which -> debugGlance(which) }
                // A window event as the system would deliver one, so the path from a signing
                // window appearing to the first spoken word can be timed:
                //   adb shell am broadcast -a xyz.heylana.app.debug.PANEL --es window seedvault
                intent.getStringExtra("window")?.let { which ->
                    val pkg = when (which) {
                        "seedvault" -> "com.solanamobile.seedvaultimpl"
                        "wallet" -> "com.solanamobile.wallet"
                        else -> which
                    }
                    onNewWindow(
                        xyz.heylana.app.screen.WindowEvent(
                            packageName = pkg,
                            className = "com.solanamobile.seedvaultimpl.MainActivity",
                            // A fresh title each time: every injection is a new window,
                            // as a second confirm sheet would be.
                            title = "Approve transaction ${SystemClock.uptimeMillis()}",
                            at = SystemClock.uptimeMillis(),
                        )
                    )
                }
                // Times the signing glance on a screen that is not a wallet's own sheet:
                //   adb shell am broadcast -a xyz.heylana.app.debug.PANEL --ez glance_any true
                if (intent.hasExtra("glance_any")) {
                    settings.glanceAnyScreen = intent.getBooleanExtra("glance_any", false)
                    HeylanaLog.state("glance: any screen with confirm words = ${settings.glanceAnyScreen}")
                }
                if (intent.getBooleanExtra("toggle", false)) view.debugToggle()
                if (intent.getBooleanExtra("thinking", false)) view.showThinking()
                if (intent.getBooleanExtra("teach", false)) debugTeach()
                // A typed question, exactly as the ask pill sends it: a real /chat call.
                //   adb shell am broadcast -a xyz.heylana.app.debug.PANEL --es ask "tell me a joke"
                intent.getStringExtra("ask")?.takeIf { it.isNotBlank() }?.let { question ->
                    HeylanaLog.state("debug: ask chars=${question.length}")
                    if (!view.isPanelOpen) view.debugToggle()
                    main.postDelayed({ ask(question) }, DEBUG_ASK_DELAY_MS)
                }
            }
        }
        ContextCompat.registerReceiver(this, receiver, android.content.IntentFilter(DEBUG_PANEL), ContextCompat.RECEIVER_EXPORTED)
        debugPanel = receiver
    }

    private var debugPanel: android.content.BroadcastReceiver? = null

    /**
     * Debug builds only: a teaching flight on the real overlay, across three made-up
     * elements, with no model and no voice — each piece dwells as long as it would take
     * to read. `adb shell am broadcast -a xyz.heylana.app.debug.PANEL --ez teach true`
     */
    private fun debugTeach() {
        val metrics = resources.displayMetrics
        val w = metrics.widthPixels
        val h = metrics.heightPixels
        fun node(id: Int, left: Int, top: Int, right: Int, bottom: Int) = xyz.heylana.app.screen.ScreenNode(
            id, 0, "android.widget.Button", "debug $id", null, null, true, false, false, null,
            android.graphics.Rect(left, top, right, bottom)
        )
        val nodes = listOf(
            node(1, w / 10, h / 4, w / 2, h / 4 + h / 20),
            node(2, w / 2, h / 2, w * 9 / 10, h / 2 + h / 20),
            node(3, w / 8, h * 3 / 4, w * 5 / 8, h * 3 / 4 + h / 20)
        )
        val snapshot = ScreenSnapshot("debug", null, nodes, false)
        val segments = listOf(
            SaySegment("Swaps live on the Swap tab, so tap Swap.", 1),
            SaySegment("This is what you pay with.", 2),
            SaySegment("Check the rate here before you confirm.", 3)
        )
        HeylanaLog.state("debug: teaching flight on the overlay, silent")
        teaching?.cancel()
        val run = TeachingRun(segments, snapshot, silent = true)
        teaching = run
        run.start()
    }

    private fun registerDebugQuickAction() {
        val receiver = object : android.content.BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                // Who "my brother" finds, by kind and count only, with nothing opened:
                //   adb shell am broadcast -a xyz.heylana.app.debug.QUICK_ACTION --es contact "'my brother'"
                intent.getStringExtra("contact")?.let { name ->
                    scope.launch(Dispatchers.IO) {
                        HeylanaLog.state("debug: contact ${quickRunner.resolveContactForDebug(name)}")
                    }
                    return
                }
                val said = intent.getStringExtra("said").orEmpty()
                val json = runCatching { org.json.JSONObject(intent.getStringExtra("action").orEmpty()) }.getOrNull()
                val fields = HashMap<String, Any?>()
                json?.keys()?.forEach { key -> fields[key] = if (json.isNull(key)) null else json.opt(key) }
                val action = QuickAction.of(fields)
                HeylanaLog.state("action: raw debug intent=${action?.intent ?: "malformed"}")
                // Never spoken: every voice is a /tts call now.
                if (action != null) handleQuick(action, said, aloud = false, debug = true)
            }
        }
        ContextCompat.registerReceiver(this, receiver, android.content.IntentFilter(DEBUG_QUICK_ACTION), ContextCompat.RECEIVER_EXPORTED)
        debugQuickAction = receiver
    }

    /**
     * An alarm, a timer, an app, a page, directions or the dialer. Every part must be
     * in the user's own words; then the phone's own app does it, in front, and
     * Heylana says one short line. Heylana itself taps nothing.
     */
    /** The last action that fired, so "turn it off" can follow the flashlight. */
    private data class LastQuick(val intent: String, val at: Long)
    private var lastQuick: LastQuick? = null

    private fun handleQuick(
        action: QuickAction,
        question: String,
        aloud: Boolean = true,
        actionId: String? = null,
        debug: Boolean = false
    ) {
        when (val verdict = QuickGuard.check(action, question)) {
            is QuickGuard.Verdict.Refused -> {
                HeylanaLog.state("action: guard verdict=refused intent=${action.intent} reason=${verdict.reason}")
                finishQuick(action, QuickActionRunner.Outcome(verdict.line, fired = false), aloud)
            }
            is QuickGuard.Verdict.Allowed -> {
                HeylanaLog.state("action: guard verdict=allowed ${QuickLog.describe(verdict.action)}")
                if (!ConfirmGate.needsConfirmation(verdict.action.intent)) {
                    finishQuick(action, quickRunner.run(verdict.action), aloud)
                    return
                }
                // R3: the worker confirms it proposed this action before the phone's app is opened.
                scope.launch {
                    val decision = ConfirmGate.check(
                        verdict.action.intent, actionId,
                        confirm = { kind, subject -> walletApi.confirmation(kind, subject, guardPassed = true) },
                        debug = debug && BuildConfig.DEBUG
                    )
                    val outcome = when (decision) {
                        is ConfirmGate.Decision.Fire -> quickRunner.run(verdict.action)
                        is ConfirmGate.Decision.Hold -> QuickActionRunner.Outcome(decision.line, fired = false)
                    }
                    finishQuick(action, outcome, aloud)
                }
            }
        }
    }

    /** After an action fired, or was refused: the line, and the box out of the way. */
    private fun finishQuick(action: QuickAction, outcome: QuickActionRunner.Outcome, aloud: Boolean) {
        if (outcome.fired) lastQuick = LastQuick(action.intent, System.currentTimeMillis())
        when {
            outcome.fired -> afterQuickAction(outcome.line, aloud)
            !aloud -> overlayView?.showNotice(outcome.line)
            else -> sayLine(outcome.line)
        }
    }

    /**
     * The phone's own app is in front now, so nothing of Heylana's may sit over it:
     * the box melts away at once, the line is spoken, and the disc settles to idle a
     * second after the speech ends — the same beat as a spoken answer. With no voice
     * the line shows for that second instead, and then the box goes.
     */
    private fun afterQuickAction(line: String, aloud: Boolean) {
        val view = overlayView ?: return
        HeylanaLog.state("action: box melts, line aloud=$aloud")
        // The close runs its draw-back and flight before it reports back, and a close
        // stops speech: without this the line started and was cut off in silence.
        keepSpeechThroughClose = aloud
        view.closePanel()
        if (!aloud || !speak(line)) {
            keepSpeechThroughClose = false
            view.showNotice(line)
            main.postDelayed({ overlayView?.closePanel() }, SETTLE_MS)
            settleSoon()
        }
    }

    // ------------------------------------------------------------ mode and speech

    /** True while Heylana's voice is playing. */
    @Volatile
    private var speakingNow = false

    /** The chip on the strip or HUD: what Heylana is doing now. */
    private fun mode(mode: BuddyMode?) {
        HeylanaLog.state("mode: ${mode?.label ?: "none"}")
        shownMode = mode
        overlayView?.showMode(mode)
    }

    private var shownMode: BuddyMode? = null

    /** "Remembered" on the strip for two seconds, unless something else has taken the chip by then. */
    private fun flashRemembered() {
        mode(BuddyMode.REMEMBERED)
        main.postDelayed({ if (shownMode == BuddyMode.REMEMBERED) mode(null) }, REMEMBERED_MS)
    }

    /**
     * The disc was touched while Heylana was speaking: she stops at once. A teaching flight
     * in progress stops too (and flies home) rather than moving on to its next sentence.
     */
    private fun interruptSpeech() {
        HeylanaLog.state("speak: stopped by a touch on the disc")
        teaching?.let {
            it.cancel()
            teaching = null
        }
        mouth?.stop()
    }

    // ------------------------------------------------------------------ sending

    /**
     * The model proposed a send. Heylana prepares it; the user signs it — always.
     * The recipient and the amount must be in the user's own words, the worker
     * checks the rest, and the strip says what will happen before anything does.
     */
    private fun handleSend(action: SendAction, question: String) {
        val verdict = SendGuard.check(action, question)
        HeylanaLog.state(
            "send: guard verdict=" + when (verdict) {
                is SendGuard.Verdict.Allowed -> "allowed amount=${verdict.amount} token=${verdict.token}"
                is SendGuard.Verdict.Refused -> "refused reason=${verdict.reason}"
            }
        )
        if (verdict is SendGuard.Verdict.Refused) {
            sayLine(verdict.line)
            return
        }
        val allowed = verdict as SendGuard.Verdict.Allowed
        if (settings.walletSession == null) {
            sayLine(SendText.NO_WALLET)
            return
        }
        overlayView?.setWorking(true)
        mode(BuddyMode.PREPARING)
        scope.launch {
            val prepared = walletApi.prepareSend(allowed.to, allowed.amount, allowed.token, said = question)
            overlayView?.setWorking(false)
            mode(null)
            when (val answer = prepared) {
                is Answer.Ok -> {
                    val quote = answer.value
                    HeylanaLog.state(
                        "send: attempt token=${quote.token} amount=${quote.amount} " +
                            "to=${quote.toAddress.take(4)} cluster=${quote.cluster.id}"
                    )
                    if (SendGuard.overLimit(quote.amount, quote.balance)) {
                        HeylanaLog.state("send: over a quarter of the balance amount=${quote.amount}")
                        overLimit = PendingSend(quote, System.currentTimeMillis())
                        sayLine(TxText.onNetwork(SendGuard.overLimitLine(quote.token), quote.cluster))
                    } else {
                        showSendStrip(quote)
                    }
                }
                is Answer.Refused -> {
                    HeylanaLog.state("send: not prepared reason=${answer.reason}")
                    sayLine(answer.detail.ifBlank { WalletProblem.fromWorker(answer.reason).words })
                }
                is Answer.Unreachable -> sayLine(WalletProblem.UNREACHABLE.words)
            }
        }
    }

    /**
     * The strip goes up at once with Confirm greyed out, while the worker builds the exact
     * transfer and simulates it. Passed: the strip shows the full preview and "Simulation
     * passed", Confirm can be tapped, and the preview is read aloud. Failed: the strip goes
     * and the reason is said; the wallet is never opened.
     */
    private fun showSendStrip(quote: SendQuote) {
        val view = overlayView ?: return
        awaitingConfirm = quote
        simulationPassed = false
        HeylanaLog.state("send: strip token=${quote.token} amount=${quote.amount} to=${quote.toAddress.take(4)}")
        view.showSendConfirm(SendText.strip(quote).removeSuffix(" Confirm?"), ChatPanelView.Simulation.CHECKING)
        overlayView?.setWorking(true)
        mode(BuddyMode.SIMULATING)
        scope.launch {
            val built = walletApi.build(id = quote.id, reference = null, cluster = quote.cluster, final = false)
            overlayView?.setWorking(false)
            mode(null)
            // Cancelled, or another send, while this one was being checked.
            if (awaitingConfirm !== quote) return@launch
            // A failure here is a send that never got as far as prepared: the reason and what
            // to do next, under "Not sent" — the plain reason is the worker's own words.
            val failure: SendResult.Stopped? = when (built) {
                is Answer.Ok -> when (val sim = built.value.simulation) {
                    SimulationResult.Passed -> null
                    is SimulationResult.Failed -> {
                        HeylanaLog.state("send: simulation failed reason=${sim.reason}, wallet not opened")
                        SendResult.Stopped(sim.words, code = sim.reason)
                    }
                }
                is Answer.Refused -> {
                    HeylanaLog.state("send: not built reason=${built.reason}")
                    SendResult.Stopped(built.detail.ifBlank { WalletProblem.fromWorker(built.reason).words }, code = built.reason)
                }
                is Answer.Unreachable -> SendResult.Stopped(WalletProblem.UNREACHABLE.words, code = "unreachable")
            }
            if (failure != null) {
                awaitingConfirm = null
                overlayView?.hideSendConfirm()
                txQuote = quote
                tx = TxState.NotSent(TxEnding.FAILED)
                HeylanaLog.state("tx: none -> ${TxText.NOT_SENT} cluster=${quote.cluster.id}")
                sayCard(TxText.ending(failure).line)
                return@launch
            }
            val preview = (built as Answer.Ok).value.preview
            simulationPassed = true
            HeylanaLog.state("send: simulation passed fee=${preview.feeSol} new_account=${preview.createsAccount} cluster=${preview.cluster}")
            txQuote = quote
            val summary = TxSummary.of(preview).copy(cluster = quote.cluster)
            advance(TxEvent.Built(summary))
            // "Prepared, not signed" with the network's badge: what leaves, what arrives, the
            // fee; what the bytes actually do under it. The first send on this phone also says
            // to approve it in Seed Vault by hand. One line is spoken, never the card.
            HeylanaLog.state("send: does lines=${preview.does.size} grants_power=${preview.grantsPower}")
            overlayView?.showSimulationPassed(TxText.preparedCard(summary, preview.does))
            speak(TxText.preparedSpoken(quote.cluster))
        }
    }

    /** True once the send on the strip has passed its simulation; Confirm does nothing before. */
    private var simulationPassed = false

    private fun confirmSend() {
        val quote = awaitingConfirm ?: return
        if (!simulationPassed) {
            HeylanaLog.state("send: confirm before the simulation passed, ignored")
            return
        }
        awaitingConfirm = null
        simulationPassed = false
        settings.sendConfirmedOnce = true
        mouth?.stop()
        overlayView?.hideSendConfirm()
        // Still prepared and still unsigned while it is built and checked once more.
        showCard(TxText.OPENING_WALLET)
        HeylanaLog.state("send: confirmed, opening Seed Vault")
        // Seed Vault, then the chain: the working orb until the send lands or stops. The
        // card's label says where it is; the chip would only say it twice.
        overlayView?.setWorking(true)
        mode(null)
        SendRelay.progress = { stage -> main.post { onSendStage(stage) } }
        SendRelay.listener = { result -> main.post { onSendResult(result) } }
        startActivity(
            SendActivity.intentFor(this, quote)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
        )
    }

    private fun cancelSend() {
        if (awaitingConfirm == null) return
        awaitingConfirm = null
        simulationPassed = false
        overlayView?.hideSendConfirm()
        HeylanaLog.state("send: cancelled")
        advance(TxEvent.Rejected)
        sayCard(TxText.CANCELLED)
    }

    /** The confirmed send moved on: the wallet opened, or the chain is being asked. */
    private fun onSendStage(stage: SendStage) {
        when (stage) {
            SendStage.SIMULATING -> Unit
            SendStage.APPROVE_IN_WALLET -> {
                advance(TxEvent.WalletOpened)
                showCard(TxText.APPROVE_IN_WALLET)
            }
            SendStage.CHECKING -> {
                advance(TxEvent.Checking(signed = true))
                showCard(TxText.CONFIRMING_DETAIL)
            }
            SendStage.LOOKING -> {
                advance(TxEvent.Checking(signed = false))
                showCard(TxText.LOOKING_DETAIL)
            }
        }
    }

    private fun onSendResult(result: SendResult) {
        SendRelay.listener = null
        SendRelay.progress = null
        overlayView?.setWorking(false)
        mode(null)
        val quote = txQuote
        when (result) {
            is SendResult.Sent -> {
                HeylanaLog.state("send: landed auto_signed=${result.autoSigned} explorer=${result.fullSignature != null}")
                val url = quote?.let { q -> result.fullSignature?.let { TxText.explorerUrl(it, q.cluster) } }
                advance(TxEvent.Landed(result.shortSignature, url))
                // Signed faster than anyone could have approved it: say so, and how to undo it.
                val trusted = if (result.autoSigned) " ${BuildText.AUTO_SIGNED}" else ""
                val done = quote?.let { TxText.done(it.amount, it.token, it.toAddress) } ?: SendText.sent(result.shortSignature)
                val chip = url?.let { listOf(Source(TxText.signatureChipTitle(result.shortSignature), it)) } ?: emptyList()
                sayCard(done + trusted, chip)
            }
            is SendResult.Stopped -> {
                HeylanaLog.state("send: stopped kind=${result.kind} code=${result.code}")
                val ending = TxText.ending(result)
                advance(ending.event)
                sayCard(ending.line)
            }
        }
    }

    // ------------------------------------------------------------ the send's label

    /** Where the send on the strip stands, and the quote it is for. */
    private var tx: TxState? = null
    private var txQuote: SendQuote? = null

    /** Moves the label on; a move that does not fit where the send stands is ignored. */
    private fun advance(event: TxEvent) {
        val before = tx
        val after = TxMachine.next(before, event) ?: return
        if (after == before) return
        tx = after
        HeylanaLog.state("tx: ${before?.label ?: "none"} -> ${after.label} cluster=${txQuote?.cluster?.id}")
    }

    /** The card for where the send stands, with [detail] under its heading. Nothing is spoken. */
    private fun showCard(detail: String) {
        val state = tx ?: return
        val cluster = txQuote?.cluster ?: return
        overlayView?.showTxCard(TxText.card(state, cluster, detail))
    }

    /** The card with [line] under its heading, and [line] spoken — the heading never is. */
    private fun sayCard(line: String, sources: List<Source> = emptyList()) {
        val view = overlayView ?: return
        val state = tx
        val cluster = txQuote?.cluster
        if (state == null || cluster == null) {
            sayLine(line, sources)
            return
        }
        val shown = Sources.spoken(AddressText.shorten(line))
        view.showTxCard(TxText.card(state, cluster, shown))
        view.showSources(sources)
        if (!speak(shown)) settleSoon()
    }

    // ------------------------------------------------------------------ lessons

    /** A lesson from the curriculum starts here; any task or earlier lesson gives way. */
    private fun startLesson(note: xyz.heylana.app.lessons.LessonNote) {
        teaching?.cancel()
        endSession(clearBox = false)
        val fresh = xyz.heylana.app.lessons.lessonFor(note, brain, settings, walletApi)
        lesson = fresh
        HeylanaLog.state("lesson: started topic=${note.id} chunks=${fresh.size} in=buddy")
        lessonTurn { fresh.start() }
    }

    /**
     * One lesson turn: no screen read (a lesson needs none), the quick model, and the line
     * shown and spoken. The user answers by holding the disc or typing in the box.
     */
    private fun lessonTurn(block: suspend () -> xyz.heylana.app.lessons.LessonLine) {
        val view = overlayView ?: return
        exchange.asking()
        mouth?.stop()
        view.showThinking()
        HeylanaLog.state("ask: screen not read why=lesson")
        inFlight = scope.launch {
            mode(BuddyMode.THINKING)
            // The topic's doc page rides under every turn, the last one included.
            val link = lesson?.note?.link
            val line = block()
            mode(null)
            exchange.over()
            view.endVoiceExchange()
            if (line.ended) lesson = null
            sayLine(line.text, listOfNotNull(link))
        }
    }

    /** One of Heylana's own lines: shown with its chips, spoken, then back to rest. */
    private fun sayLine(line: String, sources: List<Source> = emptyList()) {
        val view = overlayView ?: return
        val shown = Sources.spoken(AddressText.shorten(line))
        view.showNotice(shown)
        view.showSources(sources)
        if (!speak(shown)) settleSoon()
    }

    /**
     * A source chip was tapped: its page opens in the browser, in front of Heylana, and the
     * box goes. The log has the host only.
     */
    private fun openSource(source: Source) {
        val uri = android.net.Uri.parse(source.url)
        if (uri.scheme != "https") return
        HeylanaLog.state("source: opened host=${uri.host}")
        mouth?.stop()
        runCatching {
            startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }.onFailure { HeylanaLog.state("source: no browser ${it.javaClass.simpleName}") }
        overlayView?.closePanel()
    }

    // ------------------------------------------------------------------ teaching

    /** The teaching answer being played, one segment at a time. */
    private var teaching: TeachingRun? = null

    /**
     * A teaching answer: the disc flies to each element as its sentence is spoken, a ring
     * breathes around it, the strip travels beside the disc, and after the last one the disc
     * flies home. A segment with no element is spoken where the disc already stands.
     */
    private inner class TeachingRun(
        private val segments: List<SaySegment>,
        private val snapshot: ScreenSnapshot,
        /** Shown beside the last sentence, which then stays up long enough to tap one. */
        private val sources: List<Source> = emptyList(),
        /** Debug only: dwell as if spoken, with no voice call and no tap watch. */
        private val silent: Boolean = false
    ) {
        private var index = -1

        private val advance = Runnable { if (teaching === this) next() }

        fun start() {
            HeylanaLog.state("teach: run segments=${segments.size} points=${segments.count { it.pointAt != null }}")
            next()
        }

        private fun next() {
            main.removeCallbacks(advance)
            index++
            val view = overlayView ?: return finish()
            val segment = segments.getOrNull(index) ?: return finish()
            // A sentence that tells the user to do something points at what it names.
            val pointed = segment.pointAt?.let { id ->
                StepTarget.choose(segment.text, id, snapshot.nodes.map { StepTarget.Candidate(it.id, it.text ?: it.contentDescription, it.editable, it.clickable) })
            }
            if (pointed != segment.pointAt) HeylanaLog.state("teach: pointer moved to the instruction's element from=${segment.pointAt} to=$pointed")
            val node = pointed?.let { snapshot.node(it) }
            HeylanaLog.state("teach: segment ${index + 1}/${segments.size} element=${if (node != null) "yes" else "none"}")
            // The chips go up with the last sentence, before the window is measured and placed.
            view.showSources(if (index == segments.lastIndex) sources else emptyList())
            if (node == null) {
                // Nothing to fly to: said from where the disc stands.
                view.showAnswer(segment.text)
                speakSegment(segment.text)
                return
            }
            view.teachTo(node.bounds, segment.text) {
                if (teaching !== this) return@teachTo
                highlight?.ring(node.bounds)
                view.lookAt(android.graphics.PointF(node.bounds.exactCenterX(), node.bounds.exactCenterY()))
                if (!silent) watchForTap(node)
                speakSegment(segment.text)
            }
        }

        /** Spoken, or — with no voice — left up long enough to read. */
        private fun speakSegment(text: String) {
            if (silent || !speak(text)) main.postDelayed(advance, readingMs(text) + linger())
        }

        /** After the last sentence, chips keep the strip up a while before the disc flies home. */
        private fun linger(): Long = if (index == segments.lastIndex && sources.isNotEmpty()) SOURCE_LINGER_MS else 0L

        /** The segment finished speaking: a breath, then the next one. */
        fun spoken() {
            main.postDelayed(advance, BETWEEN_SEGMENTS_MS + linger())
        }

        /** The voice could not speak this one: read it instead, then carry on. */
        fun unspoken(text: String) {
            main.postDelayed(advance, readingMs(text) + linger())
        }

        private fun finish() {
            main.removeCallbacks(advance)
            if (teaching === this) teaching = null
            stopTapWatch()
            highlight?.hide()
            HeylanaLog.state("teach: run done, flying home")
            overlayView?.endTeaching()
        }

        fun cancel() {
            main.removeCallbacks(advance)
            if (teaching === this) teaching = null
            overlayView?.cancelTeachingFlight()
        }
    }

    /** Starts a teaching answer if it has anywhere to fly; false when it is an ordinary answer. */
    private fun runTeaching(reply: BrainReply.Say, snapshot: ScreenSnapshot, sources: List<Source> = emptyList()): Boolean {
        val segments = reply.segments
        if (!reply.teaches) return false
        if (segments.none { it.pointAt?.let { id -> snapshot.node(id) } != null }) {
            HeylanaLog.state("teach: segments point at nothing on this screen")
            return false
        }
        teaching?.cancel()
        val run = TeachingRun(segments, snapshot, sources)
        teaching = run
        run.start()
        return true
    }

    /**
     * An answer about a page that continues below: from the part on screen, with the line that
     * says so; or, when the model said the answer isn't on screen, the page's own chip first.
     */
    private fun onScreenOnly(reply: BrainReply.Say, snapshot: ScreenSnapshot): BrainReply.Say {
        val page = snapshot.pageAddress?.let(Sources::forPage)
        HeylanaLog.state("page: more below line=${if (reply.unseen) "not_added why=unseen" else "added"} page_chip=${page != null}")
        if (reply.unseen) return reply.copy(sources = listOfNotNull(page) + reply.sources)
        val text = xyz.heylana.app.screen.PageExtent.withLine(reply.text, moreBelow = true, unseen = false)
        // One piece again when it was one; a walk keeps its pieces and gains the line as the last.
        val segments = if (reply.segments.size <= 1) listOf(SaySegment(text, reply.segments.firstOrNull()?.pointAt ?: reply.pointAt))
        else reply.segments + SaySegment(xyz.heylana.app.screen.PageExtent.LINE, null)
        return reply.copy(text = text, segments = segments)
    }

    /** An ordinary answer: say it, point once, let the box time out by itself. */
    private fun showOneShot(reply: BrainReply.Say, snapshot: ScreenSnapshot) {
        // Where it came from, and the page in front when that is a web page.
        val sources = Sources.chips(reply.sources + listOfNotNull(snapshot.pageAddress?.let(Sources::forPage)))
        // An answer that walks the screen is played segment by segment instead.
        if (runTeaching(reply, snapshot, sources)) return
        val view = overlayView ?: return
        // A spoken answer normally leaves nothing on screen; the Settings switch
        // is what puts its words in a box.
        if (view.wasSpoken && settings.showTextForVoice) view.ensurePanelOpen()
        // The code first where there is code: it is what a developer came for, and it is
        // shown only — what is spoken below is the line about it.
        view.showAnswer(xyz.heylana.app.brain.Sources.shown(reply.code, reply.text))
        view.showSources(sources)
        // The snapshot is still in hand, so the id resolves to real bounds.
        snapshot.node(reply.pointAt)?.let { node ->
            view.avoidOverlap(node.bounds)
            highlight?.point(node.bounds, view.spriteCenterOnScreen())
            view.lookAt(android.graphics.PointF(node.bounds.exactCenterX(), node.bounds.exactCenterY()))
            watchForTap(node)
        }
        // Muted, or no voice on this device: nothing will report speech ending,
        // so the way back to idle has to be booked here instead.
        if (!speak(reply.text)) settleSoon()
    }

    /** Puts everything back to rest a beat from now, cancelling any earlier one. */
    private fun settleSoon() {
        main.removeCallbacks(settleToIdle)
        main.postDelayed(settleToIdle, settleDelay())
    }

    /** A beat after an answer; long enough to reach a chip when the answer has source chips. */
    private fun settleDelay(): Long = if (overlayView?.showingSources == true) SOURCE_LINGER_MS else SETTLE_MS

    /**
     * Debug builds only: how big the screen listing is before it is sent, so the
     * cost of a request can be watched without making one. Counts only, never a
     * word of what is on screen.
     */
    private fun logScreenSize(snapshot: ScreenSnapshot, screenText: String) {
        if (!BuildConfig.DEBUG) return
        Log.d(
            Proxy.USAGE_TAG,
            "screen elements=${snapshot.nodes.size} chars=${screenText.length} " +
                "memory=${conversation.size} exchanges"
        )
    }

    private fun readScreen(view: BuddyOverlayView): ScreenSnapshot? {
        if (!HeylanaAccessibilityService.isConnected) {
            exchange.over()
            view.showNotice(SCREEN_READING_OFF)
            return null
        }
        val snapshot = HeylanaAccessibilityService.snapshotOrNull()
        if (snapshot == null || snapshot.isEmpty) {
            exchange.over()
            view.showNotice(
                "I couldn't see anything on this screen. Let it finish loading and ask again."
            )
            return null
        }
        return snapshot
    }

    // ----------------------------------------------------- tap acknowledgement

    /**
     * While a box is on screen, watch for the user acting on what it points at:
     * a tap on that element, or the screen moving. Either one flashes the box
     * green and clears it, and Heylana says nothing about it.
     *
     * An answer's box gives up after fifteen seconds and clears quietly. A step's
     * box does not: it belongs to the step and stays until the step changes or
     * the task ends, while the watch behind it keeps running so acting on it is
     * still acknowledged.
     *
     * Events are on for that window only — see the privacy rule in
     * [HeylanaAccessibilityService].
     */
    private fun watchForTap(node: ScreenNode) {
        stopTapWatch()
        val inSession = session != null
        tapWatch = TapWatch(
            elementKey = node.key,
            armedAt = SystemClock.uptimeMillis(),
            persistent = inSession
        )
        HeylanaAccessibilityService.watchTaps { signal -> main.post { onTapSignal(signal) } }
        if (!inSession) main.postDelayed(tapWatchExpired, TapWatch.WINDOW_MS)
    }

    private fun onTapSignal(signal: ScreenSignal) {
        val watch = tapWatch ?: return
        when (watch.consider(SystemClock.uptimeMillis(), signal)) {
            Verdict.ACKNOWLEDGE -> endTapWatch(acknowledged = true)
            Verdict.EXPIRE -> endTapWatch(acknowledged = false)
            Verdict.IGNORE -> Unit
        }
    }

    private fun endTapWatch(acknowledged: Boolean) {
        if (tapWatch == null) return
        stopTapWatch()
        if (acknowledged) {
            highlight?.acknowledge()
        } else {
            highlight?.hide()
            overlayView?.stopLooking()
        }
    }

    /** Stops watching without touching the box — the caller owns what it does. */
    private fun stopTapWatch() {
        main.removeCallbacks(tapWatchExpired)
        if (tapWatch == null) return
        tapWatch = null
        HeylanaAccessibilityService.watchTaps(null)
    }

    // -------------------------------------------------------------- guidance

    private fun startSession(goal: String, reply: BrainReply.Say, snapshot: ScreenSnapshot, teaching: Boolean = false) {
        session = GuidanceSession(goal).also { it.teaching = teaching }
        if (teaching) HeylanaLog.state("teach: task started teaching=true")
        conversation.clear()
        // Screen-change events are switched on here and nowhere else.
        HeylanaAccessibilityService.watchScreenChanges { from -> main.post { onScreenChanged(from) } }
        showStep(reply, snapshot)
    }

    /** Draws, speaks and records one step of the running task. */
    private fun showStep(reply: BrainReply.Say, snapshot: ScreenSnapshot) {
        val view = overlayView ?: return
        val current = session ?: return

        // A step given as pieces points where its first pointing piece does, and at what takes
        // the tap: a label's clickable card rather than the label.
        // …and at what the words tell the user to act on next, if the model pointed elsewhere.
        val modelPointed = reply.pointAt ?: reply.segments.firstNotNullOfOrNull { it.pointAt }
        val pointed = StepTarget.choose(
            reply.text,
            modelPointed,
            snapshot.nodes.map { StepTarget.Candidate(it.id, it.text ?: it.contentDescription, it.editable, it.clickable) }
        )
        if (pointed != modelPointed) HeylanaLog.state("step: pointer moved to the instruction's element from=$modelPointed to=$pointed")
        val node = snapshot.node(pointed)?.let { snapshot.clickTarget(it) }
        val repeated = current.record(
            say = reply.text,
            elementKey = node?.key,
            elementLabel = node?.label,
            packageName = snapshot.packageName
        )

        val spoken = if (repeated) STUCK_LINE else reply.text

        // A teaching session: the disc flies to the step's element and stays beside it with
        // the ring; the step moves on when the user does it, so there is no Next.
        if (current.teaching) {
            view.ensurePanelOpen()
            view.showSession(current.stepNumber, GuidanceSession.MAX_STEPS, withNext = false)
            if (node == null) {
                highlight?.hide()
                view.stopLooking()
                view.showAnswer(spoken)
                armStep(null, snapshot)
                if (speak(spoken)) stepSpeaking(true)
                return
            }
            view.teachTo(node.bounds, spoken) {
                if (session !== current) return@teachTo
                highlight?.ring(node.bounds)
                view.lookAt(android.graphics.PointF(node.bounds.exactCenterX(), node.bounds.exactCenterY()))
                // The step's clock starts as the disc lands.
                armStep(node, snapshot)
                if (speak(spoken)) stepSpeaking(true)
            }
            return
        }

        // A step that only explains walks the screen and needs no Next or Done: the user
        // is not being asked to change anything yet.
        if (!repeated && Teaching.stepWalksTheScreen(reply.segments.size, reply.teaches) && runTeaching(reply, snapshot)) {
            view.hideSession()
            return
        }

        view.ensurePanelOpen()
        // Text and chip first, so the card is its final size before it is moved
        // clear of whatever is about to be boxed.
        view.showAnswer(spoken)
        view.showSession(current.stepNumber, GuidanceSession.MAX_STEPS)

        if (node != null) {
            view.avoidOverlap(node.bounds)
            // Stays up until the step changes: the user needs it while they look.
            highlight?.point(node.bounds, view.spriteCenterOnScreen(), persistent = true)
            view.lookAt(android.graphics.PointF(node.bounds.exactCenterX(), node.bounds.exactCenterY()))
        } else {
            // No box for this step, but the task is still running.
            highlight?.hide()
            view.stopLooking()
        }
        armStep(node, snapshot)
        if (speak(spoken)) stepSpeaking(true)
    }

    /**
     * Moves the task on: reads the screen again and asks for the next step.
     * [userAsked] means they tapped Next, which also clears a stuck session.
     */
    private fun advance(userAsked: Boolean) {
        val view = overlayView ?: return
        val current = session ?: return
        if (inFlight?.isActive == true) return
        if (userAsked) current.unstick()
        disarmStep()

        main.removeCallbacks(autoAdvanceCheck)

        if (current.atCap) {
            closeTask("That is as far as I can take you, so let's stop here.")
            return
        }

        // Only the user's own Next cuts the line; a step done by itself lets it finish,
        // and the next step's line waits behind it in the voice's queue.
        if (userAsked) mouth?.stop()
        view.setWorking(true)
        mode(BuddyMode.WORKING)
        exchange.asking()
        view.showThinking()
        view.hideKeyboard()

        inFlight = scope.launch {
            delay(KEYBOARD_SETTLE_MS)
            val snapshot = readScreen(view)
            if (snapshot == null) {
                view.setWorking(false)
                return@launch
            }

            val screenText = snapshot.toPromptText()
            logScreenSize(snapshot, screenText)

            val reply = brain.nextStep(
                goal = current.goal,
                historyText = current.historyText(),
                screenText = screenText,
                stepNumber = current.stepNumber + 1,
                needPointerHint = current.lastStepHadNoPointer,
                skill = skills.pick(snapshot.packageName, current.goal),
                teaching = current.teaching
            )
            view.setWorking(false)
            mode(null)
            exchange.over()

            if (reply is BrainReply.Say) {
                HeylanaLog.state(
                    "step: reply task=${reply.task != null} done=${reply.task?.done} pointed=${reply.pointAt != null} " +
                        "segments=${reply.segments.size}"
                )
            }
            when (reply) {
                is BrainReply.Say -> {
                    if (reply.task?.done == true) {
                        closeTask(reply.text)
                    } else {
                        showStep(reply, snapshot)
                    }
                }

                // Any API trouble ends the task cleanly, with the reason on screen.
                is BrainReply.Failed -> {
                    view.showNotice(reply.message)
                    endSession(clearBox = true)
                }
            }
        }
    }

    /** Called only while a session is live — see the accessibility service. */
    private fun onScreenChanged(from: String?) {
        val current = session ?: return
        if (current.stuck) return
        if (inFlight?.isActive == true) return
        // Heylana's own windows moving, or an event with no app, is not the user doing anything.
        if (from == null || from == packageName) return
        lastChangeFrom = from
        main.removeCallbacks(autoAdvanceCheck)
        main.postDelayed(autoAdvanceCheck, AUTO_ADVANCE_DEBOUNCE_MS)
    }

    /** The app the last screen change came from, for [considerAutoAdvance]. */
    private var lastChangeFrom: String? = null

    /**
     * The step is finished when the thing we pointed at has gone, or the user has
     * moved to a different app.
     */
    private fun considerAutoAdvance() {
        val current = session ?: return
        if (current.stuck) return
        if (inFlight?.isActive == true) return

        val step = stepAdvance ?: return
        val snapshot = HeylanaAccessibilityService.snapshotOrNull() ?: return
        if (snapshot.isEmpty) return
        val decision = step.onContentChange(
            SystemClock.uptimeMillis(), lastChangeFrom, snapshot.packageName, StepAdvance.signature(snapshot.nodes)
        )
        if (step.lastDifference > StepAdvance.CHANGE_THRESHOLD && decision is StepAdvance.Decision.Ignore) {
            HeylanaLog.state("step: change without a tap ignored diff=${"%.2f".format(step.lastDifference)}")
        }
        decideStep(decision)
    }

    // ------------------------------------------------------------ step advance

    /** The rule for when the running step is done: see [StepAdvance]. */
    private var stepAdvance: StepAdvance? = null
    private val stepTick = Runnable { stepAdvance?.let { decideStep(it.tick(SystemClock.uptimeMillis())) } }

    /**
     * Starts the rule for a step whose disc has just landed (or whose box has just gone
     * up): taps come from the tap feed, content changes from the screen feed, and once the
     * quiet time is over anything held is looked at again.
     */
    private fun armStep(node: ScreenNode?, snapshot: ScreenSnapshot) {
        stopTapWatch()
        main.removeCallbacks(stepTick)
        val now = SystemClock.uptimeMillis()
        stepAdvance = StepAdvance(node?.key, snapshot.packageName, StepAdvance.signature(snapshot.nodes), now)
        HeylanaAccessibilityService.watchTaps { signal -> main.post { onStepSignal(signal) } }
        main.postDelayed(stepTick, StepAdvance.QUIET_MS + STEP_TICK_SLACK_MS)
        HeylanaLog.state("step: armed pointed=${node != null} quiet_ms=${StepAdvance.QUIET_MS}")
        // Debug builds only (HeylanaLog is): where it is, never what it says.
        node?.bounds?.let { HeylanaLog.state("step: pointed bounds=${it.left},${it.top},${it.right},${it.bottom} pkg=${snapshot.packageName}") }
    }

    private fun onStepSignal(signal: ScreenSignal) {
        val step = stepAdvance ?: return
        if (signal !is ScreenSignal.Clicked) return
        decideStep(step.onClick(SystemClock.uptimeMillis(), signal.key, signal.packageName))
    }

    /** The step's line has started or finished being spoken. */
    private fun stepSpeaking(speaking: Boolean) {
        val step = stepAdvance ?: return
        if (speaking) step.speechStarted() else decideStep(step.speechEnded(SystemClock.uptimeMillis()))
    }

    private fun decideStep(decision: StepAdvance.Decision) {
        val step = stepAdvance
        if (decision is StepAdvance.Decision.Wait && step != null && step.holding && !step.speaking) {
            // Held until the quiet time ends: look again then, not before.
            main.removeCallbacks(stepTick)
            main.postDelayed(stepTick, step.quietLeft(SystemClock.uptimeMillis()) + STEP_TICK_SLACK_MS)
        }
        if (decision is StepAdvance.Decision.Wait && step?.holding == true) {
            HeylanaLog.state("step: holding until ${if (step.speaking) "the line ends" else "the quiet time ends"}")
        }
        if (decision !is StepAdvance.Decision.Advance) return
        HeylanaLog.state("advance reason=${decision.reason.log}")
        disarmStep()
        if (decision.reason == StepAdvance.Reason.CLICK) highlight?.acknowledge()
        advance(userAsked = false)
    }

    private fun disarmStep() {
        main.removeCallbacks(stepTick)
        stepAdvance = null
        HeylanaAccessibilityService.watchTaps(null)
    }

    /** The user tapped Done: say so, then close the task down. */
    private fun stopSessionOnRequest() {
        if (session == null) return
        mouth?.stop()
        closeTask(STOPPED_LINE)
    }

    /**
     * The task is over: say [line] and end it. A teaching session's disc is standing beside
     * the last element, so once the line has been spoken (or read) it flies home and the
     * strip melts.
     */
    private fun closeTask(line: String) {
        val view = overlayView ?: return
        val teaching = session?.teaching == true
        view.showAnswer(line)
        endSession(clearBox = true)
        if (!teaching) {
            speak(line)
            return
        }
        flyHomeAfterSpeech = true
        if (!speak(line)) main.postDelayed(flyHomeNow, readingMs(line))
    }

    /** Set while a teaching session's last line is spoken: its end sends the disc home. */
    private var flyHomeAfterSpeech = false
    private val flyHomeNow = Runnable {
        if (!flyHomeAfterSpeech) return@Runnable
        flyHomeAfterSpeech = false
        HeylanaLog.state("teach: session over, flying home")
        overlayView?.endTeaching()
    }

    private fun endSession(clearBox: Boolean) {
        main.removeCallbacks(autoAdvanceCheck)
        disarmStep()
        val ending = session ?: return
        session = null
        // Kept in memory for "what did I just do": the goal and the one-line steps, nothing more.
        if (ending.stepNumber > 0) {
            lastTask = FinishedTask.of(ending)
            HeylanaLog.state("teach: task kept for recap steps=${ending.stepNumber} on_chain=${lastTask?.onChain}")
        }
        // Events go straight back off: no session, no listening.
        HeylanaAccessibilityService.watchScreenChanges(null)
        overlayView?.hideSession()
        if (clearBox) {
            stopTapWatch()
            highlight?.hide()
            overlayView?.stopLooking()
        }
    }

    /** The task that ended last, for "what did I just do". Memory only; gone when the buddy stops. */
    private var lastTask: FinishedTask? = null

    /** "Why?" on a step: its reason is shown and spoken, and the task carries on, now teaching. */
    private fun explainStep(running: GuidanceSession, question: String) {
        val view = overlayView ?: return
        running.teaching = true
        HeylanaLog.state("teach: why on step=${running.stepNumber} teaching=true")
        mouth?.stop()
        view.showThinking()
        view.hideKeyboard()
        inFlight = scope.launch {
            val reply = brain.explainStep(running.goal, running.historyText(), question)
            exchange.over()
            view.endVoiceExchange()
            // The task may have moved on or ended while the reason was on its way.
            if (session !== running) return@launch
            when (reply) {
                is BrainReply.Say -> {
                    view.showAnswer(reply.text)
                    view.showSession(running.stepNumber, GuidanceSession.MAX_STEPS)
                    speak(reply.text)
                }
                is BrainReply.Failed -> view.showNotice(reply.message)
            }
        }
    }

    /** "What did I just do": the task that just ended, recapped; on chain, with what landed. */
    private fun recapTask(task: FinishedTask, question: String) {
        val view = overlayView ?: return
        HeylanaLog.state("teach: recap on_chain=${task.onChain}")
        view.showThinking()
        view.hideKeyboard()
        inFlight = scope.launch {
            val reply = brain.recap(task, question)
            exchange.over()
            view.endVoiceExchange()
            when (reply) {
                is BrainReply.Say -> {
                    conversation.record(question, reply.text, null)
                    showOneShot(reply.copy(pointAt = null, task = null), ScreenSnapshot.empty())
                }
                is BrainReply.Failed -> view.showNotice(reply.message)
            }
        }
    }

    /**
     * The voice could not speak these words (capped, out of quota, timed out, failed):
     * they are shown instead and Heylana stays silent. A task keeps its box; anything
     * else settles once there has been time to read it.
     */
    private fun showUnspoken(text: String, reason: String = VoiceFailure.ERROR) {
        val view = overlayView ?: return
        keepSpeechThroughClose = false
        // A silence with a reason the user can do nothing about says so, once, under the words.
        if (VoiceFailure.isOverLimit(reason)) view.showNote(VoiceFailure.OVER_LIMIT_LINE)
        // Mid-teaching the words are already beside the disc: read them, then fly on.
        teaching?.let { run ->
            run.unspoken(text)
            return
        }
        if (flyHomeAfterSpeech) {
            main.postDelayed(flyHomeNow, readingMs(text))
            return
        }
        if (session != null) {
            view.showAnswer(text)
            // Not spoken, but it is a line all the same: the step may move on once it has been read.
            main.postDelayed({ if (session != null) stepSpeaking(false) }, readingMs(text))
            return
        }
        view.showNotice(text)
        if (exchange.maySettle) {
            main.removeCallbacks(settleToIdle)
            main.postDelayed(settleToIdle, readingMs(text))
        }
    }

    /** Long enough to read [text]: 350ms a word, between 4 and 12 seconds. */
    private fun readingMs(text: String): Long =
        (AnswerLength.words(text) * READ_MS_PER_WORD).coerceIn(MIN_READ_MS, MAX_READ_MS)

    // ---------------------------------------------------------------- the lookout

    /** The phishing list, kept on the phone and checked here; never a question to anyone. */
    private val blocklist by lazy { xyz.heylana.app.screen.Blocklist(this, settings) }

    /** What the last glance was about, so one screen is not glanced at twice. */
    private var lastGlance: Lookout.Glance? = null

    /** When the last window change was looked at, so a burst of them is one look. */
    private var lastLookAt = 0L

    /**
     * Starts or stops watching for a new window. On while the buddy runs and "Watch signing
     * screens" is on, off the moment either stops — this is the third and last thing that
     * may switch accessibility events on at all, and it hears a new window and nothing else.
     */
    internal fun applyLookout() {
        val on = settings.watchSigning && HeylanaAccessibilityService.isRunning(this)
        if (!on) {
            HeylanaAccessibilityService.watchWindows(null)
            hideGlance()
            HeylanaLog.state("watch=signing off")
            return
        }
        HeylanaAccessibilityService.watchWindows { event -> main.post { onNewWindow(event) } }
        HeylanaLog.state("watch=signing on")
        scope.launch {
            blocklist.loadFromDisk()
            blocklist.refresh()
        }
    }

    /**
     * A new window came up. One read, on the phone, and one line if it deserves one: a
     * signing screen, a page asking for a recovery phrase, a domain that is a copy of a
     * real one. Nothing is sent anywhere and nothing is spoken.
     */
    /**
     * A new window came up.
     *
     * A burst of them is one screen settling, so looks are spread out — but a burst is
     * also exactly how a wallet's confirm sheet arrives, behind the screen it came from.
     * So a look that comes too soon is **held, never dropped**: the one that matters is
     * usually the last one in the burst. (Dropping it is what made warnings arrive late,
     * or not at all: three confirm screens in a row, no glance for any of them.)
     */
    private fun onNewWindow(event: xyz.heylana.app.screen.WindowEvent) {
        val from = event.packageName
        val at = event.at
        // Debug builds: what a window says about itself, before anything is read.
        HeylanaLog.state("glance: window pkg=$from class=${event.className} title=${event.title.take(50)}")
        if (!settings.watchSigning) return
        // Busy: an exchange, a task or a send has the buddy already, and a glance would
        // land on top of whatever the user asked for.
        if (lookoutBusy()) return
        if (from == packageName) return

        // A signature is being asked for: say so **now**, before the screen is read. A
        // wallet's tree takes long enough to read that the warning used to arrive after the
        // user had approved. Nothing is waited for here — not the read, not the worker —
        // because the sentence is one of the fixed few and its audio is already on the phone.
        val announce = Lookout.signingWindow(event) || (BuildConfig.DEBUG && settings.glanceAnyScreen)
        if (announce) speakOnSight(event)
        // A wallet's window is read the instant it appears — no gap, nothing waited for.
        // Seed Vault's has already been spoken for; a wallet's says too little about itself
        // to tell a confirm sheet from a settings dialog without reading it.
        if (announce || Lookout.urgentWindow(event)) {
            look(at, second = false)
            return
        }

        val now = SystemClock.uptimeMillis()
        val since = now - lastLookAt
        if (since < LOOK_EVERY_MS) {
            // Held: the newest window wins, and it is looked at the moment the gap is up.
            // Never held for longer than one gap's worth, whatever else keeps arriving.
            heldWindow = at
            if (heldSince == 0L) heldSince = now
            main.removeCallbacks(heldLook)
            val wait = if (now - heldSince >= LOOK_EVERY_MS) 0L else LOOK_EVERY_MS - since
            main.postDelayed(heldLook, wait)
            HeylanaLog.state("glance: held for ${wait}ms")
            return
        }
        heldSince = 0L
        look(at, second = false)
    }

    /** When the first look of a burst was held, so a burst cannot hold one for ever. */
    private var heldSince = 0L

    /** The window the opening line was said for, so one window is announced once. */
    private var announcedWindow: String? = null

    /**
     * The first sentence, on the window event itself. Playback only: the words are fixed
     * and their audio is kept on the phone, so this is the shortest path there is between
     * a signing window appearing and the user hearing about it.
     */
    private fun speakOnSight(event: xyz.heylana.app.screen.WindowEvent) {
        val window = "${event.packageName}|${event.className}|${event.title}"
        if (window == announcedWindow) {
            HeylanaLog.state("glance: signing window again, already announced")
            return
        }
        announcedWindow = window
        glanceAt = event.at
        glanceSpoke = false
        val spoke = speakWarning(Lookout.OPENING_LINE)
        // window_event_ms is the zero of everything that follows; spoke_asked_ms says the
        // voice was asked for before the screen was read, which is the whole point.
        HeylanaLog.state(
            "glance: signing window pkg=${event.packageName} class=${event.className} " +
                "window_event_ms=0 spoke_asked_ms=${SystemClock.uptimeMillis() - event.at} spoken=$spoke"
        )
        if (!spoke) HeylanaLog.state("glance: opening line not spoken (muted or no voice); the strip still comes")
    }

    /** The window a held look is for, and the look itself. */
    private var heldWindow = 0L
    private val heldLook = Runnable { look(heldWindow, second = false) }

    /**
     * A screen that was still drawing when it was read says nothing, and no second event
     * has to come. So a look that found nothing takes one more, once, a moment later.
     */
    private val secondLook = Runnable { look(heldWindow, second = true) }

    /**
     * Whether the buddy is already busy with something the user asked for. A send counts
     * until it ends, not only while its strip waits for Confirm: Seed Vault's window for
     * Heylana's own send is the signature the user just asked for, and on the Seeker the
     * lookout greeted it with "Careful…" and a warning over the "Waiting for your wallet"
     * card — then took the "Done" card away with its glance.
     */
    private fun lookoutBusy(): Boolean =
        exchange.inProgress || session != null || awaitingConfirm != null || teaching != null ||
            // After Confirm and until the send ends — which it always does, with a result.
            tx is TxState.Waiting || tx is TxState.Confirming

    private fun look(at: Long, second: Boolean) {
        if (!settings.watchSigning) return
        if (lookoutBusy()) return
        lastLookAt = SystemClock.uptimeMillis()
        // The clock for everything after it starts at the window, not at the look.
        if (glanceAt != at) {
            glanceAt = at
            glanceSpoke = false
        }
        val readStarted = SystemClock.uptimeMillis()
        val snapshot = HeylanaAccessibilityService.snapshotOrNull() ?: return
        val decideStarted = SystemClock.uptimeMillis()
        val glance = Lookout.glanceAt(
            snapshot, blocklist.domains, settings.walletSession?.pubkey,
            anySigns = BuildConfig.DEBUG && settings.glanceAnyScreen,
        )
        val decided = SystemClock.uptimeMillis()
        HeylanaLog.state(
            "glance: looked why=${glance?.why ?: "none"} second=$second to_look_ms=${readStarted - at} " +
                "read_ms=${decideStarted - readStarted} decide_ms=${decided - decideStarted} " +
                "look_done_ms=${decided - at}"
        )
        if (glance == null) {
            // Nothing to say here — but this is a wallet or a browser, which is where the
            // next thing to say usually comes from, so the connection is opened now rather
            // than when a warning is already waiting on it.
            warmForGlance(snapshot.packageName)
            // Off that screen: the glance it had goes with it.
            if (lastGlance != null && shownGlance) hideGlance()
            lastGlance = null
            // It may simply not have finished drawing: look once more, then let it be.
            if (!second) {
                heldWindow = at
                main.removeCallbacks(secondLook)
                main.postDelayed(secondLook, SECOND_LOOK_MS)
            }
            return
        }
        main.removeCallbacks(secondLook)
        if (Lookout.sameAsBefore(glance, lastGlance)) {
            HeylanaLog.state("glance: skipped why=same_screen")
            return
        }
        lastGlance = glance
        showGlance(glance)
    }

    /** When the window that is being glanced at came up: everything after it is ours. */
    private var glanceAt = 0L

    /**
     * Debug builds: the glance the lookout would show, on text of its own. It goes through
     * exactly the same path as a real one, so what is checked is the real thing.
     */
    private fun debugGlance(which: String) {
        val text = when (which) {
            "secret" -> "Wallet sync required\nEnter your 12-word recovery phrase to restore access\nSubmit"
            "approval" -> "Approve spending\nToken USDC\nSpending cap Unlimited\nSpender 9WzD…AWWM\nApprove Reject"
            else -> "Approve transaction\n0.05 USDC\nTo 7c2y…SxSv\nNetwork fee 0.000005 SOL\nApprove Reject"
        }
        val glance = when (which) {
            "secret" -> Lookout.Glance(
                xyz.heylana.app.screen.ScamWatch.SEED_PHRASE,
                Lookout.spokenWarning(Lookout.Why.SECRET), Lookout.Why.SECRET, "debug:$which"
            )
            "lookalike" -> Lookout.Glance(
                xyz.heylana.app.screen.ScamWatch.lookAlikeWords("phantom.app"),
                Lookout.spokenWarning(Lookout.Why.LOOK_ALIKE), Lookout.Why.LOOK_ALIKE, "debug:$which"
            )
            else -> Lookout.Glance(
                Lookout.signingLine(text), Lookout.spokenSigning(text), Lookout.Why.SIGNING, "debug:$which"
            )
        }
        // The debug glance is timed like a real one, from the moment it was asked for.
        glanceAt = SystemClock.uptimeMillis()
        HeylanaLog.state("debug: glance $which")
        lastGlance = glance
        showGlance(glance)
    }

    /** True while a glance of the lookout's own is on screen. */
    private var shownGlance = false

    /** The screen the last spoken warning was about, so one screen is never said twice. */
    private var spokenGlanceAbout: String? = null

    /**
     * Opens the connection to the proxy when a wallet or a browser comes up, so the voice
     * of a warning does not start with a handshake. At most once per warm window, and only
     * where a warning is actually likely — never on every screen the user opens.
     */
    private fun warmForGlance(packageName: String?) {
        if (packageName == null) return
        val kind = xyz.heylana.app.brain.SolanaApps.of(packageName)?.kind
        val worthIt = kind == xyz.heylana.app.brain.SolanaApps.Kind.WALLET ||
            kind == xyz.heylana.app.brain.SolanaApps.Kind.SIGNING ||
            packageName in xyz.heylana.app.lessons.LessonWords.BROWSERS
        if (!worthIt) return
        scope.launch {
            xyz.heylana.app.net.Proxy(settings).warmUp()
            // And the words themselves, so the first warning does not wait on the voice
            // either. One line per screen change at most; a line already kept costs nothing.
            val voice = mouth ?: return@launch
            if (settings.voiceMuted) return@launch
            for (line in Lookout.SPOKEN_LINES) {
                if (voice.prefetch(line)) break
            }
        }
    }

    private fun showGlance(glance: Lookout.Glance) {
        val view = overlayView ?: return
        // Counts and kinds only: never the domain, never a word of the screen.
        // The one number that matters: the window came up, and this is when the line is on
        // screen. Never the words of it, and never what app it was.
        HeylanaLog.state(
            "watch=signing glance why=${glance.why} chars=${glance.line.length} " +
                "glance_ms=${if (glanceAt > 0) SystemClock.uptimeMillis() - glanceAt else -1}"
        )
        shownGlance = true
        // Watching is not thinking and not answering: the chip says which it is.
        mode(if (glance.why == Lookout.Why.SIGNING) BuddyMode.WATCHING else BuddyMode.HEADS_UP)
        view.showGlance(glance.line)
        // And she says it. A warning that is only on screen is a warning nobody read: the
        // thumb is already on the way to Approve. One short sentence, once per screen, and
        // muted means the line on the strip is the whole of it.
        if (glance.about != spokenGlanceAbout) {
            spokenGlanceAbout = glance.about
            // A signing window has already been announced from the event itself. The look
            // only adds a voice when it found something worse than a transfer — an
            // approval, a handover, a close — otherwise the amount and the address are on
            // the strip and there is nothing more worth saying out loud.
            val alreadyAnnounced = glance.why == Lookout.Why.SIGNING && announcedWindow != null
            val say = if (alreadyAnnounced) Lookout.strongerLine(glance) else glance.spoken
            if (say == null) {
                HeylanaLog.state("glance: nothing worse than a transfer; the strip has the rest")
            } else {
                if (!alreadyAnnounced) glanceSpoke = false
                if (speakWarning(say)) {
                    HeylanaLog.state("glance: speaking words=${say.split(" ").size} second_line=$alreadyAnnounced")
                } else {
                    HeylanaLog.state("glance: not spoken (muted or no voice); the line stands")
                }
            }
        }
        main.removeCallbacks(glanceTimeout)
        main.postDelayed(glanceTimeout, GLANCE_MS)
    }

    /** Set once the spoken warning has really started, so it is timed once. */
    private var glanceSpoke = false

    /**
     * The number the whole of this is for: the window came up, and this is the moment the
     * first word of the warning is heard. Reported once per screen.
     */
    private fun reportGlanceSpoken() {
        // Not gated on the line being on screen: the whole point is that the voice now
        // starts before the look that draws it has finished.
        if (glanceSpoke || glanceAt <= 0L) return
        glanceSpoke = true
        HeylanaLog.state("glance: spoke first_audio_ms=${SystemClock.uptimeMillis() - glanceAt}")
    }

    private val glanceTimeout = Runnable { hideGlance() }

    private fun hideGlance() {
        main.removeCallbacks(glanceTimeout)
        if (!shownGlance) return
        shownGlance = false
        glanceSpoke = false
        announcedWindow = null
        mode(null)
        overlayView?.hideGlance()
    }

    /** Set while a quick action's line must survive the box closing itself. */
    private var keepSpeechThroughClose = false

    /** True once the worker's own voice has started on this answer: nothing says it twice. */
    @Volatile private var alreadySpeaking = false

    /** Why the worker's voice stayed silent, if it tried and could not. */
    @Volatile private var spokenFailure: String? = null

    /**
     * Where an answer is spoken from while it is still being written. The worker sends the
     * audio of each sentence as it finishes it, so the first word is heard about a second
     * earlier than it was when the voice was a second trip of its own.
     */
    private val spokenAloud = object : xyz.heylana.app.brain.SpokenAnswer {
        override fun speaking(audio: java.io.InputStream, rate: Int) {
            alreadySpeaking = true
            HeylanaLog.state("speak: from the answer itself")
            val clock = answerClock?.takeIf { !it.done }
            clock?.voiceRidesWithTheQuestion()
            mouth?.play(audio, rate, clock)
        }

        override fun notSpoken(reason: String) {
            if (reason != xyz.heylana.app.brain.SpokenAnswer.NOT_SPOKEN) spokenFailure = reason
        }
    }

    /** Whether this question's answer may be spoken as it is written. */
    private fun spokenSink(): xyz.heylana.app.brain.SpokenAnswer? {
        alreadySpeaking = false
        spokenFailure = null
        val voice = mouth ?: return null
        if (settings.voiceMuted || !voice.available) return null
        return spokenAloud
    }

    /** True if the answer really is being read out, so speech will report its end. */
    /**
     * A warning, said out loud. The words are one of a dozen fixed sentences, so their
     * audio is kept after the first time and plays from the phone — a warning that waits
     * on the network is a warning that arrives after the thumb.
     */
    private fun speakWarning(text: String): Boolean {
        val voice = mouth ?: return false.also { HeylanaLog.state("speak: no voice") }
        if (settings.voiceMuted) return false.also { HeylanaLog.state("speak: muted, the line stands") }
        if (!voice.available) return false
        HeylanaLog.state("speak: warning chars=${text.length}")
        return voice.speakFixed(text)
    }

    private fun speak(text: String): Boolean {
        // Already being said, from the answer itself: this is the same words arriving late.
        if (alreadySpeaking) {
            alreadySpeaking = false
            HeylanaLog.state("speak: already said as it was written chars=${text.length}")
            return true
        }
        // The worker tried and its voice was refused: the words are shown, not asked for again.
        spokenFailure?.let { reason ->
            spokenFailure = null
            HeylanaLog.state("speak: the answer's own voice failed reason=$reason")
            showUnspoken(text, xyz.heylana.app.voice.VoiceFailure.reasonFor(codeOf(reason), reasonWord(reason)))
            return false
        }
        val voice = mouth ?: return false.also { HeylanaLog.state("speak: no voice") }
        if (settings.voiceMuted) return false.also { HeylanaLog.state("speak: muted") }
        if (!voice.available) {
            HeylanaLog.state("speak: voice unavailable")
            if (!noteShown) {
                noteShown = true
                overlayView?.showNote("Voice unavailable on this device.")
            }
            return false
        }
        // The length only: never the words.
        HeylanaLog.state("speak: line chars=${text.length}")
        // Only the answer to a spoken question is timed; a notice or a step is not that question.
        val clock = answerClock?.takeIf { !it.done }
        return voice.speak(text, clock)
    }

    /**
     * The one line that says how long the answer took to start: released, heard, asked,
     * answered, first sound. Printed once, when every stretch of it is in.
     */
    private fun reportSpeed() {
        answerClock?.takeIf { it.complete }?.let { clock ->
            HeylanaLog.state(clock.line())
            answerClock = null
        }
    }

    /** "429 daily_cap" as the app's own voice failure: the code, then the word. */
    private fun codeOf(reason: String): Int = reason.substringBefore(' ').toIntOrNull() ?: 0

    private fun reasonWord(reason: String): String = reason.substringAfter(' ', "")

    // ---------------------------------------------------------------- voice

    private fun startListening() {
        val view = overlayView ?: return
        val phone = phoneEars ?: return

        // The phone's own ears are the floor: if even they are missing there is
        // nothing to fall back to, whatever the proxy could have lent us.
        if (!phone.available() && !Proxy(settings).isConfigured) {
            view.showNotice(Listener.UNAVAILABLE)
            return
        }
        if (!micGranted()) {
            view.showNotice("Heylana needs the microphone to listen. Allow it, then hold me again.")
            requestMic()
            return
        }

        HeylanaLog.state("listen: opening")
        // Before anything else: stopping the speaker below reports "no longer
        // speaking", and that report books a settle unless it knows better.
        exchange.listening()
        mouth?.stop()
        stopTapWatch()
        highlight?.hide()
        view.stopLooking()
        view.startedListening()
        openEars()
    }

    /**
     * Starts getting Deepgram ready at the first touch of the disc: a borrowed
     * key and an open socket take a round trip each, so they begin before anyone
     * knows whether the touch will become a hold. A tap or a drag closes it again
     * without a word — see [discardEars].
     */
    private fun prepareEars() {
        val deepgramOn = settings.earAllowed(HeylanaSettings.EAR_DEEPGRAM)
        val assemblyOn = settings.earAllowed(HeylanaSettings.EAR_ASSEMBLYAI)
        if (!deepgramOn && !assemblyOn) return
        if (!Proxy(settings).isConfigured) return
        if (!micGranted()) return

        cloudEars?.let { existing ->
            // One that is already getting ready is left alone; one that gave up
            // is thrown away, because the next touch deserves a fresh try.
            if (existing.stage != DeepgramEars.Stage.FAILED) {
                existing.startPreRoll()
                prepareAssembly(existing, assemblyOn)
                return
            }
            existing.cancel()
            cloudEars = null
        }

        earsKeyterms = Keyterms.forEars(emptyList())
        earsAskedAt = SystemClock.uptimeMillis()

        // DeepgramEars hands every report back on the main thread itself.
        val deepgram = DeepgramEars(
            proxy = Proxy(settings),
            scope = scope,
            heard = cloudCallbacks,
            ready = {
                HeylanaLog.state(
                    "ears: deepgram ready token_ms=${cloudEars?.tokenMillis ?: -1} " +
                        "socket_ms=${cloudEars?.socketMillis ?: -1}"
                )
            },
            unavailable = { reason -> deepgramUnavailable(reason) }
        )
        // Deepgram switched off: its microphone still runs, for AssemblyAI alone.
        deepgram.micOnly = !deepgramOn
        cloudEars = deepgram
        prepareAssembly(deepgram, assemblyOn)
        deepgram.prepare(earsKeyterms)
        // The microphone starts now, into memory only, so a hold's first syllable is there.
        deepgram.startPreRoll()
    }

    /**
     * The third ear, from the same first touch: a single-use token and an open socket, fed
     * by [microphone]. One that gave up, or finished a hold, is replaced by a fresh one.
     */
    private fun prepareAssembly(microphone: DeepgramEars, on: Boolean) {
        if (!on) {
            microphone.tap = null
            return
        }
        assemblyEars?.let { existing ->
            if (existing.stage == AssemblyEars.Stage.TOKEN || existing.stage == AssemblyEars.Stage.SOCKET ||
                existing.stage == AssemblyEars.Stage.READY
            ) return
            existing.cancel()
        }
        val assembly = AssemblyEars(
            proxy = Proxy(settings),
            scope = scope,
            heard = assemblyCallbacks,
            unavailable = { reason -> assemblyUnavailable(reason) }
        )
        assemblyEars = assembly
        microphone.tap = { piece -> assembly.feed(piece) }
        assembly.prepare(earsKeyterms.ifEmpty { Keyterms.forEars(emptyList()) })
    }

    /** AssemblyAI cannot deliver — before the hold or during it. */
    private fun assemblyUnavailable(reason: String) {
        HeylanaLog.state("ears: assemblyai unavailable reason=$reason token_ms=${assemblyEars?.tokenMillis ?: -1}")
        val running = race ?: return
        judge(running.failed(EarsRace.Ear.ASSEMBLYAI, SystemClock.uptimeMillis(), reason))
    }

    /** The touch was not a hold: the socket goes, quietly and unused. */
    private fun discardEars() {
        if (race != null) return
        cloudEars?.discard()
        cloudEars = null
        assemblyEars?.discard()
        assemblyEars = null
    }

    /**
     * The user is holding: **both ears start, every time.**
     *
     * The phone's own recogniser listens from this moment whatever Deepgram is
     * doing, and Deepgram records too — into its buffer until the socket is up.
     * Whichever produces words is used, Deepgram's if they arrive within a
     * second and a half of the release; see [EarsRace]. A Deepgram that cannot
     * work at all is simply out of the race, not a reason to hear nothing.
     */
    private fun openEars() {
        // The debug switch can leave any of them out, so each can be heard on its own.
        val racing = buildSet {
            if (settings.earAllowed(HeylanaSettings.EAR_DEEPGRAM)) add(EarsRace.Ear.DEEPGRAM)
            if (settings.earAllowed(HeylanaSettings.EAR_ASSEMBLYAI)) add(EarsRace.Ear.ASSEMBLYAI)
            if (settings.earAllowed(HeylanaSettings.EAR_ANDROID)) add(EarsRace.Ear.ANDROID)
        }
        if (settings.forceEar != HeylanaSettings.EAR_AUTO) HeylanaLog.state("ears: forced ${settings.forceEar}")
        val race = EarsRace(racing = racing)
        this.race = race
        deepgramSpoke = false
        assemblySpoke = false
        val now = SystemClock.uptimeMillis()
        val keyterms = earsKeyterms.ifEmpty { Keyterms.forEars(emptyList()) }

        if (EarsRace.Ear.ANDROID in racing) {
            val phone = phoneEars
            if (phone != null) {
                HeylanaLog.state("ears: android listening")
                phone.start(keyterms)
            } else {
                race.failed(EarsRace.Ear.ANDROID, now, Listener.UNAVAILABLE)
            }
        }

        val deepgram = cloudEars
        val assembly = assemblyEars
        if (EarsRace.Ear.DEEPGRAM in racing) {
            when {
                deepgram == null -> deepgramOut(race, "not_set_up")
                deepgram.stage == DeepgramEars.Stage.FAILED -> deepgramOut(race, deepgram.failure ?: "failed")
                else -> HeylanaLog.state("ears: deepgram listening stage=${deepgram.stage}")
            }
        }
        if (EarsRace.Ear.ASSEMBLYAI in racing) {
            when {
                assembly == null -> assemblyOut(race, "not_set_up")
                assembly.stage == AssemblyEars.Stage.FAILED -> assemblyOut(race, assembly.failure ?: "failed")
                else -> {
                    HeylanaLog.state("ears: assemblyai listening stage=${assembly.stage}")
                    assembly.beginSpeaking()
                }
            }
        }
        // The microphone is Deepgram's, and it runs for AssemblyAI too — even when Deepgram's
        // own socket failed or it was switched off.
        val microphoneWanted = (EarsRace.Ear.DEEPGRAM in racing && deepgram?.stage != DeepgramEars.Stage.FAILED) ||
            (EarsRace.Ear.ASSEMBLYAI in racing && assembly != null && assembly.stage != AssemblyEars.Stage.FAILED)
        if (deepgram != null && microphoneWanted) deepgram.beginSpeaking()
    }

    /**
     * Debug builds with the switch on only: every ear's words from this hold, in the app's
     * private storage, for counting word errors by hand. Never logged, overwritten each hold.
     */
    private fun captureEars(verdict: EarsRace.Verdict.Use) {
        if (!BuildConfig.DEBUG || !settings.saveEarsCapture) return
        val running = race ?: return
        val text = buildString {
            append("winner=").append(verdict.ear.name.lowercase()).append(' ').append(verdict.reason).append('\n')
            append(running.summary()).append('\n')
            for (ear in EarsRace.Ear.entries) append(ear.name.lowercase()).append('\t').append(running.wordsOf(ear) ?: "-").append('\n')
        }
        runCatching { java.io.File(filesDir, EARS_CAPTURE).writeText(text) }
    }

    private fun assemblyOut(race: EarsRace, reason: String) {
        HeylanaLog.state("ears: assemblyai out of the race reason=$reason")
        judge(race.failed(EarsRace.Ear.ASSEMBLYAI, SystemClock.uptimeMillis(), reason))
    }

    private fun deepgramOut(race: EarsRace, reason: String) {
        HeylanaLog.state("ears: deepgram out of the race reason=$reason")
        judge(race.failed(EarsRace.Ear.DEEPGRAM, SystemClock.uptimeMillis(), reason))
    }

    /** Deepgram cannot deliver — before the hold or during it. */
    private fun deepgramUnavailable(reason: String) {
        val deepgram = cloudEars
        HeylanaLog.state(
            "ears: deepgram unavailable reason=$reason " +
                "token_ms=${deepgram?.tokenMillis ?: -1} socket_ms=${deepgram?.socketMillis ?: -1}"
        )
        val running = race ?: return
        judge(running.failed(EarsRace.Ear.DEEPGRAM, SystemClock.uptimeMillis(), reason))
    }

    private fun finishListening() {
        HeylanaLog.state("listen: released")
        exchange.released()
        val view = overlayView ?: return
        val running = race
        if (running == null) {
            // No ears were ever started, so nothing is coming.
            exchange.over()
            view.endVoiceExchange()
            return
        }

        // Waiting on the final words: the capsule turns into the aurora.
        view.showThinkingCapsule()
        releasedAt = SystemClock.uptimeMillis()
        answerClock = xyz.heylana.app.voice.AnswerClock(releasedAt)
        val phone = phoneEars
        val deepgram = cloudEars
        val assembly = assemblyEars
        judge(running.released(releasedAt))
        // Any may report straight away, and may end the race while doing so.
        phone?.release()
        deepgram?.release()
        assembly?.release()

        if (race != null) {
            main.postDelayed(raceTick, EarsRace.PREFER_DEEPGRAM_MS)
            main.postDelayed(raceTick, EarsRace.GIVE_UP_MS)
        }
    }

    private fun abandonListening() {
        HeylanaLog.state("listen: abandoned")
        endRace()
        exchange.over()
    }

    private fun micGranted(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    /** The service has no activity of its own, so it borrows a one-shot one. */
    private fun requestMic() {
        startActivity(
            Intent(this, MicPermissionActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
        )
    }

    // --------------------------------------------------------- notification

    private fun startAsForeground() {
        createChannel()

        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE
        )

        val stop = PendingIntent.getService(
            this,
            1,
            Intent(this, BuddyOverlayService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notification = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("heylana")
            .setContentText("heylana is on your screen")
            .setSubText(PRIVACY_LINE)
            .setSmallIcon(R.drawable.ic_heylana_mark)
            .setContentIntent(openApp)
            .setOngoing(true)
            .addAction(
                Notification.Action.Builder(null, "Stop", stop).build()
            )
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun createChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Heylana buddy",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Shows while the Heylana buddy is floating on your screen"
            setShowBadge(false)
            setSound(null, null)
            enableVibration(false)
        }
        manager.createNotificationChannel(channel)
    }

    companion object {
        /** How long the "Remembered" chip stays on the strip. */
        private const val REMEMBERED_MS = 2_000L

        /** Debug builds only; see registerDebugQuickAction. */
        const val DEBUG_QUICK_ACTION = "xyz.heylana.app.debug.QUICK_ACTION"
        const val DEBUG_PANEL = "xyz.heylana.app.debug.PANEL"

        /**
         * The one running buddy, so a switch in the app can reach it. Held weakly by
         * being cleared in onDestroy; nothing else ever touches it.
         */
        @Volatile
        private var live: BuddyOverlayService? = null

        /** Settings changed the lookout: start or stop watching, now rather than next start. */
        fun refreshLookout() {
            live?.let { service -> service.main.post { service.applyLookout() } }
        }

        /** The beat between an answer finishing and the screen going back to rest. */
        private const val SETTLE_MS = 1_000L
        /** Debug builds only: the last hold's words from every ear ([captureEars]). */
        private const val EARS_CAPTURE = "ears_capture.txt"
        /** How long an answer with source chips stays up after it has been said, so a chip can be tapped. */
        private const val SOURCE_LINGER_MS = 10_000L

        /** Debug only: time for the box to open before a broadcast question is sent. */
        private const val DEBUG_ASK_DELAY_MS = 700L

        /** A little past the quiet time, so a held tap is acted on as soon as it may be. */
        private const val STEP_TICK_SLACK_MS = 120L

        /** How long a clarifying question waits for its answer. */
        private const val CLARIFY_WINDOW_MS = 60_000L

        /** The breath between one taught sentence and the next. */
        private const val BETWEEN_SEGMENTS_MS = 250L

        /**
         * A burst of window changes is one screen settling, not five screens: the lookout
         * looks at most this often, whatever the apps in front are doing. Short, because a
         * look inside the gap is held rather than dropped, and the whole glance — read,
         * decide, show, start speaking — has to land inside 800ms.
         */
        private const val LOOK_EVERY_MS = 250L

        /** A screen still drawing says nothing; one more look, once, catches it. */
        private const val SECOND_LOOK_MS = 400L

        /** How long an unasked glance stays up before it takes itself away. */
        private const val GLANCE_MS = 12_000L

        /** How long an answer that could not be spoken stays up to be read. */
        private const val READ_MS_PER_WORD = 350L
        private const val MIN_READ_MS = 4_000L
        private const val MAX_READ_MS = 12_000L

        /** How long "turn it off" still means the flashlight that was just switched on. */
        private const val FOLLOW_UP_MS = 5 * 60 * 1_000L

        /** Long enough for the keyboard to finish leaving and the app to re-layout. */
        private const val KEYBOARD_SETTLE_MS = 350L

        /** Lets a screen settle after a tap before deciding the step is finished. */
        private const val AUTO_ADVANCE_DEBOUNCE_MS = 900L

        /** The same sentence as the app's Permissions screen, in the operator's words. */
        const val PRIVACY_LINE = "reads the screen when you ask and when a signing screen " +
            "comes up, watches for your tap only while it is pointing at something, and " +
            "keeps what it reads on the phone"

        private const val STOPPED_LINE = "Okay, stopping here."

        /** Said when an exchange runs out of its twenty seconds. */
        const val TOOK_TOO_LONG = "That took too long, try again."

        /** Only for a task step, and only when screen reading really is off. */
        private const val SCREEN_READING_OFF =
            "I can't read this screen yet. Open Heylana and switch on its accessibility " +
                "service, then ask me again."

        private const val STUCK_LINE =
            "Looks like that didn't work. Try tapping it again, or tell me what you see."

        private const val CHANNEL_ID = "heylana_buddy"
        private const val NOTIFICATION_ID = 1001
        const val ACTION_STOP = "xyz.heylana.app.action.STOP_BUDDY"

        /** Simple flag so the launcher screen can show Start vs Stop. */
        @Volatile
        var isRunning: Boolean = false

        fun start(context: Context) {
            context.startForegroundService(Intent(context, BuddyOverlayService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, BuddyOverlayService::class.java))
        }
    }
}
