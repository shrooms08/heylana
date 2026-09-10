package xyz.heylana.app.overlay

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.text.method.ScrollingMovementMethod
import android.util.TypedValue
import android.view.Gravity
import android.view.WindowInsets
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView

/**
 * The little chat card that opens beside the buddy: one line in, one answer out.
 */
@SuppressLint("ViewConstructor")
class ChatPanelView(context: Context) : LinearLayout(context) {

    /** Called with the trimmed question when the user hits Send. */
    var onSend: ((String) -> Unit)? = null

    private val answer = TextView(context)
    private val input = EditText(context)
    private val send = Button(context)

    val panelWidth = dp(248)

    init {
        orientation = VERTICAL
        setPadding(dp(12), dp(12), dp(12), dp(12))
        background = card(Color.WHITE, Color.parseColor("#4C1D95"), dp(14).toFloat(), dp(2))
        elevation = dp(6).toFloat()

        answer.apply {
            text = IDLE_HINT
            setTextColor(Color.parseColor("#2E1065"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            maxLines = MAX_ANSWER_LINES
            movementMethod = ScrollingMovementMethod()
            setLineSpacing(dp(2).toFloat(), 1f)
        }
        addView(answer, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

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
            background = card(Color.parseColor("#F3F0FA"), Color.parseColor("#D6CCEF"), dp(10).toFloat(), dp(1))
            setPadding(dp(10), dp(8), dp(10), dp(8))
            setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_SEND) {
                    submit()
                    true
                } else {
                    false
                }
            }
        }
        row.addView(input, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))

        send.apply {
            text = "Send"
            isAllCaps = false
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            background = card(Color.parseColor("#7C3AED"), Color.parseColor("#4C1D95"), dp(10).toFloat(), dp(1))
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
    }

    fun showAnswer(text: String) {
        answer.text = text
        answer.scrollTo(0, 0)
        send.isEnabled = true
        input.isEnabled = true
        input.setText("")
    }

    /** A notice or error — keeps whatever the user typed so they can retry. */
    fun showNotice(text: String) {
        answer.text = text
        answer.scrollTo(0, 0)
        send.isEnabled = true
        input.isEnabled = true
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

    fun releaseInput() {
        context.getSystemService(InputMethodManager::class.java)
            ?.hideSoftInputFromWindow(windowToken, 0)
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
    }
}
