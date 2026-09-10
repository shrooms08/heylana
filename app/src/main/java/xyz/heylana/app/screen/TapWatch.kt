package xyz.heylana.app.screen

/** Something the screen did while Heylana was pointing at one of its elements. */
sealed interface ScreenSignal {

    /** An element was clicked. [key] identifies it, or is null if it was unreadable. */
    data class Clicked(val key: String?) : ScreenSignal

    /** The screen moved: a new window, a dialog, or its contents changed. */
    data object Changed : ScreenSignal
}

/** What to do about a signal. */
enum class Verdict {
    /** Not the tap we were waiting for; keep watching. */
    IGNORE,

    /** They did it: flash the box and clear it. */
    ACKNOWLEDGE,

    /** Long enough. Clear the box quietly. */
    EXPIRE
}

/**
 * Waits for the user to act on the element Heylana just pointed at.
 *
 * This is the whole of the rule, kept away from Android so it can be tested: the
 * watch ends on the first click on that element, or on the screen moving. Both
 * mean the same thing — they did the step — and the pointer says so and gets out
 * of the way.
 *
 * [GRACE_MS] exists because an app is rarely still at the moment we start
 * watching: a list settling or an animation finishing would otherwise read as
 * the user acting the instant the box appeared. A click on the element itself is
 * unambiguous, so it counts from the first millisecond.
 *
 * [persistent] is the difference between a box that answered a question and a
 * box that is a step of a task. An answer's box gives the user [WINDOW_MS] and
 * then clears itself, because nothing is waiting on it. A task's box never times
 * out: the user may be hunting for the thing it points at, and it belongs to the
 * step, so it stays until the step changes or the task ends. The watch itself
 * runs the whole time either way, so acting on the box is acknowledged on every
 * step however long it took.
 */
class TapWatch(
    private val elementKey: String?,
    private val armedAt: Long,
    private val persistent: Boolean = false
) {

    fun consider(now: Long, signal: ScreenSignal): Verdict {
        if (expired(now)) return Verdict.EXPIRE
        return when (signal) {
            is ScreenSignal.Clicked ->
                if (elementKey != null && signal.key == elementKey) {
                    Verdict.ACKNOWLEDGE
                } else {
                    Verdict.IGNORE
                }

            ScreenSignal.Changed ->
                if (now - armedAt >= GRACE_MS) Verdict.ACKNOWLEDGE else Verdict.IGNORE
        }
    }

    /** True once the watch has run out. A task's watch never does. */
    fun expired(now: Long): Boolean = !persistent && now - armedAt >= WINDOW_MS

    companion object {
        /**
         * How long a one-shot answer's pointer waits for the user before it
         * clears itself. A task's pointer is not on this clock.
         */
        const val WINDOW_MS = 15_000L

        /** How long the screen is allowed to settle before movement counts. */
        const val GRACE_MS = 600L
    }
}
