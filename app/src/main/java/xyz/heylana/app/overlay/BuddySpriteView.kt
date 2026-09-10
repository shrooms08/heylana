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
import kotlin.math.abs
import kotlin.math.hypot
import android.graphics.RectF
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.LinearInterpolator
import xyz.heylana.app.R
import xyz.heylana.app.ui.GlassDrawable
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
                syncThinking()
                syncActive()
            }
        }

    /** True while text-to-speech is talking; sheds rings while it is. */
    var talking: Boolean = false
        set(value) {
            if (field != value) {
                field = value
                syncRings()
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

    /**
     * Where the highlight box is, in screen coordinates, while one is showing.
     * The mark leans toward it. Null when nothing is being pointed at.
     */
    var pointTarget: android.graphics.PointF? = null
        set(value) {
            field = value
            invalidate()
        }

    /** 0 to 1, how loud the microphone is hearing. Breathes the listening ring. */
    var micLevel: Float = 0f
        set(value) {
            field = value.coerceIn(0f, 1f)
            if (expression == Expression.LISTENING) invalidate()
        }

    /** Which way the eyes look. Kept for the pointing animation in part two. */
    var pointDirection: Int = 1
        set(value) {
            field = if (value < 0) -1 else 1
        }

    private val mark: Drawable? = context.getDrawable(R.drawable.ic_heylana_mark)

    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    /** The disc is the same sheet of glass as everything else, just round. */
    private val discGlass = GlassDrawable(
        context, HeylanaTokens.RADIUS_FULL_DP, blurBehind = false,
        kind = GlassDrawable.Kind.PILL, withSheen = true
    )

    /** Lifts the glass a touch once Heylana is awake. */
    private val discLift = Paint(Paint.ANTI_ALIAS_FLAG)

    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = HeylanaTokens.dp(context, 2f)
        color = HeylanaTokens.glow
    }

    private val listenPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = HeylanaTokens.accent
    }
    private val listenRing = HeylanaTokens.dp(context, 3f)

    /**
     * How far the mark has unwound into the thinking ring: 0 is the mark, 1 is
     * a gapped ring turning on its own.
     */
    private var unwind = 0f
    private var unwindAnimator: ValueAnimator? = null
    private var spin = 0f
    private var spinAnimator: ValueAnimator? = null

    private val ringPath = android.graphics.Path()
    private val ringOval = RectF()
    private val thinkPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        color = Color.WHITE
    }

    /** Where the shed rings are in their cycle while Heylana speaks. */
    private var ringPhase = 0f
    private var ringAnimator: ValueAnimator? = null

    private val discDiameter = HeylanaTokens.dp(context, HeylanaTokens.DISC_DP)
    private val glowBlur = HeylanaTokens.dp(context, HeylanaTokens.GLOW_BLUR_DP)
    private val spriteLocation = IntArray(2)

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
        ringAnimator?.cancel(); ringAnimator = null
        unwindAnimator?.cancel(); unwindAnimator = null
        spinAnimator?.cancel(); spinAnimator = null
        super.onDetachedFromWindow()
    }

    /**
     * Thinking unwinds the mark into a gapped ring that turns once every 1.2s,
     * and snaps it back the moment the answer lands.
     */
    private fun syncThinking() {
        val wanted = if (expression == Expression.THINKING) 1f else 0f
        unwindAnimator?.cancel()
        unwindAnimator = ValueAnimator.ofFloat(unwind, wanted).apply {
            duration = HeylanaTokens.UNWIND_MS
            interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener {
                unwind = it.animatedValue as Float
                invalidate()
            }
            start()
        }

        if (wanted == 1f) {
            if (spinAnimator != null) return
            spinAnimator = ValueAnimator.ofFloat(0f, 360f).apply {
                duration = HeylanaTokens.RING_SPIN_MS
                repeatCount = ValueAnimator.INFINITE
                interpolator = LinearInterpolator()
                addUpdateListener {
                    spin = it.animatedValue as Float
                    invalidate()
                }
                start()
            }
        } else {
            spinAnimator?.cancel()
            spinAnimator = null
            spin = 0f
        }
    }

    /**
     * Rings shed outward while the answer is being spoken. Real syllable peaks
     * are not available from the platform, so this runs at a steady cadence for
     * as long as the utterance lasts.
     */
    private fun syncRings() {
        if (!talking) {
            ringAnimator?.cancel()
            ringAnimator = null
            ringPhase = 0f
            invalidate()
            return
        }
        if (ringAnimator != null) return
        ringAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = HeylanaTokens.RIPPLE_MS
            repeatCount = ValueAnimator.INFINITE
            addUpdateListener {
                ringPhase = it.animatedValue as Float
                invalidate()
            }
            start()
        }
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

    /** The sheen runs once as the disc wakes, and not again until it sleeps. */
    private fun sweepSheen() {
        ValueAnimator.ofFloat(0f, 1f).apply {
            duration = HeylanaTokens.SHEEN_SWEEP_MS
            interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener {
                discGlass.sheenProgress = it.animatedValue as Float
                invalidate()
            }
            start()
        }
        postDelayed(
            { discGlass.sheenProgress = HeylanaTokens.SHEEN_REST },
            HeylanaTokens.SHEEN_SWEEP_MS
        )
    }

    private fun syncActive() {
        val target = if (isActive) 1f else 0f
        // Waking up catches the light.
        if (target == 1f && activeAmount < 0.5f) sweepSheen()
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

        if (expression == Expression.LISTENING) {
            // A ring that breathes with the voice it is hearing.
            listenPaint.strokeWidth = listenRing
            val swell = discRadius + listenRing * (0.6f + 1.4f * micLevel)
            if (swell < minOf(w, h) / 2f) canvas.drawCircle(cx, cy, swell, listenPaint)
        }

        if (talking) {
            // Two rings, half a cycle apart, so one is always on its way out.
            for (offset in floatArrayOf(0f, 0.5f)) {
                val t = (ringPhase + offset) % 1f
                val radius = discRadius + (glowBlur * 0.9f) * t
                if (radius > minOf(w, h) / 2f) continue
                ringPaint.alpha = ((1f - t) * 150f).toInt().coerceIn(0, 255)
                canvas.drawCircle(cx, cy, radius, ringPaint)
            }
        }

        val save = canvas.save()
        canvas.scale(scale, scale, cx, cy)

        discGlass.setBounds(
            (cx - discRadius).toInt(), (cy - discRadius).toInt(),
            (cx + discRadius).toInt(), (cy + discRadius).toInt()
        )
        discGlass.draw(canvas)
        if (activeAmount > 0.01f) {
            discLift.color = HeylanaTokens.withAlpha(Color.WHITE, 0.06f * activeAmount)
            canvas.drawCircle(cx, cy, discRadius, discLift)
        }

        mark?.let { drawable ->
            val markSize = discRadius * 2f * HeylanaTokens.MARK_FRACTION
            val half = markSize / 2f
            drawable.setBounds(
                (cx - half).toInt(), (cy - half).toInt(),
                (cx + half).toInt(), (cy + half).toInt()
            )
            val amount = HeylanaTokens.MARK_DULLED +
                (HeylanaTokens.MARK_ACTIVE - HeylanaTokens.MARK_DULLED) * activeAmount
            drawable.alpha = ((1f - unwind) * amount * 255f).toInt().coerceIn(0, 255)

            val lean = canvas.save()
            pointTarget?.let { target ->
                // Lean and stretch toward whatever is being pointed at.
                getLocationOnScreen(spriteLocation)
                val dx = target.x - (spriteLocation[0] + w / 2f)
                val dy = target.y - (spriteLocation[1] + h / 2f)
                val reach = hypot(dx, dy).coerceAtLeast(1f)
                val amountX = (dx / reach) * HeylanaTokens.POINT_LEAN * discRadius
                val amountY = (dy / reach) * HeylanaTokens.POINT_LEAN * discRadius
                canvas.translate(amountX, amountY)
                canvas.scale(
                    1f + HeylanaTokens.POINT_LEAN * abs(dx) / reach,
                    1f + HeylanaTokens.POINT_LEAN * abs(dy) / reach,
                    cx, cy
                )
            }
            drawable.draw(canvas)
            canvas.restoreToCount(lean)
        }

        if (unwind > 0.01f) {
            // The arms have come apart into two arcs with a gap either side.
            thinkPaint.strokeWidth = discRadius * 0.16f
            thinkPaint.alpha = (unwind * 255f).toInt().coerceIn(0, 255)
            val ringRadius = discRadius * (0.62f - 0.04f * (1f - unwind))
            ringOval.set(cx - ringRadius, cy - ringRadius, cx + ringRadius, cy + ringRadius)
            val sweep = 150f * unwind
            val turn = canvas.save()
            canvas.rotate(spin, cx, cy)
            ringPath.reset()
            ringPath.addArc(ringOval, 0f, sweep)
            ringPath.addArc(ringOval, 180f, sweep)
            canvas.drawPath(ringPath, thinkPaint)
            canvas.restoreToCount(turn)
        }

        canvas.restoreToCount(save)
    }
}
