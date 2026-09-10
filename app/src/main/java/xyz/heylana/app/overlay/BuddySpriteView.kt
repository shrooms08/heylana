package xyz.heylana.app.overlay

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.View

/**
 * The buddy itself: a rounded purple square with a pixel-art face drawn in code.
 * No image assets — everything here is canvas drawing on a 12x12 pixel grid.
 *
 * The eyes carry the [expression]; the mouth is driven separately by [talking],
 * so Heylana can look at what it is pointing at while it speaks.
 */
class BuddySpriteView(context: Context) : View(context) {

    /** What the eyes are doing. */
    enum class Expression { IDLE, THINKING, POINTING, LISTENING }

    var expression: Expression = Expression.IDLE
        set(value) {
            if (field != value) {
                field = value
                syncAnimator()
                invalidate()
            }
        }

    /** -1 points the eyes left, +1 right. Only used while [Expression.POINTING]. */
    var pointDirection: Int = 1
        set(value) {
            val clamped = if (value < 0) -1 else 1
            if (field != clamped) {
                field = clamped
                invalidate()
            }
        }

    /** True while text-to-speech is talking; animates the mouth open and closed. */
    var talking: Boolean = false
        set(value) {
            if (field != value) {
                field = value
                syncAnimator()
                invalidate()
            }
        }

    private val bodyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#7C3AED")
    }
    private val edgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#4C1D95")
        style = Paint.Style.STROKE
    }
    private val facePaint = Paint().apply {
        color = Color.WHITE
        isAntiAlias = false
    }
    private val pupilPaint = Paint().apply {
        color = Color.parseColor("#2E1065")
        isAntiAlias = false
    }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#F87171")
    }

    private val bodyRect = RectF()

    /** Drives the talking mouth and the listening dot. 0f..1f. */
    private var phase = 0f
    private var animator: ValueAnimator? = null

    /** Face pixels on a 12x12 grid: col, row, width, height (in cells). */
    private val eyeWhites = listOf(
        intArrayOf(2, 3, 3, 3),
        intArrayOf(7, 3, 3, 3)
    )
    private val eyeWhitesWide = listOf(
        intArrayOf(2, 2, 3, 4),
        intArrayOf(7, 2, 3, 4)
    )
    private val mouthSmile = listOf(
        intArrayOf(2, 7, 1, 1),
        intArrayOf(3, 8, 1, 1),
        intArrayOf(4, 9, 4, 1),
        intArrayOf(8, 8, 1, 1),
        intArrayOf(9, 7, 1, 1)
    )

    /** A small flat "hmm" mouth. */
    private val mouthThinking = listOf(intArrayOf(4, 8, 4, 1))

    /** Mouth open, for the talking animation. */
    private val mouthOpen = listOf(intArrayOf(4, 7, 4, 3))

    override fun onDetachedFromWindow() {
        animator?.cancel()
        animator = null
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        val stroke = w / 32f
        edgePaint.strokeWidth = stroke
        val radius = w / 5f

        bodyRect.set(stroke / 2f, stroke / 2f, w - stroke / 2f, h - stroke / 2f)
        canvas.drawRoundRect(bodyRect, radius, radius, bodyPaint)
        canvas.drawRoundRect(bodyRect, radius, radius, edgePaint)

        val cell = w / 12f
        fun drawCells(cells: List<IntArray>, paint: Paint) {
            for (c in cells) {
                canvas.drawRect(
                    c[0] * cell,
                    c[1] * cell,
                    (c[0] + c[2]) * cell,
                    (c[1] + c[3]) * cell,
                    paint
                )
            }
        }

        val listening = expression == Expression.LISTENING
        drawCells(if (listening) eyeWhitesWide else eyeWhites, facePaint)
        drawCells(pupils(), pupilPaint)
        drawCells(mouth(), facePaint)

        if (listening) {
            // A small dot that breathes while the microphone is open.
            val pulse = 0.6f + 0.4f * kotlin.math.sin(phase * 2f * Math.PI.toFloat()).let { (it + 1f) / 2f }
            dotPaint.alpha = (255 * pulse).toInt().coerceIn(0, 255)
            canvas.drawCircle(w - cell * 1.6f, cell * 1.6f, cell * 0.7f * pulse, dotPaint)
        }
    }

    private fun pupils(): List<IntArray> = when (expression) {
        // Eyes up while Heylana works something out.
        Expression.THINKING -> listOf(intArrayOf(3, 3, 2, 2), intArrayOf(8, 3, 2, 2))
        // Eyes cut toward whatever is highlighted.
        Expression.POINTING -> if (pointDirection < 0) {
            listOf(intArrayOf(2, 4, 2, 2), intArrayOf(7, 4, 2, 2))
        } else {
            listOf(intArrayOf(4, 4, 2, 2), intArrayOf(9, 4, 2, 2))
        }
        // Wide eyes: smaller pupils, centred in the taller whites.
        Expression.LISTENING -> listOf(intArrayOf(3, 4, 2, 2), intArrayOf(8, 4, 2, 2))
        Expression.IDLE -> listOf(intArrayOf(3, 4, 2, 2), intArrayOf(8, 4, 2, 2))
    }

    private fun mouth(): List<IntArray> = when {
        talking -> if (phase < 0.5f) mouthOpen else mouthThinking
        expression == Expression.THINKING -> mouthThinking
        expression == Expression.LISTENING -> mouthThinking
        else -> mouthSmile
    }

    /** Runs the frame ticker only while something on the face is actually moving. */
    private fun syncAnimator() {
        val needed = talking || expression == Expression.LISTENING
        if (!needed) {
            animator?.cancel()
            animator = null
            phase = 0f
            return
        }
        val wanted = if (talking) TALK_CYCLE_MS else LISTEN_CYCLE_MS
        if (animator?.duration == wanted && animator?.isRunning == true) return

        animator?.cancel()
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = wanted
            repeatCount = ValueAnimator.INFINITE
            addUpdateListener {
                phase = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    private companion object {
        const val TALK_CYCLE_MS = 280L
        const val LISTEN_CYCLE_MS = 1000L
    }
}
