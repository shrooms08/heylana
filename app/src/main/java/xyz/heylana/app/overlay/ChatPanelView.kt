package xyz.heylana.app.overlay

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.os.Build
import android.text.InputType
import android.text.method.ScrollingMovementMethod
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.dynamicanimation.animation.DynamicAnimation
import androidx.dynamicanimation.animation.SpringAnimation
import androidx.dynamicanimation.animation.SpringForce
import xyz.heylana.app.HeylanaLog
import xyz.heylana.app.R
import xyz.heylana.app.brain.Source
import xyz.heylana.app.brain.Sources
import xyz.heylana.app.ui.GlassDrawable
import xyz.heylana.app.ui.GlassSpec
import xyz.heylana.app.ui.BorderBeam
import xyz.heylana.app.ui.HeylanaTokens

/**
 * The speaker glyph that doubles as the mute switch — drawn in code, no assets.
 */
@SuppressLint("ViewConstructor")
private class MuteToggleView(context: Context) : View(context) {

    var muted: Boolean = false
        set(value) {
            if (field != value) {
                field = value
                invalidate()
            }
        }

    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = HeylanaTokens.textSecondary
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = HeylanaTokens.textSecondary
        style = Paint.Style.FILL
    }
    private val cone = Path()
    private val wave = RectF()

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        val unit = minOf(w, h) / 24f
        stroke.strokeWidth = unit * 1.8f

        val cx = w / 2f - unit * 3f
        val cy = h / 2f
        cone.reset()
        cone.moveTo(cx - unit * 4f, cy - unit * 2.5f)
        cone.lineTo(cx - unit * 1f, cy - unit * 2.5f)
        cone.lineTo(cx + unit * 3f, cy - unit * 6f)
        cone.lineTo(cx + unit * 3f, cy + unit * 6f)
        cone.lineTo(cx - unit * 1f, cy + unit * 2.5f)
        cone.lineTo(cx - unit * 4f, cy + unit * 2.5f)
        cone.close()
        canvas.drawPath(cone, fill)

        if (muted) {
            canvas.drawLine(
                cx + unit * 5f, cy - unit * 5f,
                cx + unit * 10f, cy + unit * 5f,
                stroke
            )
        } else {
            for (i in 1..2) {
                val r = unit * (2f + 3.5f * i)
                wave.set(cx + unit * 3f - r, cy - r, cx + unit * 3f + r, cy + r)
                canvas.drawArc(wave, -45f, 90f, false, stroke)
            }
        }
    }
}

/**
 * The thin rail along the bottom of the task HUD: how far through the steps the
 * user is, out of the cap.
 */
@SuppressLint("ViewConstructor")
private class ProgressRailView(context: Context) : View(context) {

    var progress: Float = 0f
        set(value) {
            field = value.coerceIn(0f, 1f)
            invalidate()
        }

    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = HeylanaTokens.withAlpha(HeylanaTokens.accent, 0.20f)
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = HeylanaTokens.accent
    }
    private val bar = RectF()

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        val r = h / 2f
        bar.set(0f, 0f, w, h)
        canvas.drawRoundRect(bar, r, r, track)
        if (progress > 0f) {
            bar.set(0f, 0f, w * progress, h)
            canvas.drawRoundRect(bar, r, r, fill)
        }
    }
}

/**
 * The message box: one sheet of glass holding whatever Heylana last said, the
 * question field and the ask button. During a task it also carries the step chip
 * and the next and done buttons.
 */
@SuppressLint("ViewConstructor")
class ChatPanelView(context: Context) : LinearLayout(context), PanelReset.Resettable {

    /** Called with the trimmed question when the user asks. */
    var onSend: ((String) -> Unit)? = null

    /** Called with the new muted state when the speaker glyph is tapped. */
    var onMuteToggled: ((Boolean) -> Unit)? = null

    /** Called when the user asks for the next step of a task. */
    var onNext: (() -> Unit)? = null

    /** Called when the user ends a task early. */
    var onDone: (() -> Unit)? = null

    /** Called when the user confirms the send on the strip. */
    var onConfirm: (() -> Unit)? = null

    /** Called when the user cancels the send on the strip. */
    var onCancel: (() -> Unit)? = null

    /** Called when the user taps the question field, so the window can take focus. */
    var onInputTapped: (() -> Unit)? = null

    /** Called when the compact strip is tapped, to reopen the box for a follow-up. */
    var onStripTapped: (() -> Unit)? = null

    private val modeChip = TextView(context)
    private val answer = TextView(context)
    private val note = TextView(context)
    private val input = EditText(context)
    private val ask = TextView(context)
    private val mute = MuteToggleView(context)

    private val inputRow = LinearLayout(context)

    private val rail = ProgressRailView(context)
    private val sessionRow = LinearLayout(context)
    private val stepChip = TextView(context)
    private val next = TextView(context)
    private val done = TextView(context)

    /** Where the answer came from: up to two glass chips under it, each opening its page. */
    private val sourceRow = LinearLayout(context)
    private val sourceChips = List(Sources.MAX_CHIPS) { TextView(context) }
    private var sources: List<Source> = emptyList()

    /** True while chips are up: the box then waits longer before it settles, so they can be tapped. */
    val hasSources: Boolean get() = sources.isNotEmpty()

    /** A source chip was tapped: open its page. */
    var onSourceTapped: ((Source) -> Unit)? = null

    // ------------------------------------------------------- transaction card
    /** A send's card, in place of the answer while a send is on the strip: see [showTxCard]. */
    private val txScroll = CappedScroll(context)
    private val txCard = LinearLayout(context)
    private val txTitle = TextView(context)
    private val txRows = LinearLayout(context)
    private val txBody = TextView(context)
    private val txDetails = TextView(context)
    private val txWarning = TextView(context)
    private var txShown = false

    private val confirmRow = LinearLayout(context)
    private val simStatus = TextView(context)
    private val confirm = TextView(context)
    private val cancel = TextView(context)

    /** Where the send's simulation stands; Confirm can be tapped only once it has passed. */
    enum class Simulation { CHECKING, PASSED }

    private var simulation = Simulation.CHECKING

    /** The width the box takes when it rides beside the buddy as a task HUD. */
    val hudWidth = dp(264f)

    /**
     * What this pane currently is. The box, the reply strip and the task HUD are
     * one shape in three shapes' clothing: the pane interpolates between them
     * rather than swapping one view out for another.
     */
    enum class Shape { BOX, STRIP, HUD }

    var shape: Shape = Shape.BOX
        private set

    private var morph: android.animation.ValueAnimator? = null

    private var blurBehind = false

    /** Room for the pane's own shadow, on top of the content padding. */
    private val shadowPad = HeylanaTokens.dpInt(context, HeylanaTokens.GLASS_SHADOW_DP)

    init {
        orientation = VERTICAL
        val pad = dp(HeylanaTokens.SPACE_4_DP) + shadowPad
        setPadding(pad, pad, pad, pad)
        // Hardware rendered: the glass's shadow is a GPU shadow layer, and its liquid
        // edges are a RuntimeShader, which only runs on a hardware canvas.

        // ------------------------------------------------------- mode chip
        // What Heylana is doing: reading, thinking, simulating, approve in wallet…
        styleLabel(modeChip, HeylanaTokens.textPrimary)
        modeChip.background = android.graphics.drawable.GradientDrawable().apply {
            setColor(HeylanaTokens.inputFill)
            cornerRadius = HeylanaTokens.dp(context, HeylanaTokens.RADIUS_FULL_DP)
        }
        modeChip.setPadding(
            dp(HeylanaTokens.SPACE_3_DP), dp(HeylanaTokens.SPACE_1_DP),
            dp(HeylanaTokens.SPACE_3_DP), dp(HeylanaTokens.SPACE_1_DP)
        )
        softShadow(modeChip)
        modeChip.visibility = View.GONE
        addView(
            modeChip,
            LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(HeylanaTokens.SPACE_2_DP)
            }
        )

        // ---------------------------------------------------- answer + mute
        // Packed to the end so the speaker stays in the corner even with no answer.
        val topRow = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.END
        }

        answer.apply {
            visibility = View.GONE
            setTextColor(HeylanaTokens.textPrimary)
            softShadow(this)
            typeface = HeylanaTokens.typeface(context, HeylanaTokens.WEIGHT_LIGHT)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, HeylanaTokens.BODY_SP)
            maxLines = MAX_ANSWER_LINES
            movementMethod = ScrollingMovementMethod()
            // Long answers scroll inside the strip rather than being cut off.
            isVerticalScrollBarEnabled = true
            setLineSpacing(HeylanaTokens.dp(context, HeylanaTokens.SPACE_1_DP), 1f)
        }
        topRow.addView(answer, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        buildTxCard()
        topRow.addView(txScroll, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))

        mute.apply {
            contentDescription = MUTE_LABEL
            setOnClickListener {
                muted = !muted
                contentDescription = if (muted) UNMUTE_LABEL else MUTE_LABEL
                onMuteToggled?.invoke(muted)
            }
        }
        topRow.addView(
            mute,
            LayoutParams(dp(26f), dp(26f)).apply { marginStart = dp(HeylanaTokens.SPACE_3_DP) }
        )
        addView(topRow, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        // ------------------------------------------------------- source chips
        sourceRow.apply {
            orientation = VERTICAL
            visibility = View.GONE
        }
        sourceChips.forEachIndexed { i, chip ->
            stylePill(chip, "", secondaryText) { sources.getOrNull(i)?.let { onSourceTapped?.invoke(it) } }
            // Two lines before an ellipsis: a signature chip on a narrow card was clipped at one.
            chip.maxLines = 2
            chip.ellipsize = android.text.TextUtils.TruncateAt.END
            chip.gravity = Gravity.CENTER_VERTICAL or Gravity.START
            sourceRow.addView(
                chip,
                LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
                    if (i > 0) topMargin = dp(HeylanaTokens.SPACE_2_DP)
                }
            )
        }
        addView(
            sourceRow,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(HeylanaTokens.SPACE_3_DP)
            }
        )

        // ------------------------------------------------------- task strip
        sessionRow.apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            visibility = View.GONE
        }
        styleLabel(stepChip, secondaryText)
        stepChip.setPadding(
            dp(HeylanaTokens.SPACE_3_DP), dp(HeylanaTokens.SPACE_1_DP),
            dp(HeylanaTokens.SPACE_3_DP), dp(HeylanaTokens.SPACE_1_DP)
        )
        sessionRow.addView(stepChip, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
        sessionRow.addView(View(context), LayoutParams(0, 1, 1f))

        stylePill(next, "next", HeylanaTokens.textPrimary) { onNext?.invoke() }
        sessionRow.addView(next, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))

        stylePill(done, "done", secondaryText) { onDone?.invoke() }
        sessionRow.addView(
            done,
            LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
                marginStart = dp(HeylanaTokens.SPACE_2_DP)
            }
        )
        addView(
            sessionRow,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(HeylanaTokens.SPACE_3_DP)
            }
        )

        // ---------------------------------------------------- send confirm
        // Outside the shapes on purpose: it stays until confirm or cancel is tapped.
        confirmRow.apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL or Gravity.END
            visibility = View.GONE
        }
        // "Checking it with the network…", then "✓ Simulation passed" in green.
        styleLabel(simStatus, secondaryText)
        confirmRow.addView(simStatus, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        stylePill(cancel, "cancel", secondaryText) { onCancel?.invoke() }
        confirmRow.addView(cancel, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
        stylePill(confirm, "confirm", selectedText, selected = true) {
            // Never before the simulation has passed, whatever reaches the view.
            if (simulation == Simulation.PASSED) onConfirm?.invoke()
        }
        confirmRow.addView(
            confirm,
            LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
                marginStart = dp(HeylanaTokens.SPACE_2_DP)
            }
        )
        addView(
            confirmRow,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(HeylanaTokens.SPACE_3_DP)
            }
        )

        // ------------------------------------------------------ field + ask
        val row = inputRow.apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        input.apply {
            hint = PLACEHOLDER
            setHintTextColor(secondaryText)
            setTextColor(HeylanaTokens.textPrimary)
            softShadow(this)
            typeface = HeylanaTokens.typeface(context, HeylanaTokens.WEIGHT_LIGHT)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, HeylanaTokens.BODY_SP)
            inputType = InputType.TYPE_CLASS_TEXT
            isSingleLine = true
            // A caret that can be seen on the dark field: the accent, 2dp.
            textCursorDrawable = android.graphics.drawable.GradientDrawable().apply {
                setColor(HeylanaTokens.accent)
                setSize(dp(HeylanaTokens.CARET_DP), 0)
            }
            imeOptions = EditorInfo.IME_ACTION_SEND
            setPadding(
                dp(HeylanaTokens.SPACE_3_DP), dp(HeylanaTokens.SPACE_3_DP),
                dp(HeylanaTokens.SPACE_3_DP), dp(HeylanaTokens.SPACE_3_DP)
            )
            setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_SEND) {
                    submit()
                    true
                } else {
                    false
                }
            }
            // While a task is running the window is deliberately not focusable, so
            // the app underneath keeps the keyboard. Touching the field asks for it.
            @Suppress("ClickableViewAccessibility")
            setOnTouchListener { _, event ->
                if (event.actionMasked == MotionEvent.ACTION_UP) onInputTapped?.invoke()
                false
            }
        }
        row.addView(input, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))

        stylePill(ask, "ask", selectedText, selected = true) { submit() }
        ask.setPadding(
            dp(HeylanaTokens.SPACE_4_DP), dp(HeylanaTokens.SPACE_3_DP),
            dp(HeylanaTokens.SPACE_4_DP), dp(HeylanaTokens.SPACE_3_DP)
        )
        row.addView(
            ask,
            LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
                marginStart = dp(HeylanaTokens.SPACE_3_DP)
            }
        )
        addView(
            row,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(HeylanaTokens.SPACE_3_DP)
            }
        )

        rail.visibility = View.GONE
        addView(
            rail,
            LayoutParams(LayoutParams.MATCH_PARENT, dp(HeylanaTokens.RAIL_DP)).apply {
                topMargin = dp(HeylanaTokens.SPACE_3_DP)
            }
        )

        note.apply {
            visibility = View.GONE
            setTextColor(secondaryText)
            softShadow(this)
            typeface = HeylanaTokens.typeface(context, HeylanaTokens.WEIGHT_LIGHT)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, HeylanaTokens.LABEL_SP)
        }
        addView(
            note,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(HeylanaTokens.SPACE_2_DP)
            }
        )

        applyGlass(blurBehind = false)

        // Tapping the strip is a request to ask something else; tapping the box
        // is not, so the listener only answers in the one shape.
        setOnClickListener { if (shape == Shape.STRIP) onStripTapped?.invoke() }
    }

    /**
     * Rebuilds every glass surface for whether the window really got its blur.
     * Without blur the fill has to be heavier, or the box reads as a grey smear.
     */
    // ------------------------------------------------------ purple streak

    /** While thinking the streak runs faster (a 4s pass) and at 95%. */
    private var streakThinking = false
    private var streakPhase = 0f
    private var streakLevel = 0f
    private var streakTarget = 0f

    /** What the rim beam is signalling; [Beam.NONE] leaves the streak to drift instead. */
    enum class Beam { NONE, THINKING, LISTENING, SPEAKING }

    private var beam = Beam.NONE
    private var voiceLevel = 0f
    private var beamPhase = 0f
    private var beamLevel = 0f
    private var shown = false

    /** Thinking or listening lights the rim beam; speaking pulses it with the voice. */
    fun setBeam(next: Beam, level: Float = 0f) {
        if (GlassSpec.TINTED_EXTRAS) return
        voiceLevel = level.coerceIn(0f, 1f)
        if (beam == next) return
        beam = next
        streakOn(shown)
    }

    /**
     * One frame of the streak: advance the phase by the real frame time at this pass's
     * speed, ease the level toward its target (it melts in and out over the glass fade),
     * and hand both to the glass. Floats only — nothing is allocated per frame.
     */
    private val streakClock = android.animation.TimeAnimator().apply {
        setTimeListener { animator, _, deltaMs ->
            val pass = if (streakThinking) GlassSpec.STREAK_THINKING_PASS_MS else GlassSpec.STREAK_PASS_MS
            streakPhase = (streakPhase + deltaMs.toFloat() / pass) % 1f
            beamPhase = (beamPhase + deltaMs.toFloat() / BorderBeam.LAP_MS) % 1f
            val step = deltaMs.toFloat() / HeylanaTokens.FADE_MS
            streakLevel = ease(streakLevel, streakTarget, step)
            beamLevel = ease(beamLevel, if (shown && beam != Beam.NONE) 1f else 0f, step)
            (background as? GlassDrawable)?.let { glass ->
                glass.streakPhase = streakPhase
                glass.streakStrength = streakLevel * (if (streakThinking) GlassSpec.STREAK_THINKING_STRENGTH else 1f)
                glass.beamPhase = beamPhase
                glass.beamStrength = beamLevel * if (beam == Beam.SPEAKING) {
                    BorderBeam.SPEAKING_FLOOR + (1f - BorderBeam.SPEAKING_FLOOR) * voiceLevel
                } else {
                    1f
                }
            }
            invalidate()
            if (streakLevel <= 0f && streakTarget <= 0f && beamLevel <= 0f && (!shown || beam == Beam.NONE)) animator.end()
        }
    }

    private fun ease(value: Float, target: Float, step: Float): Float =
        if (value < target) (value + step).coerceAtMost(target) else (value - step).coerceAtLeast(target)

    /** The panel shows or goes: the streak drifts only while no beam is lit. */
    private fun streakOn(on: Boolean) {
        if (GlassSpec.TINTED_EXTRAS) return
        shown = on
        streakTarget = if (on && beam == Beam.NONE) 1f else 0f
        if (!streakClock.isStarted && (on || streakLevel > 0f || beamLevel > 0f)) streakClock.start()
    }

    /** The panel is closing: the streak melts away with the glass. */
    fun meltStreak() = streakOn(false)

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        streakOn(visibility == View.VISIBLE)
    }

    override fun onDetachedFromWindow() {
        streakClock.end()
        streakLevel = 0f
        beamLevel = 0f
        super.onDetachedFromWindow()
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        if (changedView === this && isAttachedToWindow) streakOn(visibility == View.VISIBLE)
    }

    fun applyGlass(blurBehind: Boolean) {
        this.blurBehind = blurBehind
        background = GlassDrawable(
            context,
            if (GlassSpec.TINTED_EXTRAS) HeylanaTokens.RADIUS_CARD_DP else GlassSpec.PANEL.radiusDp,
            blurBehind, GlassDrawable.Kind.PANEL,
            withShadow = true
        ).also {
            // Opaque, whatever it is showing: an answer, a warning, a step or a send. Clear glass
            // let a home screen's icons through the words. The glass keeps only its edge.
            it.solid = HeylanaTokens.txCardFill
        }
        // The field is a lighter sheet sunk into the panel, never a darker hole — and opaque.
        input.background = GlassDrawable(
            context, HeylanaTokens.RADIUS_MD_DP, blurBehind, GlassDrawable.Kind.INPUT
        ).also { it.solid = HeylanaTokens.inputFillSolid }
        stepChip.background = GlassDrawable(
            context, HeylanaTokens.RADIUS_FULL_DP, blurBehind, GlassDrawable.Kind.PILL
        )
        // The ask pill and confirm are the thing to press: the selected chip style.
        ask.background = GlassDrawable(
            context, HeylanaTokens.RADIUS_FULL_DP, blurBehind, GlassDrawable.Kind.PILL,
            HeylanaTokens.bandPrimary, selected = true
        )
        next.background = GlassDrawable(
            context, HeylanaTokens.RADIUS_FULL_DP, blurBehind, GlassDrawable.Kind.PILL,
            HeylanaTokens.bandPrimary
        )
        done.background = GlassDrawable(
            context, HeylanaTokens.RADIUS_FULL_DP, blurBehind, GlassDrawable.Kind.PILL,
            HeylanaTokens.accentBand
        )
        confirm.background = GlassDrawable(
            context, HeylanaTokens.RADIUS_FULL_DP, blurBehind, GlassDrawable.Kind.PILL,
            HeylanaTokens.bandPrimary, selected = true
        )
        cancel.background = GlassDrawable(
            context, HeylanaTokens.RADIUS_FULL_DP, blurBehind, GlassDrawable.Kind.PILL,
            HeylanaTokens.accentBand
        )
        for (chip in sourceChips) {
            chip.background = GlassDrawable(context, HeylanaTokens.RADIUS_FULL_DP, blurBehind, GlassDrawable.Kind.PILL)
        }
        invalidate()
    }

    /**
     * Melts the pane into another shape: the corner radius, the height and the
     * content all travel together, so it reads as one thing changing rather
     * than two things swapping.
     */
    fun morphTo(next: Shape, onDone: (() -> Unit)? = null) {
        if (shape == next) {
            onDone?.invoke()
            return
        }
        val from = shape
        gooeyMorph(next)
        shape = next
        val glass = background as? GlassDrawable

        morph?.cancel()
        morph = android.animation.ValueAnimator.ofFloat(0f, 1f).apply {
            duration = HeylanaTokens.GROW_MS
            interpolator = android.view.animation.AccelerateDecelerateInterpolator()
            addUpdateListener {
                val t = it.animatedValue as Float
                glass?.radiusOverrideDp = lerp(radiusFor(from), radiusFor(next), t)
                // The rows that are leaving fade out over the first half, the
                // ones arriving fade in over the second.
                leaving(from, next).forEach { row -> row.alpha = (1f - t * 2f).coerceIn(0f, 1f) }
                arriving(from, next).forEach { row -> row.alpha = ((t - 0.5f) * 2f).coerceIn(0f, 1f) }
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    glass?.radiusOverrideDp = radiusFor(next)
                    applyShape()
                    onDone?.invoke()
                }
            })
            start()
        }
    }

    private fun radiusFor(s: Shape): Float = when (s) {
        Shape.BOX -> HeylanaTokens.RADIUS_CARD_DP
        Shape.STRIP, Shape.HUD -> HeylanaTokens.RADIUS_STRIP_DP
    }

    private fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t

    private fun rowsFor(s: Shape): List<View> = when (s) {
        Shape.BOX -> listOf(inputRow)
        Shape.STRIP -> emptyList()
        Shape.HUD -> listOf(sessionRow)
    }

    private fun leaving(from: Shape, to: Shape) = rowsFor(from) - rowsFor(to).toSet()

    private fun arriving(from: Shape, to: Shape) = rowsFor(to) - rowsFor(from).toSet()

    /** Puts the rows in the state the current shape calls for. */
    private fun applyShape() {
        inputRow.visibility = if (shape == Shape.BOX && !isVoiceMode) View.VISIBLE else View.GONE
        sessionRow.visibility = if (shape == Shape.HUD) View.VISIBLE else View.GONE
        rail.visibility = if (shape == Shape.HUD) View.VISIBLE else View.GONE
        for (row in listOf(inputRow, sessionRow)) row.alpha = 1f
        // The strip is the answer and the mute glyph, and nothing else.
        answer.maxLines = if (shape == Shape.BOX) MAX_ANSWER_LINES else STRIP_LINES
    }

    /** Runs the sheen once across the pane, as it appears. */
    fun sweepSheen() {
        val glass = background as? GlassDrawable ?: return
        android.animation.ValueAnimator.ofFloat(0f, 1f).apply {
            duration = HeylanaTokens.SHEEN_SWEEP_MS
            interpolator = android.view.animation.DecelerateInterpolator()
            addUpdateListener { glass.sheenProgress = it.animatedValue as Float }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    glass.sheenProgress = HeylanaTokens.SHEEN_REST
                }
            })
            start()
        }
    }

    /** Secondary text on clear glass is white, a step back; on tinted glass the old lavender grey. */
    private val secondaryText: Int
        get() = if (GlassSpec.TINTED_EXTRAS) HeylanaTokens.textSecondary else HeylanaTokens.withAlpha(android.graphics.Color.WHITE, HeylanaTokens.SECONDARY_WHITE)

    private val selectedText: Int
        get() = if (GlassSpec.TINTED_EXTRAS) HeylanaTokens.textPrimary else GlassSpec.CHIP_SELECTED_TEXT

    /** 0 1dp 6dp, black 30%: white text stays legible over a bright app behind clear glass. */
    private fun softShadow(view: TextView) {
        if (GlassSpec.TINTED_EXTRAS) return
        view.setShadowLayer(
            HeylanaTokens.dp(context, GlassSpec.TEXT_SHADOW_RADIUS_DP), 0f,
            HeylanaTokens.dp(context, GlassSpec.TEXT_SHADOW_DY_DP),
            HeylanaTokens.withAlpha(android.graphics.Color.BLACK, GlassSpec.TEXT_SHADOW_BLACK)
        )
    }

    private fun styleLabel(view: TextView, colour: Int) {
        view.setTextColor(colour)
        view.typeface = HeylanaTokens.typeface(context, HeylanaTokens.WEIGHT_MEDIUM)
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, HeylanaTokens.LABEL_SP)
        view.letterSpacing = HeylanaTokens.LABEL_TRACKING_EM
    }

    private fun stylePill(view: TextView, text: String, colour: Int, selected: Boolean = false, onTap: () -> Unit) {
        view.text = text
        styleLabel(view, colour)
        view.gravity = Gravity.CENTER
        view.setPadding(
            dp(HeylanaTokens.SPACE_4_DP), dp(HeylanaTokens.SPACE_2_DP),
            dp(HeylanaTokens.SPACE_4_DP), dp(HeylanaTokens.SPACE_2_DP)
        )
        if (!GlassSpec.TINTED_EXTRAS) view.minHeight = dp(GlassSpec.CHIP_HEIGHT_DP)
        // White text sits on dark or clear glass and needs the soft shadow; dark text on a white chip does not.
        if (!selected) softShadow(view)
        view.isClickable = true
        view.setOnClickListener { onTap() }
    }

    // ----------------------------------------------------------- voice mode

    /** True while the current question was asked by voice rather than typed. */
    var isVoiceMode = false
        private set

    /** Whether a spoken answer also shows its words. Remembered in settings. */
    private var voiceShowsText = false

    fun setVoiceMode(voice: Boolean, showsText: Boolean) {
        isVoiceMode = voice
        voiceShowsText = showsText
        applyVoiceVisibility()
    }

    /**
     * In voice mode the box is a stage, not a transcript: no field to type in,
     * and no wall of text unless the user has asked to see it.
     */
    private fun applyVoiceVisibility() {
        inputRow.visibility = if (isVoiceMode) View.GONE else View.VISIBLE
        if (isVoiceMode && !voiceShowsText) {
            answer.visibility = View.GONE
        } else if (isVoiceMode) {
            answer.visibility = if (answer.text.isNullOrBlank()) View.GONE else View.VISIBLE
        }
    }

    private fun submit() {
        val question = input.text.toString().trim()
        if (question.isEmpty()) return
        onSend?.invoke(question)
    }

    private fun enable(enabled: Boolean) {
        ask.isEnabled = enabled
        ask.alpha = if (enabled) 1f else 0.5f
        next.isEnabled = enabled
        next.alpha = if (enabled) 1f else 0.5f
        confirm.isEnabled = enabled
        confirm.alpha = if (enabled) 1f else 0.5f
        input.isEnabled = enabled
    }

    /**
     * Shows the answer area only when there is something to read — and, when the
     * question was spoken, only if the user has asked to see the words at all.
     */
    private fun say(text: String) {
        hideTxCard()
        answer.text = xyz.heylana.app.ui.NumberText.spanned(text)
        val wanted = text.isNotBlank() && (!isVoiceMode || voiceShowsText)
        answer.visibility = if (wanted) View.VISIBLE else View.GONE
        answer.scrollTo(0, 0)
    }

    /**
     * The chips under the answer: where it came from, at most [Sources.MAX_CHIPS]. The link
     * is only ever here, never in the words. An empty list takes them away.
     */
    fun showSources(list: List<Source>) {
        sources = Sources.chips(list)
        sourceChips.forEachIndexed { i, chip ->
            val source = sources.getOrNull(i)
            chip.text = source?.let { "${it.title}  ${HeylanaTokens.SOURCE_ARROW}" } ?: ""
            chip.visibility = if (source == null) View.GONE else View.VISIBLE
        }
        sourceRow.visibility = if (sources.isEmpty()) View.GONE else View.VISIBLE
        if (sources.isNotEmpty()) HeylanaLog.state("panel: sources shown=${sources.size}")
    }

    fun showThinking() {
        showSources(emptyList())
        streakThinking = true
        // A spoken question has its own capsule beside the disc; the box shows
        // nothing at all until there is an answer to read.
        if (isVoiceMode) return
        say(THINKING)
        enable(false)
    }

    fun showAnswer(text: String) {
        streakThinking = false
        say(text)
        enable(true)
        input.setText("")
    }

    /** A notice or error — keeps whatever the user typed so they can retry. */
    fun showNotice(text: String) {
        hideTxCard()
        streakThinking = false
        // A problem is always worth reading, whatever the user asked for.
        answer.text = xyz.heylana.app.ui.NumberText.spanned(text)
        answer.visibility = if (text.isBlank()) View.GONE else View.VISIBLE
        answer.scrollTo(0, 0)
        enable(true)
    }

    /**
     * A send's card ([xyz.heylana.app.wallet.TxCard]), readable over any page: a near-opaque
     * blue-black fill with the glass kept on its edge only; the label in semibold with the
     * network's pill beside it and the speaker on the right; then label-and-value rows, the
     * amount largest; the line, what the bytes do and the warning in the secondary colour.
     * Every line wraps — nothing is cut off — and past 60% of the screen it scrolls. Numbers
     * are Outfit's tabular figures, never monospace.
     */
    fun showTxCard(card: xyz.heylana.app.wallet.TxCard) {
        streakThinking = false
        answer.visibility = View.GONE
        // The badge is part of the title's own text, a pill drawn in place: it wraps with the
        // title and can never be squeezed off the row, however narrow the card.
        txTitle.text = android.text.SpannableStringBuilder(card.title).apply {
            append(" ")
            val at = length
            append(card.badge.label)
            setSpan(
                PillSpan(
                    fill = if (card.badge.amber) HeylanaTokens.warnSoft else HeylanaTokens.txBadgeNeutralFill,
                    words = if (card.badge.amber) HeylanaTokens.warn else HeylanaTokens.text2,
                    textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, HeylanaTokens.TX_LABEL_SP, resources.displayMetrics),
                    typeface = HeylanaTokens.typeface(context, HeylanaTokens.WEIGHT_MEDIUM),
                    padH = HeylanaTokens.dp(context, HeylanaTokens.SPACE_2_DP),
                    padV = HeylanaTokens.dp(context, HeylanaTokens.SPACE_1_DP) / 2f,
                    gap = HeylanaTokens.dp(context, HeylanaTokens.SPACE_1_DP)
                ),
                at, length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }
        txRows.removeAllViews()
        card.rows.forEachIndexed { i, row ->
            val label = TextView(context).also { txText(it, HeylanaTokens.TX_LABEL_SP, HeylanaTokens.WEIGHT_REGULAR, HeylanaTokens.textSecondary) }
            label.text = row.label
            val value = TextView(context).also {
                if (row.lead) txText(it, HeylanaTokens.TX_AMOUNT_SP, HeylanaTokens.WEIGHT_SEMIBOLD, HeylanaTokens.textPrimary)
                else txText(it, HeylanaTokens.TX_VALUE_SP, HeylanaTokens.WEIGHT_REGULAR, HeylanaTokens.textPrimary)
            }
            value.text = row.value
            txRows.addView(label, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                if (i > 0) topMargin = dp(HeylanaTokens.SPACE_3_DP)
            })
            txRows.addView(value, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        }
        txRows.visibility = if (card.rows.isEmpty()) View.GONE else View.VISIBLE
        showOrHide(txBody, card.body)
        showOrHide(txDetails, card.details.takeIf { it.isNotEmpty() }?.joinToString("\n") { "• $it" })
        showOrHide(txWarning, card.warning)
        txCard.contentDescription = card.text
        txScroll.visibility = View.VISIBLE
        txScroll.scrollTo(0, 0)
        txShown = true
        enable(true)
        HeylanaLog.state("panel: tx card title=\"${card.title}\" rows=${card.rows.size} chars=${card.text.length}")
    }

    /** Back to clear glass and the ordinary answer: any other words on the strip. */
    private fun hideTxCard() {
        if (!txShown) return
        txShown = false
        txScroll.visibility = View.GONE
    }

    private fun showOrHide(view: TextView, text: String?) {
        view.text = text ?: ""
        view.visibility = if (text.isNullOrBlank()) View.GONE else View.VISIBLE
    }

    /** A card's words: Outfit at [weight] on its own axis, tabular figures, no halo (the card is opaque). */
    private fun txText(view: TextView, sp: Float, weight: Int, color: Int) {
        view.setTextColor(color)
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
        view.typeface = HeylanaTokens.typeface(context, weight)
        view.fontVariationSettings = "'wght' $weight"
        view.fontFeatureSettings = HeylanaTokens.TABULAR_FIGURES
        view.setLineSpacing(HeylanaTokens.dp(context, HeylanaTokens.SPACE_1_DP) / 2f, 1f)
    }

    private fun buildTxCard() {
        txCard.orientation = VERTICAL
        val title = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        txText(txTitle, HeylanaTokens.TX_TITLE_SP, HeylanaTokens.WEIGHT_SEMIBOLD, HeylanaTokens.textPrimary)
        title.addView(txTitle, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        txCard.addView(title, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        txRows.orientation = VERTICAL
        txCard.addView(txRows, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(HeylanaTokens.SPACE_3_DP)
        })
        txText(txBody, HeylanaTokens.BODY_SP, HeylanaTokens.WEIGHT_REGULAR, HeylanaTokens.textPrimary)
        txCard.addView(txBody, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(HeylanaTokens.SPACE_2_DP)
        })
        txText(txDetails, HeylanaTokens.TX_LABEL_SP, HeylanaTokens.WEIGHT_REGULAR, HeylanaTokens.text2)
        txCard.addView(txDetails, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(HeylanaTokens.SPACE_3_DP)
        })
        txText(txWarning, HeylanaTokens.TX_LABEL_SP, HeylanaTokens.WEIGHT_REGULAR, HeylanaTokens.text2)
        txCard.addView(txWarning, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(HeylanaTokens.SPACE_3_DP)
        })
        txScroll.addView(txCard, android.widget.FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        txScroll.isVerticalScrollBarEnabled = true
        txScroll.visibility = View.GONE
    }

    /** The network's badge inside the title's text: a rounded pill with its word, kept whole. */
    private class PillSpan(
        private val fill: Int,
        private val words: Int,
        private val textSize: Float,
        private val typeface: android.graphics.Typeface,
        private val padH: Float,
        private val padV: Float,
        private val gap: Float
    ) : android.text.style.ReplacementSpan() {
        private val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)

        private fun ready(): android.graphics.Paint = paint.also {
            it.textSize = textSize
            it.typeface = typeface
            it.fontFeatureSettings = HeylanaTokens.TABULAR_FIGURES
        }

        override fun getSize(p: android.graphics.Paint, text: CharSequence, start: Int, end: Int, fm: android.graphics.Paint.FontMetricsInt?): Int {
            return (gap + ready().measureText(text, start, end) + 2 * padH).toInt()
        }

        override fun draw(
            canvas: android.graphics.Canvas, text: CharSequence, start: Int, end: Int,
            x: Float, top: Int, y: Int, bottom: Int, p: android.graphics.Paint
        ) {
            val pill = ready()
            val width = pill.measureText(text, start, end) + 2 * padH
            val metrics = pill.fontMetrics
            val textTop = y + metrics.ascent
            val textBottom = y + metrics.descent
            val rect = android.graphics.RectF(x + gap, textTop - padV, x + gap + width, textBottom + padV)
            pill.color = fill
            canvas.drawRoundRect(rect, rect.height() / 2f, rect.height() / 2f, pill)
            pill.color = words
            canvas.drawText(text, start, end, x + gap + padH, y.toFloat(), pill)
        }
    }

    /** A scroll that grows with what it holds, up to [HeylanaTokens.TX_CARD_MAX_SCREEN] of the screen. */
    private class CappedScroll(context: Context) : android.widget.ScrollView(context) {
        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val cap = (resources.displayMetrics.heightPixels * HeylanaTokens.TX_CARD_MAX_SCREEN).toInt()
            super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(cap, MeasureSpec.AT_MOST))
        }
    }

    /** Microphone open: the field fills in live as words are recognised. */
    fun showListening() {
        say(LISTENING)
        enable(true)
        input.setText("")
    }

    fun setSpokenText(text: String) {
        input.setText(text)
        input.setSelection(input.text.length)
    }

    fun spokenText(): String = input.text.toString().trim()

    /** A one-off footnote, e.g. that this device has no voice. */
    fun showNote(text: String) {
        note.text = text
        note.visibility = View.VISIBLE
    }

    /**
     * Shows the step counter, the rail and the buttons for a running task. A teaching
     * session has no Next — the step moves on when the user does it — and keeps Done.
     */
    fun showSession(stepNumber: Int, ofSteps: Int, withNext: Boolean = true) {
        next.visibility = if (withNext) View.VISIBLE else View.GONE
        val firstStep = shape != Shape.HUD
        stepChip.text = xyz.heylana.app.ui.NumberText.spanned(context.getString(R.string.step_label, stepNumber))
        rail.progress = if (ofSteps > 0) stepNumber.toFloat() / ofSteps else 0f
        morphTo(Shape.HUD) {
            if (firstStep) growEdges()
        }
    }

    fun hideSession() {
        morphTo(Shape.STRIP)
    }

    /** The chip saying what Heylana is doing; null takes it away. */
    fun showMode(mode: BuddyMode?) {
        if (mode == null) {
            modeChip.visibility = View.GONE
            return
        }
        modeChip.text = mode.label
        modeChip.visibility = View.VISIBLE
    }

    /** Shows confirm and cancel under whatever the answer line says; Confirm waits for the simulation. */
    fun showConfirm(state: Simulation) {
        confirmRow.visibility = View.VISIBLE
        enable(true)
        setSimulation(state)
    }

    fun setSimulation(state: Simulation) {
        simulation = state
        val passed = state == Simulation.PASSED
        simStatus.text = if (passed) SIMULATION_PASSED else SIMULATING
        simStatus.setTextColor(if (passed) HeylanaTokens.success else secondaryText)
        confirm.isEnabled = passed
        confirm.alpha = if (passed) 1f else DISABLED_ALPHA
    }

    fun hideConfirm() {
        confirmRow.visibility = View.GONE
    }

    // ------------------------------------------------------------- reset

    /** Back to the empty compose box: see [PanelReset]. */
    fun resetToCompose() {
        PanelReset.apply(this)
    }

    override fun setStatusText(text: String) {
        say(text)
        note.text = ""
        note.visibility = View.GONE
    }

    override fun setInputText(text: String) {
        input.setText(text)
    }

    override fun setAskEnabled(enabled: Boolean) = enable(enabled)

    override fun setConfirmShown(shown: Boolean) {
        confirmRow.visibility = if (shown) View.VISIBLE else View.GONE
    }

    override fun setShapeNow(shape: Shape) {
        morph?.cancel()
        this.shape = shape
        (background as? GlassDrawable)?.radiusOverrideDp = null
        applyShape()
    }

    override fun clearSignals() {
        hideTxCard()
        modeChip.visibility = View.GONE
        showSources(emptyList())
        streakThinking = false
        setBeam(Beam.NONE)
        gooeyRun?.cancel()
        gooeyReset()
    }

    /**
     * The chip comes out of the left edge and the buttons out of the right, so
     * the HUD grows its controls rather than having them appear on top of it.
     */
    private fun growEdges() {
        if (gooeyEmergeChips()) return
        growFromEdge(stepChip, pivot = 0f)
        for (pill in listOf(next, done)) growFromEdge(pill, pivot = 1f)
    }

    // -------------------------------------------------------- gooey merges

    /**
     * The silhouette layer behind this pane, set by whoever hosts it. While a merge runs
     * the glass steps aside and the goo carries the shape; the words stay crisp here.
     */
    var gooey: GooeyLayer? = null

    private var gooeyRun: SpringAnimation? = null
    private val gooA = FloatArray(5)
    private val gooB = FloatArray(5)
    private val gooFrom = android.graphics.RectF()
    private val gooTo = android.graphics.RectF()
    private val gooDisc = android.graphics.RectF()
    private val gooChip = android.graphics.RectF()

    private val canGoo: Boolean
        get() = gooey != null && GooeyLayer.available && !GlassSpec.TINTED_EXTRAS && isAttachedToWindow && width > 0

    private fun setBlob(layer: GooeyLayer, index: Int, v: FloatArray) {
        val blob = layer.blobs[index]
        blob.rect.set(v[0], v[1], v[2], v[3])
        blob.radius = v[4]
        blob.visible = true
    }

    /** This pane's glass body in the layer: the view less its shadow room. */
    private fun bodyIn(layer: GooeyLayer, out: android.graphics.RectF) {
        layer.rectOf(this, out)
        out.inset(shadowPad.toFloat(), shadowPad.toFloat())
    }

    /** The visible disc in the layer: its view less the bloom room around it. */
    private fun discIn(layer: GooeyLayer, disc: View, out: android.graphics.RectF) {
        layer.rectOf(disc, out)
        val visible = out.width() / (1f + 2f * HeylanaTokens.DISC_BLEED_RATIO)
        out.inset((out.width() - visible) / 2f, (out.height() - visible) / 2f)
    }

    private fun contentAlpha(value: Float) {
        for (i in 0 until childCount) getChildAt(i).alpha = value
    }

    private fun panelRadius() = HeylanaTokens.dp(context, GlassSpec.PANEL.radiusDp)

    /**
     * The box grows out of the disc as one blob, then separates. Returns false where
     * there is no goo (below API 31), so the caller does its plain scale instead.
     */
    fun gooeyGrowFrom(disc: View, onDone: () -> Unit = {}): Boolean {
        val layer = gooey ?: return false
        if (!canGoo) return false
        gooeyRun?.cancel()
        layer.clear()
        discIn(layer, disc, gooDisc)
        bodyIn(layer, gooTo)
        gooA[0] = gooDisc.left; gooA[1] = gooDisc.top; gooA[2] = gooDisc.right; gooA[3] = gooDisc.bottom
        gooA[4] = gooDisc.width() / 2f
        setBlob(layer, 0, gooA)
        (background as? GlassDrawable)?.hidden = true
        contentAlpha(0f)
        gooeyRun = layer.spring({ t ->
            GooeySpec.growFromDisc(gooDisc, gooTo, panelRadius(), t, gooB)
            setBlob(layer, 1, gooB)
            contentAlpha(((t - 0.6f) / 0.4f).coerceIn(0f, 1f))
        }) {
            (background as? GlassDrawable)?.hidden = false
            contentAlpha(1f)
            layer.fadeAway()
            onDone()
        }
        return true
    }

    /** After a merge that ended with the pane gone: the glass and the words are put back for next time. */
    fun gooeyReset() {
        (background as? GlassDrawable)?.hidden = false
        contentAlpha(1f)
    }

    /** The box draws back into the disc as one blob. False where there is no goo. */
    fun gooeyShrinkInto(disc: View, onDone: () -> Unit): Boolean {
        val layer = gooey ?: return false
        if (!canGoo) return false
        gooeyRun?.cancel()
        layer.clear()
        discIn(layer, disc, gooDisc)
        bodyIn(layer, gooTo)
        gooA[0] = gooDisc.left; gooA[1] = gooDisc.top; gooA[2] = gooDisc.right; gooA[3] = gooDisc.bottom
        gooA[4] = gooDisc.width() / 2f
        setBlob(layer, 0, gooA)
        (background as? GlassDrawable)?.hidden = true
        var tookOff = false
        val takeOff = {
            if (!tookOff) {
                tookOff = true
                // Gone at once as the disc takes off: a fading silhouette left behind at
                // the top reads as a second disc while the real one flies home.
                gooeyRun?.cancel()
                layer.animate().cancel()
                layer.clear()
                layer.visibility = View.GONE
                onDone()
            }
        }
        gooeyRun = layer.spring({ t ->
            GooeySpec.growFromDisc(gooDisc, gooTo, panelRadius(), 1f - t, gooB)
            setBlob(layer, 1, gooB)
            contentAlpha((1f - t / 0.4f).coerceIn(0f, 1f))
            // The disc leaves as the box is all but in, not after the spring's long tail:
            // a pause at the top between the two read as a second movement.
            if (t >= TAKE_OFF_AT) post { takeOff() }
        }) { takeOff() }
        return true
    }

    /**
     * Box to strip, strip to HUD: the body springs to its new height and a droplet left
     * at the old bottom pinches back into it. The target height is measured up front.
     */
    private fun gooeyMorph(next: Shape) {
        val layer = gooey ?: return
        if (!canGoo || visibility != View.VISIBLE) return
        gooeyRun?.cancel()
        layer.clear()
        bodyIn(layer, gooFrom)
        val targetHeight = measuredHeightFor(next) - 2 * shadowPad
        gooTo.set(gooFrom.left, gooFrom.top, gooFrom.right, gooFrom.top + targetHeight)
        (background as? GlassDrawable)?.hidden = true
        gooeyRun = layer.spring({ t ->
            GooeySpec.pinch(gooFrom, gooTo, panelRadius(), t, gooA, gooB)
            setBlob(layer, 0, gooA)
            setBlob(layer, 1, gooB)
        }) {
            (background as? GlassDrawable)?.hidden = false
            layer.fadeAway()
        }
    }

    /** How tall this pane will be in [next], measured with that shape's rows, then put back. */
    private fun measuredHeightFor(next: Shape): Int {
        val saved = listOf(inputRow.visibility, sessionRow.visibility, rail.visibility)
        inputRow.visibility = if (next == Shape.BOX && !isVoiceMode) View.VISIBLE else View.GONE
        sessionRow.visibility = if (next == Shape.HUD) View.VISIBLE else View.GONE
        rail.visibility = if (next == Shape.HUD) View.VISIBLE else View.GONE
        measure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
        val h = measuredHeight
        inputRow.visibility = saved[0]
        sessionRow.visibility = saved[1]
        rail.visibility = saved[2]
        return h
    }

    private val TAKE_OFF_AT = 0.9f

    /** The step chip and next/done come out of the pane's bottom edge as droplets. */
    private fun gooeyEmergeChips(): Boolean {
        val layer = gooey ?: return false
        if (!canGoo) return false
        gooeyRun?.cancel()
        layer.clear()
        bodyIn(layer, gooFrom)
        gooA[0] = gooFrom.left; gooA[1] = gooFrom.top; gooA[2] = gooFrom.right; gooA[3] = gooFrom.bottom
        gooA[4] = panelRadius()
        setBlob(layer, 0, gooA)
        val chips = listOf(stepChip, next, done)
        val rects = chips.map { chip -> android.graphics.RectF().also { layer.rectOf(chip, it) } }
        chips.forEach { chip ->
            (chip.background as? GlassDrawable)?.hidden = true
            chip.alpha = 0f
        }
        val edge = gooFrom.bottom
        gooeyRun = layer.spring({ t ->
            rects.forEachIndexed { i, rect ->
                GooeySpec.emerge(edge, rect, rect.height() / 2f, t, gooB)
                setBlob(layer, i + 1, gooB)
            }
            chips.forEach { it.alpha = ((t - 0.5f) / 0.5f).coerceIn(0f, 1f) }
        }) {
            chips.forEach { chip ->
                (chip.background as? GlassDrawable)?.hidden = false
                chip.alpha = 1f
            }
            layer.fadeAway()
        }
        return true
    }

    private fun growFromEdge(view: View, pivot: Float) {
        view.pivotX = view.width * pivot
        view.pivotY = view.height / 2f
        view.scaleX = 0f
        SpringAnimation(view, DynamicAnimation.SCALE_X).apply {
            spring = SpringForce(1f).apply {
                stiffness = HeylanaTokens.SPRING_STIFFNESS
                dampingRatio = HeylanaTokens.SPRING_DAMPING
            }
            start()
        }
    }

    fun setMuted(muted: Boolean) {
        mute.muted = muted
        mute.contentDescription = if (muted) UNMUTE_LABEL else MUTE_LABEL
    }

    fun focusInput() {
        input.requestFocus()
        // The insets controller is Android 11 and up; before that, and whenever the window
        // has none, the input method manager is the only way to ask for the keyboard.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val controller = input.windowInsetsController
            if (controller != null) {
                controller.show(WindowInsets.Type.ime())
                return
            }
        }
        context.getSystemService(InputMethodManager::class.java)?.showSoftInput(input, 0)
    }

    /** Opens the box for dictation without shoving the keyboard in the way. */
    fun hideKeyboard() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val controller = input.windowInsetsController
            if (controller != null) {
                controller.hide(WindowInsets.Type.ime())
                return
            }
        }
        context.getSystemService(InputMethodManager::class.java)
            ?.hideSoftInputFromWindow(windowToken, 0)
    }

    fun releaseInput() {
        hideKeyboard()
        input.clearFocus()
    }

    private fun dp(value: Float): Int = HeylanaTokens.dpInt(context, value)

    companion object {
        private const val PLACEHOLDER = "ask about this screen"
        private const val SIMULATING = "checking with the network…"
        private const val SIMULATION_PASSED = "✓ Simulation passed"

        /** How far a pill that cannot be tapped yet is faded. */
        private const val DISABLED_ALPHA = 0.4f
        private const val THINKING = "thinking…"
        private const val LISTENING = "listening…"
        private const val MAX_ANSWER_LINES = 8

        /** The strip shows three lines and then scrolls. */
        private const val STRIP_LINES = 6
        private const val MUTE_LABEL = "Mute Heylana's voice"
        private const val UNMUTE_LABEL = "Unmute Heylana's voice"
    }
}
