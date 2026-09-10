package xyz.heylana.app.overlay

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.util.TypedValue
import android.view.Gravity
import android.view.animation.LinearInterpolator
import android.widget.TextView
import xyz.heylana.app.ui.HeylanaTokens

/**
 * The thinking capsule: a glass pill with an aurora drifting through it.
 *
 * This is what a spoken question gets instead of a wall of text — there is
 * nothing to read yet, so it says so in colour rather than in words.
 */
@SuppressLint("ViewConstructor")
class AuroraCapsuleView(context: Context) : TextView(context) {

    private val aurora = Paint(Paint.ANTI_ALIAS_FLAG)
    private val body = RectF()
    private var drift = 0f
    private var animator: ValueAnimator? = null

    init {
        text = THINKING
        gravity = Gravity.CENTER
        setTextColor(HeylanaTokens.textPrimary)
        typeface = HeylanaTokens.typeface(context, HeylanaTokens.WEIGHT_MEDIUM)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, HeylanaTokens.BODY_SP)
        val padH = HeylanaTokens.dpInt(context, HeylanaTokens.SPACE_5_DP)
        val padV = HeylanaTokens.dpInt(context, HeylanaTokens.SPACE_3_DP)
        setPadding(padH, padV, padH, padV)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (animator != null) return
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = HeylanaTokens.AURORA_DRIFT_MS
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener {
                drift = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    override fun onDetachedFromWindow() {
        animator?.cancel()
        animator = null
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w > 0f && h > 0f) {
            body.set(0f, 0f, w, h)
            val r = h / 2f
            // The gradient is twice the pill's width and slides one width per
            // cycle, so the colours arrive from the left and leave to the right.
            val span = w * 2f
            val start = -span / 2f + drift * span
            aurora.shader = LinearGradient(
                start, 0f, start + span, h,
                HeylanaTokens.auroraStops, null, Shader.TileMode.MIRROR
            )
            canvas.drawRoundRect(body, r, r, aurora)
        }
        super.onDraw(canvas)
    }

    private companion object {
        const val THINKING = "thinking…"
    }
}
