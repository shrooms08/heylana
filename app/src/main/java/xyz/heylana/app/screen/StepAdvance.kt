package xyz.heylana.app.screen

/**
 * When a task's step is done, decided from what the screen reports — kept away from
 * Android so every ordering can be tested with a made-up stream of events.
 *
 * A step moves on only when:
 *  - the user clicks the element it points at, or
 *  - on a step that says to type (a [typing] step), the pointed field's value — digits and
 *    all — has changed and then stayed put for [TYPED_SETTLE_MS]: Jupiter's amount is typed
 *    on its own keypad, reports no clicks, and a number changing is otherwise no change, or
 *  - the target app's content really changes: its node set differs from the step's
 *    by more than [CHANGE_THRESHOLD] (a new screen, a sheet, the app handing over to
 *    another one), measured on a fresh read — with every number taken out first, so a
 *    live price or a quote refreshing is not a change — and, unless the user has tapped
 *    something in the app since the step began, by more than [BIG_CHANGE]: an app that
 *    redraws itself while nobody touches it is not the user doing the step.
 *
 * And never:
 *  - within [QUIET_MS] of the disc landing, or of Heylana's own windows changing again
 *    (the box settling into its small form uncovers part of the app) — the target app
 *    re-lays itself out around Heylana's windows, and that is not the user doing anything;
 *    changes in that time only update what the step is compared against;
 *  - while the step's line is still being spoken — a click or change then is held and
 *    acted on the moment the line ends;
 *  - for anything from Heylana's own package, or with no package at all.
 */
class StepAdvance(
    val pointedKey: String?,
    private val targetPackage: String,
    baseline: Set<String>,
    private val landedAt: Long,
    private val ownPackage: String = OWN_PACKAGE
) {

    enum class Reason(val log: String) { CLICK("click"), CONTENT_CHANGED("content_changed"), TYPED("typed") }

    /** The step says to type into the pointed field; [value] is what it held when the step began. */
    private var typedFrom: String? = null
    private var typing = false
    private var typedAt = 0L

    fun expectTyping(value: String?) {
        typing = true
        typedFrom = value
    }

    /**
     * A fresh read of the pointed field during a typing step. A new value starts the settle
     * clock again; a value back to where it began stops it. Returns how long to wait before
     * [tick] should look again, or null when nothing is pending.
     */
    fun onValue(now: Long, value: String?): Long? {
        if (!typing || done) return null
        if (value == null || value == typedFrom) {
            typedAt = 0L
            return null
        }
        if (value != lastValue) typedAt = now
        lastValue = value
        return TYPED_SETTLE_MS - (now - typedAt)
    }

    private var lastValue: String? = null

    sealed interface Decision {
        data object Wait : Decision
        data object Ignore : Decision
        data class Advance(val reason: Reason) : Decision
    }

    private var baseline: Set<String> = baseline
    private var held: Reason? = null
    private var done = false
    /** The user has tapped something in the target app since the step began. */
    private var acted = false

    /** The last fresh read's difference from the step's screen, for the log. */
    var lastDifference: Double = 0.0
        private set

    /** True when a tap or a change is waiting for the quiet time or the line to end. */
    val holding: Boolean get() = held != null && !done

    /** When the quiet time last began: the landing, or Heylana's windows last changing. */
    private var quietFrom = landedAt

    /** How long until the quiet time is over. */
    fun quietLeft(now: Long): Long = (QUIET_MS - (now - quietFrom)).coerceAtLeast(0)

    /** Heylana's own windows changed (the box settled, the window moved): a fresh quiet time. */
    fun ownWindowsChanged(now: Long) {
        quietFrom = maxOf(quietFrom, now)
    }

    /** True while the step's line is being spoken. */
    var speaking: Boolean = false
        private set

    fun speechStarted() {
        speaking = true
    }

    /** The line has ended: anything held is acted on now, if the quiet time is over too. */
    fun speechEnded(now: Long): Decision {
        speaking = false
        return release(now)
    }

    /** Time has passed with nothing new: the quiet time may be over, with something held. */
    fun tick(now: Long): Decision {
        if (typing && typedAt > 0 && now - typedAt >= TYPED_SETTLE_MS) return hold(now, Reason.TYPED)
        return release(now)
    }

    fun onClick(now: Long, key: String?, packageName: String?): Decision {
        if (done) return Decision.Ignore
        if (packageName == null || packageName == ownPackage) return Decision.Ignore
        acted = true
        if (pointedKey == null || key != pointedKey) return Decision.Ignore
        return hold(now, Reason.CLICK)
    }

    /**
     * A fresh read after the screen reported a change: [topPackage] is the app now in front
     * (Heylana's own windows are never in a read) and [nodes] its [signature].
     */
    fun onContentChange(now: Long, eventPackage: String?, topPackage: String?, nodes: Set<String>): Decision {
        if (done) return Decision.Ignore
        if (eventPackage == null || eventPackage == ownPackage) return Decision.Ignore
        if (topPackage == null || topPackage == ownPackage) return Decision.Ignore
        val difference = if (topPackage != targetPackage) 1.0 else difference(baseline, nodes)
        lastDifference = difference
        if (now - quietFrom < QUIET_MS) {
            // Settling around our windows: that is the step's screen now.
            if (topPackage == targetPackage) baseline = nodes
            return Decision.Ignore
        }
        if (difference <= CHANGE_THRESHOLD) return Decision.Ignore
        if (!acted && difference <= BIG_CHANGE) return Decision.Ignore
        return hold(now, Reason.CONTENT_CHANGED)
    }

    private fun hold(now: Long, reason: Reason): Decision {
        // A click outranks a change held earlier: it is the clearer signal.
        if (held == null || reason == Reason.CLICK) held = reason
        return release(now)
    }

    private fun release(now: Long): Decision {
        val reason = held ?: return Decision.Wait
        if (done) return Decision.Ignore
        if (speaking || now - quietFrom < QUIET_MS) return Decision.Wait
        done = true
        return Decision.Advance(reason)
    }

    companion object {
        const val OWN_PACKAGE = "xyz.heylana.app"

        /** A typed value this long unchanged is the user done typing. */
        const val TYPED_SETTLE_MS = 2_500L

        /** No step moves on this soon after the disc lands. */
        const val QUIET_MS = 1_500L

        /** More than this share of the nodes different is a real change. */
        const val CHANGE_THRESHOLD = 0.15

        /** With no tap from the user, only a change this big counts: a new screen, a sheet. */
        const val BIG_CHANGE = 0.5

        /** What a screen is, for comparing: its nodes by key, numbers taken out. */
        fun signature(nodes: List<ScreenNode>): Set<String> = nodes.mapTo(HashSet()) { withoutNumbers(it.key) }

        private val NUMBER = Regex("\\d[\\d.,:]*")

        /** "$0.00111" and "$0.00112" are the same element: a price ticking is not the screen changing. */
        fun withoutNumbers(key: String): String = key.replace(NUMBER, "#")

        /** 0 for the same set, 1 for nothing in common (Jaccard distance). */
        fun difference(a: Set<String>, b: Set<String>): Double {
            if (a.isEmpty() && b.isEmpty()) return 0.0
            val union = a.size + b.size - a.count { it in b }
            val common = a.count { it in b }
            return 1.0 - common.toDouble() / union
        }
    }
}
