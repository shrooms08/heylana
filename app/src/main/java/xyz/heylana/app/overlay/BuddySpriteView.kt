package xyz.heylana.app.overlay

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import xyz.heylana.app.R
import xyz.heylana.app.ui.HeylanaTokens

/**
 * The buddy: the brand mark riding inside an 88dp disc of dark glass.
 *
 * Resting, the mark is knocked back and the disc is dark, breathing slowly.
 * The moment Heylana is doing anything — composing, thinking, listening,
 * speaking, pointing — the mark comes up to full white, the disc lightens and
 * a purple bloom opens behind it.
 *
 * The per-state animations (the thinking ring, the listening pulse) arrive in
 * the next part; for now every active state renders as the same active disc.
 */
class BuddySpriteView(context: Context) : View(context) {

    /** What Heylana is doing. Everything but [IDLE] renders as active. */
    enum class Expression { IDLE, THINKING, POINTING, LISTENING }

    var expression: Expression = Expression.IDLE
        set(value) {
            if (field != value) {
                field = value
                syncActive()
            }
        }

    /** True while text-to-speech is talking. */
    var talking: Boolean = false
        set(value) {
            if (field != value) {
                field = value
                syncActive()
            }
        }

    /** True while the message box is open and waiting for the user to type. */
    var composing: Boolean = false
        set(value) {
            if (field != value) {
                field = value
                syncActive()
            }
        }

    /** Which way the eyes look. Kept for the pointing animation in part two. */
    var pointDirection: Int = 1
        set(value) {
            field = if (value < 0) -1 else 1
        }

    private val mark: Drawable? = context.getDrawable(R.drawable.ic_heylana_mark)

    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val discPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val discBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = HeylanaTokens.dp(context, 1f)
        color = HeylanaTokens.discBorder
    }

    private val discDiameter = HeylanaTokens.dp(context, HeylanaTokens.DISC_DP)
    private val glowBlur = HeylanaTokens.dp(context, HeylanaTokens.GLOW_BLUR_DP)

    /** The resting breath, and how far through it we are. */
    private var breathe = 0f
    private var breatheAnimator: ValueAnimator? = null

    /** 0 while resting, 1 while active; animated so states cross-fade. */
    private var activeAmount = 0f
    private var activeAnimator: ValueAnimator? = null

    private val isActive: Boolean
        get() = composing || talking || expression != Expression.IDLE

    init {
        mark?.setTint(Color.WHITE)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        startBreathing()
        refreshState()
    }

    /**
     * Snaps straight to whatever state the buddy is supposed to be in.
     *
     * Moving the disc between layouts detaches it, which cancels the cross-fade
     * part-way, so the owner re-asserts the state after any re-parenting rather
     * than depending on when the animator happened to be cancelled.
     */
    fun refreshState() {
        activeAnimator?.cancel()
        activeAnimator = null
        activeAmount = if (isActive) 1f else 0f
        invalidate()
    }

    override fun onDetachedFromWindow() {
        breatheAnimator?.cancel(); breatheAnimator = null
        activeAnimator?.cancel(); activeAnimator = null
        super.onDetachedFromWindow()
    }

    private fun startBreathing() {
        if (breatheAnimator != null) return
        breatheAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = HeylanaTokens.BREATHE_MS
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
            interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener {
                breathe = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    private fun syncActive() {
        val target = if (isActive) 1f else 0f
        if (activeAmount == target && activeAnimator == null) return
        activeAnimator?.cancel()
        activeAnimator = ValueAnimator.ofFloat(activeAmount, target).apply {
            duration = 220L
            interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener {
                activeAmount = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        val cx = w / 2f
        val cy = h / 2f
        // The disc is a fixed size; the rest of the view is room for the bloom.
        val discRadius = minOf(discDiameter / 2f, minOf(w, h) / 2f)
        val scale = 1f + (HeylanaTokens.BREATHE_SCALE - 1f) * breathe

        if (activeAmount > 0.01f) {
            val outer = minOf(discRadius + glowBlur, minOf(w, h) / 2f)
            glowPaint.shader = RadialGradient(
                cx, cy, outer,
                intArrayOf(
                    HeylanaTokens.withAlpha(HeylanaTokens.glow, 0.55f * activeAmount),
                    HeylanaTokens.withAlpha(HeylanaTokens.glow, 0.28f * activeAmount),
                    Color.TRANSPARENT
                ),
                floatArrayOf(0f, 0.55f, 1f),
                Shader.TileMode.CLAMP
            )
            canvas.drawCircle(cx, cy, outer, glowPaint)
        }

        val save = canvas.save()
        canvas.scale(scale, scale, cx, cy)

        // Glass: a touch brighter once Heylana is awake.
        val fill = HeylanaTokens.withAlpha(
            Color.WHITE,
            0.06f + 0.06f * activeAmount
        )
        discPaint.color = fill
        canvas.drawCircle(cx, cy, discRadius, discPaint)
        canvas.drawCircle(cx, cy, discRadius - discBorderPaint.strokeWidth / 2f, discBorderPaint)

        mark?.let { drawable ->
            val markSize = discRadius * 2f * HeylanaTokens.MARK_FRACTION
            val half = markSize / 2f
            drawable.setBounds(
                (cx - half).toInt(), (cy - half).toInt(),
                (cx + half).toInt(), (cy + half).toInt()
            )
            val amount = HeylanaTokens.MARK_DULLED +
                (HeylanaTokens.MARK_ACTIVE - HeylanaTokens.MARK_DULLED) * activeAmount
            drawable.alpha = (amount * 255f).toInt().coerceIn(0, 255)
            drawable.draw(canvas)
        }

        canvas.restoreToCount(save)
    }
}
