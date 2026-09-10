package xyz.heylana.app.overlay

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.PixelFormat
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
import androidx.dynamicanimation.animation.SpringAnimation
import androidx.dynamicanimation.animation.SpringForce
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

    /**
     * The full-screen layer the disc flies across. Moving a window every frame
     * is not GPU animated and stutters; a view translation on a layer that is
     * already full screen is.
     */
    var flightStage: HighlightOverlayView? = null

    private val windowManager = context.getSystemService(WindowManager::class.java)

    /** The buddy's view: the disc plus the room its bloom needs on every side. */
    private val discSize = dp(HeylanaTokens.DISC_DP + 2 * HeylanaTokens.DISC_BLEED_DP)

    /**
     * The dock inset is measured to the visible disc, not to the view, so the
     * bloom's transparent margin does not read as a gap at the screen edge.
     */
    private val dockInset = dp(HeylanaTokens.DOCK_INSET_DP - HeylanaTokens.DISC_BLEED_DP)
    /**
     * The gutter is measured to the visible pane. The panel carries its own
     * shadow margin, so that much is taken off the layout margin or the box
     * would sit twice as far in as it should.
     */
    private val gutter = (dp(HeylanaTokens.SPACE_5_DP) -
        dp(HeylanaTokens.GLASS_SHADOW_DP)).coerceAtLeast(0)

    private val sprite = BuddySpriteView(context)
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
    private var flying = false
    private var attached = false

    private var measuredContainerWidth = discSize
    private var measuredContainerHeight = discSize

    private val spriteLocation = IntArray(2)
    private val occupied = Rect()

    private val longPress = Runnable {
        if (!dragging && !holding) {
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
        spriteLeft = usableWidth - discSize - dockInset
        spriteTop = usableHeight / 3
        measureContainer()
        params.x = spriteLeft
        params.y = spriteTop
        windowManager.addView(this, params)
        attached = true
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
        sprite.expression = BuddySpriteView.Expression.THINKING
        panel.showThinking()
    }

    fun showAnswer(text: String) {
        sprite.expression = BuddySpriteView.Expression.IDLE
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
        sprite.expression = BuddySpriteView.Expression.IDLE
        // Spoken exchanges have no box of their own, and a problem still has to
        // be readable, so this is the one thing that opens one uninvited.
        ensurePanelOpen()
        panel.showNotice(text)
        applyPosition()
    }

    fun showNote(text: String) {
        panel.showNote(text)
        applyPosition()
    }

    fun setMuted(muted: Boolean) = panel.setMuted(muted)

    /** True when the exchange in progress was asked by voice rather than typed. */
    val wasSpoken: Boolean get() = panel.isVoiceMode

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
    }

    /** Look at whatever is being highlighted. */
    fun lookAt(target: PointF) {
        sprite.pointDirection = if (target.x < spriteCenterOnScreen().x) -1 else 1
        sprite.pointTarget = target
        sprite.expression = BuddySpriteView.Expression.POINTING
    }

    fun stopLooking() {
        sprite.pointTarget = null
        if (sprite.expression == BuddySpriteView.Expression.POINTING) {
            sprite.expression = BuddySpriteView.Expression.IDLE
        }
    }

    /** Shows the step counter with next and done while a task is running. */
    fun showSession(stepNumber: Int, ofSteps: Int) {
        panel.showSession(stepNumber, ofSteps)
        // A task hands the keyboard back to the app the user is about to operate.
        if (mode == Mode.COMPOSE) enterMode(Mode.HUD)
        applyPosition()
    }

    fun hideSession() {
        panel.hideSession()
        applyPosition()
    }

    /** Opens the box from code — passive, so a task never dims the screen. */
    fun ensurePanelOpen() {
        if (mode == Mode.DOCKED || mode == Mode.CAPSULE) enterMode(Mode.HUD)
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
        sprite.expression = BuddySpriteView.Expression.LISTENING
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
        sprite.expression = BuddySpriteView.Expression.THINKING
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

    fun stoppedListening() {
        if (sprite.expression == BuddySpriteView.Expression.LISTENING) {
            sprite.expression = BuddySpriteView.Expression.IDLE
        }
    }

    // ----------------------------------------------------------------- mode

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
    private fun openCompose() {
        if (mode == Mode.COMPOSE) return
        val flew = flyTo(composeScreenPosition()) { enterMode(Mode.COMPOSE) }
        if (!flew) enterMode(Mode.COMPOSE)
    }

    /**
     * Switches mode and rebuilds the window to match. Compose takes the whole
     * screen so it can dim and type; the other two stay small so touches fall
     * through to the app underneath.
     */
    private fun enterMode(next: Mode) {
        if (mode == next) return
        mode = next
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
                if (!panel.isVoiceMode) panel.focusInput()
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
        content.removeView(sprite)
        content.removeView(panel)
        content.removeView(capsule)
        val discParams = LinearLayout.LayoutParams(discSize, discSize)
        val boxParams = LinearLayout.LayoutParams(
            panel.hudWidth, LinearLayout.LayoutParams.WRAP_CONTENT
        )
        val capsuleParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
        )
        if (panelOnLeft) {
            content.addView(capsule, capsuleParams)
            content.addView(panel, boxParams)
            content.addView(sprite, discParams)
        } else {
            content.addView(sprite, discParams)
            content.addView(panel, boxParams)
            content.addView(capsule, capsuleParams)
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
        content.addView(
            sprite,
            LinearLayout.LayoutParams(discSize, discSize).apply {
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
    private fun dockedScreenPosition(): PointF =
        PointF((usableLeft + spriteLeft).toFloat(), (usableTop + spriteTop).toFloat())

    /** Where the disc sits on the display while composing: top centre. */
    private fun composeScreenPosition(): PointF = PointF(
        (usableLeft + (usableWidth - discSize) / 2).toFloat(),
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
    private fun flyTo(target: PointF, onLanded: () -> Unit): Boolean {
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
        flyer.refreshState()

        stage.addFlyer(flyer, discSize)
        val origin = stage.stageOrigin()
        flyer.translationX = from.x - origin.x
        flyer.translationY = from.y - origin.y
        sprite.visibility = View.INVISIBLE

        var settled = 0
        val land = {
            settled++
            // Both axes have to stop before the disc is handed back.
            if (settled == 2 && flying) {
                flying = false
                onLanded()
                // Only once the destination has been measured does the real disc
                // reappear and the stand-in leave, so the two never disagree.
                content.post { leaveStage() }
            }
        }

        flightX = spring(DynamicAnimation.TRANSLATION_X, target.x - origin.x, land)
        flightY = spring(DynamicAnimation.TRANSLATION_Y, target.y - origin.y, land)
        return true
    }

    private fun spring(
        property: DynamicAnimation.ViewProperty,
        finalValue: Float,
        onEnd: () -> Unit
    ): SpringAnimation = SpringAnimation(flyer, property).apply {
        this.spring = SpringForce(finalValue).apply {
            stiffness = HeylanaTokens.SPRING_STIFFNESS
            dampingRatio = HeylanaTokens.SPRING_DAMPING
        }
        addEndListener { _, _, _, _ -> onEnd() }
        start()
    }

    private fun cancelFlight() {
        flightX?.cancel(); flightX = null
        flightY?.cancel(); flightY = null
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
        panel.releaseInput()
        if (mode != Mode.COMPOSE) {
            enterMode(Mode.DOCKED)
            onPanelClosed?.invoke()
            return
        }
        // The glass goes first, then the disc flies home over the bare app.
        scrim.animate().alpha(0f).setDuration(HeylanaTokens.FADE_MS).start()
        panel.animate().alpha(0f).setDuration(HeylanaTokens.FADE_MS).start()
        val flew = flyTo(dockedScreenPosition()) {
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
                }
                if (dragging) {
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
                removeCallbacks(longPress)
                when {
                    holding -> {
                        holding = false
                        onHoldEnd?.invoke()
                    }

                    dragging -> snapToNearestEdge()
                    else -> togglePanel()
                }
                dragging = false
                return true
            }

            MotionEvent.ACTION_CANCEL -> {
                removeCallbacks(longPress)
                if (holding) {
                    holding = false
                    onHoldCancel?.invoke()
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
        val targetLeft = if (spriteCenter < usableWidth / 2) {
            dockInset
        } else {
            usableWidth - discSize - dockInset
        }
        val targetTop = clamp(spriteTop, dockInset, usableHeight - discSize - dockInset)

        val flew = flyTo(
            PointF((usableLeft + targetLeft).toFloat(), (usableTop + targetTop).toFloat())
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
        content.removeView(sprite)
        content.removeView(panel)
        content.removeView(capsule)
        val discParams = LinearLayout.LayoutParams(discSize, discSize)
        val boxParams = LinearLayout.LayoutParams(
            panel.hudWidth, LinearLayout.LayoutParams.WRAP_CONTENT
        )
        if (panelOnLeft) {
            content.addView(panel, boxParams)
            content.addView(sprite, discParams)
        } else {
            content.addView(sprite, discParams)
            content.addView(panel, boxParams)
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

        val x = clamp(spriteLeft - spriteOffsetX, 0, usableWidth - measuredContainerWidth)
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
        /** The box starts as a small pill under the disc. */
        private const val GROW_FROM_X = 0.25f
        private const val GROW_FROM_Y = 0.12f

        /** Small window: invisible to touch and keyboard beyond its own bounds. */
        private const val FLAGS_PASSIVE =
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL

        /** Composing: focusable so the field can type, and told about outside taps. */
        private const val FLAGS_COMPOSE =
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
    }
}
