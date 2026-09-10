package xyz.heylana.app.overlay

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.PixelFormat
import android.graphics.PointF
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowInsets
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.widget.LinearLayout
import kotlin.math.abs
import kotlin.math.hypot

/**
 * The window-level container for the buddy: [ChatPanelView] + [BuddySpriteView].
 *
 * Owns its own WindowManager layout params, the gestures, the snap-to-edge
 * behaviour, and opening the chat panel on whichever side of the sprite has room.
 *
 * Gestures on the sprite are kept strictly apart:
 *  - a tap toggles the chat panel,
 *  - a drag moves the buddy and snaps it to an edge,
 *  - a press-and-hold opens the microphone; if the hold turns into a drag the
 *    listening is cancelled and it becomes an ordinary drag.
 *
 * The window is only made focusable while the panel was opened by a tap, so the
 * rest of the time it never steals touches or the keyboard from the app underneath.
 */
@SuppressLint("ViewConstructor")
class BuddyOverlayView(context: Context) : LinearLayout(context) {

    /** Called with the user's question when they hit Send. */
    var onQuestion: ((String) -> Unit)? = null

    /** The user has held the sprite down: start listening. */
    var onHoldStart: (() -> Unit)? = null

    /** The user let go after holding: finish listening and send. */
    var onHoldEnd: (() -> Unit)? = null

    /** The hold turned into a drag: throw the listening away. */
    var onHoldCancel: (() -> Unit)? = null

    /** The speaker glyph was tapped. */
    var onMuteToggled: ((Boolean) -> Unit)? = null

    /** The panel just closed, however it closed. */
    var onPanelClosed: (() -> Unit)? = null

    private val windowManager = context.getSystemService(WindowManager::class.java)

    private val spriteSize = dp(96f)
    private val sprite = BuddySpriteView(context)
    private val panel = ChatPanelView(context)

    private val params = WindowManager.LayoutParams(
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        FLAGS_CLOSED,
        PixelFormat.TRANSLUCENT
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_PAN or
            WindowManager.LayoutParams.SOFT_INPUT_STATE_UNCHANGED
    }

    private var usableWidth = 0
    private var usableHeight = 0

    /** Where the sprite itself sits — the panel is laid out around it. */
    private var spriteLeft = 0
    private var spriteTop = 0

    private var panelOpen = false
    private var panelOnLeft = false

    private var dragging = false
    private var holding = false
    private var downRawX = 0f
    private var downRawY = 0f
    private var startLeft = 0
    private var startTop = 0
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop

    private var snapAnimator: ValueAnimator? = null
    private var attached = false

    private var measuredContainerWidth = spriteSize
    private var measuredContainerHeight = spriteSize

    private val spriteLocation = IntArray(2)

    private val longPress = Runnable {
        if (!dragging && !holding) {
            holding = true
            onHoldStart?.invoke()
        }
    }

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        clipChildren = false

        panel.visibility = View.GONE
        panel.onSend = { question -> onQuestion?.invoke(question) }
        panel.onMuteToggled = { muted -> onMuteToggled?.invoke(muted) }

        addView(sprite, LayoutParams(spriteSize, spriteSize))
        addView(panel, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))

        @Suppress("ClickableViewAccessibility")
        sprite.setOnTouchListener { _, event -> handleSpriteTouch(event) }
    }

    // ---------------------------------------------------------------- window

    fun addToWindow() {
        if (attached) return
        refreshMetrics()
        panelOpen = false
        panel.visibility = View.GONE
        params.flags = FLAGS_CLOSED
        spriteLeft = usableWidth - spriteSize
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
        snapAnimator?.cancel()
        snapAnimator = null
        panel.releaseInput()
        windowManager.removeView(this)
        attached = false
    }

    /** Middle of the sprite in screen coordinates, for the pointer's arrow. */
    fun spriteCenterOnScreen(): PointF {
        sprite.getLocationOnScreen(spriteLocation)
        return PointF(
            spriteLocation[0] + sprite.width / 2f,
            spriteLocation[1] + sprite.height / 2f
        )
    }

    // ------------------------------------------------------------ chat state

    val isPanelOpen: Boolean get() = panelOpen

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

    // ---------------------------------------------------------------- voice

    /** Microphone opened: show the panel without shoving the keyboard in the way. */
    fun startedListening() {
        sprite.expression = BuddySpriteView.Expression.LISTENING
        openPanel(takeFocus = false)
        panel.showListening()
        panel.hideKeyboard()
    }

    fun showPartialSpeech(text: String) = panel.setSpokenText(text)

    fun spokenText(): String = panel.spokenText()

    fun stoppedListening() {
        if (sprite.expression == BuddySpriteView.Expression.LISTENING) {
            sprite.expression = BuddySpriteView.Expression.IDLE
        }
        // The gesture is over, so it is safe to give the window focus back.
        if (panelOpen && params.flags != FLAGS_OPEN) {
            params.flags = FLAGS_OPEN
            updateLayout()
        }
    }

    // ---------------------------------------------------------------- panel

    private fun togglePanel() = if (panelOpen) closePanel() else openPanel(takeFocus = true)

    /**
     * [takeFocus] false leaves the window flags alone. Changing focusability
     * mid-gesture tears down the touch stream, which would swallow the release
     * that ends a voice hold.
     */
    private fun openPanel(takeFocus: Boolean) {
        if (panelOpen) {
            if (takeFocus && params.flags != FLAGS_OPEN) {
                params.flags = FLAGS_OPEN
                applyPosition()
                panel.focusInput()
            }
            return
        }
        refreshMetrics()

        val roomLeft = spriteLeft
        val roomRight = usableWidth - (spriteLeft + spriteSize)
        panelOnLeft = roomLeft > roomRight

        removeView(panel)
        addView(panel, if (panelOnLeft) 0 else childCount)
        panel.visibility = View.VISIBLE

        panelOpen = true
        if (takeFocus) params.flags = FLAGS_OPEN
        applyPosition()
        if (takeFocus) panel.focusInput()
    }

    fun closePanel() {
        if (!panelOpen) return
        panel.releaseInput()
        panel.visibility = View.GONE
        panelOpen = false
        params.flags = FLAGS_CLOSED
        applyPosition()
        onPanelClosed?.invoke()
    }

    // ---------------------------------------------------------------- touch

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (ev.actionMasked == MotionEvent.ACTION_OUTSIDE) {
            closePanel()
            return true
        }
        return super.dispatchTouchEvent(ev)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_BACK && panelOpen) {
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
                    dragging = true
                    // Fold the panel away so only the sprite follows the finger.
                    closePanel()
                    startLeft = spriteLeft
                    startTop = spriteTop
                    downRawX = event.rawX
                    downRawY = event.rawY
                }
                if (dragging) {
                    spriteLeft = clamp(
                        startLeft + (event.rawX - downRawX).toInt(),
                        0,
                        usableWidth - spriteSize
                    )
                    spriteTop = clamp(
                        startTop + (event.rawY - downRawY).toInt(),
                        0,
                        usableHeight - spriteSize
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
        val spriteCenter = spriteLeft + spriteSize / 2
        val targetLeft = if (spriteCenter < usableWidth / 2) 0 else usableWidth - spriteSize
        spriteTop = clamp(spriteTop, 0, usableHeight - spriteSize)
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

    /** Re-derives the window position from where the sprite should be. */
    private fun applyPosition() {
        measureContainer()
        val spriteOffsetX = if (panelOpen && panelOnLeft) measuredContainerWidth - spriteSize else 0

        var x = clamp(spriteLeft - spriteOffsetX, 0, usableWidth - measuredContainerWidth)
        var y = clamp(
            spriteTop - (measuredContainerHeight - spriteSize) / 2,
            0,
            usableHeight - measuredContainerHeight
        )

        if (panelOpen) {
            // Keep the card clear of where the keyboard will come up.
            val safeBottom = (usableHeight * KEYBOARD_SAFE_FRACTION).toInt()
            if (y + measuredContainerHeight > safeBottom) {
                y = (safeBottom - measuredContainerHeight).coerceAtLeast(0)
            }
        }

        params.x = x
        params.y = y
        updateLayout()
    }

    private fun measureContainer() {
        measure(
            MeasureSpec.makeMeasureSpec(usableWidth, MeasureSpec.AT_MOST),
            MeasureSpec.makeMeasureSpec(usableHeight, MeasureSpec.AT_MOST)
        )
        measuredContainerWidth = measuredWidth.coerceAtLeast(spriteSize)
        measuredContainerHeight = measuredHeight.coerceAtLeast(spriteSize)
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
        usableWidth = metrics.bounds.width() - insets.left - insets.right
        usableHeight = metrics.bounds.height() - insets.top - insets.bottom
    }

    private fun clamp(value: Int, min: Int, max: Int): Int =
        if (max < min) min else value.coerceIn(min, max)

    private fun dp(value: Float): Int = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, value, resources.displayMetrics
    ).toInt()

    companion object {
        /** Idle: invisible to touch and keyboard, so the app underneath is untouched. */
        private const val FLAGS_CLOSED =
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL

        /** Panel open: focusable so the field can type, and told about outside taps. */
        private const val FLAGS_OPEN =
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH

        private const val KEYBOARD_SAFE_FRACTION = 0.55f
    }
}
