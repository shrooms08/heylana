package xyz.heylana.app.overlay

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.PointF
import android.graphics.Rect
import android.graphics.RectF
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.view.animation.LinearInterpolator
import xyz.heylana.app.ui.HeylanaTokens
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * The pointer: a pulsing box around one element, with a curved arrow from the buddy.
 *
 * It lives in its own full-screen window that is neither touchable nor focusable, so
 * every touch falls straight through to the app underneath. The window is laid out
 * across the whole display, but rather than trusting that, the view converts the
 * accessibility bounds (which are display coordinates) through its own
 * [getLocationOnScreen], so any status bar or cutout offset corrects itself.
 */
@SuppressLint("ViewConstructor")
class HighlightOverlayView(context: Context) : FrameLayout(context) {

    /** Called when the pointer has finished fading out on its own. */
    var onFadedOut: (() -> Unit)? = null

    private val windowManager = context.getSystemService(WindowManager::class.java)

    private val params = WindowManager.LayoutParams(
        WindowManager.LayoutParams.MATCH_PARENT,
        WindowManager.LayoutParams.MATCH_PARENT,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
        PixelFormat.TRANSLUCENT
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        x = 0
        y = 0
    }

    private val boxPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = PURPLE
        style = Paint.Style.STROKE
    }
    private val arrowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = PURPLE
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val headPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = PURPLE
        style = Paint.Style.FILL
    }

    private val strokeWidth = dp(4f)
    private val inset = dp(4f)
    private val corner = dp(10f)

    /** Target in screen coordinates, as accessibility reported it. */
    private val targetOnScreen = Rect()
    private var buddyOnScreen: PointF? = null
    private var hasTarget = false

    private val box = RectF()
    private val arrow = Path()
    private val head = Path()

    /** 0f..1f pulse, and a separate 0f..1f fade for the exit. */
    private var pulse = 1f
    private var fade = 1f

    private var pulseAnimator: ValueAnimator? = null
    private var fadeAnimator: ValueAnimator? = null
    private var attached = false

    /** True for the moment between the user acting and the box clearing. */
    private var acknowledged = false

    private val autoHide = Runnable { fadeOut() }
    private val clearAfterAck = Runnable {
        if (!hasTarget) return@Runnable
        hide()
        onFadedOut?.invoke()
    }
    private val locationOnScreen = IntArray(2)

    init {
        // A ViewGroup skips its own drawing unless told otherwise, and this one
        // draws the pointer underneath whatever is flying across it.
        setWillNotDraw(false)
        clipChildren = false
    }

    /**
     * Puts a view on this layer so it can be animated across the whole display
     * without moving a window. The layer is not touchable, so only things that
     * are purely being animated belong here.
     */
    fun addFlyer(view: View, size: Int) {
        if (view.parent === this) return
        (view.parent as? android.view.ViewGroup)?.removeView(view)
        addView(view, LayoutParams(size, size))
        visibility = View.VISIBLE
    }

    fun removeFlyer(view: View) {
        if (view.parent === this) removeView(view)
        if (!hasTarget && childCount == 0) visibility = View.GONE
    }

    /** Where this layer's own coordinate space starts on the display. */
    fun stageOrigin(): PointF {
        getLocationOnScreen(locationOnScreen)
        return PointF(locationOnScreen[0].toFloat(), locationOnScreen[1].toFloat())
    }

    fun addToWindow() {
        if (attached) return
        visibility = View.GONE
        windowManager.addView(this, params)
        attached = true
    }

    fun removeFromWindow() {
        if (!attached) return
        cancelEverything()
        windowManager.removeView(this)
        attached = false
    }

    /**
     * Points at [bounds] (screen coordinates from the accessibility snapshot),
     * with the arrow starting at [buddyCenter] (also screen coordinates).
     *
     * [persistent] keeps the box up until it is replaced or cleared, instead of
     * fading after a few seconds — during a task the user needs it to stay put
     * while they find the thing it is pointing at.
     */
    fun point(bounds: Rect, buddyCenter: PointF, persistent: Boolean = false) {
        if (!attached || bounds.isEmpty) return
        cancelEverything()

        targetOnScreen.set(bounds)
        buddyOnScreen = buddyCenter
        hasTarget = true
        acknowledged = false
        fade = 1f
        visibility = View.VISIBLE

        pulseAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = PULSE_HALF_CYCLE_MS
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
            interpolator = LinearInterpolator()
            addUpdateListener {
                pulse = it.animatedValue as Float
                invalidate()
            }
            start()
        }

        if (!persistent) postDelayed(autoHide, VISIBLE_MS)
        invalidate()
    }

    /**
     * The user did the thing: the box goes green for a moment, says nothing, and
     * clears itself. [onFadedOut] runs after it, as it does for any other exit.
     */
    fun acknowledge() {
        if (!hasTarget) return
        cancelEverything()
        acknowledged = true
        invalidate()
        postDelayed(clearAfterAck, ACK_MS)
    }

    /** Clears the pointer straight away — a new question, or the panel closing. */
    fun hide() {
        if (!hasTarget) return
        cancelEverything()
        acknowledged = false
        hasTarget = false
        // Something may still be flying across the layer, so it only goes away
        // when it is carrying nothing at all.
        if (childCount == 0) visibility = View.GONE
        invalidate()
    }

    private fun fadeOut() {
        if (!hasTarget) return
        fadeAnimator?.cancel()
        fadeAnimator = ValueAnimator.ofFloat(1f, 0f).apply {
            duration = FADE_MS
            addUpdateListener {
                fade = it.animatedValue as Float
                invalidate()
            }
            start()
        }
        postDelayed({
            if (hasTarget) {
                hide()
                onFadedOut?.invoke()
            }
        }, FADE_MS)
    }

    private fun cancelEverything() {
        removeCallbacks(autoHide)
        removeCallbacks(clearAfterAck)
        pulseAnimator?.cancel()
        pulseAnimator = null
        fadeAnimator?.cancel()
        fadeAnimator = null
        pulse = 1f
        fade = 1f
    }

    override fun onDraw(canvas: Canvas) {
        if (!hasTarget) return

        // Screen coordinates → this view's coordinates, whatever the window offset is.
        getLocationOnScreen(locationOnScreen)
        val dx = -locationOnScreen[0].toFloat()
        val dy = -locationOnScreen[1].toFloat()

        box.set(
            targetOnScreen.left + dx,
            targetOnScreen.top + dy,
            targetOnScreen.right + dx,
            targetOnScreen.bottom + dy
        )
        box.inset(inset, inset)
        if (box.width() <= 0f || box.height() <= 0f) return

        val alpha = ((MIN_ALPHA + (1f - MIN_ALPHA) * pulse) * fade * 255f).toInt().coerceIn(0, 255)

        val colour = if (acknowledged) HeylanaTokens.ack else PURPLE
        boxPaint.color = colour
        arrowPaint.color = colour
        headPaint.color = colour

        boxPaint.strokeWidth = strokeWidth
        boxPaint.alpha = alpha
        canvas.drawRoundRect(box, corner, corner, boxPaint)

        buddyOnScreen?.let { buddy ->
            drawArrow(canvas, buddy.x + dx, buddy.y + dy, alpha)
        }
    }

    /** A short curved arrow from the buddy toward the nearest edge of the box. */
    private fun drawArrow(canvas: Canvas, fromX: Float, fromY: Float, alpha: Int) {
        // Aim at the point on the box outline closest to the buddy: meet it on the
        // left/right edge when the buddy is mostly beside it, top/bottom when above
        // or below it.
        val sideOn = abs(fromX - box.centerX()) > abs(fromY - box.centerY())
        val targetX = if (sideOn) {
            if (fromX > box.centerX()) box.right else box.left
        } else {
            fromX.coerceIn(box.left, box.right)
        }
        val targetY = if (sideOn) {
            fromY.coerceIn(box.top, box.bottom)
        } else {
            if (fromY > box.centerY()) box.bottom else box.top
        }

        val distance = hypot(targetX - fromX, targetY - fromY)
        if (distance < dp(24f)) return

        // Start clear of the sprite, stop clear of the box.
        val angle = atan2(targetY - fromY, targetX - fromX)
        val startX = fromX + cos(angle) * dp(46f)
        val startY = fromY + sin(angle) * dp(46f)
        val endX = targetX - cos(angle) * dp(12f)
        val endY = targetY - sin(angle) * dp(12f)

        if (hypot(endX - startX, endY - startY) < dp(8f)) return

        // Bow the line out perpendicular to its own direction.
        val midX = (startX + endX) / 2f
        val midY = (startY + endY) / 2f
        val bow = (hypot(endX - startX, endY - startY) * 0.22f).coerceAtMost(dp(56f))
        val controlX = midX + cos(angle + HALF_PI) * bow
        val controlY = midY + sin(angle + HALF_PI) * bow

        arrow.reset()
        arrow.moveTo(startX, startY)
        arrow.quadTo(controlX, controlY, endX, endY)
        arrowPaint.strokeWidth = dp(3f)
        arrowPaint.alpha = alpha
        canvas.drawPath(arrow, arrowPaint)

        // Arrowhead, aligned with the curve's final direction.
        val tipAngle = atan2(endY - controlY, endX - controlX)
        val headSize = dp(10f)
        head.reset()
        head.moveTo(endX, endY)
        head.lineTo(
            endX - cos(tipAngle - HEAD_SPREAD) * headSize,
            endY - sin(tipAngle - HEAD_SPREAD) * headSize
        )
        head.lineTo(
            endX - cos(tipAngle + HEAD_SPREAD) * headSize,
            endY - sin(tipAngle + HEAD_SPREAD) * headSize
        )
        head.close()
        headPaint.alpha = alpha
        canvas.drawPath(head, headPaint)
    }

    private fun dp(value: Float): Float = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, value, resources.displayMetrics
    )

    private companion object {
        val PURPLE = HeylanaTokens.accent
        const val MIN_ALPHA = 0.6f
        const val PULSE_HALF_CYCLE_MS = 500L
        const val VISIBLE_MS = 8_000L
        const val FADE_MS = 300L

        /** How long the box stays green before it clears. */
        const val ACK_MS = 300L
        const val HALF_PI = (Math.PI / 2).toFloat()
        const val HEAD_SPREAD = 0.5f
    }
}
