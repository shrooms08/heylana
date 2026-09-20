package xyz.heylana.app.screen

/**
 * A window coming to the front, as the accessibility event itself describes it — the app,
 * the window's class, and the title the system sent with it.
 *
 * It is deliberately everything that is known **before the screen is read**. Reading the
 * tree of a wallet takes long enough to matter at the moment a signature is being asked
 * for, so the decision to say something at once is made from this and nothing else.
 */
data class WindowEvent(
    val packageName: String?,
    val className: String?,
    val title: String,
    /** When it happened, on the same clock everything after it is measured against. */
    val at: Long,
) {
    /** The class and the title together: what there is to recognise a window by. */
    val describedAs: String get() = "${className.orEmpty()} $title".trim()
}
