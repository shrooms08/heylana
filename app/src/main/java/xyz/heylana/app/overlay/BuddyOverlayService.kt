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
import kotlinx.coroutines.launch
import xyz.heylana.app.BuildConfig
import xyz.heylana.app.HeylanaLog
import xyz.heylana.app.MainActivity
import xyz.heylana.app.R
import xyz.heylana.app.brain.ProxyClient
import xyz.heylana.app.brain.BrainReply
import xyz.heylana.app.brain.Conversation
import xyz.heylana.app.brain.Greeting
import xyz.heylana.app.brain.Routing
import xyz.heylana.app.brain.AddressText
import xyz.heylana.app.brain.TypedAddresses
import xyz.heylana.app.brain.SendAction
import xyz.heylana.app.brain.SendGuard
import xyz.heylana.app.wallet.Answer
import xyz.heylana.app.wallet.SendActivity
import xyz.heylana.app.wallet.SendQuote
import xyz.heylana.app.wallet.SendRelay
import xyz.heylana.app.wallet.SendResult
import xyz.heylana.app.wallet.SendText
import xyz.heylana.app.wallet.WalletApi
import xyz.heylana.app.wallet.WalletProblem
import xyz.heylana.app.brain.GuidanceSession
import xyz.heylana.app.net.Proxy
import xyz.heylana.app.screen.HeylanaAccessibilityService
import xyz.heylana.app.screen.Keyterms
import xyz.heylana.app.screen.ScreenNode
import xyz.heylana.app.screen.ScreenSignal
import xyz.heylana.app.screen.ScreenSnapshot
import xyz.heylana.app.screen.TapWatch
import xyz.heylana.app.screen.Verdict
import xyz.heylana.app.settings.HeylanaSettings
import xyz.heylana.app.voice.CartesiaVoice
import xyz.heylana.app.voice.DeepgramEars
import xyz.heylana.app.voice.EarsRace
import xyz.heylana.app.voice.EarCallbacks
import xyz.heylana.app.voice.Listener
import xyz.heylana.app.voice.MicPermissionActivity
import xyz.heylana.app.voice.Speaker

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

    /** Heylana's voice: Cartesia when it can be reached, the phone's own when not. */
    private var mouth: CartesiaVoice? = null

    /** The phone's own ears: always there, and what everything falls back to. */
    private var phoneEars: Listener? = null

    /** The good ears, when the proxy can lend a key for them. */
    private var cloudEars: DeepgramEars? = null

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

    /** Addresses typed since the buddy started, to recognise them shortened on a wallet screen. Memory only. */
    private val typedAddresses = TypedAddresses()

    private val main = Handler(Looper.getMainLooper())

    /** What the user has going on, and therefore what may not be torn down. */
    private val exchange = Exchange { SystemClock.uptimeMillis() }

    /** Deciding whose words to use, from the long press until one is chosen. */
    private var race: EarsRace? = null

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
        HeylanaLog.state("settle: run")
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
        val onSpeaking: (Boolean) -> Unit = { speaking ->
            main.post {
                HeylanaLog.state("speak: speaking=$speaking")
                overlayView?.setTalking(speaking)
                main.removeCallbacks(settleToIdle)
                if (!speaking && exchange.maySettle) {
                    HeylanaLog.state("settle: scheduled")
                    main.postDelayed(settleToIdle, SETTLE_MS)
                }
            }
        }
        mouth = CartesiaVoice(
            context = this,
            settings = settings,
            scope = scope,
            phone = Speaker(this) { speaking -> onSpeaking(speaking) },
            onSpeaking = onSpeaking
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

    /** True once Deepgram has put words in the capsule this hold. */
    private var deepgramSpoke = false

    /**
     * Both ears report into the race rather than acting on their own, so the
     * two cannot each decide the exchange is over.
     */
    private fun callbacksFor(ear: EarsRace.Ear) = EarCallbacks(
        onPartial = { text ->
            // Deepgram's words, once it has any, are the ones shown.
            if (ear == EarsRace.Ear.DEEPGRAM) deepgramSpoke = true
            if (ear == EarsRace.Ear.DEEPGRAM || !deepgramSpoke) overlayView?.showHeard(text)
        },
        onFinal = { text ->
            HeylanaLog.state("ears: ${ear.name.lowercase()} final")
            judge(race?.heard(ear, SystemClock.uptimeMillis(), text))
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
                HeylanaLog.state(
                    "ears=${verdict.ear.name.lowercase()} won reason=${verdict.reason} " +
                        "after_release=${SystemClock.uptimeMillis() - releasedAt}ms " +
                        "token_ms=${deepgram?.tokenMillis ?: -1} " +
                        "socket_ms=${deepgram?.socketMillis ?: -1}"
                )
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
                HeylanaLog.state("ears: problem reported")
                endRace()
                exchange.over()
                overlayView?.endVoiceExchange()
                overlayView?.showNotice(verdict.message)
            }
        }
    }

    /** The race is over: both ears stop and the socket closes. */
    private fun endRace() {
        main.removeCallbacks(raceTick)
        race = null
        deepgramSpoke = false
        phoneEars?.cancel()
        cloudEars?.cancel()
        cloudEars = null
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
        // Stop means forget: the memory never outlives the buddy.
        conversation.clear()
        endSession(clearBox = false)
        stopTapWatch()
        main.removeCallbacksAndMessages(null)
        scope.cancel()
        cloudEars?.shutdown()
        cloudEars = null
        phoneEars?.shutdown()
        phoneEars = null
        mouth?.shutdown()
        mouth = null
        highlight?.removeFromWindow()
        highlight = null
        overlayView?.removeFromWindow()
        overlayView = null
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
            view.onCancelSend = { cancelSend() }
            view.onPanelClosed = {
                HeylanaLog.state("panel: closed")
                if (awaitingConfirm != null) {
                    awaitingConfirm = null
                    HeylanaLog.state("send: cancelled, panel closed")
                }
                mouth?.stop()
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
    }

    // ------------------------------------------------------------------ ask

    /**
     * Reads the screen first — while the app the user is asking about is still the
     * topmost non-Heylana window — then asks the brain off the main thread.
     */
    private fun ask(question: String) {
        HeylanaLog.state("ask: sending")
        val view = overlayView ?: return
        if (inFlight?.isActive == true) return
        exchange.asking()

        // A new question drops whatever the last one left behind, including any
        // task that was running.
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

        inFlight = scope.launch {
            delay(KEYBOARD_SETTLE_MS)

            // A screen with nothing readable on it is not a reason to refuse.
            // Plenty of questions are not about the screen at all, and some apps
            // hand us an empty tree however hard we look — Chrome does unless
            // something is subscribed to its events, which Heylana deliberately
            // is not. The model is told the screen was unreadable and answers
            // from general knowledge. A task step is different: see advance().
            val reading = HeylanaAccessibilityService.isConnected
            HeylanaLog.state("ask: screen reading connected=$reading")
            val snapshot = HeylanaAccessibilityService.snapshotOrNull()
                ?: ScreenSnapshot.empty(readingOff = !reading)

            val screenText = snapshot.toPromptText()
            logScreenSize(snapshot, screenText)

            val memory = conversation.asPromptText(snapshot.packageName)
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
            val route = Routing.forQuestion(snapshot.packageName, question, screenText, settings.walletSession?.pubkey)
            // No hello by name on a send or a signing explanation, and it is not used up by one.
            val greetingLine = if (route.allowsGreeting) greeting.lineFor(settings.callMe) else null
            val reply = brain.ask(question, screenText, memory, greetingLine, route, typedAddresses.all())
            // The answer is here: from now on settling back to idle is allowed.
            exchange.over()
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
                    if (route.why == Routing.Why.SEND_QUESTION) {
                        // A send never falls back to the model's own words.
                        HeylanaLog.state("send: raw action missing")
                        sayLine(SendText.NO_ACTION)
                        return@launch
                    }
                    val task = reply.task
                    if (task != null && !task.done) {
                        startSession(task.goal, reply, snapshot)
                    } else {
                        conversation.record(question, reply.text, snapshot.packageName)
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
        scope.launch {
            when (val answer = walletApi.prepareSend(allowed.to, allowed.amount, allowed.token)) {
                is Answer.Ok -> {
                    val quote = answer.value
                    if (SendGuard.overLimit(quote.amount, quote.balance)) {
                        HeylanaLog.state("send: over a quarter of the balance amount=${quote.amount}")
                        overLimit = PendingSend(quote, System.currentTimeMillis())
                        sayLine(SendGuard.overLimitLine(quote.token))
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

    /** "Send 5 USDC to bob.skr (7c2y…ab12). Fee ~0.000005 SOL." — shown, read aloud, and held. */
    private fun showSendStrip(quote: SendQuote) {
        val view = overlayView ?: return
        val text = SendText.strip(quote)
        awaitingConfirm = quote
        HeylanaLog.state("send: strip token=${quote.token} amount=${quote.amount} to=${quote.toAddress.take(4)}")
        view.showSendConfirm(text)
        speak(text)
    }

    private fun confirmSend() {
        val quote = awaitingConfirm ?: return
        awaitingConfirm = null
        mouth?.stop()
        overlayView?.hideSendConfirm()
        overlayView?.showNotice("Approve it in Seed Vault.")
        HeylanaLog.state("send: confirmed, opening Seed Vault")
        SendRelay.listener = { result -> main.post { onSendResult(result) } }
        startActivity(
            SendActivity.intentFor(this, quote)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
        )
    }

    private fun cancelSend() {
        if (awaitingConfirm == null) return
        awaitingConfirm = null
        overlayView?.hideSendConfirm()
        HeylanaLog.state("send: cancelled")
        sayLine(SendText.CANCELLED)
    }

    private fun onSendResult(result: SendResult) {
        SendRelay.listener = null
        when (result) {
            is SendResult.Sent -> {
                HeylanaLog.state("send: landed")
                sayLine(SendText.sent(result.shortSignature))
            }
            is SendResult.Stopped -> {
                HeylanaLog.state("send: stopped")
                sayLine(result.line)
            }
        }
    }

    /** One of Heylana's own lines: shown, spoken, then back to rest. */
    private fun sayLine(line: String) {
        val view = overlayView ?: return
        val shown = AddressText.shorten(line)
        view.showNotice(shown)
        if (!speak(shown)) settleSoon()
    }

    /** An ordinary answer: say it, point once, let the box time out by itself. */
    private fun showOneShot(reply: BrainReply.Say, snapshot: ScreenSnapshot) {
        val view = overlayView ?: return
        // A spoken answer normally leaves nothing on screen; the Settings switch
        // is what puts its words in a box.
        if (view.wasSpoken && settings.showTextForVoice) view.ensurePanelOpen()
        view.showAnswer(reply.text)
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
        main.postDelayed(settleToIdle, SETTLE_MS)
    }

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

    private fun startSession(goal: String, reply: BrainReply.Say, snapshot: ScreenSnapshot) {
        session = GuidanceSession(goal)
        conversation.clear()
        // Screen-change events are switched on here and nowhere else.
        HeylanaAccessibilityService.watchScreenChanges { main.post { onScreenChanged() } }
        showStep(reply, snapshot)
    }

    /** Draws, speaks and records one step of the running task. */
    private fun showStep(reply: BrainReply.Say, snapshot: ScreenSnapshot) {
        val view = overlayView ?: return
        val current = session ?: return

        val node = snapshot.node(reply.pointAt)
        val repeated = current.record(
            say = reply.text,
            elementKey = node?.key,
            elementLabel = node?.label,
            packageName = snapshot.packageName
        )

        val spoken = if (repeated) STUCK_LINE else reply.text

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
            watchForTap(node)
        } else {
            // No box for this step, but the task is still running.
            highlight?.hide()
            view.stopLooking()
        }

        speak(spoken)
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

        main.removeCallbacks(autoAdvanceCheck)

        if (current.atCap) {
            val line = "That is as far as I can take you, so let's stop here."
            view.showAnswer(line)
            speak(line)
            endSession(clearBox = true)
            return
        }

        mouth?.stop()
        exchange.asking()
        view.showThinking()
        view.hideKeyboard()

        inFlight = scope.launch {
            delay(KEYBOARD_SETTLE_MS)
            val snapshot = readScreen(view) ?: return@launch

            val screenText = snapshot.toPromptText()
            logScreenSize(snapshot, screenText)

            val reply = brain.nextStep(
                goal = current.goal,
                historyText = current.historyText(),
                screenText = screenText,
                stepNumber = current.stepNumber + 1,
                needPointerHint = current.lastStepHadNoPointer
            )
            exchange.over()

            when (reply) {
                is BrainReply.Say -> {
                    if (reply.task?.done == true) {
                        view.showAnswer(reply.text)
                        speak(reply.text)
                        endSession(clearBox = true)
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
    private fun onScreenChanged() {
        val current = session ?: return
        if (current.stuck) return
        if (inFlight?.isActive == true) return
        main.removeCallbacks(autoAdvanceCheck)
        main.postDelayed(autoAdvanceCheck, AUTO_ADVANCE_DEBOUNCE_MS)
    }

    /**
     * The step is finished when the thing we pointed at has gone, or the user has
     * moved to a different app.
     */
    private fun considerAutoAdvance() {
        val current = session ?: return
        if (current.stuck) return
        if (inFlight?.isActive == true) return

        val snapshot = HeylanaAccessibilityService.snapshotOrNull() ?: return
        if (snapshot.isEmpty) return

        val movedApp = current.pointedPackage != null &&
            snapshot.packageName != current.pointedPackage
        val pointedGone = current.pointedKey?.let { !snapshot.contains(it) } ?: false

        if (movedApp || pointedGone) advance(userAsked = false)
    }

    /** The user tapped Done: say so, then close the task down. */
    private fun stopSessionOnRequest() {
        if (session == null) return
        mouth?.stop()
        overlayView?.showAnswer(STOPPED_LINE)
        speak(STOPPED_LINE)
        endSession(clearBox = true)
    }

    private fun endSession(clearBox: Boolean) {
        main.removeCallbacks(autoAdvanceCheck)
        if (session == null) return
        session = null
        // Events go straight back off: no session, no listening.
        HeylanaAccessibilityService.watchScreenChanges(null)
        overlayView?.hideSession()
        if (clearBox) {
            stopTapWatch()
            highlight?.hide()
            overlayView?.stopLooking()
        }
    }

    /** True if the answer really is being read out, so speech will report its end. */
    private fun speak(text: String): Boolean {
        val voice = mouth ?: return false
        if (settings.voiceMuted) return false
        if (!voice.available) {
            if (voice.settled && !noteShown) {
                noteShown = true
                overlayView?.showNote("Voice unavailable on this device.")
            }
            return false
        }
        return voice.speak(text)
    }

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
        if (settings.forcePhoneEars) return
        if (!Proxy(settings).isConfigured) return
        if (!micGranted()) return

        cloudEars?.let { existing ->
            // One that is already getting ready is left alone; one that gave up
            // is thrown away, because the next touch deserves a fresh try.
            if (existing.stage != DeepgramEars.Stage.FAILED) return
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
        cloudEars = deepgram
        deepgram.prepare(earsKeyterms)
    }

    /** The touch was not a hold: the socket goes, quietly and unused. */
    private fun discardEars() {
        if (race != null) return
        cloudEars?.discard()
        cloudEars = null
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
        val race = EarsRace()
        this.race = race
        deepgramSpoke = false
        val now = SystemClock.uptimeMillis()
        val keyterms = earsKeyterms.ifEmpty { Keyterms.forEars(emptyList()) }

        val phone = phoneEars
        if (phone != null) {
            HeylanaLog.state("ears: android listening")
            phone.start(keyterms)
        } else {
            race.failed(EarsRace.Ear.ANDROID, now, Listener.UNAVAILABLE)
        }

        val deepgram = cloudEars
        when {
            settings.forcePhoneEars -> deepgramOut(race, "forced")
            deepgram == null -> deepgramOut(race, "not_set_up")
            deepgram.stage == DeepgramEars.Stage.FAILED ->
                deepgramOut(race, deepgram.failure ?: "failed")
            else -> {
                HeylanaLog.state("ears: deepgram listening stage=${deepgram.stage}")
                deepgram.beginSpeaking()
            }
        }
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
        val phone = phoneEars
        val deepgram = cloudEars
        judge(running.released(releasedAt))
        // Either may report straight away, and may end the race while doing so.
        phone?.release()
        deepgram?.release()

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
        /** The beat between an answer finishing and the screen going back to rest. */
        private const val SETTLE_MS = 1_000L

        /** Long enough for the keyboard to finish leaving and the app to re-layout. */
        private const val KEYBOARD_SETTLE_MS = 350L

        /** Lets a screen settle after a tap before deciding the step is finished. */
        private const val AUTO_ADVANCE_DEBOUNCE_MS = 900L

        /** The same sentence as the onboarding screen, in the operator's words. */
        const val PRIVACY_LINE = "reads the screen only when you ask, and watches for " +
            "your tap only while it is pointing at something"

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
