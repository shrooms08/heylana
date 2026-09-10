package xyz.heylana.app.overlay

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.text.method.ScrollingMovementMethod
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView

/**
 * A speaker glyph that doubles as the mute switch — drawn in code, no assets.
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
        color = Color.parseColor("#4C1D95")
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#4C1D95")
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

        // Speaker body: a small box opening into a cone to the right.
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
 * The little chat card that opens beside the buddy: one line in, one answer out,
 * plus the switch that silences Heylana's voice.
 */
@SuppressLint("ViewConstructor")
class ChatPanelView(context: Context) : LinearLayout(context) {

    /** Called with the trimmed question when the user hits Send. */
    var onSend: ((String) -> Unit)? = null

    /** Called with the new muted state when the speaker glyph is tapped. */
    var onMuteToggled: ((Boolean) -> Unit)? = null

    /** Called when the user asks for the next step of a task. */
    var onNext: (() -> Unit)? = null

    /** Called when the user ends a task early. */
    var onDone: (() -> Unit)? = null

    /** Called when the user taps the question field, so the window can take focus. */
    var onInputTapped: (() -> Unit)? = null

    private val answer = TextView(context)
    private val note = TextView(context)
    private val input = EditText(context)
    private val send = Button(context)
    private val mute = MuteToggleView(context)

    private val sessionRow = LinearLayout(context)
    private val stepChip = TextView(context)
    private val next = Button(context)
    private val done = Button(context)

    val panelWidth = dp(248)

    init {
        orientation = VERTICAL
        setPadding(dp(12), dp(12), dp(12), dp(12))
        background = card(Color.WHITE, Color.parseColor("#4C1D95"), dp(14).toFloat(), dp(2))
        elevation = dp(6).toFloat()

        val topRow = LinearLayout(context).apply { orientation = HORIZONTAL }

        answer.apply {
            text = IDLE_HINT
            setTextColor(Color.parseColor("#2E1065"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            maxLines = MAX_ANSWER_LINES
            movementMethod = ScrollingMovementMethod()
            setLineSpacing(dp(2).toFloat(), 1f)
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
            LayoutParams(dp(26), dp(26)).apply { marginStart = dp(8) }
        )

        addView(topRow, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        sessionRow.apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            visibility = View.GONE
        }

        stepChip.apply {
            setTextColor(Color.parseColor("#4C1D95"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            background = card(
                Color.parseColor("#EDE7FB"),
                Color.parseColor("#C4B5F0"),
                dp(9).toFloat(),
                dp(1)
            )
            setPadding(dp(8), dp(3), dp(8), dp(3))
        }
        sessionRow.addView(
            stepChip,
            LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT)
        )
        sessionRow.addView(View(context), LayoutParams(0, 1, 1f))

        next.apply {
            text = "Next"
            isAllCaps = false
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            background = card(
                Color.parseColor("#7C3AED"),
                Color.parseColor("#4C1D95"),
                dp(9).toFloat(),
                dp(1)
            )
            minWidth = 0
            minimumWidth = 0
            setPadding(dp(10), dp(4), dp(10), dp(4))
            stateListAnimator = null
            setOnClickListener { onNext?.invoke() }
        }
        sessionRow.addView(
            next,
            LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT)
        )

        done.apply {
            text = "Done"
            isAllCaps = false
            setTextColor(Color.parseColor("#4C1D95"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            background = card(
                Color.WHITE,
                Color.parseColor("#4C1D95"),
                dp(9).toFloat(),
                dp(1)
            )
            minWidth = 0
            minimumWidth = 0
            setPadding(dp(10), dp(4), dp(10), dp(4))
            stateListAnimator = null
            setOnClickListener { onDone?.invoke() }
        }
        sessionRow.addView(
            done,
            LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
                marginStart = dp(6)
            }
        )

        addView(
            sessionRow,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(10)
            }
        )

        val row = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        input.apply {
            hint = "Ask about this screen"
            setHintTextColor(Color.parseColor("#8B7FA8"))
            setTextColor(Color.parseColor("#2E1065"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            inputType = InputType.TYPE_CLASS_TEXT
            isSingleLine = true
            imeOptions = EditorInfo.IME_ACTION_SEND
            background = card(
                Color.parseColor("#F3F0FA"),
                Color.parseColor("#D6CCEF"),
                dp(10).toFloat(),
                dp(1)
            )
            setPadding(dp(10), dp(8), dp(10), dp(8))
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

        send.apply {
            text = "Send"
            isAllCaps = false
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            background = card(
                Color.parseColor("#7C3AED"),
                Color.parseColor("#4C1D95"),
                dp(10).toFloat(),
                dp(1)
            )
            minWidth = dp(64)
            minimumWidth = dp(64)
            setPadding(dp(12), dp(6), dp(12), dp(6))
            stateListAnimator = null
            setOnClickListener { submit() }
        }
        row.addView(
            send,
            LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
                marginStart = dp(8)
            }
        )

        addView(
            row,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(10)
            }
        )

        note.apply {
            visibility = View.GONE
            setTextColor(Color.parseColor("#8B7FA8"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
        }
        addView(
            note,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(6)
            }
        )
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(
            MeasureSpec.makeMeasureSpec(panelWidth, MeasureSpec.EXACTLY),
            heightMeasureSpec
        )
    }

    private fun submit() {
        val question = input.text.toString().trim()
        if (question.isEmpty()) return
        onSend?.invoke(question)
    }

    fun showThinking() {
        answer.text = "thinking…"
        send.isEnabled = false
        input.isEnabled = false
        next.isEnabled = false
    }

    fun showAnswer(text: String) {
        answer.text = text
        answer.scrollTo(0, 0)
        send.isEnabled = true
        input.isEnabled = true
        next.isEnabled = true
        input.setText("")
    }

    /** A notice or error — keeps whatever the user typed so they can retry. */
    fun showNotice(text: String) {
        answer.text = text
        answer.scrollTo(0, 0)
        send.isEnabled = true
        input.isEnabled = true
        next.isEnabled = true
    }

    /** Shows the step counter and the Next / Done buttons for a running task. */
    fun showSession(stepNumber: Int) {
        stepChip.text = "step $stepNumber"
        sessionRow.visibility = View.VISIBLE
    }

    fun hideSession() {
        sessionRow.visibility = View.GONE
    }

    /** Microphone open: the field fills in live as words are recognised. */
    fun showListening() {
        answer.text = "listening…"
        answer.scrollTo(0, 0)
        input.isEnabled = true
        send.isEnabled = true
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

    /** Opens the panel for dictation without shoving the keyboard in the way. */
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

    private fun card(fill: Int, border: Int, radius: Float, strokeWidth: Int): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(fill)
            cornerRadius = radius
            setStroke(strokeWidth, border)
        }

    private fun dp(value: Int): Int = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), resources.displayMetrics
    ).toInt()

    companion object {
        const val IDLE_HINT = "Ask me about this screen."
        private const val MAX_ANSWER_LINES = 8
        private const val MUTE_LABEL = "Mute Heylana's voice"
        private const val UNMUTE_LABEL = "Unmute Heylana's voice"
    }
}
