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
import xyz.heylana.app.ui.GlassDrawable
import xyz.heylana.app.ui.HeylanaTokens

/**
 * The capsule that sits beside the docked disc during a spoken exchange.
 *
 * It fills in with the words as they are heard, then turns into the aurora in
 * place when the user lets go — the same pill, a different surface, rather than
 * one thing disappearing and another arriving.
 */
@SuppressLint("ViewConstructor")
class VoiceCapsuleView(context: Context) : TextView(context) {

    private val aurora = Paint(Paint.ANTI_ALIAS_FLAG)
    private val body = RectF()
    private val glass = GlassDrawable(
        context, HeylanaTokens.RADIUS_FULL_DP, blurBehind = false, kind = GlassDrawable.Kind.PILL
    )

    private var drift = 0f
    private var animator: ValueAnimator? = null

    /** True once the user has let go and the answer is being fetched. */
    private var thinking = false

    init {
        gravity = Gravity.CENTER
        maxLines = 2
        maxWidth = HeylanaTokens.dpInt(context, MAX_WIDTH_DP)
        setTextColor(HeylanaTokens.textPrimary)
        typeface = HeylanaTokens.typeface(context, HeylanaTokens.WEIGHT_MEDIUM)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, HeylanaTokens.BODY_SP)
        val padH = HeylanaTokens.dpInt(context, HeylanaTokens.SPACE_4_DP)
        val padV = HeylanaTokens.dpInt(context, HeylanaTokens.SPACE_3_DP)
        setPadding(padH, padV, padH, padV)
        background = glass
    }

    /** What has been heard so far. Empty until the first words arrive. */
    fun showTranscript(words: String) {
        thinking = false
        stopDrift()
        text = words.ifBlank { LISTENING }
        background = glass
        invalidate()
    }

    /**
     * The answer has landed: the pill shrinks and fades out of the way, then it
     * is gone. [onGone] runs once it has, whether it had to animate or was not
     * showing in the first place.
     */
    fun melt(onGone: () -> Unit) {
        if (visibility != VISIBLE) {
            reset()
            onGone()
            return
        }
        animate().cancel()
        animate()
            .alpha(0f)
            .scaleX(MELT_SCALE)
            .scaleY(MELT_SCALE)
            .setDuration(HeylanaTokens.FADE_MS)
            .withEndAction {
                visibility = GONE
                reset()
                onGone()
            }
            .start()
    }

    /** Back to a blank pill ready for the next exchange. */
    private fun reset() {
        stopDrift()
        thinking = false
        text = ""
        alpha = 1f
        scaleX = 1f
        scaleY = 1f
        background = glass
    }

    /** The same pill, now carrying the aurora while the answer is fetched. */
    fun showThinking() {
        thinking = true
        text = THINKING
        background = null
        startDrift()
        invalidate()
    }

    private fun startDrift() {
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

    private fun stopDrift() {
        animator?.cancel()
        animator = null
    }

    override fun onDetachedFromWindow() {
        stopDrift()
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        if (thinking) {
            val w = width.toFloat()
            val h = height.toFloat()
            if (w > 0f && h > 0f) {
                body.set(0f, 0f, w, h)
                // The gradient is twice the pill wide and slides one width per
                // cycle, so the colours arrive from the left and leave right.
                val span = w * 2f
                val start = -span / 2f + drift * span
                aurora.shader = LinearGradient(
                    start, 0f, start + span, h,
                    HeylanaTokens.auroraStops, null, Shader.TileMode.MIRROR
                )
                canvas.drawRoundRect(body, h / 2f, h / 2f, aurora)
            }
        }
        super.onDraw(canvas)
    }

    private companion object {
        const val LISTENING = "listening…"
        const val THINKING = "thinking…"
        const val MAX_WIDTH_DP = 200f

        /** How far the pill shrinks as it melts away. */
        const val MELT_SCALE = 0.7f
    }
}
