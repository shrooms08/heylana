package xyz.heylana.app.overlay

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.PixelFormat
import android.util.TypedValue
import android.view.Gravity
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
 * The window-level container for the buddy: [SpeechBubbleView] + [BuddySpriteView].
 *
 * Owns its own WindowManager layout params, the drag gesture, the snap-to-edge
 * behaviour and the tap-to-toggle speech bubble.
 */
@SuppressLint("ViewConstructor")
class BuddyOverlayView(context: Context) : LinearLayout(context) {

    private val windowManager = context.getSystemService(WindowManager::class.java)

    private val spriteSize = dp(96f)
    private val sprite = BuddySpriteView(context)

    /** Shown when the buddy is snapped to the right edge (bubble sits to its left). */
    private val leftBubble = SpeechBubbleView(context, tailOnRight = true)

    /** Shown when the buddy is snapped to the left edge (bubble sits to its right). */
    private val rightBubble = SpeechBubbleView(context, tailOnRight = false)

    private val params = WindowManager.LayoutParams(
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
        PixelFormat.TRANSLUCENT
    ).apply { gravity = Gravity.TOP or Gravity.START }

    private var usableWidth = 0
    private var usableHeight = 0

    private var snappedLeft = false
    private var bubbleVisible = false

    private var dragging = false
    private var downRawX = 0f
    private var downRawY = 0f
    private var startX = 0
    private var startY = 0
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop

    private var snapAnimator: ValueAnimator? = null
    private var attached = false

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        clipChildren = false

        leftBubble.visibility = View.GONE
        rightBubble.visibility = View.GONE

        addView(leftBubble, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
        addView(sprite, LayoutParams(spriteSize, spriteSize))
        addView(rightBubble, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
    }

    fun addToWindow() {
        if (attached) return
        refreshMetrics()
        snappedLeft = false
        bubbleVisible = false
        params.x = usableWidth - spriteSize
        params.y = usableHeight / 3
        windowManager.addView(this, params)
        attached = true
    }

    fun removeFromWindow() {
        if (!attached) return
        snapAnimator?.cancel()
        snapAnimator = null
        windowManager.removeView(this)
        attached = false
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                snapAnimator?.cancel()
                refreshMetrics()
                dragging = false
                downRawX = event.rawX
                downRawY = event.rawY
                startX = params.x
                startY = params.y
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                val dx = event.rawX - downRawX
                val dy = event.rawY - downRawY
                if (!dragging && hypot(dx, dy) > touchSlop) {
                    dragging = true
                    // Collapse the bubble so the sprite is the only thing being dragged,
                    // then re-anchor the gesture to the sprite's current position.
                    setBubbleVisible(false)
                    startX = params.x
                    startY = params.y
                    downRawX = event.rawX
                    downRawY = event.rawY
                }
                if (dragging) {
                    params.x = clamp(startX + (event.rawX - downRawX).toInt(), 0, usableWidth - spriteSize)
                    params.y = clamp(startY + (event.rawY - downRawY).toInt(), 0, usableHeight - spriteSize)
                    updateLayout()
                }
                return true
            }

            MotionEvent.ACTION_UP -> {
                if (dragging) snapToNearestEdge() else toggleBubble()
                dragging = false
                return true
            }

            MotionEvent.ACTION_CANCEL -> {
                if (dragging) snapToNearestEdge()
                dragging = false
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun toggleBubble() = setBubbleVisible(!bubbleVisible)

    private fun setBubbleVisible(visible: Boolean) {
        bubbleVisible = visible
        leftBubble.visibility = if (visible && !snappedLeft) View.VISIBLE else View.GONE
        rightBubble.visibility = if (visible && snappedLeft) View.VISIBLE else View.GONE
        params.x = if (snappedLeft) 0 else usableWidth - currentWidth()
        params.y = clamp(params.y, 0, usableHeight - spriteSize)
        updateLayout()
    }

    /** Width the container will occupy with the bubbles in their current visibility. */
    private fun currentWidth(): Int {
        measure(
            MeasureSpec.makeMeasureSpec(usableWidth, MeasureSpec.AT_MOST),
            MeasureSpec.makeMeasureSpec(usableHeight, MeasureSpec.AT_MOST)
        )
        return measuredWidth.coerceAtLeast(spriteSize)
    }

    private fun snapToNearestEdge() {
        val spriteCenter = params.x + spriteSize / 2
        snappedLeft = spriteCenter < usableWidth / 2
        val targetX = if (snappedLeft) 0 else usableWidth - spriteSize
        params.y = clamp(params.y, 0, usableHeight - spriteSize)
        animateXTo(targetX)
    }

    private fun animateXTo(targetX: Int) {
        if (abs(params.x - targetX) < 2) {
            params.x = targetX
            updateLayout()
            return
        }
        snapAnimator?.cancel()
        snapAnimator = ValueAnimator.ofInt(params.x, targetX).apply {
            duration = 180L
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                params.x = it.animatedValue as Int
                updateLayout()
            }
            start()
        }
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
}
