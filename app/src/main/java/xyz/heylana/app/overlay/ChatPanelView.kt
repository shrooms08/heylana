package xyz.heylana.app.overlay

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
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
import xyz.heylana.app.R
import xyz.heylana.app.ui.GlassDrawable
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
class ChatPanelView(context: Context) : LinearLayout(context) {

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

    private val confirmRow = LinearLayout(context)
    private val confirm = TextView(context)
    private val cancel = TextView(context)

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
        // A blur mask needs software rendering.
        setLayerType(LAYER_TYPE_SOFTWARE, null)

        // ---------------------------------------------------- answer + mute
        // Packed to the end so the speaker stays in the corner even with no answer.
        val topRow = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.END
        }

        answer.apply {
            visibility = View.GONE
            setTextColor(HeylanaTokens.textPrimary)
            typeface = HeylanaTokens.typeface(context, HeylanaTokens.WEIGHT_LIGHT)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, HeylanaTokens.BODY_SP)
            maxLines = MAX_ANSWER_LINES
            movementMethod = ScrollingMovementMethod()
            setLineSpacing(HeylanaTokens.dp(context, HeylanaTokens.SPACE_1_DP), 1f)
        }
        topRow.addView(answer, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))

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

        // ------------------------------------------------------- task strip
        sessionRow.apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            visibility = View.GONE
        }
        styleLabel(stepChip, HeylanaTokens.textSecondary)
        stepChip.setPadding(
            dp(HeylanaTokens.SPACE_3_DP), dp(HeylanaTokens.SPACE_1_DP),
            dp(HeylanaTokens.SPACE_3_DP), dp(HeylanaTokens.SPACE_1_DP)
        )
        sessionRow.addView(stepChip, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
        sessionRow.addView(View(context), LayoutParams(0, 1, 1f))

        stylePill(next, "next", HeylanaTokens.textPrimary) { onNext?.invoke() }
        sessionRow.addView(next, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))

        stylePill(done, "done", HeylanaTokens.textSecondary) { onDone?.invoke() }
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
        stylePill(cancel, "cancel", HeylanaTokens.textSecondary) { onCancel?.invoke() }
        confirmRow.addView(cancel, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
        stylePill(confirm, "confirm", HeylanaTokens.textPrimary) { onConfirm?.invoke() }
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
            setHintTextColor(HeylanaTokens.textSecondary)
            setTextColor(HeylanaTokens.textPrimary)
            typeface = HeylanaTokens.typeface(context, HeylanaTokens.WEIGHT_LIGHT)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, HeylanaTokens.BODY_SP)
            inputType = InputType.TYPE_CLASS_TEXT
            isSingleLine = true
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

        stylePill(ask, "ask", HeylanaTokens.textPrimary) { submit() }
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
            setTextColor(HeylanaTokens.textSecondary)
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
    fun applyGlass(blurBehind: Boolean) {
        this.blurBehind = blurBehind
        background = GlassDrawable(
            context, HeylanaTokens.RADIUS_CARD_DP, blurBehind, GlassDrawable.Kind.PANEL,
            withShadow = true
        )
        // The field is a lighter sheet sunk into the panel, never a darker hole.
        input.background = GlassDrawable(
            context, HeylanaTokens.RADIUS_MD_DP, blurBehind, GlassDrawable.Kind.INPUT
        )
        stepChip.background = GlassDrawable(
            context, HeylanaTokens.RADIUS_FULL_DP, blurBehind, GlassDrawable.Kind.PILL
        )
        ask.background = GlassDrawable(
            context, HeylanaTokens.RADIUS_FULL_DP, blurBehind, GlassDrawable.Kind.PILL,
            HeylanaTokens.bandPrimary
        )
        next.background = GlassDrawable(
            context, HeylanaTokens.RADIUS_FULL_DP, blurBehind, GlassDrawable.Kind.PILL,
            HeylanaTokens.bandPrimary
        )
        done.background = GlassDrawable(
            context, HeylanaTokens.RADIUS_FULL_DP, blurBehind, GlassDrawable.Kind.PILL,
            HeylanaTokens.purpleBand
        )
        confirm.background = GlassDrawable(
            context, HeylanaTokens.RADIUS_FULL_DP, blurBehind, GlassDrawable.Kind.PILL,
            HeylanaTokens.bandPrimary
        )
        cancel.background = GlassDrawable(
            context, HeylanaTokens.RADIUS_FULL_DP, blurBehind, GlassDrawable.Kind.PILL,
            HeylanaTokens.purpleBand
        )
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

    private fun styleLabel(view: TextView, colour: Int) {
        view.setTextColor(colour)
        view.typeface = HeylanaTokens.typeface(context, HeylanaTokens.WEIGHT_MEDIUM)
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, HeylanaTokens.LABEL_SP)
        view.letterSpacing = HeylanaTokens.LABEL_TRACKING_EM
    }

    private fun stylePill(view: TextView, text: String, colour: Int, onTap: () -> Unit) {
        view.text = text
        styleLabel(view, colour)
        view.gravity = Gravity.CENTER
        view.setPadding(
            dp(HeylanaTokens.SPACE_3_DP), dp(HeylanaTokens.SPACE_2_DP),
            dp(HeylanaTokens.SPACE_3_DP), dp(HeylanaTokens.SPACE_2_DP)
        )
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
        answer.text = text
        val wanted = text.isNotBlank() && (!isVoiceMode || voiceShowsText)
        answer.visibility = if (wanted) View.VISIBLE else View.GONE
        answer.scrollTo(0, 0)
    }

    fun showThinking() {
        // A spoken question has its own capsule beside the disc; the box shows
        // nothing at all until there is an answer to read.
        if (isVoiceMode) return
        say(THINKING)
        enable(false)
    }

    fun showAnswer(text: String) {
        say(text)
        enable(true)
        input.setText("")
    }

    /** A notice or error — keeps whatever the user typed so they can retry. */
    fun showNotice(text: String) {
        // A problem is always worth reading, whatever the user asked for.
        answer.text = text
        answer.visibility = if (text.isBlank()) View.GONE else View.VISIBLE
        answer.scrollTo(0, 0)
        enable(true)
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

    /** Shows the step counter, the rail and the buttons for a running task. */
    fun showSession(stepNumber: Int, ofSteps: Int) {
        val firstStep = shape != Shape.HUD
        stepChip.text = context.getString(R.string.step_label, stepNumber)
        rail.progress = if (ofSteps > 0) stepNumber.toFloat() / ofSteps else 0f
        morphTo(Shape.HUD) {
            if (firstStep) growEdges()
        }
    }

    fun hideSession() {
        morphTo(Shape.STRIP)
    }

    /** Shows confirm and cancel under whatever the answer line says. */
    fun showConfirm() {
        confirmRow.visibility = View.VISIBLE
        enable(true)
    }

    fun hideConfirm() {
        confirmRow.visibility = View.GONE
    }

    /**
     * The chip comes out of the left edge and the buttons out of the right, so
     * the HUD grows its controls rather than having them appear on top of it.
     */
    private fun growEdges() {
        growFromEdge(stepChip, pivot = 0f)
        for (pill in listOf(next, done)) growFromEdge(pill, pivot = 1f)
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
        val controller = input.windowInsetsController
        if (controller != null) {
            controller.show(WindowInsets.Type.ime())
        } else {
            context.getSystemService(InputMethodManager::class.java)?.showSoftInput(input, 0)
        }
    }

    /** Opens the box for dictation without shoving the keyboard in the way. */
    fun hideKeyboard() {
        val controller = input.windowInsetsController
        if (controller != null) {
            controller.hide(WindowInsets.Type.ime())
        } else {
            context.getSystemService(InputMethodManager::class.java)
                ?.hideSoftInputFromWindow(windowToken, 0)
        }
    }

    fun releaseInput() {
        hideKeyboard()
        input.clearFocus()
    }

    private fun dp(value: Float): Int = HeylanaTokens.dpInt(context, value)

    companion object {
        private const val PLACEHOLDER = "ask about this screen"
        private const val THINKING = "thinking…"
        private const val LISTENING = "listening…"
        private const val MAX_ANSWER_LINES = 8

        /** The strip shows three lines and then scrolls. */
        private const val STRIP_LINES = 3
        private const val MUTE_LABEL = "Mute Heylana's voice"
        private const val UNMUTE_LABEL = "Unmute Heylana's voice"
    }
}
