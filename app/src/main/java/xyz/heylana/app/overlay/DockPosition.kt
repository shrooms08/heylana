package xyz.heylana.app.overlay

/**
 * Where the docked disc's window really ends up. The dock inset is measured to the
 * visible disc, so with the bloom room around it the wanted position runs past the
 * screen edge; the window manager keeps an overlay window on screen, so it lands
 * clamped. A flight has to aim at the clamped position, or it lands off the edge and
 * the window then jumps back on: two movements instead of one.
 */
object DockPosition {

    /** The window's left (or top) for a [wanted] one, a view of [viewSize] in [usable] px. */
    fun clamped(wanted: Int, viewSize: Int, usable: Int): Int {
        val max = usable - viewSize
        return if (max < 0) 0 else wanted.coerceIn(0, max)
    }

    /** The docked left on either side, given the (possibly negative) inset to the visible disc. */
    fun dockLeft(onLeft: Boolean, inset: Int, viewSize: Int, usableWidth: Int): Int =
        clamped(if (onLeft) inset else usableWidth - viewSize - inset, viewSize, usableWidth)
}
