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
import xyz.heylana.app.orbs.OrbEngine
import xyz.heylana.app.orbs.OrbPainter
import xyz.heylana.app.orbs.OrbState
import xyz.heylana.app.ui.GlassDrawable
import xyz.heylana.app.ui.GlassSpec
import xyz.heylana.app.ui.HeylanaTokens
import xyz.heylana.app.ui.LiquidGlass

/**
 * The buddy: the brand mark riding inside a disc of dark glass, 64dp docked and
 * 80dp while the box is open.
 *
 * Resting, the mark is knocked back and the disc is dark, breathing slowly.
 * The moment Heylana is doing anything — composing, thinking, listening,
 * speaking, pointing — the mark comes up to full white, the disc lightens and
 * a purple bloom opens behind it.
 *
 * Listening, thinking, working and speaking dissolve the mark into a living orb
 * (a port of thinking-orbs, see [xyz.heylana.app.orbs.OrbEngine]); back to idle, the
 * orb reassembles into the mark. Pointing keeps the mark and leans it.
 */
class BuddySpriteView(context: Context) : View(context) {

    /** What Heylana is doing. Everything but [IDLE] renders as active. */
    enum class Expression { IDLE, THINKING, POINTING, LISTENING, WORKING }

    var expression: Expression = Expression.IDLE
        set(value) {
            if (field != value) {
                field = value
                syncOrb()
                syncActive()
            }
        }

    /** True while Heylana is talking; the orb speaks while it is. */
    var talking: Boolean = false
        set(value) {
            if (field != value) {
                field = value
                syncOrb()
                syncActive()
            }
        }

    /** 0 to 1, how loud what is being heard from the speaker is. Swells the speaking orb. */
    var playbackLevel: Float = 0f
        set(value) {
            field = value.coerceIn(0f, 1f)
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
        kind = GlassDrawable.Kind.PILL, withSheen = true,
        bandColor = HeylanaTokens.discBand,
        baseColor = HeylanaTokens.discBase,
        // The whole face is a lens already, with its own fringe.
        liquidEdges = false
    )

    /** The clear glass disc (design-2d). */
    private val clearDisc = GlassDrawable(
        context, HeylanaTokens.RADIUS_FULL_DP, blurBehind = false,
        kind = GlassDrawable.Kind.PANEL, withShadow = true, withSheen = false,
        liquidEdges = false, shadowInView = false
    )

    /** Lifts the glass a touch once Heylana is awake. */
    private val discLift = Paint(Paint.ANTI_ALIAS_FLAG)

    private val listenPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = HeylanaTokens.accent
    }
    private val listenRing = HeylanaTokens.dp(context, 3f)

    private val orbPainter = OrbPainter()

    // ------------------------------------------------------ liquid glass face

    /** The lens over the face, on API 33+ hardware canvases only. */
    private val lens: Any? by lazy {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) LiquidGlass.Lens() else null
    }
    private var aurora: android.graphics.Shader? = null
    private var auroraSweep: android.graphics.SweepGradient? = null
    private var auroraRadius = 0f
    private var auroraCentre = android.graphics.PointF(Float.NaN, Float.NaN)
    private val auroraMatrix = android.graphics.Matrix()
    private val faceRect = RectF()
    private val fringePaint = Paint(Paint.ANTI_ALIAS_FLAG)

    // ------------------------------------------------ motion colour split

    private var splitX = 0f
    private var splitY = 0f
    private var splitTargetX = 0f
    private var splitTargetY = 0f
    private var lastMotionNanos = 0L
    private val splitMax = HeylanaTokens.dp(context, HeylanaTokens.MOTION_SPLIT_MAX_DP)
    private val splitFullSpeed = HeylanaTokens.dp(context, HeylanaTokens.MOTION_SPLIT_FULL_DP_PER_S)
    private var markBitmap: android.graphics.Bitmap? = null
    private val channelPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

    /**
     * How fast the disc is moving, in px per second, while it flies or is dragged.
     * The mark splits into red, green and blue along the motion, in proportion to
     * the speed, and settles back together when the updates stop.
     */
    fun setMotion(vx: Float, vy: Float) {
        // The split is one of the design-2c tints: off with them.
        if (!GlassSpec.TINTED_EXTRAS) return
        val speed = kotlin.math.hypot(vx, vy)
        if (speed < 1f) {
            splitTargetX = 0f
            splitTargetY = 0f
        } else {
            val amount = (speed / splitFullSpeed).coerceAtMost(1f) * splitMax
            splitTargetX = vx / speed * amount
            splitTargetY = vy / speed * amount
        }
        lastMotionNanos = System.nanoTime()
        invalidate()
    }

    /** The orb being shown, and the one it is cross-fading from. */
    private var orbState: OrbState? = null
    private var previousOrb: OrbState? = null

    /** 0 is the mark, 1 is the orb. Animated over [HeylanaTokens.ORB_DISSOLVE_MS]. */
    private var dissolve = 0f
    private var dissolveAnimator: ValueAnimator? = null

    /** 0 shows [previousOrb], 1 shows [orbState]. */
    private var orbMix = 1f
    private var mixAnimator: ValueAnimator? = null

    /** Each state's engine clock, advanced frame by frame so a change of speed never jumps. */
    private val orbClock = HashMap<OrbState, Double>()
    private var lastFrameNanos = 0L
    private var shownLevel = 0f

    /**
     * The disc's diameter in dp: [HeylanaTokens.DISC_DP] docked, swelling to
     * [HeylanaTokens.DISC_OPEN_DP] on the way to the top centre. The bloom scales with it.
     */
    var discDp: Float = HeylanaTokens.DISC_DP
        set(value) {
            if (field != value) {
                field = value
                invalidate()
            }
        }

    private val discDiameter: Float get() = HeylanaTokens.dp(context, discDp)
    private val glowBlur: Float get() = discDiameter * HeylanaTokens.GLOW_BLUR_RATIO
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
        // Re-parenting cancels the dissolve part-way: snap it to where it belongs.
        dissolveAnimator?.cancel(); dissolveAnimator = null
        mixAnimator?.cancel(); mixAnimator = null
        orbState = wantedOrb()
        previousOrb = null
        orbMix = 1f
        dissolve = if (orbState != null) 1f else 0f
        invalidate()
    }

    override fun onDetachedFromWindow() {
        breatheAnimator?.cancel(); breatheAnimator = null
        activeAnimator?.cancel(); activeAnimator = null
        dissolveAnimator?.cancel(); dissolveAnimator = null
        mixAnimator?.cancel(); mixAnimator = null
        super.onDetachedFromWindow()
    }

    /** Which orb, if any, the disc should be showing right now. */
    fun wantedOrb(): OrbState? = orbFor(expression, talking)

    /**
     * Dissolves the mark into the wanted orb, cross-fades between orbs, or
     * reassembles the mark — each over [HeylanaTokens.ORB_DISSOLVE_MS].
     */
    private fun syncOrb() {
        val wanted = wantedOrb()
        if (wanted != null && wanted != orbState) {
            if (orbState != null && dissolve > 0.01f) {
                previousOrb = orbState
                orbMix = 0f
                mixAnimator?.cancel()
                mixAnimator = animate(0f, 1f) { orbMix = it }
            } else {
                previousOrb = null
                orbMix = 1f
            }
            orbState = wanted
        }
        val target = if (wanted != null) 1f else 0f
        if (dissolve == target && dissolveAnimator == null) return
        dissolveAnimator?.cancel()
        dissolveAnimator = animate(dissolve, target) { dissolve = it }.also { animator ->
            animator.addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    if (dissolveAnimator === animator) dissolveAnimator = null
                    if (dissolve == 0f) {
                        orbState = null
                        previousOrb = null
                    }
                }
            })
        }
        invalidate()
    }

    private fun animate(from: Float, to: Float, apply: (Float) -> Unit): ValueAnimator =
        ValueAnimator.ofFloat(from, to).apply {
            duration = HeylanaTokens.ORB_DISSOLVE_MS
            interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener {
                apply(it.animatedValue as Float)
                invalidate()
            }
            start()
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

        if (GlassSpec.TINTED_EXTRAS && activeAmount > 0.01f) {
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

        val save = canvas.save()
        canvas.scale(scale, scale, cx, cy)

        discGlass.setBounds(
            (cx - discRadius).toInt(), (cy - discRadius).toInt(),
            (cx + discRadius).toInt(), (cy + discRadius).toInt()
        )
        if (GlassSpec.TINTED_EXTRAS) {
            discGlass.draw(canvas)
        } else {
            // Clear glass at the disc's own numbers, E and strength growing with the swell;
            // its shadow falls into the bloom room around the disc.
            clearDisc.surface = GlassSpec.disc(discDp)
            clearDisc.bounds = discGlass.bounds
            clearDisc.draw(canvas)
        }
        if (GlassSpec.TINTED_EXTRAS && activeAmount > 0.01f) {
            discLift.color = HeylanaTokens.withAlpha(Color.WHITE, 0.06f * activeAmount)
            canvas.drawCircle(cx, cy, discRadius, discLift)
        }
        if (GlassSpec.TINTED_EXTRAS && LiquidGlass.available(canvas)) drawFace(canvas, cx, cy, discRadius)
        stepSplit()

        mark?.let { drawable ->
            val markSize = discRadius * 2f * HeylanaTokens.MARK_FRACTION
            val half = markSize / 2f
            drawable.setBounds(
                (cx - half).toInt(), (cy - half).toInt(),
                (cx + half).toInt(), (cy + half).toInt()
            )
            val dulled = if (GlassSpec.TINTED_EXTRAS) HeylanaTokens.MARK_DULLED else GlassSpec.MARK_DOCKED
            val full = if (GlassSpec.TINTED_EXTRAS) HeylanaTokens.MARK_ACTIVE else GlassSpec.MARK_ACTIVE
            val amount = dulled + (full - dulled) * activeAmount
            drawable.alpha = ((1f - dissolve) * amount * 255f).toInt().coerceIn(0, 255)

            val lean = canvas.save()
            // Dissolving, the mark swells a little as it fades into the dots.
            val swell = 1f + DISSOLVE_SWELL * dissolve
            canvas.scale(swell, swell, cx, cy)
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
            if (kotlin.math.hypot(splitX, splitY) > SPLIT_VISIBLE_PX) {
                drawSplitMark(canvas, drawable)
            } else {
                drawable.draw(canvas)
            }
            canvas.restoreToCount(lean)
        }

        if (dissolve > 0.01f) drawOrb(canvas, cx, cy, discRadius)

        // The chromatic rim, pulled a little further apart while moving.
        faceRect.set(cx - discRadius, cy - discRadius, cx + discRadius, cy + discRadius)
        if (GlassSpec.TINTED_EXTRAS) {
            LiquidGlass.drawFringe(canvas, context, faceRect, discRadius, fringePaint, splitX * 0.5f, splitY * 0.5f)
        }

        canvas.restoreToCount(save)
        if (dissolve > 0.01f || kotlin.math.hypot(splitX, splitY) > SPLIT_SETTLED_PX) postInvalidateOnAnimation()
    }

    /**
     * The face: Heylana's own aurora, turning slowly, bent by the lens and lit by the
     * reference shader's bands. Only the aurora is refracted — never the screen.
     */
    @android.annotation.SuppressLint("NewApi")
    private fun drawFace(canvas: Canvas, cx: Float, cy: Float, discRadius: Float) {
        val faceLens = lens as? LiquidGlass.Lens ?: return
        if (aurora == null || auroraCentre.x != cx || auroraCentre.y != cy || auroraRadius != discRadius) {
            val stops = HeylanaTokens.auroraStops
            val sweep = android.graphics.SweepGradient(cx, cy, stops + stops[0], null)
            // Colour toward the rim only: the centre stays clear, as glass does, and the
            // lens bends the coloured edge in.
            val falloff = android.graphics.RadialGradient(
                cx, cy, discRadius,
                intArrayOf(Color.TRANSPARENT, Color.TRANSPARENT, Color.WHITE),
                floatArrayOf(0f, HeylanaTokens.FACE_AURORA_CLEAR, 1f),
                android.graphics.Shader.TileMode.CLAMP
            )
            auroraSweep = sweep
            aurora = android.graphics.ComposeShader(sweep, falloff, android.graphics.PorterDuff.Mode.DST_IN)
            auroraCentre.set(cx, cy)
            auroraRadius = discRadius
        }
        val turn = (System.currentTimeMillis() % HeylanaTokens.FACE_AURORA_TURN_MS).toFloat() /
            HeylanaTokens.FACE_AURORA_TURN_MS * 360f
        auroraMatrix.setRotate(turn, cx, cy)
        auroraSweep?.setLocalMatrix(auroraMatrix)

        faceRect.set(cx - discRadius, cy - discRadius, cx + discRadius, cy + discRadius)
        val layerAlpha = HeylanaTokens.FACE_AURORA_IDLE +
            (HeylanaTokens.FACE_AURORA_ACTIVE - HeylanaTokens.FACE_AURORA_IDLE) * activeAmount
        faceLens.set(
            bounds = faceRect,
            corner = discRadius,
            rim = discRadius,
            layer = aurora!!,
            layerAlpha = layerAlpha,
            light = HeylanaTokens.FACE_LIGHT,
            edgeOnly = false
        )
        canvas.drawCircle(cx, cy, discRadius, faceLens.paint)
    }

    /** Eases the split toward where the motion wants it, and back to nothing once motion stops. */
    private fun stepSplit() {
        if (System.nanoTime() - lastMotionNanos > MOTION_STALE_NANOS) {
            splitTargetX = 0f
            splitTargetY = 0f
        }
        splitX += (splitTargetX - splitX) * SPLIT_EASE
        splitY += (splitTargetY - splitY) * SPLIT_EASE
    }

    /** The mark three times, red back, green in place, blue forward, added together. */
    private fun drawSplitMark(canvas: Canvas, drawable: Drawable) {
        val bounds = drawable.bounds
        val w = bounds.width()
        val h = bounds.height()
        if (w <= 0 || h <= 0) return
        val bitmap = markBitmap?.takeIf { it.width == w && it.height == h }
            ?: android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888).also { made ->
                val alpha = drawable.alpha
                drawable.alpha = 255
                val saved = drawable.copyBounds()
                drawable.setBounds(0, 0, w, h)
                drawable.draw(Canvas(made))
                drawable.bounds = saved
                drawable.alpha = alpha
                markBitmap = made
            }
        channelPaint.alpha = drawable.alpha
        channelPaint.blendMode = android.graphics.BlendMode.PLUS
        val channels = intArrayOf(Color.RED, Color.GREEN, Color.BLUE)
        for (i in 0 until 3) {
            val motion = (i - 1).toFloat()
            channelPaint.colorFilter = android.graphics.PorterDuffColorFilter(channels[i], android.graphics.PorterDuff.Mode.SRC_IN)
            canvas.drawBitmap(
                bitmap,
                bounds.left + splitX * motion,
                bounds.top + splitY * motion,
                channelPaint
            )
        }
    }

    /**
     * The orb on the face: each state's own clock advances by the frame's real time
     * at the preset speed, sped up a little by a loud voice, and the orb swells with
     * the mic level while listening and with the playback level while speaking.
     */
    private fun drawOrb(canvas: Canvas, cx: Float, cy: Float, discRadius: Float) {
        val now = System.nanoTime()
        val dt = if (lastFrameNanos == 0L) 0.0 else ((now - lastFrameNanos) / 1e9).coerceAtMost(MAX_FRAME_SECONDS)
        lastFrameNanos = now
        val heard = when {
            orbState == OrbState.LISTENING -> micLevel
            orbState == OrbState.COMPOSING -> playbackLevel
            else -> 0f
        }
        shownLevel += (heard - shownLevel) * LEVEL_EASE
        val diameter = discRadius * 2f * HeylanaTokens.ORB_FRACTION * (1f + HeylanaTokens.ORB_LEVEL_SWELL * shownLevel)
        val gather = GATHER_FROM + (1f - GATHER_FROM) * dissolve
        val hue = (now % AURORA_TURN_NANOS).toFloat() / AURORA_TURN_NANOS

        previousOrb?.let { state ->
            if (orbMix < 0.99f) {
                val t = advance(state, dt, heard)
                orbPainter.draw(canvas, state, t, cx, cy, diameter, dissolve * (1f - orbMix), gather, hue)
            }
        }
        orbState?.let { state ->
            val t = advance(state, dt, heard)
            orbPainter.draw(canvas, state, t, cx, cy, diameter, dissolve * orbMix, gather, hue)
        }
    }

    private fun advance(state: OrbState, dt: Double, level: Float): Double {
        val speed = OrbEngine.resolve(state, OrbPainter.GEOMETRY_SIZE).speed * (1.0 + LEVEL_SPEEDUP * level)
        val t = (orbClock[state] ?: 0.0) + dt * speed
        orbClock[state] = t
        return t
    }

    companion object {
        /** Which orb each look becomes: speaking wins, then listening, working, thinking. */
        fun orbFor(expression: Expression, talking: Boolean): OrbState? = when {
            talking -> OrbState.COMPOSING
            expression == Expression.LISTENING -> OrbState.LISTENING
            expression == Expression.WORKING -> OrbState.WORKING
            expression == Expression.THINKING -> OrbState.BREATHING
            else -> null
        }

        private const val DISSOLVE_SWELL = 0.25f
        private const val SPLIT_VISIBLE_PX = 0.3f
        private const val SPLIT_SETTLED_PX = 0.05f
        private const val SPLIT_EASE = 0.3f
        /** No motion update for this long and the split starts settling. */
        private const val MOTION_STALE_NANOS = 80_000_000L
        /** How far toward the centre the dots start as they come out of the mark. */
        private const val GATHER_FROM = 0.3f
        private const val LEVEL_EASE = 0.25f
        private const val LEVEL_SPEEDUP = 0.8
        private const val MAX_FRAME_SECONDS = 0.1
        /** The thinking aurora turns once every six seconds. */
        private const val AURORA_TURN_NANOS = 6_000_000_000L
    }
}
