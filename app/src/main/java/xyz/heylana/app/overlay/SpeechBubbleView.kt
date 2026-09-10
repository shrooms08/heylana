package xyz.heylana.app.overlay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.util.TypedValue
import android.widget.TextView

/** White rounded bubble with a small tail pointing at the sprite. */
private class BubbleBackground(
    private val tailOnRight: Boolean,
    private val tailSize: Float,
    private val corner: Float
) : Drawable() {

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#4C1D95")
        style = Paint.Style.STROKE
    }
    private val body = RectF()
    private val tail = Path()

    override fun draw(canvas: Canvas) {
        val b = bounds
        if (b.isEmpty) return
        stroke.strokeWidth = tailSize / 6f
        val inset = stroke.strokeWidth / 2f

        val left = if (tailOnRight) b.left + inset else b.left + tailSize + inset
        val right = if (tailOnRight) b.right - tailSize - inset else b.right - inset
        body.set(left, b.top + inset, right, b.bottom - inset)
        canvas.drawRoundRect(body, corner, corner, fill)
        canvas.drawRoundRect(body, corner, corner, stroke)

        val midY = b.exactCenterY()
        tail.reset()
        if (tailOnRight) {
            tail.moveTo(right, midY - tailSize)
            tail.lineTo(right + tailSize, midY)
            tail.lineTo(right, midY + tailSize)
        } else {
            tail.moveTo(left, midY - tailSize)
            tail.lineTo(left - tailSize, midY)
            tail.lineTo(left, midY + tailSize)
        }
        tail.close()
        canvas.drawPath(tail, fill)
        canvas.drawPath(tail, stroke)
    }

    override fun setAlpha(alpha: Int) = Unit
    override fun setColorFilter(colorFilter: ColorFilter?) = Unit

    @Deprecated("Deprecated in Drawable")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}

/**
 * The speech bubble shown next to the buddy.
 * [tailOnRight] true means the bubble sits to the LEFT of the sprite and points right at it.
 */
class SpeechBubbleView(context: Context, tailOnRight: Boolean) : TextView(context) {

    init {
        val tail = dp(6f)
        val padH = dp(10f).toInt()
        val padV = dp(8f).toInt()
        text = BUBBLE_TEXT
        setTextColor(Color.parseColor("#2E1065"))
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        includeFontPadding = false
        maxLines = 1
        background = BubbleBackground(tailOnRight, tail, dp(10f))
        setPadding(
            padH + if (tailOnRight) 0 else tail.toInt(),
            padV,
            padH + if (tailOnRight) tail.toInt() else 0,
            padV
        )
    }

    private fun dp(value: Float): Float =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, resources.displayMetrics)

    companion object {
        const val BUBBLE_TEXT = "hey, I'm Heylana"
    }
}
