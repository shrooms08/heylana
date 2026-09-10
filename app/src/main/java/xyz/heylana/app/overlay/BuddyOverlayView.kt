package xyz.heylana.app.overlay

import android.animation.ValueAnimator
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
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
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

    private enum class Mode { DOCKED, COMPOSE, HUD }

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

    private val windowManager = context.getSystemService(WindowManager::class.java)

    /** The buddy's view: the disc plus the room its bloom needs on every side. */
    private val discSize = dp(HeylanaTokens.DISC_DP + 2 * HeylanaTokens.DISC_BLEED_DP)

    /**
     * The dock inset is measured to the visible disc, not to the view, so the
     * bloom's transparent margin does not read as a gap at the screen edge.
     */
    private val dockInset = dp(HeylanaTokens.DOCK_INSET_DP - HeylanaTokens.DISC_BLEED_DP)
    private val gutter = dp(HeylanaTokens.SPACE_5_DP)

    private val sprite = BuddySpriteView(context)
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

    private var snapAnimator: ValueAnimator? = null
    private var flightAnimator: ValueAnimator? = null
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
        snapAnimator?.cancel(); snapAnimator = null
        flightAnimator?.cancel(); flightAnimator = null
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
        applyPosition()
    }

    fun showNotice(text: String) {
        sprite.expression = BuddySpriteView.Expression.IDLE
        panel.showNotice(text)
        applyPosition()
    }

    fun showNote(text: String) {
        panel.showNote(text)
        applyPosition()
    }

    fun setMuted(muted: Boolean) = panel.setMuted(muted)

    /** Drops the keyboard so the app underneath un-squeezes before it is read. */
    fun hideKeyboard() = panel.hideKeyboard()

    fun setTalking(talking: Boolean) {
        sprite.talking = talking
    }

    /** Look at whatever is being highlighted. */
    fun lookAt(targetCenterX: Int) {
        val center = spriteCenterOnScreen().x
        sprite.pointDirection = if (targetCenterX < center) -1 else 1
        sprite.expression = BuddySpriteView.Expression.POINTING
    }

    fun stopLooking() {
        if (sprite.expression == BuddySpriteView.Expression.POINTING) {
            sprite.expression = BuddySpriteView.Expression.IDLE
        }
    }

    /** Shows the step counter with next and done while a task is running. */
    fun showSession(stepNumber: Int) {
        panel.showSession(stepNumber)
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
        if (mode == Mode.DOCKED) enterMode(Mode.HUD)
    }

    // ---------------------------------------------------------------- voice

    /** Microphone opened: show the box without shoving the keyboard in the way. */
    fun startedListening() {
        ensurePanelOpen()
        sprite.expression = BuddySpriteView.Expression.LISTENING
        sprite.refreshState()
        panel.showListening()
        panel.hideKeyboard()
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
        if (mode == Mode.DOCKED) enterMode(Mode.COMPOSE) else closePanel()
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

            Mode.HUD -> {
                panel.releaseInput()
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
                flyIntoCompose()
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

    /** How far the docked disc is from where it lands when composing. */
    private fun flightOffsetX(): Float = (spriteLeft - (usableWidth - discSize) / 2).toFloat()

    private fun flightOffsetY(): Float = (spriteTop - dp(HeylanaTokens.SPACE_5_DP)).toFloat()

    /** The buddy flies from where it was docked up to the top centre. */
    private fun flyIntoCompose() {
        val fromX = flightOffsetX()
        val fromY = flightOffsetY()

        flightAnimator?.cancel()
        content.translationX = fromX
        content.translationY = fromY
        panel.alpha = 0f
        scrim.alpha = 0f

        flightAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = HeylanaTokens.SPRING_MS
            interpolator = OvershootInterpolator(1.1f)
            addUpdateListener {
                val t = it.animatedValue as Float
                content.translationX = fromX * (1f - t)
                content.translationY = fromY * (1f - t)
                val fade = t.coerceIn(0f, 1f)
                scrim.alpha = fade
                panel.alpha = fade
            }
            start()
        }
        panel.focusInput()
    }

    fun closePanel() {
        if (mode == Mode.DOCKED) return
        panel.releaseInput()
        if (mode != Mode.COMPOSE) {
            enterMode(Mode.DOCKED)
            onPanelClosed?.invoke()
            return
        }
        // Let the buddy fly back before the window shrinks again.
        flightAnimator?.cancel()
        val toX = flightOffsetX()
        val toY = flightOffsetY()
        flightAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = HeylanaTokens.SPRING_MS
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                val t = it.animatedValue as Float
                content.translationX = toX * t
                content.translationY = toY * t
                scrim.alpha = 1f - t
                panel.alpha = 1f - t
            }
            start()
        }
        postDelayed({
            content.translationX = 0f
            content.translationY = 0f
            panel.alpha = 1f
            enterMode(Mode.DOCKED)
            onPanelClosed?.invoke()
        }, HeylanaTokens.SPRING_MS)
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
                snapAnimator?.cancel()
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
        spriteTop = clamp(spriteTop, dockInset, usableHeight - discSize - dockInset)
        animateLeftTo(targetLeft)
    }

    private fun animateLeftTo(targetLeft: Int) {
        if (abs(spriteLeft - targetLeft) < 2) {
            spriteLeft = targetLeft
            applyPosition()
            return
        }
        snapAnimator?.cancel()
        snapAnimator = ValueAnimator.ofInt(spriteLeft, targetLeft).apply {
            duration = 180L
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                spriteLeft = it.animatedValue as Int
                applyPosition()
            }
            start()
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
