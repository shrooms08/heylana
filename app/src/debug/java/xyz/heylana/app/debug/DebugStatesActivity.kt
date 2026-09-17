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
    private lateinit var sprite: DiscPair
    private lateinit var discRow: LinearLayout
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

        // Both sizes side by side, driven together: docked (64dp) and open (80dp).
        val docked = BuddySpriteView(this).apply { discDp = HeylanaTokens.DISC_DP }
        val open = BuddySpriteView(this).apply { discDp = HeylanaTokens.DISC_OPEN_DP }
        sprite = DiscPair(docked, open)
        discRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val dockedView = dp(HeylanaTokens.discViewDp(HeylanaTokens.DISC_DP))
            val openView = dp(HeylanaTokens.discViewDp(HeylanaTokens.DISC_OPEN_DP))
            addView(docked, LinearLayout.LayoutParams(dockedView, dockedView))
            addView(open, LinearLayout.LayoutParams(openView, openView))
        }
        stage.addView(
            discRow,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                // Clear of the status bar, so both discs are whole.
                topMargin = dp(HeylanaTokens.SPACE_6_DP)
            }
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
            ).apply {
                gravity = Gravity.TOP
                topMargin = dp(180f)
                // The same side gutters the real box has, so width reads true here.
                leftMargin = dp(HeylanaTokens.SPACE_5_DP)
                rightMargin = dp(HeylanaTokens.SPACE_5_DP)
            }
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
        } + button("backdrop") { onBlack = !onBlack; applyBackdrop() } +
            button("darker glass") {
                // A preview only: the real setting is in Settings.
                xyz.heylana.app.ui.GlassSpec.darkerGlass = !xyz.heylana.app.ui.GlassSpec.darkerGlass
                caption.text = "darker glass ${if (xyz.heylana.app.ui.GlassSpec.darkerGlass) "on" else "off"}"
                stage.invalidate()
                invalidateAll(stage)
            }

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
        intent.getStringExtra(EXTRA_DARKER)?.let { xyz.heylana.app.ui.GlassSpec.darkerGlass = it == "on" }
        applyBackdrop()
        val wanted = intent.getStringExtra(EXTRA_STATE)
        val chosen = states().firstOrNull { it.first == wanted } ?: states().first()
        caption.text = chosen.second
        stage.post { chosen.third() }
    }

    /** key (what adb passes), label (what the button says), what it does. */
    private fun states(): List<Triple<String, String, () -> Unit>> = listOf(
        Triple("idle", "idle", ::idle),
        Triple("listening", "listening", ::listening),
        Triple("thinking", "thinking", ::thinking),
        Triple("working", "working", ::working),
        Triple("speaking", "speaking", ::speaking),
        Triple("back-to-idle", "back to idle", ::backToIdle),
        Triple("motion", "fast drag", ::fastDrag),
        Triple("panel", "panel edges", ::panelEdges),
        Triple("send", "send strip", ::sendStrip),
        Triple("streak", "purple streak", ::purpleStreak),
        Triple("tapped", "tapped", ::tapped),
        Triple("typed", "typed answer", ::typedAnswer),
        Triple("voice", "voice answer", ::voiceAnswer),
        Triple("cycle", "voice full cycle", ::voiceFullCycle),
        Triple("nothing", "nothing heard", ::nothingHeard),
        Triple("error", "error", ::errorState),
        Triple("pointing", "pointing", ::pointing),
        Triple("task", "task 2 of 4", ::taskStep),
        Triple("done", "done", ::done),
        Triple("compose-left", "box from left dock", { composeFromDock(fromLeft = true) }),
        Triple("compose-right", "box from right dock", { composeFromDock(fromLeft = false) })
    )

    /**
     * The box opening with the buddy docked on one side: the disc flies to the top
     * centre and the box drops in full width beneath it, never beside it.
     */
    private fun composeFromDock(fromLeft: Boolean) {
        reset()
        val edge = (stage.width - discRow.width) / 2f
        discRow.translationX = if (fromLeft) -edge else edge
        discRow.translationY = dp(360f).toFloat()
        discRow.animate()
            .translationX(0f)
            .translationY(0f)
            .setDuration(HeylanaTokens.FADE_MS)
            .withEndAction {
                tapped()
                panel.showAnswer(LONG_ANSWER)
            }
            .start()
    }

    // ------------------------------------------------------------- states

    private fun idle() {
        reset()
        sprite.expression = BuddySpriteView.Expression.IDLE
        sprite.refreshState()
    }

    /** The mark dissolves into the listening orb, and the ring breathes with a made-up voice. */
    private fun listening() {
        reset()
        sprite.expression = BuddySpriteView.Expression.LISTENING
        pulse { level -> sprite.micLevel = level }
    }

    private fun working() {
        reset()
        sprite.expression = BuddySpriteView.Expression.WORKING
    }

    /** The speaking orb, driven by a made-up playback level. */
    private fun speaking() {
        reset()
        sprite.talking = true
        pulse { level -> sprite.playbackLevel = level }
    }

    /** Speaking for two seconds, then idle: the orb reassembles into the mark. */
    private fun backToIdle() {
        speaking()
        main.postDelayed({
            caption.text = "back to idle · reassembling"
            main.removeCallbacksAndMessages(null)
            sprite.playbackLevel = 0f
            sprite.talking = false
        }, BACK_TO_IDLE_MS)
    }

    /**
     * The discs sweep across the stage and back, fast, as a quick drag would, and are
     * told their speed each frame: the mark's colours split along the motion and
     * settle when they stop.
     */
    private fun fastDrag() {
        reset()
        val travel = (stage.width - discRow.width) / 2f
        var lastX = 0f
        var lastTime = System.nanoTime()
        discRow.animate()
            .translationX(-travel)
            .setDuration(DRAG_LEG_MS)
            .setUpdateListener {
                val now = System.nanoTime()
                val seconds = (now - lastTime) / 1e9f
                if (seconds > 0f) sprite.setMotion((discRow.translationX - lastX) / seconds, 0f)
                lastX = discRow.translationX
                lastTime = now
            }
            .withEndAction {
                discRow.animate().translationX(0f).setDuration(DRAG_LEG_MS).withEndAction {
                    discRow.animate().setUpdateListener(null)
                }.start()
            }
            .start()
    }

    /**
     * The box with an answer and the task pills, still, so its liquid edges — the lit
     * rim, the top band, the colour fringe — can be looked at over black and white.
     */
    private fun panelEdges() {
        tapped()
        panel.showAnswer(LONG_ANSWER)
        panel.showSession(2, 4)
    }

    /** The send confirmation strip, as the service shows it: the words, then cancel and confirm. */
    private fun sendStrip() {
        reset()
        panel.visibility = View.VISIBLE
        panel.showNotice(SEND_STRIP)
        panel.morphTo(ChatPanelView.Shape.STRIP)
        panel.showConfirm()
    }

    /**
     * The box open with an answer, the disc beside it in its open look: the purple light
     * streak loops behind both glasses, a 6 second pass, over black and white.
     */
    private fun purpleStreak() {
        tapped()
        panel.showAnswer(LONG_ANSWER)
    }

    /** A level that rises and falls like a voice, every frame until the next state. */
    private fun pulse(apply: (Float) -> Unit) {
        val started = System.currentTimeMillis()
        val tick = object : Runnable {
            override fun run() {
                val seconds = (System.currentTimeMillis() - started) / 1000f
                val syllables = kotlin.math.abs(kotlin.math.sin(seconds * 9f)) * (0.5f + 0.5f * kotlin.math.sin(seconds * 2.3f))
                apply(syllables.coerceIn(0f, 1f))
                main.postDelayed(this, PULSE_MS)
            }
        }
        main.post(tick)
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

    /**
     * Held the buddy and said nothing: the capsule listens, turns to the aurora
     * on release, then melts — and the disc is idle. No message, nothing left.
     */
    private fun nothingHeard() {
        reset()
        panel.setVoiceMode(voice = true, showsText = false)
        capsule.visibility = View.VISIBLE
        capsule.showTranscript("")
        sprite.expression = BuddySpriteView.Expression.LISTENING
        sprite.refreshState()

        main.postDelayed({
            caption.text = "nothing heard · waiting for words"
            sprite.expression = BuddySpriteView.Expression.THINKING
            sprite.refreshState()
            capsule.showThinking()
        }, HOLD_MS)

        main.postDelayed({
            caption.text = "nothing heard · idle"
            capsule.melt { idle() }
        }, HOLD_MS + THINKING_MS)
    }

    /**
     * Something went wrong, or took too long: a short notice in the strip, and
     * the disc is idle rather than thinking.
     */
    private fun errorState() {
        reset()
        panel.visibility = View.VISIBLE
        sprite.expression = BuddySpriteView.Expression.THINKING
        sprite.refreshState()
        panel.showThinking()

        main.postDelayed({
            caption.text = "error · notice, disc idle"
            sprite.expression = BuddySpriteView.Expression.IDLE
            sprite.refreshState()
            panel.showNotice(TOOK_TOO_LONG)
            panel.morphTo(ChatPanelView.Shape.STRIP)
        }, THINKING_MS)
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
        discRow.animate().cancel()
        discRow.animate().setUpdateListener(null)
        discRow.translationX = 0f
        discRow.translationY = 0f
        sprite.talking = false
        sprite.composing = false
        sprite.pointTarget = null
        sprite.micLevel = 0f
        sprite.playbackLevel = 0f
        sprite.expression = BuddySpriteView.Expression.IDLE
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

    private fun invalidateAll(view: View) {
        view.invalidate()
        if (view is android.view.ViewGroup) for (i in 0 until view.childCount) invalidateAll(view.getChildAt(i))
    }

    private fun dp(value: Float): Int = HeylanaTokens.dpInt(this, value)

    private companion object {
        const val ANSWER = "The search bar is at the top."
        const val LONG_ANSWER =
            "Seed Vault didn't confirm the send, and I can't find it on chain. Check your wallet before trying again."
        const val HEARD = "where is the seed vault"
        val MIC_LEVELS = floatArrayOf(0.35f, 0.7f, 0.5f, 0.85f, 0.4f)
        const val HEARD_STEP_MS = 260L
        const val THINKING_MS = 1_400L
        const val SPEAKING_MS = 1_600L
        const val BACK_TO_IDLE_MS = 2_000L
        const val SEND_STRIP = "Send 0.05 USDC to 7c2y…SxSv. Confirm?"
        const val DRAG_LEG_MS = 260L
        const val PULSE_MS = 16L

        /** How long the silent hold lasts before the release. */
        const val HOLD_MS = 1_800L

        /** The notice the service shows when an exchange runs out of time. */
        const val TOOK_TOO_LONG = "That took too long, try again."

        /** The same beat BuddyOverlayService waits before it settles. */
        const val SETTLE_MS = 1_000L
        /** How many buttons fit across the screen without crowding. */
        const val PER_ROW = 3
        const val EXTRA_STATE = "state"
        const val EXTRA_BACKDROP = "backdrop"
        const val EXTRA_DARKER = "darker"
    }
}

/** The two debug discs, 64dp and 80dp, told the same thing at once. */
private class DiscPair(private val docked: BuddySpriteView, private val open: BuddySpriteView) {
    private val both = listOf(docked, open)

    var expression: BuddySpriteView.Expression
        get() = open.expression
        set(value) = both.forEach { it.expression = value }

    var talking: Boolean
        get() = open.talking
        set(value) = both.forEach { it.talking = value }

    var composing: Boolean
        get() = open.composing
        set(value) = both.forEach { it.composing = value }

    var micLevel: Float
        get() = open.micLevel
        set(value) = both.forEach { it.micLevel = value }

    var playbackLevel: Float
        get() = open.playbackLevel
        set(value) = both.forEach { it.playbackLevel = value }

    var pointTarget: PointF?
        get() = open.pointTarget
        set(value) = both.forEach { it.pointTarget = value }

    fun refreshState() = both.forEach { it.refreshState() }

    fun setMotion(vx: Float, vy: Float) = both.forEach { it.setMotion(vx, vy) }
}
