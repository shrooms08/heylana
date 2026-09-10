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
 * watch runs for [WINDOW_MS] and ends on the first click on that element, or on
 * the screen moving. Both mean the same thing — they did the step — and the
 * pointer says so and gets out of the way.
 *
 * [GRACE_MS] exists because an app is rarely still at the moment we start
 * watching: a list settling or an animation finishing would otherwise read as
 * the user acting the instant the box appeared. A click on the element itself is
 * unambiguous, so it counts from the first millisecond.
 */
class TapWatch(private val elementKey: String?, private val armedAt: Long) {

    fun consider(now: Long, signal: ScreenSignal): Verdict {
        if (now - armedAt >= WINDOW_MS) return Verdict.EXPIRE
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

    /** True once the watch has run out, whatever else happens. */
    fun expired(now: Long): Boolean = now - armedAt >= WINDOW_MS

    companion object {
        /** How long the pointer waits for the user before it clears itself. */
        const val WINDOW_MS = 15_000L

        /** How long the screen is allowed to settle before movement counts. */
        const val GRACE_MS = 600L
    }
}
