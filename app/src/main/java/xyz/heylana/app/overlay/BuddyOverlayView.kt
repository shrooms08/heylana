package xyz.heylana.app.overlay

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.graphics.PointF
import android.graphics.Rect
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.dynamicanimation.animation.DynamicAnimation
import androidx.dynamicanimation.animation.FloatValueHolder
import androidx.dynamicanimation.animation.SpringAnimation
import androidx.dynamicanimation.animation.SpringForce
import xyz.heylana.app.HeylanaLog
import xyz.heylana.app.ui.GlassBlur
import xyz.heylana.app.ui.HeylanaTokens
import kotlin.math.abs
import kotlin.math.hypot

/**
 * The window that carries the buddy and its message box.
 *
 * It works in three modes, and everything about the window — its size, whether
 * it can take the keyboard, whether touches fall through it — follows from which
 * one it is in:
 *
 *  - **Docked**: a small window holding only the disc, parked against an edge.
 *    Touches anywhere else reach the app underneath.
 *  - **Compose**: the whole screen. The app behind is dimmed, the buddy flies to
 *    the top centre and the glass box drops in beneath it, keyboard and all.
 *  - **Hud**: the box is open but passive, which is what a guidance task uses.
 *    No dim, no keyboard, and the window stays small, so a tap outside it is the
 *    user doing the step rather than a request to dismiss.
 *
 * Gestures on the disc are kept strictly apart: a tap opens the box, a drag
 * moves the buddy and snaps it to an edge, and a press-and-hold opens the
 * microphone — a hold that turns into a drag cancels the listening.
 */
@SuppressLint("ViewConstructor")
class BuddyOverlayView(context: Context) : FrameLayout(context) {

    private enum class Mode {
        DOCKED,
        COMPOSE,

        /** A spoken exchange: the disc stays put, a capsule sits beside it. */
        CAPSULE,
        HUD
    }

    /** Called with the user's question when they ask. */
    var onQuestion: ((String) -> Unit)? = null

    /**
     * The disc has been touched, before anyone knows whether it will turn into a
     * tap, a drag or a hold. The one thing worth doing this early is opening the
     * connection the answer will need.
     */
    var onTouched: (() -> Unit)? = null

    /**
     * The touch is over and it was never a hold — a tap, or a drag. Whatever was
     * got ready on the way down can be put away again.
     */
    var onNotAHold: (() -> Unit)? = null

    /** The user has held the disc down: start listening. */
    var onHoldStart: (() -> Unit)? = null

    /** The user let go after holding: finish listening and send. */
    var onHoldEnd: (() -> Unit)? = null

    /** The hold turned into a drag: throw the listening away. */
    var onHoldCancel: (() -> Unit)? = null

    /** The speaker glyph was tapped. */
    var onMuteToggled: ((Boolean) -> Unit)? = null

    /** The box just closed, however it closed. */
    var onPanelClosed: (() -> Unit)? = null

    /** The user asked for the next step of a task. */
    var onNext: (() -> Unit)? = null

    /** The user ended a task early. */
    var onDone: (() -> Unit)? = null

    /** The user confirmed the send on the strip. */
    var onConfirmSend: (() -> Unit)? = null

    /** The user cancelled the send on the strip. */
    var onCancelSend: (() -> Unit)? = null

    /** The user tapped a source chip under the answer. */
    var onSourceTapped: ((xyz.heylana.app.brain.Source) -> Unit)? = null

    /** Whether Heylana is speaking now; a touch on the disc then stops her. */
    var isSpeaking: () -> Boolean = { false }

    /** The disc was touched while Heylana was speaking: stop the voice. */
    var onInterrupt: (() -> Unit)? = null

    /** This touch began by stopping Heylana's voice, so a tap is not also a toggle. */
    private var interruptedSpeech = false

    /**
     * The full-screen layer the disc flies across. Moving a window every frame
     * is not GPU animated and stutters; a view translation on a layer that is
     * already full screen is.
     */
    var flightStage: HighlightOverlayView? = null

    private val windowManager = context.getSystemService(WindowManager::class.java)

    /** The buddy's view, docked: the 64dp disc plus the room its bloom needs on every side. */
    private val discSize = dp(HeylanaTokens.discViewDp(HeylanaTokens.DISC_DP))

    /** The buddy's view at the top centre while the box is open: the 80dp disc and its bleed. */
    private val openDiscSize = dp(HeylanaTokens.discViewDp(HeylanaTokens.DISC_OPEN_DP))

    /**
     * The dock inset is measured to the visible disc, not to the view, so the
     * bloom's transparent margin does not read as a gap at the screen edge.
     */
    private val dockInset = dp(HeylanaTokens.DISC_DP * HeylanaTokens.DOCK_INSET_RATIO) -
        dp(HeylanaTokens.discBleedDp(HeylanaTokens.DISC_DP))

    /** How far the docked window may hang off the side: its transparent bloom margin, never the disc. */
    private val edgeOverhang = dp(HeylanaTokens.discBleedDp(HeylanaTokens.DISC_DP))
    /**
     * The gutter is measured to the visible pane. The panel carries its own
     * shadow margin, so that much is taken off the layout margin or the box
     * would sit twice as far in as it should.
     */
    private val gutter = (dp(HeylanaTokens.SPACE_5_DP) -
        dp(HeylanaTokens.GLASS_SHADOW_DP)).coerceAtLeast(0)

    private val sprite = BuddySpriteView(context)
    private val gooey = GooeyLayer(context)
    private val capsule = VoiceCapsuleView(context)

    /**
     * The disc that actually crosses the screen. The real one keeps its place in
     * the layout the whole time, just hidden, so nothing has to be re-parented
     * into a window that has not been measured yet — which is what makes a
     * handoff pop.
     */
    private val flyer = BuddySpriteView(context)

    private val panel = ChatPanelView(context)

    /** The dim over the app behind the box. Never shown during a task. */
    private val scrim = View(context).apply {
        setBackgroundColor(HeylanaTokens.scrim)
        alpha = 0f
        visibility = View.GONE
    }

    /** The disc and the box together, so they can fly as one. */
    private val content = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        clipChildren = false
    }

    private val params = WindowManager.LayoutParams(
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        FLAGS_PASSIVE,
        PixelFormat.TRANSLUCENT
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_PAN or
            WindowManager.LayoutParams.SOFT_INPUT_STATE_UNCHANGED
        // The system slides a window to its new position over about 220ms. Heylana
        // moves its window itself — the flight has already put the disc there — so that
        // slide was a second movement after landing: from the full-screen box's origin
        // to the dock. Android 14+ lets a window opt out; see flyTo for older phones.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) setCanPlayMoveAnimation(false)
    }

    private var mode = Mode.DOCKED

    private var usableWidth = 0
    private var usableHeight = 0
    private var usableLeft = 0
    private var usableTop = 0

    /** Where the disc rests, in the window's own coordinate space. */
    private var spriteLeft = 0
    private var spriteTop = 0

    private var panelOnLeft = false

    private var dragging = false
    private var holding = false
    private var downRawX = 0f
    private var downRawY = 0f
    private var startLeft = 0
    private var startTop = 0
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop

    private var flightX: SpringAnimation? = null
    private var flightY: SpringAnimation? = null
    private var flightSize: SpringAnimation? = null
    private var flying = false
    private var attached = false

    private var measuredContainerWidth = discSize
    private var measuredContainerHeight = discSize

    private val spriteLocation = IntArray(2)
    private val occupied = Rect()

    private val longPress = Runnable {
        if (!dragging && !holding) {
            HeylanaLog.state("touch: long press")
            holding = true
            onHoldStart?.invoke()
        }
    }

    init {
        clipChildren = false
        panel.visibility = View.GONE
        panel.onSend = { question -> onQuestion?.invoke(question) }
        panel.onMuteToggled = { muted -> onMuteToggled?.invoke(muted) }
        panel.onNext = { onNext?.invoke() }
        panel.onDone = { onDone?.invoke() }
        panel.onConfirm = { onConfirmSend?.invoke() }
        panel.onCancel = { onCancelSend?.invoke() }
        panel.onSourceTapped = { onSourceTapped?.invoke(it) }
        panel.onInputTapped = { takeFocusForTyping() }
        panel.onStripTapped = {
            panel.morphTo(ChatPanelView.Shape.BOX) {
                applyPosition()
                takeFocusForTyping()
                panel.focusInput()
            }
        }

        capsule.visibility = View.GONE
        content.addView(sprite, LinearLayout.LayoutParams(discSize, discSize))
        content.addView(
            panel,
            LinearLayout.LayoutParams(panel.hudWidth, LinearLayout.LayoutParams.WRAP_CONTENT)
        )

        addView(scrim, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        // The goo's silhouette sits under the glass and the words, over the dim.
        addView(gooey, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        panel.gooey = gooey
        addView(content, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))

        scrim.setOnClickListener { closePanel() }

        @Suppress("ClickableViewAccessibility")
        sprite.setOnTouchListener { _, event -> handleSpriteTouch(event) }
    }

    // ---------------------------------------------------------------- window

    fun addToWindow() {
        if (attached) return
        refreshMetrics()
        mode = Mode.DOCKED
        panel.visibility = View.GONE
        params.flags = FLAGS_PASSIVE
        spriteLeft = dockedLeft(usableWidth - discSize - dockInset)
        spriteTop = usableHeight / 3
        measureContainer()
        params.x = spriteLeft
        params.y = spriteTop
        windowManager.addView(this, params)
        attached = true
        popIn()
    }

    /** Start buddy: the disc pops out from the edge it docks on, growing as it comes. */
    private fun popIn() {
        val fromRight = spriteLeft > usableWidth / 2
        sprite.scaleX = HeylanaTokens.POP_IN_FROM_SCALE
        sprite.scaleY = HeylanaTokens.POP_IN_FROM_SCALE
        sprite.alpha = 0f
        sprite.translationX = if (fromRight) discSize.toFloat() else -discSize.toFloat()
        sprite.animate().scaleX(1f).scaleY(1f).alpha(1f).translationX(0f)
            .setDuration(HeylanaTokens.POP_IN_MS)
            .setInterpolator(android.view.animation.OvershootInterpolator(HeylanaTokens.POP_IN_OVERSHOOT))
            .start()
        HeylanaLog.state("overlay: pop in side=${if (fromRight) "right" else "left"}")
    }

    /**
     * The screen turned, or changed size. The window manager keeps a window on screen by
     * moving it, but it does not tell the disc where it ended up — and a disc docked at the
     * right of a landscape screen has a left that is off a portrait one entirely. So the
     * position is worked out again against the new metrics, and the disc is put back on the
     * edge it was docked to (found on the Seeker: rotate, rotate back, and the buddy was
     * gone, its window sitting at x=2455 on a 1200-wide screen).
     */
    override fun onConfigurationChanged(newConfig: android.content.res.Configuration?) {
        super.onConfigurationChanged(newConfig)
        if (!attached) return
        val wasRight = spriteLeft + discSize / 2 > usableWidth / 2
        val heightBefore = usableHeight.coerceAtLeast(1)
        val downFraction = (spriteTop.toFloat() / heightBefore).coerceIn(0f, 1f)
        refreshMetrics()
        // The same edge, and about the same way down it.
        spriteLeft = dockedLeft(if (wasRight) usableWidth - discSize - dockInset else dockInset)
        spriteTop = dockedTop((downFraction * usableHeight).toInt())
        HeylanaLog.state("overlay: re-docked after a turn side=${if (wasRight) "right" else "left"} x=$spriteLeft y=$spriteTop")
        if (mode == Mode.COMPOSE) {
            updateLayout()
        } else {
            applyPosition()
        }
    }

    fun removeFromWindow() {
        if (!attached) return
        removeCallbacks(longPress)
        cancelFlight()
        panel.releaseInput()
        windowManager.removeView(this)
        attached = false
    }

    /** Middle of the disc in screen coordinates, for the pointer's arrow. */
    fun spriteCenterOnScreen(): PointF {
        sprite.getLocationOnScreen(spriteLocation)
        return PointF(
            spriteLocation[0] + sprite.width / 2f,
            spriteLocation[1] + sprite.height / 2f
        )
    }

    // ------------------------------------------------------------ box state

    val isPanelOpen: Boolean get() = mode != Mode.DOCKED

    fun showThinking() {
        panel.showThinking()
    }

    fun showAnswer(text: String) {
        panel.showAnswer(text)
        // A typed answer melts the box down into the compact strip. A spoken one
        // has no box to melt, and a task keeps its HUD.
        if (mode == Mode.COMPOSE && !panel.isVoiceMode &&
            panel.shape == ChatPanelView.Shape.BOX
        ) {
            panel.releaseInput()
            panel.morphTo(ChatPanelView.Shape.STRIP) { applyPosition() }
        }
        applyPosition()
    }

    fun showNotice(text: String) {
        // Spoken exchanges have no box of their own, and a problem still has to
        // be readable, so this is the one thing that opens one uninvited.
        ensurePanelOpen()
        panel.showNotice(text)
        applyPosition()
    }

    /** Source chips are up under the answer. */
    val showingSources: Boolean get() = panel.hasSources

    /** The chips under the answer; an empty list takes them away. */
    fun showSources(sources: List<xyz.heylana.app.brain.Source>) {
        panel.showSources(sources)
        applyPosition()
    }

    fun showNote(text: String) {
        panel.showNote(text)
        applyPosition()
    }

    fun setMuted(muted: Boolean) = panel.setMuted(muted)

    /** True when the exchange in progress was asked by voice rather than typed. */
    val wasSpoken: Boolean get() = panel.isVoiceMode

    /** True while the spoken-exchange capsule is still on screen. */
    val isCapsuleShowing: Boolean get() = mode == Mode.CAPSULE

    /** Remembered preference: whether spoken answers also show their words. */
    var voiceShowsText: Boolean = false
        set(value) {
            field = value
            panel.setVoiceMode(voice = panel.isVoiceMode, showsText = value)
        }

    /** Drops the keyboard so the app underneath un-squeezes before it is read. */
    fun hideKeyboard() = panel.hideKeyboard()

    fun setTalking(talking: Boolean) {
        sprite.talking = talking
        updateBeam()
    }

    /** How loud the spoken answer is right now, as it is heard. */
    fun setPlaybackLevel(level: Float) {
        sprite.playbackLevel = level
        updateBeam()
    }

    /** The box's rim beam follows the disc: listening, thinking or working, and speaking with the voice. */
    private fun updateBeam() {
        val kind = when {
            sprite.talking -> ChatPanelView.Beam.SPEAKING
            sprite.expression == BuddySpriteView.Expression.LISTENING -> ChatPanelView.Beam.LISTENING
            sprite.expression == BuddySpriteView.Expression.THINKING ||
                sprite.expression == BuddySpriteView.Expression.WORKING -> ChatPanelView.Beam.THINKING
            else -> ChatPanelView.Beam.NONE
        }
        panel.setBeam(kind, sprite.playbackLevel)
    }

    private var working = false

    /** A task step being worked out, or a send under way: the working orb. */
    fun setWorking(value: Boolean) {
        if (working == value) return
        working = value
        applyLook()
    }

    /** Look at whatever is being highlighted. */
    fun lookAt(target: PointF) {
        sprite.pointDirection = if (target.x < spriteCenterOnScreen().x) -1 else 1
        sprite.pointTarget = target
        applyLook()
    }

    fun stopLooking() {
        sprite.pointTarget = null
        applyLook()
    }

    /**
     * What the exchange is doing now. The disc's look is derived from this and
     * nothing else — see [DiscLook] — so no path can leave it thinking.
     */
    fun showPhase(next: Exchange.Phase) {
        phase = next
        applyLook()
    }

    private var phase: Exchange.Phase = Exchange.Phase.NONE

    private fun applyLook() {
        sprite.expression = when (DiscLook.of(phase, pointing = sprite.pointTarget != null, working = working)) {
            DiscLook.IDLE -> BuddySpriteView.Expression.IDLE
            DiscLook.LISTENING -> BuddySpriteView.Expression.LISTENING
            DiscLook.THINKING -> BuddySpriteView.Expression.THINKING
            DiscLook.WORKING -> BuddySpriteView.Expression.WORKING
            DiscLook.POINTING -> BuddySpriteView.Expression.POINTING
        }
        sprite.refreshState()
        updateBeam()
    }

    /** Shows the step counter with next and done while a task is running. */
    fun showSession(stepNumber: Int, ofSteps: Int, withNext: Boolean = true) {
        panel.showSession(stepNumber, ofSteps, withNext)
        // A task hands the keyboard back to the app the user is about to operate.
        if (mode == Mode.COMPOSE) enterMode(Mode.HUD)
        applyPosition()
    }

    /**
     * A glance: one line beside the disc, unasked, while the user is looking at something
     * that deserves it — a signing screen, a page asking for a recovery phrase.
     *
     * It uses the task HUD's shape on purpose. The box would dim the app and take the
     * keyboard, and covering a wallet's confirm screen with Heylana is the last thing this
     * should do: a glance is small, passive, and every touch outside it still reaches the
     * app underneath. Nothing is spoken; tapping the disc opens the box as it always does.
     */
    fun showGlance(text: String) {
        panel.showNotice(text)
        if (mode == Mode.DOCKED || mode == Mode.CAPSULE) enterMode(Mode.HUD)
        if (panel.shape != ChatPanelView.Shape.STRIP) {
            panel.releaseInput()
            panel.morphTo(ChatPanelView.Shape.STRIP) { applyPosition() }
        }
        applyPosition()
    }

    /** Takes the glance away and puts the disc back where it was. */
    fun hideGlance() {
        if (mode == Mode.HUD) closePanel()
    }

    fun hideSession() {
        panel.hideSession()
        applyPosition()
    }

    /**
     * The confirmation strip for a send: what will be sent, to whom, the fee, and
     * confirm or cancel. It stays until one of them is tapped.
     */
    fun showSendConfirm(text: String, simulation: ChatPanelView.Simulation) {
        panel.showNotice(text)
        ensurePanelOpen()
        if (mode == Mode.COMPOSE && panel.shape == ChatPanelView.Shape.BOX) {
            panel.releaseInput()
            panel.morphTo(ChatPanelView.Shape.STRIP) { applyPosition() }
        }
        panel.showConfirm(simulation)
        applyPosition()
    }

    /** The chip on the strip or HUD saying what Heylana is doing; null takes it away. */
    fun showMode(mode: BuddyMode?) {
        panel.showMode(mode)
        applyPosition()
    }

    /**
     * The send's simulation passed: the strip becomes the "Prepared, not signed" card with
     * the network's badge, and Confirm can be tapped.
     */
    fun showSimulationPassed(text: String, badge: xyz.heylana.app.wallet.ClusterBadge) {
        panel.showTxCard(text, badge)
        panel.setSimulation(ChatPanelView.Simulation.PASSED)
        applyPosition()
    }

    /**
     * A send's card after Confirm: waiting for the wallet, confirming, sent or not sent.
     *
     * Always the passive shape a glance uses, never the full-screen box. Seed Vault opens
     * underneath Heylana, and the box would take the user's first tap on the wallet as a tap
     * outside it — found on the Seeker, where the tap meant for "Testing wallet" closed
     * Heylana's box instead and the wallet ended "cancelled before connected". Beside the disc,
     * every touch outside the card reaches the wallet.
     */
    fun showTxCard(text: String, badge: xyz.heylana.app.wallet.ClusterBadge) {
        panel.showTxCard(text, badge)
        if (mode != Mode.HUD) enterMode(Mode.HUD)
        if (panel.shape != ChatPanelView.Shape.STRIP) {
            panel.releaseInput()
            panel.morphTo(ChatPanelView.Shape.STRIP) { applyPosition() }
        }
        HeylanaLog.state("send: card is passive, touches reach the wallet")
        applyPosition()
    }

    /**
     * The full-screen box covers the app, and Android leaves a covered window out
     * of what accessibility can read — so a typed question used to go with an
     * empty screen. While the screen is read, the box lets touches through, which
     * puts the app back in that list. Nothing changes on screen.
     */
    private var flagsBeforeRead: Int? = null

    fun letScreenReadThrough(through: Boolean) {
        val passing = params.flags and READ_THROUGH_FLAGS == READ_THROUGH_FLAGS
        if (through && (mode != Mode.COMPOSE || passing)) return
        if (!through && !passing) return
        if (through) {
            flagsBeforeRead = params.flags
            params.flags = params.flags or READ_THROUGH_FLAGS
        } else {
            params.flags = flagsBeforeRead ?: (params.flags and READ_THROUGH_FLAGS.inv())
            flagsBeforeRead = null
        }
        runCatching { windowManager.updateViewLayout(this, params) }
        HeylanaLog.state("screen: box ${if (through) "lets the read through" else "takes touches again"}")
    }

    fun hideSendConfirm() {
        panel.hideConfirm()
        applyPosition()
    }

    /** Opens the box from code — passive, so a task never dims the screen. */
    /**
     * Opens the box for something Heylana says by itself — a notice, a send strip,
     * the words of a spoken answer. Outside a task it always opens the one way a
     * tap does: the disc flies to the top and the box is full width beneath it,
     * from either dock side. It used to open the small task box beside the disc,
     * which, docked on the right, had no room and wrapped every two words. Only
     * a task keeps the box beside the disc, and a task is already open by then.
     */
    fun ensurePanelOpen() {
        if (mode == Mode.DOCKED || mode == Mode.CAPSULE) openCompose(withKeyboard = false)
    }

    // ---------------------------------------------------------------- voice

    /** Microphone opened: show the box without shoving the keyboard in the way. */
    /**
     * Microphone opened. The disc stays exactly where it is docked: no flight,
     * no dim, no blur. A small capsule appears on whichever side has room and
     * fills in with the words as they are heard.
     */
    fun startedListening() {
        panel.setVoiceMode(voice = true, showsText = voiceShowsText)
        sprite.micLevel = 0f
        sprite.refreshState()
        capsule.showTranscript("")
        enterMode(Mode.CAPSULE)
    }

    fun showMicLevel(level: Float) {
        sprite.micLevel = level
    }

    /** The words so far, as they are heard. */
    fun showHeard(text: String) = capsule.showTranscript(text)

    /** Released: the capsule turns into the aurora while the answer is fetched. */
    fun showThinkingCapsule() {
        sprite.micLevel = 0f
        sprite.refreshState()
        capsule.showThinking()
    }

    /**
     * The answer is in, or there is no longer anything to wait for: the capsule
     * melts away and the disc is left on its own. Safe to call twice.
     */
    fun endVoiceExchange() {
        if (mode != Mode.CAPSULE && capsule.visibility != View.VISIBLE) return
        capsule.melt {
            // Only if nothing else has taken the window over in the meantime —
            // showing the words of a spoken answer opens the box, for instance.
            if (mode == Mode.CAPSULE) enterMode(Mode.DOCKED)
        }
    }

    fun showPartialSpeech(text: String) = panel.setSpokenText(text)

    fun spokenText(): String = panel.spokenText()

    // ----------------------------------------------------------------- mode

    /** Debug builds only: dock the disc on a side, as a drag would, without touching it. */
    fun debugDock(left: Boolean) {
        if (mode != Mode.DOCKED) return
        refreshMetrics()
        spriteLeft = dockedLeft(if (left) dockInset else usableWidth - discSize - dockInset)
        applyPosition()
        post { logDiscPosition("docked") }
    }

    /** Debug builds only: open or close the box as a tap would, without touching the disc. */
    fun debugToggle() = togglePanel()

    /** Where the real disc's view is on screen right now, for the flight log. */
    private fun logDiscPosition(label: String) {
        sprite.getLocationOnScreen(spriteLocation)
        HeylanaLog.state("flight: $label x=${spriteLocation[0]} y=${spriteLocation[1]} size=${sprite.width}")
    }

    private fun togglePanel() {
        if (mode != Mode.DOCKED) {
            closePanel()
            return
        }
        panel.setVoiceMode(voice = false, showsText = voiceShowsText)
        openCompose()
    }

    /**
     * The disc travels first over the bare app; the blurred glass only arrives
     * once it has landed, so no window moves mid-flight.
     */
    /** Whether the next compose opening brings the keyboard up: a tap does, a notice does not. */
    private var keyboardOnCompose = true

    private fun openCompose(withKeyboard: Boolean = true) {
        if (mode == Mode.COMPOSE) return
        keyboardOnCompose = withKeyboard
        val flew = flyTo(composeScreenPosition(), HeylanaTokens.DISC_OPEN_DP) { enterMode(Mode.COMPOSE) }
        if (!flew) enterMode(Mode.COMPOSE)
    }

    /**
     * Switches mode and rebuilds the window to match. Compose takes the whole
     * screen so it can dim and type; the other two stay small so touches fall
     * through to the app underneath.
     */
    private fun enterMode(next: Mode) {
        if (mode == next) return
        HeylanaLog.state("mode: $mode -> $next")
        val previous = mode
        mode = next
        // Every close lands here: the box is emptied for the next time it opens.
        // Docked again: a teaching flight's remembered home is spent.
        if (next == Mode.DOCKED) teachHome = null
        if (PanelReset.resetsOn(previous.name, next.name)) {
            panel.resetToCompose()
            HeylanaLog.state("panel: reset to empty compose")
        }
        refreshMetrics()

        when (next) {
            Mode.DOCKED -> {
                panel.releaseInput()
                panel.visibility = View.GONE
                // The capsule belongs to a spoken exchange and nothing else, so
                // it never survives the way back to the bare disc.
                capsule.visibility = View.GONE
                scrim.visibility = View.GONE
                scrim.alpha = 0f
                sprite.composing = false
                layoutBeside()
                params.width = WindowManager.LayoutParams.WRAP_CONTENT
                params.height = WindowManager.LayoutParams.WRAP_CONTENT
                params.flags = FLAGS_PASSIVE
                GlassBlur.clear(params)
                panel.applyGlass(blurBehind = false)
                sprite.refreshState()
                applyPosition()
            }

            Mode.CAPSULE -> {
                panel.releaseInput()
                panel.visibility = View.GONE
                scrim.visibility = View.GONE
                scrim.alpha = 0f
                sprite.composing = false
                layoutBeside()
                capsule.visibility = View.VISIBLE
                params.width = WindowManager.LayoutParams.WRAP_CONTENT
                params.height = WindowManager.LayoutParams.WRAP_CONTENT
                params.flags = FLAGS_PASSIVE
                GlassBlur.clear(params)
                sprite.refreshState()
                applyPosition()
            }

            Mode.HUD -> {
                panel.releaseInput()
                capsule.visibility = View.GONE
                panel.visibility = View.VISIBLE
                scrim.visibility = View.GONE
                scrim.alpha = 0f
                sprite.composing = false
                content.translationX = 0f
                content.translationY = 0f
                panel.alpha = 1f
                panel.translationY = 0f
                layoutBeside()
                params.width = WindowManager.LayoutParams.WRAP_CONTENT
                params.height = WindowManager.LayoutParams.WRAP_CONTENT
                params.flags = FLAGS_PASSIVE
                GlassBlur.clear(params)
                panel.applyGlass(blurBehind = false)
                sprite.refreshState()
                applyPosition()
            }

            Mode.COMPOSE -> {
                panel.visibility = View.VISIBLE
                sprite.composing = true
                layoutForCompose()
                params.width = WindowManager.LayoutParams.MATCH_PARENT
                params.height = WindowManager.LayoutParams.MATCH_PARENT
                params.x = 0
                params.y = 0
                params.flags = FLAGS_COMPOSE
                val blurred = GlassBlur.apply(context, params)
                panel.applyGlass(blurred)
                scrim.visibility = View.VISIBLE
                sprite.refreshState()
                updateLayout()
                scrim.alpha = 0f
                scrim.animate().alpha(1f).setDuration(HeylanaTokens.FADE_MS).start()
                growBoxOutOfDisc()
                // A spoken question has no field to type in.
                if (!panel.isVoiceMode && keyboardOnCompose) panel.focusInput()
            }
        }
    }

    /** Docked and task layout: the disc, with the box beside it if it is open. */
    private fun layoutBeside() {
        content.orientation = LinearLayout.HORIZONTAL
        content.gravity = Gravity.CENTER_VERTICAL
        setContentLayout(Gravity.TOP or Gravity.START, 0, 0)

        val roomLeft = spriteLeft
        val roomRight = usableWidth - (spriteLeft + discSize)
        panelOnLeft = roomLeft > roomRight

        val boxParams = LinearLayout.LayoutParams(
            panel.hudWidth, LinearLayout.LayoutParams.WRAP_CONTENT
        )
        val capsuleParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
        )

        // The disc is never detached here. Detaching the view that is holding a
        // gesture cancels the gesture, and opening the microphone comes through
        // this method — rebuilding the whole row cancelled the very hold that
        // had just opened it, so no release ever reached us again. Only the box
        // and the capsule move; they are never the ones being touched.
        content.removeView(panel)
        content.removeView(capsule)
        dockSizedSprite()
        val discIndex = content.indexOfChild(sprite)
        if (panelOnLeft) {
            content.addView(capsule, discIndex, capsuleParams)
            content.addView(panel, discIndex + 1, boxParams)
        } else {
            content.addView(panel, discIndex + 1, boxParams)
            content.addView(capsule, discIndex + 2, capsuleParams)
        }
    }

    /**
     * The disc at its docked size, in the row. Resizing it changes only its layout
     * params and never detaches it, so a gesture it is holding survives.
     */
    private fun dockSizedSprite() {
        sprite.discDp = HeylanaTokens.DISC_DP
        if (content.indexOfChild(sprite) < 0) {
            content.addView(sprite, LinearLayout.LayoutParams(discSize, discSize))
        } else if (sprite.layoutParams.width != discSize) {
            sprite.layoutParams = (sprite.layoutParams as LinearLayout.LayoutParams).apply {
                width = discSize
                height = discSize
            }
        }
    }

    /** Compose layout: the disc at the top centre, the box hanging beneath it. */
    private fun layoutForCompose() {
        content.orientation = LinearLayout.VERTICAL
        content.gravity = Gravity.CENTER_HORIZONTAL
        setContentLayout(
            Gravity.TOP or Gravity.CENTER_HORIZONTAL,
            gutter,
            dp(HeylanaTokens.SPACE_5_DP)
        )
        content.removeView(sprite)
        content.removeView(panel)
        content.removeView(capsule)
        capsule.visibility = View.GONE
        sprite.discDp = HeylanaTokens.DISC_OPEN_DP
        content.addView(
            sprite,
            LinearLayout.LayoutParams(openDiscSize, openDiscSize).apply {
                gravity = Gravity.CENTER_HORIZONTAL
            }
        )
        content.addView(
            panel,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(HeylanaTokens.SPACE_4_DP) }
        )
    }

    private fun setContentLayout(gravity: Int, horizontalMargin: Int, topMargin: Int) {
        val centred = gravity and Gravity.CENTER_HORIZONTAL != 0
        content.layoutParams = LayoutParams(
            if (centred) LayoutParams.MATCH_PARENT else LayoutParams.WRAP_CONTENT,
            LayoutParams.WRAP_CONTENT
        ).apply {
            this.gravity = gravity
            leftMargin = horizontalMargin
            rightMargin = horizontalMargin
            this.topMargin = topMargin
        }
    }

    // -------------------------------------------------------------- flight

    /**
     * The box does not drop in — it grows out of the disc, from a small pill
     * just under it out to full size, on the same spring as the flight.
     */
    private fun growBoxOutOfDisc() {
        // The box grows out of the disc as one blob, then separates (API 31+).
        if (GooeyLayer.available) {
            panel.alpha = 1f
            panel.scaleX = 1f
            panel.scaleY = 1f
            panel.post { if (panel.gooeyGrowFrom(sprite)) panel.sweepSheen() }
            return
        }
        panel.alpha = 0f
        panel.post {
            // Pivot at the top centre of the box, which is where the disc is.
            panel.pivotX = panel.width / 2f
            panel.pivotY = 0f
            panel.scaleX = GROW_FROM_X
            panel.scaleY = GROW_FROM_Y
            panel.alpha = 1f
            for (property in arrayOf(DynamicAnimation.SCALE_X, DynamicAnimation.SCALE_Y)) {
                SpringAnimation(panel, property).apply {
                    spring = SpringForce(1f).apply {
                        stiffness = HeylanaTokens.SPRING_STIFFNESS
                        dampingRatio = HeylanaTokens.SPRING_DAMPING
                    }
                    start()
                }
            }
            panel.sweepSheen()
        }
    }

    /** The disc's top-left on the display, right now. */
    private fun spriteScreenPosition(): PointF {
        sprite.getLocationOnScreen(spriteLocation)
        return PointF(spriteLocation[0].toFloat(), spriteLocation[1].toFloat())
    }

    /** Where the disc sits on the display when it is docked. */
    /**
     * Where the disc's view will sit once docked, exactly: the same clamp the docked
     * window gets in [applyPosition], so a flight that lands here is never nudged
     * again. The dock inset is measured to the visible disc and so runs past the
     * screen edge by the bloom; the window cannot, so this is where it really ends up.
     */
    private fun dockedScreenPosition(): PointF =
        PointF((usableLeft + dockedLeft(spriteLeft)).toFloat(), (usableTop + dockedTop(spriteTop)).toFloat())

    /** The docked window's left for a wanted disc left: on screen, as the window manager keeps it. */
    private fun dockedLeft(wanted: Int): Int = DockPosition.clamped(wanted, discSize, usableWidth, edgeOverhang)

    private fun dockedTop(wanted: Int): Int = DockPosition.clamped(wanted, discSize, usableHeight)

    /** Where the disc sits on the display while composing: top centre. */
    private fun composeScreenPosition(): PointF = PointF(
        (usableLeft + (usableWidth - openDiscSize) / 2).toFloat(),
        (usableTop + dp(HeylanaTokens.SPACE_5_DP)).toFloat()
    )

    /**
     * Flies the disc across the full-screen stage and hands it back when it
     * settles. Moving this window every frame is not GPU animated and stutters;
     * a translation on a layer that is already full screen is.
     *
     * Returns false when there is no stage to fly on, so the caller can simply
     * arrive instead of pretending to travel.
     */
    /**
     * [target] is the top-left of the buddy's view where it lands, at the size of a
     * [targetDiscDp] disc. The stand-in is always the open-sized view, so it can
     * swell or shrink its disc on the way without its window-sized box changing:
     * its position is offset by half the difference at both ends.
     */
    private fun flyTo(
        target: PointF,
        targetDiscDp: Float,
        glide: Boolean = false,
        startVx: Float = 0f,
        startVy: Float = 0f,
        onLanded: () -> Unit
    ): Boolean {
        val stage = flightStage ?: return false
        val from = spriteScreenPosition()
        if (from.x == 0f && from.y == 0f) return false

        cancelFlight()
        flying = true

        // The stand-in wears the same face and leaves from exactly where the
        // real disc is standing.
        flyer.expression = sprite.expression
        flyer.talking = sprite.talking
        flyer.composing = sprite.composing
        flyer.discDp = sprite.discDp
        flyer.refreshState()
        if (targetDiscDp == HeylanaTokens.DISC_DP && flyer.composing) {
            // Going home: the dim to its resting look plays during the flight, not after it.
            flyer.composing = false
            sprite.composing = false
        }

        stage.addFlyer(flyer, openDiscSize)
        val origin = stage.stageOrigin()
        val fromView = sprite.width.takeIf { it > 0 } ?: discSize
        val toView = dp(HeylanaTokens.discViewDp(targetDiscDp))
        flyer.translationX = from.x - (openDiscSize - fromView) / 2f - origin.x
        flyer.translationY = from.y - (openDiscSize - fromView) / 2f - origin.y
        sprite.visibility = View.INVISIBLE

        // The disc swells (or settles back) on the same spring as the flight.
        val stiffness = if (glide) HeylanaTokens.GLIDE_STIFFNESS else HeylanaTokens.SPRING_STIFFNESS
        val damping = if (glide) HeylanaTokens.GLIDE_DAMPING else HeylanaTokens.SPRING_DAMPING
        flightSize = SpringAnimation(FloatValueHolder(flyer.discDp)).apply {
            spring = SpringForce(targetDiscDp).apply {
                this.stiffness = stiffness
                dampingRatio = damping
            }
            addUpdateListener { _, value, _ -> flyer.discDp = value }
            start()
        }

        var settled = 0
        val land = {
            settled++
            // Both axes have to stop before the disc is handed back.
            if (settled == 2 && flying) {
                flying = false
                // The swell or shrink ends exactly where the flight does.
                flightSize?.cancel(); flightSize = null
                flyer.discDp = targetDiscDp
                onLanded()
                // Only once the destination has been measured does the real disc
                // reappear and the stand-in leave, so the two never disagree. Where the
                // window still slides to its new place (before Android 14), the stand-in
                // stays in front until the slide is over.
                content.postDelayed({
                    leaveStage()
                    // Where it really is once handed back, and again once any layout has run:
                    // both must equal the target, or the disc moved twice.
                    logDiscPosition("landed")
                    postDelayed({ logDiscPosition("settled") }, SETTLE_CHECK_MS)
                }, if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) 0L else WINDOW_SLIDE_MS)
            }
        }

        HeylanaLog.state("flight: target x=${target.x.toInt()} y=${target.y.toInt()} size=$toView")
        val landingOffset = (openDiscSize - toView) / 2f
        flightVx = 0f
        flightVy = 0f
        // A glide carries the finger's fling into the flight: it starts at that speed.
        flightX = spring(DynamicAnimation.TRANSLATION_X, target.x - landingOffset - origin.x, stiffness, damping, startVx, land)
        flightY = spring(DynamicAnimation.TRANSLATION_Y, target.y - landingOffset - origin.y, stiffness, damping, startVy, land)
        HeylanaLog.state("flight: spring stiffness=${stiffness.toInt()} damping=$damping start_vx=${startVx.toInt()} start_vy=${startVy.toInt()}")
        return true
    }

    private fun spring(
        property: DynamicAnimation.ViewProperty,
        finalValue: Float,
        stiffness: Float,
        damping: Float,
        startVelocity: Float,
        onEnd: () -> Unit
    ): SpringAnimation = SpringAnimation(flyer, property).apply {
        this.spring = SpringForce(finalValue).apply {
            this.stiffness = stiffness
            dampingRatio = damping
        }
        setStartVelocity(startVelocity)
        // The flight's speed splits the mark's colours; landing settles them.
        addUpdateListener { _, _, velocity ->
            if (property === DynamicAnimation.TRANSLATION_X) flightVx = velocity else flightVy = velocity
            flyer.setMotion(flightVx, flightVy)
        }
        addEndListener { _, _, _, _ -> onEnd() }
        start()
    }

    private var flightVx = 0f
    private var flightVy = 0f

    /** The last drag sample, to turn finger movement into a speed. */
    private var dragSampleX = 0f
    private var dragSampleY = 0f
    private var dragSampleTime = 0L

    /** The finger's speed at the end of a drag, smoothed, in px per second. */
    private var dragVx = 0f
    private var dragVy = 0f

    private fun trackDragSpeed(event: MotionEvent) {
        val elapsed = event.eventTime - dragSampleTime
        if (dragSampleTime != 0L && elapsed > 0) {
            val seconds = elapsed / 1000f
            val vx = (event.rawX - dragSampleX) / seconds
            val vy = (event.rawY - dragSampleY) / seconds
            dragVx = Glide.smooth(dragVx, vx)
            dragVy = Glide.smooth(dragVy, vy)
            sprite.setMotion(vx, vy)
        } else {
            dragVx = 0f
            dragVy = 0f
        }
        dragSampleX = event.rawX
        dragSampleY = event.rawY
        dragSampleTime = event.eventTime
    }

    private fun cancelFlight() {
        flightX?.cancel(); flightX = null
        flightY?.cancel(); flightY = null
        flightSize?.cancel(); flightSize = null
        if (flying) {
            flying = false
            leaveStage()
        }
    }

    /** Retires the stand-in and shows the real disc again. */
    private fun leaveStage() {
        sprite.visibility = View.VISIBLE
        flightStage?.removeFlyer(flyer)
        flyer.translationX = 0f
        flyer.translationY = 0f
    }

    fun closePanel() {
        if (mode == Mode.DOCKED) return
        // A teaching flight moved the disc out over the app: it flies home, not docks where it stands.
        if (mode == Mode.HUD && teachHome != null) {
            cancelTeachingFlight()
            panel.releaseInput()
            endTeaching()
            return
        }
        panel.meltStreak()
        panel.releaseInput()
        if (mode != Mode.COMPOSE) {
            enterMode(Mode.DOCKED)
            onPanelClosed?.invoke()
            return
        }
        // The glass goes first — drawn back into the disc as one blob where there is goo,
        // faded where there is not — then the disc flies home over the bare app.
        scrim.animate().alpha(0f).setDuration(HeylanaTokens.FADE_MS).start()
        val shrinking = panel.gooeyShrinkInto(sprite) {
            panel.alpha = 0f
            panel.gooeyReset()
            flyHome()
        }
        if (shrinking) return
        panel.animate().alpha(0f).setDuration(HeylanaTokens.FADE_MS).start()
        flyHome()
    }

    /** The disc's flight back to its dock after the box has gone. */
    private fun flyHome() {
        // The exact dock, worked out before take-off; the disc lands on it and stays.
        spriteLeft = dockedLeft(spriteLeft)
        spriteTop = dockedTop(spriteTop)
        val flew = flyTo(dockedScreenPosition(), HeylanaTokens.DISC_DP, glide = true) {
            enterMode(Mode.DOCKED)
            onPanelClosed?.invoke()
        }
        if (!flew) {
            enterMode(Mode.DOCKED)
            onPanelClosed?.invoke()
        }
    }

    /** The user touched the question field while the window was passive. */
    private fun takeFocusForTyping() {
        if (mode != Mode.HUD) return
        params.flags = FLAGS_COMPOSE
        updateLayout()
        panel.focusInput()
    }

    // ---------------------------------------------------------------- touch

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (ev.actionMasked == MotionEvent.ACTION_OUTSIDE) {
            // While a task runs, a tap outside is the user doing the step.
            if (mode == Mode.COMPOSE) closePanel()
            return true
        }
        return super.dispatchTouchEvent(ev)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_BACK && mode != Mode.DOCKED) {
            if (event.action == KeyEvent.ACTION_UP) closePanel()
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    private fun handleSpriteTouch(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                HeylanaLog.state("touch: down")
                // Mid-sentence, the first thing a touch does is stop her.
                interruptedSpeech = SpeechTouch.stops(isSpeaking())
                if (interruptedSpeech) onInterrupt?.invoke()
                onTouched?.invoke()
                cancelFlight()
                refreshMetrics()
                dragging = false
                holding = false
                downRawX = event.rawX
                downRawY = event.rawY
                startLeft = spriteLeft
                startTop = spriteTop
                postDelayed(longPress, ViewConfiguration.getLongPressTimeout().toLong())
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                val dx = event.rawX - downRawX
                val dy = event.rawY - downRawY
                if (!dragging && hypot(dx, dy) > touchSlop) {
                    removeCallbacks(longPress)
                    if (holding) {
                        holding = false
                        onHoldCancel?.invoke()
                    } else {
                        onNotAHold?.invoke()
                    }
                    // The buddy is parked while composing; dragging it is not a thing.
                    if (mode == Mode.COMPOSE) return true
                    dragging = true
                    // Fold the box away so only the disc follows the finger.
                    closePanel()
                    startLeft = spriteLeft
                    startTop = spriteTop
                    downRawX = event.rawX
                    downRawY = event.rawY
                    dragSampleTime = 0L
                }
                if (dragging) {
                    trackDragSpeed(event)
                    spriteLeft = clamp(
                        startLeft + (event.rawX - downRawX).toInt(),
                        dockInset,
                        usableWidth - discSize - dockInset
                    )
                    spriteTop = clamp(
                        startTop + (event.rawY - downRawY).toInt(),
                        dockInset,
                        usableHeight - discSize - dockInset
                    )
                    applyPosition()
                }
                return true
            }

            MotionEvent.ACTION_UP -> {
                HeylanaLog.state("touch: up holding=$holding dragging=$dragging")
                removeCallbacks(longPress)
                when {
                    holding -> {
                        holding = false
                        onHoldEnd?.invoke()
                    }

                    dragging -> snapToNearestEdge()
                    else -> {
                        onNotAHold?.invoke()
                        // A tap that stopped the voice did what it was for; the box stays as it is.
                        if (SpeechTouch.tapToggles(interruptedSpeech)) togglePanel()
                    }
                }
                dragging = false
                return true
            }

            MotionEvent.ACTION_CANCEL -> {
                HeylanaLog.state("touch: cancel holding=$holding dragging=$dragging")
                removeCallbacks(longPress)
                if (holding) {
                    holding = false
                    onHoldCancel?.invoke()
                } else {
                    onNotAHold?.invoke()
                }
                if (dragging) snapToNearestEdge()
                dragging = false
                return true
            }
        }
        return false
    }

    // ------------------------------------------------------------- position

    private fun snapToNearestEdge() {
        val spriteCenter = spriteLeft + discSize / 2
        // The exact dock, clamped as the window will be, so the snap is one movement.
        val targetLeft = dockedLeft(
            if (spriteCenter < usableWidth / 2) dockInset else usableWidth - discSize - dockInset
        )
        val targetTop = dockedTop(clamp(spriteTop, dockInset, usableHeight - discSize - dockInset))

        val maxVelocity = HeylanaTokens.dp(context, HeylanaTokens.GLIDE_MAX_START_DP_PER_S)
        val flew = flyTo(
            PointF((usableLeft + targetLeft).toFloat(), (usableTop + targetTop).toFloat()),
            HeylanaTokens.DISC_DP,
            glide = true,
            startVx = Glide.startVelocity(dragVx, maxVelocity),
            startVy = Glide.startVelocity(dragVy, maxVelocity)
        ) {
            spriteLeft = targetLeft
            spriteTop = targetTop
            applyPosition()
        }
        if (!flew) {
            spriteLeft = targetLeft
            spriteTop = targetTop
            applyPosition()
        }
    }

    // ------------------------------------------------------------ teaching flight

    private var teachingFlight: android.animation.ValueAnimator? = null
    private val flightPoint = FloatArray(2)
    private val standAt = IntArray(3)

    /**
     * One hop of a teaching answer: the disc flies to [target] (screen coordinates) along
     * an arc, swelling as it goes, stands beside it, and the strip travels with it showing
     * [text]. [onArrived] runs when it lands — that is when the ring goes up and the
     * sentence is spoken.
     */
    fun teachTo(target: Rect, text: String, onArrived: () -> Unit) {
        refreshMetrics()
        // Where the flight ends up: the edge the disc was docked on, remembered before the
        // first hop overwrites where it stands.
        if (teachHome == null) teachHome = homeEdge()
        if (mode != Mode.HUD) enterMode(Mode.HUD)
        panel.setVoiceMode(voice = false, showsText = true)
        showAnswer(text)

        // The whole window — disc and strip, as they measure now with this sentence in —
        // goes clear of the element, so a tap on it can never land on Heylana.
        reorderBeside()
        measureContainer()
        TeachingFlight.placeWindow(
            target.left - usableLeft, target.top - usableTop,
            target.right - usableLeft, target.bottom - usableTop,
            measuredContainerWidth, measuredContainerHeight,
            dp(HeylanaTokens.SPACE_4_DP),
            usableWidth,
            usableHeight,
            standAt
        )
        if (panelOnLeft != (standAt[2] == 1)) {
            panelOnLeft = standAt[2] == 1
            reorderBeside()
            measureContainer()
        }
        // Where the disc has to be for [applyPosition] to put the window there.
        val spriteX = standAt[0] + if (panelOnLeft) measuredContainerWidth - discSize else 0
        val spriteY = standAt[1] + (measuredContainerHeight - discSize) / 2
        HeylanaLog.state(
            "teach: window at ${standAt[0] + usableLeft},${standAt[1] + usableTop} " +
                "size=${measuredContainerWidth}x$measuredContainerHeight clear_of=${target.left},${target.top},${target.right},${target.bottom}"
        )
        flyArc(spriteX, spriteY, onArrived)
    }

    /** The disc's flight home at the end of a teaching answer: the same arc, then the strip melts. */
    fun endTeaching(onHome: () -> Unit = {}) {
        refreshMetrics()
        // The dock it left from; failing that, the nearer edge. Clamping where it stands
        // now left the disc mid-screen beside the last element.
        val home = teachHome ?: homeEdge()
        teachHome = null
        HeylanaLog.state("teach: flying home x=${home[0]} y=${home[1]}")
        panel.meltStreak()
        flyArc(home[0], home[1]) {
            enterMode(Mode.DOCKED)
            onPanelClosed?.invoke()
            onHome()
        }
    }

    /** The dock a teaching flight returns to; null until the first hop. */
    private var teachHome: IntArray? = null

    /** The edge nearer the disc, at its height, as a docked position. */
    private fun homeEdge(): IntArray {
        val onLeft = spriteLeft + discSize / 2 < usableWidth / 2
        val left = dockedLeft(if (onLeft) dockInset else usableWidth - discSize - dockInset)
        val top = dockedTop(clamp(spriteTop, dockInset, (usableHeight - discSize - dockInset).coerceAtLeast(dockInset)))
        return intArrayOf(left, top)
    }

    /** Moves the window itself along the arc, frame by frame, and hands the disc back its size. */
    private fun flyArc(toLeft: Int, toTop: Int, onLanded: () -> Unit) {
        teachingFlight?.cancel()
        val fromLeft = spriteLeft.toFloat()
        val fromTop = spriteTop.toFloat()
        val toLeftClamped = clamp(toLeft, 0, (usableWidth - discSize).coerceAtLeast(0))
        val toTopClamped = clamp(toTop, 0, (usableHeight - discSize).coerceAtLeast(0))
        val distance = TeachingFlight.distance(fromLeft, fromTop, toLeftClamped.toFloat(), toTopClamped.toFloat())
        val perDp = dp(1f).toFloat()
        val arc = TeachingFlight.arcHeightDp(distance / perDp) * perDp
        val duration = TeachingFlight.durationMs(distance / perDp)
        HeylanaLog.state("teach: flight ${duration}ms distance=${distance.toInt()} arc=${arc.toInt()}")
        if (distance < 2f * perDp) {
            spriteLeft = toLeftClamped
            spriteTop = toTopClamped
            applyPosition()
            onLanded()
            return
        }
        teachingFlight = android.animation.ValueAnimator.ofFloat(0f, 1f).apply {
            this.duration = duration
            interpolator = android.view.animation.LinearInterpolator()
            addUpdateListener { animation ->
                val progress = animation.animatedValue as Float
                TeachingFlight.at(progress, fromLeft, fromTop, toLeftClamped.toFloat(), toTopClamped.toFloat(), arc, flightPoint)
                spriteLeft = flightPoint[0].toInt()
                spriteTop = flightPoint[1].toInt()
                val scale = TeachingFlight.scaleAt(progress)
                sprite.scaleX = scale
                sprite.scaleY = scale
                applyPosition()
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    sprite.scaleX = 1f
                    sprite.scaleY = 1f
                    if (teachingFlight !== animation) return
                    teachingFlight = null
                    spriteLeft = toLeftClamped
                    spriteTop = toTopClamped
                    applyPosition()
                    onLanded()
                }
            })
            start()
        }
    }

    /** Stops a teaching flight where it is: a new question, or the panel closing. */
    fun cancelTeachingFlight() {
        teachingFlight?.cancel()
        teachingFlight = null
        sprite.scaleX = 1f
        sprite.scaleY = 1f
    }

    /**
     * Keeps the buddy and its box off the thing being pointed at. Only means
     * anything while a task is running, since that is the only time the box and
     * the highlight share the screen.
     */
    fun avoidOverlap(target: Rect) {
        if (mode != Mode.HUD || target.isEmpty) return
        refreshMetrics()

        val startedOnLeft = panelOnLeft
        for (side in booleanArrayOf(startedOnLeft, !startedOnLeft)) {
            panelOnLeft = side
            reorderBeside()
            applyPosition()
            if (!Rect.intersects(currentScreenRect(), target)) return
        }

        panelOnLeft = startedOnLeft
        reorderBeside()
        val gap = dp(HeylanaTokens.SPACE_2_DP)
        val height = measuredContainerHeight
        val startingTop = spriteTop

        for (candidate in intArrayOf(
            target.top - gap - height - usableTop,
            target.bottom + gap - usableTop
        )) {
            if (candidate < 0 || candidate + height > usableHeight) continue
            spriteTop = clamp(candidate + (height - discSize) / 2, 0, usableHeight - discSize)
            applyPosition()
            if (!Rect.intersects(currentScreenRect(), target)) return
        }

        spriteTop = startingTop
        applyPosition()
    }

    /** Puts the box on whichever side [panelOnLeft] currently says. */
    private fun reorderBeside() {
        // Same rule as [layoutBeside]: the disc keeps its place in the row, so
        // whatever gesture it is holding survives the box moving sides.
        content.removeView(panel)
        content.removeView(capsule)
        val boxParams = LinearLayout.LayoutParams(
            panel.hudWidth, LinearLayout.LayoutParams.WRAP_CONTENT
        )
        dockSizedSprite()
        val discIndex = content.indexOfChild(sprite)
        if (panelOnLeft) {
            content.addView(panel, discIndex, boxParams)
        } else {
            content.addView(panel, discIndex + 1, boxParams)
        }
    }

    /**
     * Where the container will sit on the display, in screen coordinates.
     * Derived from the window insets rather than [getLocationOnScreen], because
     * this is called straight after moving the window and the view's real
     * on-screen position does not update until the next layout pass.
     */
    private fun currentScreenRect(): Rect {
        occupied.set(
            params.x + usableLeft,
            params.y + usableTop,
            params.x + usableLeft + measuredContainerWidth,
            params.y + usableTop + measuredContainerHeight
        )
        return occupied
    }

    /** Re-derives the window position from where the disc should be. */
    private fun applyPosition() {
        if (mode == Mode.COMPOSE) {
            params.x = 0
            params.y = 0
            updateLayout()
            return
        }
        measureContainer()
        val spriteOffsetX = if (mode == Mode.HUD && panelOnLeft) {
            measuredContainerWidth - discSize
        } else {
            0
        }

        // Docked, the bloom margin may hang off the side, so the disc rests 6dp from it.
        val overhang = if (mode == Mode.DOCKED) edgeOverhang else 0
        val x = clamp(spriteLeft - spriteOffsetX, -overhang, usableWidth - measuredContainerWidth + overhang)
        val y = clamp(
            spriteTop - (measuredContainerHeight - discSize) / 2,
            0,
            usableHeight - measuredContainerHeight
        )

        params.x = x
        params.y = y
        updateLayout()
    }

    private fun measureContainer() {
        content.measure(
            MeasureSpec.makeMeasureSpec(usableWidth, MeasureSpec.AT_MOST),
            MeasureSpec.makeMeasureSpec(usableHeight, MeasureSpec.AT_MOST)
        )
        measuredContainerWidth = content.measuredWidth.coerceAtLeast(discSize)
        measuredContainerHeight = content.measuredHeight.coerceAtLeast(discSize)
    }

    private fun updateLayout() {
        if (attached && isAttachedToWindow) {
            windowManager.updateViewLayout(this, params)
        }
    }

    private fun refreshMetrics() {
        val metrics = windowManager.currentWindowMetrics
        val insets = metrics.windowInsets
            .getInsetsIgnoringVisibility(WindowInsets.Type.systemBars())
        usableLeft = insets.left
        usableTop = insets.top
        usableWidth = metrics.bounds.width() - insets.left - insets.right
        usableHeight = metrics.bounds.height() - insets.top - insets.bottom
    }

    private fun clamp(value: Int, min: Int, max: Int): Int =
        if (max < min) min else value.coerceIn(min, max)

    private fun dp(value: Float): Int = HeylanaTokens.dpInt(context, value)

    companion object {
        /** Neither touches nor focus while the screen is read, so the app beneath is listed again. */
        private const val READ_THROUGH_FLAGS =
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE

        /** The box starts as a small pill under the disc. */
        private const val GROW_FROM_X = 0.25f
        private const val GROW_FROM_Y = 0.12f

        /** Small window: invisible to touch and keyboard beyond its own bounds. */
        /** How long after landing the disc's position is checked again. */
        private const val SETTLE_CHECK_MS = 500L

        /** How long the system's window slide lasts where it cannot be switched off. */
        private const val WINDOW_SLIDE_MS = 250L

        /** Docked and HUD. NO_LIMITS lets the docked window's bloom margin hang off the edge. */
        private const val FLAGS_PASSIVE =
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS

        /** Composing: focusable so the field can type, and told about outside taps. */
        private const val FLAGS_COMPOSE =
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or
                // The window becomes the docked one at landing, before its flags change:
                // without this the overhang is clamped away there, and the disc rests 14dp in.
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
    }
}
