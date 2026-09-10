package xyz.heylana.app.overlay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.View

/**
 * The buddy itself: a rounded purple square with a pixel-art face drawn in code.
 * No image assets — everything here is canvas drawing on a 12x12 pixel grid.
 */
class BuddySpriteView(context: Context) : View(context) {

    /** Which face to draw. */
    enum class Expression { IDLE, THINKING }

    var expression: Expression = Expression.IDLE
        set(value) {
            if (field != value) {
                field = value
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

    private val bodyRect = RectF()

    /** Face pixels on a 12x12 grid: col, row, width, height (in cells). */
    private val eyeWhites = listOf(
        intArrayOf(2, 3, 3, 3),
        intArrayOf(7, 3, 3, 3)
    )
    private val pupilsIdle = listOf(
        intArrayOf(3, 4, 2, 2),
        intArrayOf(8, 4, 2, 2)
    )

    /** Eyes looking up while Heylana is working something out. */
    private val pupilsThinking = listOf(
        intArrayOf(3, 3, 2, 2),
        intArrayOf(8, 3, 2, 2)
    )
    private val mouthSmile = listOf(
        intArrayOf(2, 7, 1, 1),
        intArrayOf(3, 8, 1, 1),
        intArrayOf(4, 9, 4, 1),
        intArrayOf(8, 8, 1, 1),
        intArrayOf(9, 7, 1, 1)
    )

    /** A small flat "hmm" mouth. */
    private val mouthThinking = listOf(
        intArrayOf(4, 8, 4, 1)
    )

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
        val thinking = expression == Expression.THINKING
        drawCells(eyeWhites, facePaint)
        drawCells(if (thinking) pupilsThinking else pupilsIdle, pupilPaint)
        drawCells(if (thinking) mouthThinking else mouthSmile, facePaint)
    }
}
