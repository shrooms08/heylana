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

    /** Called when the user taps the question field, so the window can take focus. */
    var onInputTapped: (() -> Unit)? = null

    /** Called with the new preference when the show-text chip is tapped. */
    var onShowTextToggled: ((Boolean) -> Unit)? = null

    private val answer = TextView(context)
    private val note = TextView(context)
    private val input = EditText(context)
    private val ask = TextView(context)
    private val mute = MuteToggleView(context)

    private val capsule = AuroraCapsuleView(context)
    private val showText = TextView(context)
    private val inputRow = LinearLayout(context)

    private val sessionRow = LinearLayout(context)
    private val stepChip = TextView(context)
    private val next = TextView(context)
    private val done = TextView(context)

    /** The width the box takes when it rides beside the buddy as a task HUD. */
    val hudWidth = dp(264f)

    private var blurBehind = false

    init {
        orientation = VERTICAL
        val pad = dp(HeylanaTokens.SPACE_4_DP)
        setPadding(pad, pad, pad, pad)

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
        // Only offered when the answer is being spoken, since typing already
        // shows the text.
        stylePill(showText, SHOW_TEXT, HeylanaTokens.textSecondary) {
            voiceShowsText = !voiceShowsText
            applyVoiceVisibility()
            onShowTextToggled?.invoke(voiceShowsText)
        }
        showText.visibility = View.GONE
        topRow.addView(
            showText,
            LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT)
        )
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

        // ------------------------------------------------- the thinking capsule
        capsule.visibility = View.GONE
        addView(
            capsule,
            LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                topMargin = dp(HeylanaTokens.SPACE_2_DP)
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
    }

    /**
     * Rebuilds every glass surface for whether the window really got its blur.
     * Without blur the fill has to be heavier, or the box reads as a grey smear.
     */
    fun applyGlass(blurBehind: Boolean) {
        this.blurBehind = blurBehind
        background = GlassDrawable(
            context, HeylanaTokens.RADIUS_CARD_DP, blurBehind, GlassDrawable.Kind.PANEL
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
        invalidate()
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
        showText.visibility = if (isVoiceMode) View.VISIBLE else View.GONE
        showText.text = if (voiceShowsText) HIDE_TEXT else SHOW_TEXT
        if (isVoiceMode && !voiceShowsText && capsule.visibility != View.VISIBLE) {
            answer.visibility = View.GONE
        } else if (isVoiceMode && voiceShowsText) {
            answer.visibility = if (answer.text.isNullOrBlank()) View.GONE else View.VISIBLE
        }
    }

    /** The aurora capsule, shown while a spoken question is being worked on. */
    fun showThinkingCapsule() {
        answer.visibility = View.GONE
        capsule.visibility = View.VISIBLE
        enable(false)
    }

    private fun hideCapsule() {
        capsule.visibility = View.GONE
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
        if (isVoiceMode) {
            showThinkingCapsule()
            return
        }
        say(THINKING)
        enable(false)
    }

    fun showAnswer(text: String) {
        hideCapsule()
        say(text)
        enable(true)
        input.setText("")
    }

    /** A notice or error — keeps whatever the user typed so they can retry. */
    fun showNotice(text: String) {
        hideCapsule()
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

    /** Shows the step counter and the next and done buttons for a running task. */
    fun showSession(stepNumber: Int) {
        stepChip.text = "step $stepNumber"
        sessionRow.visibility = View.VISIBLE
    }

    fun hideSession() {
        sessionRow.visibility = View.GONE
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
        private const val SHOW_TEXT = "show text"
        private const val HIDE_TEXT = "hide text"
        private const val MUTE_LABEL = "Mute Heylana's voice"
        private const val UNMUTE_LABEL = "Unmute Heylana's voice"
    }
}
