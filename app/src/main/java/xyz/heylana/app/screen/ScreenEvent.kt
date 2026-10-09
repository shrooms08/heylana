package xyz.heylana.app.screen

/**
 * Reading an accessibility event, assuming nothing.
 *
 * An accessibility event is other people's data. Every field on it comes from whichever app
 * drew the window, and the Android API marks nearly all of them nullable — including the
 * entries of the text list, which the framework and the app both add to. On 9 October 2026 a
 * window arrived on the Seeker whose text list held a null entry; the title was built with
 * `it.toString()`, that threw, and because this service lives in the app's own process the
 * whole of screen reading went down with it until it was switched off and on again.
 *
 * So the awkward shapes are handled here, in plain Kotlin with no Android in it, and tested
 * on the JVM rather than found on a judge's phone. [surviving] is the second half of the same
 * rule: one bad event costs that event, never the service.
 */
object ScreenEvent {

    /**
     * The title an event carries: its text pieces joined. The list may be missing altogether
     * and any piece in it may be null or blank, so each is checked before it is read.
     */
    fun titleOf(texts: List<CharSequence?>?): String = texts.orEmpty()
        .mapNotNull { piece -> piece?.toString()?.trim()?.takeIf { it.isNotEmpty() } }
        .joinToString(" ")

    /** What the event says about a window, with every one of its fields allowed to be absent. */
    fun windowOf(
        packageName: CharSequence?,
        className: CharSequence?,
        texts: List<CharSequence?>?,
        at: Long
    ): WindowEvent = WindowEvent(
        packageName = packageName?.toString(),
        className = className?.toString(),
        title = titleOf(texts),
        at = at
    )

    /**
     * Runs [body]. Whatever it throws goes to [onError] and the caller carries on, so a single
     * unreadable event cannot take screen reading down with it. Nothing is swallowed quietly:
     * the caller's [onError] is what puts it on the record.
     */
    inline fun surviving(onError: (Throwable) -> Unit, body: () -> Unit) {
        try {
            body()
        } catch (error: Throwable) {
            onError(error)
        }
    }
}
