package xyz.heylana.app.debug

import android.app.Activity
import android.graphics.Color
import android.graphics.PointF
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import xyz.heylana.app.overlay.BuddySpriteView
import xyz.heylana.app.overlay.ChatPanelView
import xyz.heylana.app.overlay.VoiceCapsuleView
import xyz.heylana.app.ui.GlassDrawable
import xyz.heylana.app.ui.HeylanaTokens

/**
 * Debug-only. Drives the real views through every state Heylana can be in, with
 * a fixed answer instead of the network, so all of it can be checked without
 * spending a call.
 *
 * These are the same view classes the overlay uses, not mock-ups — what is not
 * here is the window machinery: the flight between windows, the blur behind the
 * pane and the real highlight window. Those still need the buddy running.
 */
class DebugStatesActivity : Activity() {

    private lateinit var stage: FrameLayout
    private lateinit var sprite: BuddySpriteView
    private lateinit var panel: ChatPanelView
    private lateinit var capsule: VoiceCapsuleView
    private lateinit var fakeHighlight: View
    private lateinit var caption: TextView

    private val main = Handler(Looper.getMainLooper())
    private var onBlack = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        stage = FrameLayout(this)
        root.addView(stage, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

        // The stand-in for a highlight box, so the pointing lean has something
        // to lean at.
        fakeHighlight = View(this).apply {
            visibility = View.GONE
            background = GlassDrawable(
                this@DebugStatesActivity, HeylanaTokens.RADIUS_MD_DP,
                blurBehind = false, kind = GlassDrawable.Kind.PILL,
                bandColor = HeylanaTokens.bandPrimary
            )
        }
        stage.addView(
            fakeHighlight,
            FrameLayout.LayoutParams(dp(160f), dp(56f)).apply {
                gravity = Gravity.TOP or Gravity.START
                leftMargin = dp(24f)
                topMargin = dp(320f)
            }
        )

        sprite = BuddySpriteView(this)
        stage.addView(
            sprite,
            FrameLayout.LayoutParams(
                dp(HeylanaTokens.DISC_DP + 2 * HeylanaTokens.DISC_BLEED_DP),
                dp(HeylanaTokens.DISC_DP + 2 * HeylanaTokens.DISC_BLEED_DP)
            ).apply { gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL }
        )

        capsule = VoiceCapsuleView(this).apply { visibility = View.GONE }
        stage.addView(
            capsule,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                topMargin = dp(190f)
            }
        )

        panel = ChatPanelView(this).apply {
            visibility = View.GONE
            applyGlass(blurBehind = false)
        }
        stage.addView(
            panel,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply { gravity = Gravity.TOP; topMargin = dp(180f) }
        )

        caption = TextView(this).apply {
            setBackgroundColor(Color.BLACK)
            setTextColor(HeylanaTokens.textPrimary)
            typeface = HeylanaTokens.typeface(context, HeylanaTokens.WEIGHT_MEDIUM)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, HeylanaTokens.LABEL_SP)
            setPadding(dp(16f), dp(8f), dp(16f), dp(8f))
        }
        root.addView(caption)

        // Rows rather than one long scrolling strip: a sideways swipe down here
        // fights the system's own gesture bar, and half the buttons were
        // unreachable because of it.
        val buttons = states().map { (_, label, action) ->
            button(label) { caption.text = label; action() }
        } + button("backdrop") { onBlack = !onBlack; applyBackdrop() }

        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.BLACK)
            setPadding(dp(8f), dp(4f), dp(8f), dp(28f))
        }
        for (row in buttons.chunked(PER_ROW)) {
            val line = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            row.forEach { line.addView(it) }
            bar.addView(
                line,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            )
        }
        root.addView(
            bar,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )

        setContentView(root)

        // adb can drive a single state straight away:
        //   -e state "task 2/4" -e backdrop white
        onBlack = intent.getStringExtra(EXTRA_BACKDROP) != "white"
        applyBackdrop()
        val wanted = intent.getStringExtra(EXTRA_STATE)
        val chosen = states().firstOrNull { it.first == wanted } ?: states().first()
        caption.text = chosen.second
        stage.post { chosen.third() }
    }

    /** key (what adb passes), label (what the button says), what it does. */
    private fun states(): List<Triple<String, String, () -> Unit>> = listOf(
        Triple("idle", "idle", ::idle),
        Triple("tapped", "tapped", ::tapped),
        Triple("thinking", "thinking", ::thinking),
        Triple("typed", "typed answer", ::typedAnswer),
        Triple("voice", "voice answer", ::voiceAnswer),
        Triple("cycle", "voice full cycle", ::voiceFullCycle),
        Triple("pointing", "pointing", ::pointing),
        Triple("task", "task 2 of 4", ::taskStep),
        Triple("done", "done", ::done)
    )

    // ------------------------------------------------------------- states

    private fun idle() {
        reset()
        sprite.expression = BuddySpriteView.Expression.IDLE
        sprite.refreshState()
    }

    private fun tapped() {
        reset()
        sprite.composing = true
        sprite.refreshState()
        panel.visibility = View.VISIBLE
        panel.morphTo(ChatPanelView.Shape.BOX)
        panel.sweepSheen()
    }

    private fun thinking() {
        tapped()
        sprite.expression = BuddySpriteView.Expression.THINKING
        sprite.refreshState()
        panel.showThinking()
    }

    private fun typedAnswer() {
        tapped()
        sprite.expression = BuddySpriteView.Expression.IDLE
        panel.showAnswer(ANSWER)
        panel.morphTo(ChatPanelView.Shape.STRIP)
    }

    private fun voiceAnswer() {
        reset()
        panel.setVoiceMode(voice = true, showsText = false)
        capsule.visibility = View.VISIBLE
        capsule.showThinking()
        sprite.expression = BuddySpriteView.Expression.THINKING
        sprite.refreshState()
        // Then the answer lands and it speaks.
        main.postDelayed({
            capsule.visibility = View.GONE
            sprite.expression = BuddySpriteView.Expression.IDLE
            sprite.talking = true
            sprite.refreshState()
        }, 1_600)
    }

    /**
     * The whole spoken exchange end to end, on the same clock the buddy uses:
     * the words fill in, the pill becomes the aurora, the aurora melts away as
     * the answer lands, the disc speaks, and a second later it is idle again.
     */
    private fun voiceFullCycle() {
        reset()
        panel.setVoiceMode(voice = true, showsText = false)
        capsule.visibility = View.VISIBLE
        capsule.showTranscript("")
        sprite.expression = BuddySpriteView.Expression.LISTENING
        sprite.refreshState()

        val words = HEARD.split(' ')
        for ((index, _) in words.withIndex()) {
            main.postDelayed({
                caption.text = "voice full cycle · listening"
                capsule.showTranscript(words.take(index + 1).joinToString(" "))
                sprite.micLevel = MIC_LEVELS[index % MIC_LEVELS.size]
            }, HEARD_STEP_MS * (index + 1))
        }

        val released = HEARD_STEP_MS * (words.size + 1)
        main.postDelayed({
            caption.text = "voice full cycle · thinking"
            sprite.micLevel = 0f
            sprite.expression = BuddySpriteView.Expression.THINKING
            sprite.refreshState()
            capsule.showThinking()
        }, released)

        main.postDelayed({
            caption.text = "voice full cycle · speaking"
            capsule.melt {
                sprite.expression = BuddySpriteView.Expression.IDLE
                sprite.talking = true
                sprite.refreshState()
            }
        }, released + THINKING_MS)

        // Speech ends, then the same one second beat the service waits.
        main.postDelayed({
            sprite.talking = false
            sprite.refreshState()
        }, released + THINKING_MS + SPEAKING_MS)
        main.postDelayed({
            caption.text = "voice full cycle · idle"
            idle()
        }, released + THINKING_MS + SPEAKING_MS + SETTLE_MS)
    }

    private fun pointing() {
        reset()
        fakeHighlight.visibility = View.VISIBLE
        panel.visibility = View.VISIBLE
        panel.showAnswer(ANSWER)
        panel.morphTo(ChatPanelView.Shape.STRIP)
        fakeHighlight.post {
            val spot = IntArray(2)
            fakeHighlight.getLocationOnScreen(spot)
            sprite.pointTarget = PointF(
                spot[0] + fakeHighlight.width / 2f,
                spot[1] + fakeHighlight.height / 2f
            )
            sprite.expression = BuddySpriteView.Expression.POINTING
            sprite.refreshState()
        }
    }

    private fun taskStep() {
        reset()
        panel.visibility = View.VISIBLE
        panel.showAnswer("Tap Receive to show your address.")
        panel.showSession(2, 4)
        sprite.expression = BuddySpriteView.Expression.IDLE
        sprite.refreshState()
    }

    private fun done() {
        // Starts where a task ends, so the way back to idle is what you see.
        taskStep()
        main.postDelayed({
            panel.showAnswer("That is it — the address is on screen.")
            panel.hideSession()
        }, 900)
        main.postDelayed({ idle() }, 2_200)
    }

    private fun reset() {
        main.removeCallbacksAndMessages(null)
        sprite.talking = false
        sprite.composing = false
        sprite.pointTarget = null
        sprite.micLevel = 0f
        sprite.expression = BuddySpriteView.Expression.IDLE
        sprite.refreshState()
        capsule.visibility = View.GONE
        fakeHighlight.visibility = View.GONE
        panel.setVoiceMode(voice = false, showsText = false)
        panel.visibility = View.GONE
    }

    // ------------------------------------------------------------ chrome

    private fun applyBackdrop() {
        stage.setBackgroundColor(if (onBlack) Color.BLACK else Color.WHITE)
    }

    private fun button(label: String, onTap: () -> Unit) = TextView(this).apply {
        text = label
        gravity = Gravity.CENTER
        setTextColor(HeylanaTokens.textPrimary)
        typeface = HeylanaTokens.typeface(context, HeylanaTokens.WEIGHT_MEDIUM)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, HeylanaTokens.LABEL_SP)
        background = GlassDrawable(
            this@DebugStatesActivity, HeylanaTokens.RADIUS_FULL_DP,
            blurBehind = false, kind = GlassDrawable.Kind.PILL
        )
        setPadding(dp(14f), dp(10f), dp(14f), dp(10f))
        val margin = dp(4f)
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { setMargins(margin, margin, margin, margin) }
        setOnClickListener { onTap() }
    }

    private fun dp(value: Float): Int = HeylanaTokens.dpInt(this, value)

    private companion object {
        const val ANSWER = "The search bar is at the top."
        const val HEARD = "where is the seed vault"
        val MIC_LEVELS = floatArrayOf(0.35f, 0.7f, 0.5f, 0.85f, 0.4f)
        const val HEARD_STEP_MS = 260L
        const val THINKING_MS = 1_400L
        const val SPEAKING_MS = 1_600L

        /** The same beat BuddyOverlayService waits before it settles. */
        const val SETTLE_MS = 1_000L
        /** How many buttons fit across the screen without crowding. */
        const val PER_ROW = 3
        const val EXTRA_STATE = "state"
        const val EXTRA_BACKDROP = "backdrop"
    }
}
