package xyz.heylana.app.screen

/**
 * When a task's step is done, decided from what the screen reports — kept away from
 * Android so every ordering can be tested with a made-up stream of events.
 *
 * A step moves on only when:
 *  - the user clicks the element it points at, or
 *  - the target app's content really changes: its node set differs from the step's
 *    by more than [CHANGE_THRESHOLD] (a new screen, a sheet, the app handing over to
 *    another one), measured on a fresh read.
 *
 * And never:
 *  - within [QUIET_MS] of the disc landing — the target app re-lays itself out when
 *    Heylana's windows appear, and that is not the user doing anything; changes in that
 *    time only update what the step is compared against;
 *  - while the step's line is still being spoken — a click or change then is held and
 *    acted on the moment the line ends;
 *  - for anything from Heylana's own package, or with no package at all.
 */
class StepAdvance(
    private val pointedKey: String?,
    private val targetPackage: String,
    baseline: Set<String>,
    private val landedAt: Long,
    private val ownPackage: String = OWN_PACKAGE
) {

    enum class Reason(val log: String) { CLICK("click"), CONTENT_CHANGED("content_changed") }

    sealed interface Decision {
        data object Wait : Decision
        data object Ignore : Decision
        data class Advance(val reason: Reason) : Decision
    }

    private var baseline: Set<String> = baseline
    private var held: Reason? = null
    private var done = false

    /** True when a tap or a change is waiting for the quiet time or the line to end. */
    val holding: Boolean get() = held != null && !done

    /** How long until the quiet time is over. */
    fun quietLeft(now: Long): Long = (QUIET_MS - (now - landedAt)).coerceAtLeast(0)

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
    fun tick(now: Long): Decision = release(now)

    fun onClick(now: Long, key: String?, packageName: String?): Decision {
        if (done) return Decision.Ignore
        if (packageName == null || packageName == ownPackage) return Decision.Ignore
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
        if (now - landedAt < QUIET_MS) {
            // Settling around our windows: that is the step's screen now.
            if (topPackage == targetPackage) baseline = nodes
            return Decision.Ignore
        }
        if (difference <= CHANGE_THRESHOLD) return Decision.Ignore
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
        if (speaking || now - landedAt < QUIET_MS) return Decision.Wait
        done = true
        return Decision.Advance(reason)
    }

    companion object {
        const val OWN_PACKAGE = "xyz.heylana.app"

        /** No step moves on this soon after the disc lands. */
        const val QUIET_MS = 1_500L

        /** More than this share of the nodes different is a real change. */
        const val CHANGE_THRESHOLD = 0.15

        /** What a screen is, for comparing: its nodes by key. */
        fun signature(nodes: List<ScreenNode>): Set<String> = nodes.mapTo(HashSet()) { it.key }

        /** 0 for the same set, 1 for nothing in common (Jaccard distance). */
        fun difference(a: Set<String>, b: Set<String>): Double {
            if (a.isEmpty() && b.isEmpty()) return 0.0
            val union = a.size + b.size - a.count { it in b }
            val common = a.count { it in b }
            return 1.0 - common.toDouble() / union
        }
    }
}
